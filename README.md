# 交易反欺诈监控服务

本地可运行的交易反欺诈监控服务（Spring Boot + 纯内存账本，无外部依赖）。
不同入口（单笔 / 批量 / 查询）进来的交易，风控结论一致、可解释、可复现。

## 运行

```bash
mvn spring-boot:run          # 启动服务，默认 http://localhost:18080
mvn test                     # 运行全部测试（26 个用例）
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

- 所有判定、拒绝、重复提交、名单变更、复核流转、越权与非法迁移尝试都会写入审计日志。
- 审计事件以 SHA-256 哈希链连接（每条事件哈希覆盖前一条哈希），追加后不可篡改。
- `GET /api/audit` 查看全部事件；`GET /api/audit/verify` 校验哈希链完整性。

## 名单管理

- `POST /api/watchlist/{accountId}`，请求体 `{"reason":"涉诈账户"}`：加入名单；
- `DELETE /api/watchlist/{accountId}`：移出名单；
- 每次变更名单版本号 +1，体现在后续判定的 `ruleSetVersion` 中。

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
curl -s -X POST localhost:18080/api/watchlist/A9 -H 'Content-Type: application/json' -d '{"reason":"涉诈"}'
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
```

## 测试覆盖

| 测试类 | 覆盖业务 |
|---|---|
| `TransactionIngestTest` | 字段校验拒绝、单笔/批量幂等、规则命中解释、配置更新后历史结论复现、审计留痕 |
| `ConcurrencyIngestTest` | 同账户并发频率计数精确性、同一交易并发提交只入账一次、多账户并发隔离 |
| `ReviewServiceTest` | 状态机合法/非法流转、ANALYST/SUPERVISOR/AUDITOR 权限边界、越权留痕 |
| `ApiIntegrationTest` | HTTP 入口端到端：接入、幂等、复核、403/409、审计端点 |

测试日志按业务分组输出每个用例的名称与结果（`[通过]`/`[失败]`），并汇总各类通过数。
