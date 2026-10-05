# 交易反欺诈监控服务（本地内存版）

一个本地即可运行的交易反欺诈监控服务：用**内存账本**模拟交易数据、名单与复核记录，
让单笔 / 批量 / 并发等不同入口进来的同一笔交易，在风控结论上保持**一致、可解释、可复现**。

技术栈：Java 21 + Spring Boot 3.4 + 内存并发集合（无外部数据库/中间件）。

---

## 1. 本地运行

```bash
# 运行服务（默认随机端口由 application.properties 的 server.port=0 控制，
# 如需固定端口可改为 server.port=8080）
mvn spring-boot:run

# 跑全部单测
mvn test
```

所有写接口通过请求头识别操作人（本地模拟鉴权）：

| 请求头 | 含义 | 示例 |
|---|---|---|
| `X-Operator` | 操作人标识（必填） | `alice` |
| `X-Roles` | 逗号分隔角色 | `ANALYST` / `SENIOR_ANALYST` / `ADMIN` |

### 快速体验（curl）

```bash
# 1) 单笔提交（小额新收款方：命中 R005，15 分 -> APPROVE）
curl -s -X POST localhost:8080/api/transactions \
  -H 'Content-Type: application/json' -H 'X-Operator: teller' -H 'X-Roles: ANALYST' \
  -d '{"requestId":"t-1","accountId":"A1","targetId":"P1","amount":100,
       "currency":"CNY","txnTimeEpochMilli":'$(date +%s%3N)'}'

# 2) 大额交易（60000：R001 30 + R005 15 = 45 -> REVIEW，自动开复核单）
curl -s -X POST localhost:8080/api/transactions \
  -H 'Content-Type: application/json' -H 'X-Operator: teller' -H 'X-Roles: ANALYST' \
  -d '{"requestId":"t-2","accountId":"A1","targetId":"P2","amount":60000,
       "currency":"CNY","txnTimeEpochMilli":'$(date +%s%3N)'}'

# 3) 批量提交
curl -s -X POST localhost:8080/api/transactions/batch \
  -H 'Content-Type: application/json' -H 'X-Operator: teller' -H 'X-Roles: ANALYST' \
  -d '{"transactions":[
       {"requestId":"b-1","accountId":"A2","targetId":"P3","amount":10,"currency":"CNY","txnTimeEpochMilli":'$(date +%s%3N)'}
     ]}'

# 4) ADMIN 加黑名单（仅 ADMIN）
curl -s -X POST localhost:8080/api/admin/watchlist \
  -H 'Content-Type: application/json' -H 'X-Operator: root' -H 'X-Roles: ADMIN' \
  -d '{"type":"ACCOUNT","subject":"A1","reason":"涉案账户"}'

# 5) ADMIN 发布新阈值版本（只传需要改的字段）
curl -s -X POST localhost:8080/api/admin/rule-config \
  -H 'Content-Type: application/json' -H 'X-Operator: root' -H 'X-Roles: ADMIN' \
  -d '{"largeAmount":30000,"largeAmountScore":50}'

# 6) 复现历史结论（SENIOR_ANALYST 及以上，用交易当时的配置版本重跑）
curl -s -X POST localhost:8080/api/admin/transactions/TXN-1/reproduce \
  -H 'X-Operator: lead' -H 'X-Roles: SENIOR_ANALYST'

# 7) 复核流转
curl -s -X POST localhost:8080/api/reviews/REV-1/claim   -H 'X-Operator: alice' -H 'X-Roles: ANALYST'
curl -s -X POST localhost:8080/api/reviews/REV-1/resolve \
  -H 'Content-Type: application/json' -H 'X-Operator: alice' -H 'X-Roles: ANALYST' \
  -d '{"action":"REJECT","comment":"维持系统建议"}'

# 8) 查询：交易 / 复核单 / 不可篡改审计轨迹
curl -s 'localhost:8080/api/transactions?requestId=t-1'
curl -s localhost:8080/api/reviews
curl -s localhost:8080/api/admin/audits
```

---

## 2. 风险规则与计分口径

判定在**账户锁内**进行：同一账户的并发交易串行评估，频率窗口统计不会漏算或重复计数。
每条规则命中都会在结论里返回 `rule / score / reason / detail`，总分 = 各命中规则加分之和。

| 规则 | 编号 | 默认口径 | 默认加分 |
|---|---|---|---|
| 大额交易 | `R001_LARGE_AMOUNT` | 金额 ≥ **50,000** | **30** |
| 窗口频率 | `R002_FREQUENCY_WINDOW` | 同一账户在本笔**之前已被系统处理**、且处理时刻在 **300 秒**滑动窗口内的已接受交易 ≥ **3 笔** | **40** |
| 账户名单 | `R003_WATCHLIST_ACCOUNT` | 付款账户命中名单（类型 ACCOUNT） | **60** |
| 收款方名单 | `R004_WATCHLIST_TARGET` | 收款方命中名单（类型 TARGET） | **50** |
| 新收款方 | `R005_NEW_PAYEE_FIRST_TIME` | 按系统**处理顺序**，此前没有同账户→同收款方的已处理成功交易（并发首单只有处理序号最小的一笔命中） | **15** |

处置阈值（默认 v1）：

| 总分 | 处置结论 |
|---|---|
| `< 40` | `APPROVE`（放行） |
| `40 ~ 79` | `REVIEW`（转人工复核，自动创建复核单） |
| `≥ 80` | `REJECT`（拒绝） |

频率与新收款方规则都以账户锁内分配的**全局处理序号（processedSeq）**为唯一先后依据：
同一账户的判定串行执行，序号与处理时刻在锁内一并确定、单调一致，
因此同账户并发进来的交易不会漏算或重复计数，结论与线程调度顺序无关、可复现。
时间窗口以"进入账户锁开始处理的时刻"为基准（而非线程提交时刻）。
被字段校验拒绝的交易不进入任何风控计数。

### 结论可复现口径

- 每次判定在结论上记录 `configVersion`；阈值/计分更新以**新版本追加**方式发布，旧版本永久保留。
- 复现接口（`POST /api/admin/transactions/{txnId}/reproduce`）加载交易当时的 `configVersion`，
  并重放该交易时点之前的窗口数据重新计算，因此名单或阈值更新后，历史交易仍能算出当初的分数与处置，
  同时写一条 `REPRODUCED` 审计（`CONSISTENT` / `DIFF`）。
- 内存实现保留了配置的全部历史版本；名单按"当前状态"参与复现
  （内存账本未维护名单的历史时间线，这是内存版的已知边界）。

---

## 3. 状态与权限约定

### 3.1 交易接入状态

- `ACCEPTED`：通过字段校验并落账，随后挂载风险判定结论。
- `REJECTED`：字段缺失/非法等，**落账留痕**（`status=REJECTED + rejectReason`），但不进入风控计数，不静默丢弃。

明确的拒绝原因（HTTP 400，`code` 字段）：

| code | 触发条件 |
|---|---|
| `REQUEST_ID_BLANK` / `ACCOUNT_ID_BLANK` / `TARGET_ID_BLANK` / `CURRENCY_BLANK` | 对应字段为空 |
| `AMOUNT_NULL` / `AMOUNT_NOT_POSITIVE` | 金额缺失或 ≤ 0 |
| `TXN_TIME_NULL` / `TXN_TIME_IN_FUTURE` / `TXN_TIME_TOO_OLD` | 交易时间缺失、超过当前 1 分钟以上、或早于 30 天前 |
| `IDEMPOTENCY_MISMATCH`（HTTP 409） | 同一 `requestId` 重放但报文与首次不一致 |

### 3.2 幂等

- 以 `requestId` 为幂等键，`ConcurrentHashMap.putIfAbsent` 保证只落账一次、只出一次结论；
  重复提交（报文一致）直接返回首次记录与首次结论。
- 批量提交中每一项独立幂等，非法项带原因返回且不影响同批其他项。

### 3.3 复核单状态机

```
PENDING ──claim──▶ INVESTIGATING ──resolve──▶ RESOLVED
   │                    │
   └─ 任何其他迁移（PENDING 直接结案、RESOLVED 再认领/结案、逆向等）一律 409 ILLEGAL_TRANSITION
```

### 3.4 角色与权限

| 角色 | 能力 |
|---|---|
| `ANALYST` | 认领复核单；作为认领人**维持系统建议**结案 |
| `SENIOR_ANALYST` | 上述全部 + **覆盖系统判断**结案 + 历史结论复现 |
| `ADMIN` | 上述全部 + 名单维护 + 规则阈值/计分配置版本发布 |

- 结案时若 `finalDisposition` 与系统处置不同，视为**覆盖**，仅 `SENIOR_ANALYST / ADMIN` 可执行，且只有该单的**认领人**能结案。
- 任何越权操作返回 **HTTP 403 `ACCESS_DENIED`**，状态/数据不变，并写入一条 `ACCESS_DENIED` 审计事件。

### 3.5 审计轨迹

所有关键动作都以全局递增 `seq` 只追加写入内存审计日志（无修改/删除接口），可经
`GET /api/admin/audits` 查询。动作类型包括：`TXN_SUBMIT_SINGLE / TXN_SUBMIT_BATCH /
TXN_REJECTED / RISK_EVALUATED / REVIEW_CREATED / REVIEW_CLAIM / REVIEW_RESOLVE /
REVIEW_OVERRIDE / ACCESS_DENIED / RULE_CONFIG_UPDATED / WATCHLIST_UPDATED / REPRODUCED`。

---

## 4. 主要代码结构

```
model/      枚举与领域模型（交易、判定结论、命中规则、复核单、名单条目、规则配置、审计事件）
error/      业务异常（校验/冲突/未找到/越权）与稳定错误码
ledger/     Ledger 接口 + InMemoryLedger（幂等落账、账户分段锁、配置版本、只追加审计）
rule/       RiskRule 五条规则 + RuleContext + RiskEngine（计分、处置、复现）
service/    TransactionService（接入/校验/幂等）、ReviewService（状态机/权限）、AdminService（名单/配置/复现）
web/        REST 控制器、OperatorResolver（本地请求头鉴权）、GlobalExceptionHandler、Views
```

---

## 5. 本地验证方法（单测）

`mvn test` 共 25 个用例，按业务分在 7 个测试类；每个用例开始/结束都会打印
`【用例开始】/【用例结果】... => PASS|FAIL`，并在开始处打印 `覆盖业务[...] 用例: ...`，
日志可直接看出覆盖了哪些业务、各业务有哪些用例及结果。

| 测试类 | 覆盖业务边界 |
|---|---|
| `IdempotencyTest` | 同 requestId 重复提交不重复计数/不出第二份结论；报文不一致拒绝 |
| `ConcurrentEvaluationTest` | 12 线程并发同账户交易：全部落账、无重复 txnId、频率命中数恒为 9、新收款方只命中一次 |
| `ValidationTest` | 8 类字段原因码拒绝；批量中非法项拒绝、合法项正常出结论；拒绝也留痕 |
| `ReviewFlowTest` | 非法状态迁移（PENDING 结案 / RESOLVED 再认领）；越权覆盖；非认领人结案；合法覆盖 |
| `AdminPermissionTest` | 非 ADMIN 改名单/改阈值被拒并留痕；ADMIN 发布新版本且旧版本保留 |
| `RiskAndReproduceTest` | 命中规则明细与加和；阈值更新后历史结论按旧版本复现一致 |
| `RestApiIntegrationTest` | HTTP 端到端：缺操作人、非法金额 400、幂等重放、HTTP 越权覆盖 403 |

```bash
mvn test                                  # 全部
mvn test -Dtest=ConcurrentEvaluationTest  # 只跑并发判定
```
