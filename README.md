# 交易反欺诈监控服务

本地可运行的交易反欺诈监控服务（Spring Boot + 纯内存账本，无外部依赖）。
不同入口（单笔 / 批量 / 查询）进来的交易，风控结论一致、可解释、可复现。

## 运行

```bash
mvn spring-boot:run          # 启动服务，默认 http://localhost:18080
mvn test                     # 运行全部测试（45 个用例）
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

- 复核操作：`POST /api/reviews/{caseId}/actions`，请求体
  `{"operator":"sup1","role":"SUPERVISOR","targetStatus":"IN_PROGRESS","note":"..."}`。
- 工单查询：`GET /api/reviews`、`GET /api/reviews/{caseId}`。

## 审计轨迹

- 所有判定、拒绝、重复提交、名单变更、复核流转、策略发布、历史重算、影响预演、
  越权与非法操作尝试都会写入审计日志。
- 审计事件以 SHA-256 哈希链连接（每条事件哈希覆盖前一条哈希），追加后不可篡改。
- `GET /api/audit` 查看全部事件；`GET /api/audit/verify` 校验哈希链完整性。

## 名单管理

- `POST /api/watchlist/{accountId}`，请求体
  `{"reason":"涉诈账户","operator":"ops1","role":"POLICY_ADMIN"}`：加入名单；
- `DELETE /api/watchlist/{accountId}?operator=ops1&role=POLICY_ADMIN`：移出名单；
- **权限**：名单增删仅 `POLICY_ADMIN` 可执行。缺少 operator/role 返回 400，
  角色不符返回 403；被拒绝的尝试全部留痕，且不对名单产生任何改动；
- 每次变更名单版本号 +1，体现在后续判定的 `ruleSetVersion` 中。

## 策略版本：发布与切换

策略内容（各规则阈值与分值、处置阈值）以**版本**为单位运营，服务运行中即可发布与切换，无需重启。

- `POST /api/policies` 发布新版本，请求体：
  ```json
  {"operator":"ops1","role":"POLICY_ADMIN","version":"v2",
   "highAmountThreshold":100000,"highAmountScore":40,
   "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
   "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}
  ```
- `GET /api/policies` 查看全部版本；`GET /api/policies/active` 查看当前生效版本。

规则：

- **不可变**：每次发布记录发布人、发布时间与完整内容；版本号唯一，
  同版本号重复发布返回 409——想再调整只能发新版本；
- **切换口径**：发布成功即对之后新接入的交易生效；发布前已出结论的交易
  结论不被改写（结论中记录了 `policyVersion` 与 `ruleSetVersion`）；
- **并发一致**：一笔交易的判定从头到尾只使用一份策略快照与一份名单快照，
  发布与交易并发时，该笔交易要么完整按旧版本、要么完整按新版本判定，
  不会出现新旧规则掺半的结果；
- **权限**：仅 `POLICY_ADMIN` 可发布。越权返回 403、内容非法（缺字段 /
  阈值为负 / reviewThreshold > blockThreshold）返回 400，均留痕且无副作用。

## 历史重算（对账）

- `POST /api/transactions/{transactionId}/replay`，请求体 `{"operator":"aud1","role":"AUDITOR"}`。
- **口径**：严格使用该交易当时生效的策略版本内容 + 当时的判定输入快照
  （窗口内已有笔数、名单命中条目）重新计算，与落库结论逐项比对
  （总分 / 命中规则 / 处置结论），返回 `consistent`。
  之后的策略发布、名单变更、新交易入账都不影响重算结果。
- **只读**：重算不新增交易计数、不重复开工单、不覆盖原结论；
  每次重算的操作者、时间、是否一致都写入审计（`TRANSACTION_REPLAY`）。
- 重算为只读核对，所有角色（含 AUDITOR）均可发起；交易不存在返回 404。

## 影响预演（Dry-Run）

- `POST /api/policies/dry-run`，请求体同发布（`version` 为候选标签，不会真的发布）。
- **口径**：固定每笔历史交易当时的判定输入，只替换为候选策略内容重算，
  统计 `changedCount` / `unchangedCount`，并给出最多 10 条典型样例
  （哪笔交易、从什么分数/结论变成什么分数/结论）。
- **严格只读**：不落库、不计数、不开工单、不切换生效策略，
  候选版本不会进入版本注册表，对正在运行的实时判定没有任何影响；
  预演动作本身留痕（`POLICY_DRY_RUN`）。

## 角色与权限总览

| 操作 | ANALYST | SUPERVISOR | AUDITOR | POLICY_ADMIN |
|---|---|---|---|---|
| 复核认领/一致结论 | ✅ | ✅ | ❌ | ❌ |
| 覆盖系统结论 / 归档 | ❌ | ✅ | ❌ | ❌ |
| 发布策略版本 | ❌ | ❌ | ❌ | ✅ |
| 名单增删 | ❌ | ❌ | ❌ | ✅ |
| 历史重算 / 影响预演（只读） | ✅ | ✅ | ✅ | ✅ |

## 兼容范围说明

本轮改动与既有能力的关系：

- **原有接口不变**：交易接入（单笔/批量/查询）、复核流转、审计查询的
  请求与响应结构均未改变；`RiskAssessment` 仅**新增**字段
  （`policyVersion` / `watchlistVersion` / `recentCount` / `watchlistEntry`），
  原有字段（含 `ruleSetVersion` 格式）保持不变；
- **原有结论不变**：历史结论按当时版本保留，发布新策略、名单变更均不会改写；
- **名单接口的破坏性变更**：`POST/DELETE /api/watchlist/{accountId}` 现在
  要求携带 `operator` 与 `role=POLICY_ADMIN`，未携带返回 400、角色不符返回 403；
- **新增接口**：`POST/GET /api/policies`、`GET /api/policies/active`、
  `POST /api/policies/dry-run`、`POST /api/transactions/{id}/replay`；
- `Role` 新增 `POLICY_ADMIN`，该角色不能执行复核流转。

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

# 6. 加名单后交易 -> REVIEW 并自动开工单（名单操作需 POLICY_ADMIN）
curl -s -X POST localhost:18080/api/watchlist/A9 -H 'Content-Type: application/json' \
  -d '{"reason":"涉诈","operator":"ops1","role":"POLICY_ADMIN"}'
curl -s -X POST localhost:18080/api/transactions -H 'Content-Type: application/json' -d '{
  "transactionId":"T3","accountId":"A9","amount":100,
  "currency":"CNY","merchantId":"M1","channel":"APP","occurredAt":"2026-10-06T12:00:00Z"}'
curl -s localhost:18080/api/reviews

# 7. 复核流转（把 <caseId> 换成上一步的工单号）；越权/非法迁移会返回 403/409
curl -s -X POST localhost:18080/api/reviews/<caseId>/actions -H 'Content-Type: application/json' \
  -d '{"operator":"sup1","role":"SUPERVISOR","targetStatus":"IN_PROGRESS","note":"认领"}'

# 8. 审计轨迹与完整性校验
curl -s localhost:18080/api/audit
curl -s localhost:18080/api/audit/verify

# 9. 发布新策略版本（仅 POLICY_ADMIN；之后新交易按 v2 判定，历史结论不变）
curl -s -X POST localhost:18080/api/policies -H 'Content-Type: application/json' -d '{
  "operator":"ops1","role":"POLICY_ADMIN","version":"v2",
  "highAmountThreshold":100000,"highAmountScore":40,
  "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
  "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}'
curl -s localhost:18080/api/policies/active

# 10. 对历史交易按当时策略重算核对 -> consistent=true
curl -s -X POST localhost:18080/api/transactions/T1/replay -H 'Content-Type: application/json' \
  -d '{"operator":"aud1","role":"AUDITOR"}'

# 11. 候选策略影响预演（只读，不发布、不影响实时判定）
curl -s -X POST localhost:18080/api/policies/dry-run -H 'Content-Type: application/json' -d '{
  "operator":"ops1","role":"POLICY_ADMIN","version":"v3-candidate",
  "highAmountThreshold":5000,"highAmountScore":60,
  "velocityWindowSeconds":60,"velocityMaxCount":5,"velocityScore":30,
  "watchlistScore":50,"reviewThreshold":50,"blockThreshold":80}'
```

## 测试覆盖

| 测试类 | 覆盖业务 |
|---|---|
| `TransactionIngestTest` | 字段校验拒绝、单笔/批量幂等、规则命中解释、配置更新后历史结论复现、审计留痕 |
| `ConcurrencyIngestTest` | 同账户并发频率计数精确性、同一交易并发提交只入账一次、多账户并发隔离 |
| `ReviewServiceTest` | 状态机合法/非法流转、ANALYST/SUPERVISOR/AUDITOR 权限边界、越权留痕 |
| `PolicyPublishTest` | 发布后新交易走新版本且历史结论不变、版本不可变（409）、越权/非法发布拒绝且无副作用、发布与并发交易不混版本 |
| `ReplayServiceTest` | 重算与原始结论一致、策略与名单变更后重算仍一致、重算只读无副作用、重算留痕 |
| `DryRunServiceTest` | 预演统计与样例正确、预演只读且不影响实时判定、候选版本不进注册表、预演留痕 |
| `WatchlistPermissionTest` | 名单增删的 POLICY_ADMIN 权限边界、越权/缺参拒绝且无副作用并留痕 |
| `ApiIntegrationTest` | HTTP 入口端到端：接入、幂等、复核、403/409、策略发布/预演/重算端点、审计端点 |

测试日志按业务分组输出每个用例的名称与结果（`[通过]`/`[失败]`），并汇总各类通过数。
