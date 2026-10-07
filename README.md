# 交易反欺诈监控服务

本地可运行的交易反欺诈监控服务（Spring Boot + 纯内存账本，无外部依赖）。
不同入口（单笔 / 批量 / 查询）进来的交易，风控结论一致、可解释、可复现。

## 运行

```bash
mvn spring-boot:run          # 启动服务，默认 http://localhost:18080
mvn test                     # 运行全部测试（51 个用例）
```

## 风险规则与计分口径

默认规则集版本 `rules:v1`（`RuleConfig.defaultConfig()`）：

| 规则 ID | 名称 | 触发条件 | 加分 |
|---|---|---|---|
| `AMOUNT_HIGH` | 大额交易 | 单笔金额 ≥ 10000 | 40 |
| `VELOCITY_HIGH` | 短时高频交易 | 同一账户 60 秒窗口内已入账 ≥ 5 笔（不含当前笔） | 30 |
| `WATCHLIST_HIT` | 命中风险名单 | 账户在名单中 | 50 |

- **总分** = 各命中规则分值之和。
- **处置结论**：总分 ≥ 80 → `BLOCK`；≥ 50 → `REVIEW`；否则 `APPROVE`。
- **可解释**：每次判定返回 `hits` 列表，含规则 ID、名称、加分与具体原因（如"金额 15000 达到阈值 10000"）。
- **可复现**：每条结论记录 `ruleSetVersion`（形如 `rules:v1/watchlist:v2`）。
  规则阈值或名单更新后版本号递增，历史结论仍按当时版本保留，重复提交同一交易返回原结论。
- **并发正确性**：同一账户的"窗口计数 + 评估 + 入账"在账户级锁内串行完成，
  并发交易不会漏算或重复计算频率；同一 `transactionId` 通过原子 `computeIfAbsent` 保证只入账一次。

## 交易接入与幂等

- `POST /api/transactions` 单笔；`POST /api/transactions/batch` 批量（逐笔返回结论）。
- 返回 `status`：
  - `ACCEPTED`：已落库并给出结论；
  - `DUPLICATE`：同一 `transactionId` 重复提交，返回首次结论，不重复计数；
  - `REJECTED`：字段缺失或非法，`rejectReason` 说明具体原因（缺哪个字段 / 金额非正 / 时间格式非法），不落库、不计数、有审计。
- 必填字段：`transactionId, accountId, amount(>0), currency, occurredAt(ISO-8601)`。
- 结论为 `REVIEW`/`BLOCK` 时自动开立复核工单。

## 复核状态机与权限约定

状态机（其余迁移一律拒绝，HTTP 409 并留痕）：

```
OPEN --认领--> IN_PROGRESS --复核--> RESOLVED_APPROVE / RESOLVED_BLOCK --归档--> CLOSED
```

角色权限（越权拒绝，HTTP 403 并留痕）：

| 操作 | ANALYST | SUPERVISOR | AUDITOR |
|---|---|---|---|
| 认领（→IN_PROGRESS） | ✅ | ✅ | ❌ 只读 |
| 与系统结论一致的复核 | ✅ | ✅ | ❌ |
| 覆盖系统结论 | ❌ | ✅ | ❌ |
| 归档（→CLOSED） | ❌ | ✅ | ❌ |

复核角色之外另有策略运营角色 `POLICY_ADMIN`：负责发布策略版本、影响预演、名单增删
（见下文对应章节）；该角色不参与复核流转，复核角色也不能做策略运营操作。

- 复核操作：`POST /api/reviews/{caseId}/actions`，请求体
  `{"operator":"sup1","role":"SUPERVISOR","targetStatus":"IN_PROGRESS","note":"..."}`。
- 工单查询：`GET /api/reviews`、`GET /api/reviews/{caseId}`。

## 策略版本发布与切换

- `POST /api/policies` 发布新策略版本，请求体：
  ```json
  {"operator":"ops1","role":"POLICY_ADMIN","note":"调高大额阈值",
   "config":{"version":"v2","highAmountThreshold":100000,"highAmountScore":40,
             "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
             "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}}
  ```
- **发布即切换**：发布后新接入的交易按新版本判定；发布前已落库的结论不被改写。
- **版本不可变**：每次发布必须使用的新的 `config.version`；同版本号重复发布视为篡改已发布版本，
  返回 409 并留痕（`POLICY_PUBLISH_REJECTED`）。
- **权限**：只有 `POLICY_ADMIN` 可以发布；其他角色返回 403 并留痕（`POLICY_PERMISSION_DENIED`），
  不产生任何副作用（版本数、生效版本、实时判定均不变）。
- **配置校验**：阈值/分值/窗口非法（如 `reviewThreshold > blockThreshold`、负分值）返回 400。
- **并发一致性**：每笔交易评估时只取一次策略快照与一次名单快照，
  发布与交易并发进行时，同一笔交易完整按某一个版本计算，不会新旧规则混算。
- `GET /api/policies` 查看全部已发布版本（含发布人、发布时间、内容）；
  `GET /api/policies/active` 查看当前生效版本。
- 服务启动时把内置默认配置登记为初始版本 `v1`（发布人 `system`）。

## 决策重算（历史对账）

- `POST /api/transactions/{transactionId}/replay`，请求体 `{"operator":"aud1"}`。
- 对一笔历史交易，严格按它**当初判定时的策略版本与名单状态**重新计算并核对：
  - 策略版本：取结论中记录的 `rulesVersion` 对应的已发布版本；
  - 名单状态：取结论中记录的 `watchlistVersion` 对应的名单快照；
  - 窗口频率：只统计入账序号小于该交易的同账户交易，还原它当初判定时已入账的交易集合。
- 返回 `consistent`（分数、命中规则、结论三者是否完全一致）以及原始/重算两份结论。
- **只读核对**：不新增交易计数、不重复开工单、不覆盖原结论；
  重算行为本身写入审计（`DECISION_REPLAY`，含操作者、时间、是否一致）。

## 影响预演（策略上线前评估）

- `POST /api/policies/preview`，请求体与发布类似（`operator`/`role`/`config`），
  `config` 是**尚未发布**的候选策略。
- 口径：对库里每笔历史交易，保持它当初的名单状态与入账次序不变，
  只把策略配置换成候选配置重算，与落库结论比对。
- 返回：总笔数、结论会变化的笔数、典型变化样本（最多 5 笔，含从什么结论变成什么结论、分数变化）。
- **纯只读**：不落库、不计数、不开工单、不写审计、不触碰当前生效配置；
  候选策略在正式发布前对实时判定无任何影响。
- 只有 `POLICY_ADMIN` 可以预演；越权返回 403 并留痕（权限事件，预演本身未执行）。

## 审计轨迹

- 所有判定、拒绝、重复提交、策略发布与其越权/非法尝试、名单变更与其越权尝试、
  决策重算、复核流转、越权与非法迁移尝试都会写入审计日志。
  （影响预演是纯只读计算，成功执行时不产生审计事件。）
- 审计事件以 SHA-256 哈希链连接（每条事件哈希覆盖前一条哈希），追加后不可篡改。
- `GET /api/audit` 查看全部事件；`GET /api/audit/verify` 校验哈希链完整性。

## 名单管理

名单增删属于策略运营操作，与策略发布同级的权限与留痕要求：**只有 `POLICY_ADMIN` 角色可以增删**。

- `POST /api/watchlist/{accountId}`，请求体
  `{"operator":"ops1","role":"POLICY_ADMIN","reason":"涉诈账户"}`：加入名单；
- `DELETE /api/watchlist/{accountId}`，请求体 `{"operator":"ops1","role":"POLICY_ADMIN"}`：移出名单；
- 每次成功变更名单版本号 +1，体现在后续判定的 `ruleSetVersion` 中；
  每个版本的名单完整快照都会保留，供历史交易按当时名单状态重算；
- 越权增删返回 403 并留痕（`WATCHLIST_PERMISSION_DENIED`），名单与版本号不变；
  删除不存在的账户返回 404 并留痕（`WATCHLIST_REMOVE_REJECTED`）。

## 本地验证方法

```bash
# 1. 跑测试：日志会按业务分组输出每个用例的名称与通过/失败结果
mvn test

# 2. 启动服务
mvn spring-boot:run

# 3. 提交一笔大额交易（命中 AMOUNT_HIGH，40 分 -> APPROVE）
curl -s -X POST localhost:18080/api/transactions -H 'Content-Type: application/json' -d '{
  "transactionId":"T1","accountId":"A1","amount":15000,
  "currency":"CNY","merchantId":"M1","channel":"APP","occurredAt":"2026-10-06T12:00:00Z"}'

# 4. 重复提交同一笔 -> DUPLICATE，结论与首次一致
curl -s -X POST localhost:18080/api/transactions -H 'Content-Type: application/json' -d '{
  "transactionId":"T1","accountId":"A1","amount":15000,
  "currency":"CNY","merchantId":"M1","channel":"APP","occurredAt":"2026-10-06T12:00:00Z"}'

# 5. 缺字段 -> REJECTED 并说明原因
curl -s -X POST localhost:18080/api/transactions -H 'Content-Type: application/json' -d '{"transactionId":"T2"}'

# 6. 加名单后交易 -> REVIEW 并自动开工单
curl -s -X POST localhost:18080/api/watchlist/A9 -H 'Content-Type: application/json' -d '{
  "operator":"ops1","role":"POLICY_ADMIN","reason":"涉诈"}'
curl -s -X POST localhost:18080/api/transactions -H 'Content-Type: application/json' -d '{
  "transactionId":"T3","accountId":"A9","amount":100,
  "currency":"CNY","merchantId":"M1","channel":"APP","occurredAt":"2026-10-06T12:00:00Z"}'
curl -s localhost:18080/api/reviews

# 7. 策略运营：发布新版本（需 POLICY_ADMIN），之后新交易按 v2 判定
curl -s -X POST localhost:18080/api/policies -H 'Content-Type: application/json' -d '{
  "operator":"ops1","role":"POLICY_ADMIN","note":"调高大额阈值",
  "config":{"version":"v2","highAmountThreshold":100000,"highAmountScore":40,
            "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
            "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}}'
curl -s localhost:18080/api/policies/active     # 当前生效版本
curl -s localhost:18080/api/policies            # 全部已发布版本

# 8. 决策重算：对历史交易 T1 按当时策略重算并核对（只读，结果 consistent=true）
curl -s -X POST localhost:18080/api/transactions/T1/replay -H 'Content-Type: application/json' -d '{
  "operator":"aud1"}'

# 9. 影响预演：拿未发布的候选策略对历史交易试算（只读，不影响实时判定）
curl -s -X POST localhost:18080/api/policies/preview -H 'Content-Type: application/json' -d '{
  "operator":"ops1","role":"POLICY_ADMIN",
  "config":{"version":"v3-candidate","highAmountThreshold":1000,"highAmountScore":40,
            "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
            "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}}'

# 10. 越权发布 / 越权增删名单 -> 403 并留痕
curl -s -X POST localhost:18080/api/policies -H 'Content-Type: application/json' -d '{
  "operator":"ana1","role":"ANALYST",
  "config":{"version":"v4","highAmountThreshold":1,"highAmountScore":40,
            "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
            "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}}'

# 11. 复核流转（把 <caseId> 换成上一步的工单号）；越权/非法迁移会返回 403/409
curl -s -X POST localhost:18080/api/reviews/<caseId>/actions -H 'Content-Type: application/json' \
  -d '{"operator":"sup1","role":"SUPERVISOR","targetStatus":"IN_PROGRESS","note":"认领"}'

# 12. 审计轨迹与完整性校验
curl -s localhost:18080/api/audit
curl -s localhost:18080/api/audit/verify
```

## 兼容性说明（本轮策略运营改动）

- **接口兼容**：交易接入（单笔/批量/查询）、复核流转、审计查询等原有端点的路径、
  请求体与响应结构均未变化；`RiskAssessment` 的 JSON 只新增 `rulesVersion`、
  `watchlistVersion` 两个字段（原 `ruleSetVersion` 标签保留），按新增字段兼容。
- **行为变化（一处）**：名单增删端点从"无权限要求"收紧为"仅 `POLICY_ADMIN`"，
  请求体需带 `operator` 与 `role`；这是本轮权限口径的刻意收紧，旧调用方式会收到 403。
- **结论兼容**：所有历史已落库结论不因策略发布、名单变更、重算或预演而改变；
  重复提交同一交易仍返回首次结论；重算口径保证与原始判定逐项一致。
- **数据兼容**：纯内存存储，重启后策略版本注册表以默认 `v1` 重新初始化，
  与引擎内置默认配置一致。

## 测试覆盖

| 测试类 | 覆盖业务 |
|---|---|
| `TransactionIngestTest` | 字段校验拒绝、单笔/批量幂等、规则命中解释、配置更新后历史结论复现、审计留痕 |
| `ConcurrencyIngestTest` | 同账户并发频率计数精确性、同一交易并发提交只入账一次、多账户并发隔离 |
| `ReviewServiceTest` | 状态机合法/非法流转、ANALYST/SUPERVISOR/AUDITOR 权限边界、越权留痕 |
| `ApiIntegrationTest` | HTTP 入口端到端：接入、幂等、复核、403/409、审计端点 |
| `PolicyPublishTest` | 策略发布/切换、版本不可变、越权与非法配置拒绝且无副作用、发布与交易并发时单笔结论不混版本 |
| `ReplayServiceTest` | 按当时策略与名单状态重算结果一致（含名单变更后、频率命中、策略发布后）、重算只读且留痕 |
| `PreviewServiceTest` | 预演统计与样本正确、纯只读（不落库/不计数/不开工单/不写审计）、候选策略不影响实时判定、越权拒绝 |
| `WatchlistAdminTest` | 名单增删权限、版本递增与历史快照保留、越权/非法删除被拒绝且无副作用 |
| `PolicyApiTest` | 策略运营 HTTP 端到端：发布/切换/重算/预演、403/409/400 |

测试日志按业务分组输出每个用例的名称与结果（`[通过]`/`[失败]`），并汇总各类通过数。
