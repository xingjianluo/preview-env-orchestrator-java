# Preview Env Orchestrator（本地预览环境编排控制面）

用进程内可替换的运行后端（`RuntimeBackend` / `InProcessRuntimeBackend`）模拟环境实例，
对预览环境提供从创建到回收的全生命周期编排，保证多租户并发下结果一致且可解释。

本版在不推翻既有接口的前提下增强三点：

1. **创建区分**：调用方带同一请求标识的重试合并到同一个环境（只开通一次、只占一份配额）；
   两次都不带标识的创建是彼此独立的环境，不会因为租户相同而互相顶替、连带销毁。
2. **按阶段判定生命周期操作**：操作必须在环境当前阶段“说得通”，开通未完成阶段的
   启动/停止会被明确拒绝并给出可区分原因，杜绝“列表显示已停/运行中、后端却无实例”。
3. **请求回执**：创建/启动/停止/销毁支持请求标识回查，结论分成功/处理中/失败可重试，
   且历史结论冻结，不被后续操作改写。老的不带标识调用方式照旧可用。

## 生命周期状态机

```
                 provision 成功
            ┌──────────────────────┐
            ▼                      │
 CREATING ───────► STOPPED ◄──────► RUNNING
    │               │  ▲              │
    │               │  │ start/stop   │
    │               ▼  │              ▼
    └──────────────► DESTROYING ──► DESTROYED（终态）
```

### 各阶段允许 / 不允许的操作（含拒绝原因）

| 当前状态 | start | stop | destroy | keep-alive |
|---|---|---|---|---|
| CREATING（开通未完成） | ❌ `ENV_STILL_PROVISIONING`（可重试） | ❌ `ENV_STILL_PROVISIONING`（可重试） | ✅ 清理到 DESTROYED（等在途开通落定的同时清残留，见下） | ❌ |
| STOPPED | ✅ → RUNNING | ❌ `ILLEGAL_STATE_TRANSITION`（重复停止，不可盲目重试） | ✅ | ✅ |
| RUNNING | ❌ `ILLEGAL_STATE_TRANSITION`（重复启动） | ✅ → STOPPED | ✅ | ✅ |
| DESTROYING（销毁中途失败残留） | ❌ `ILLEGAL_STATE_TRANSITION` | ❌ `ILLEGAL_STATE_TRANSITION` | ✅ 重试清理 | ❌ |
| DESTROYED（终态） | ❌ `ILLEGAL_STATE_TRANSITION` | ❌ `ILLEGAL_STATE_TRANSITION` | ✅ 幂等返回当前记录 | ❌ |

两类“拒绝”刻意区分：

- **`ENV_STILL_PROVISIONING`（HTTP 409，回执标记 `retryable=true`）**：不是请求本身错，
  而是环境还没开通完；等开通成功后重试同样的请求即可成功。错误信息包含具体操作名（start/stop）。
- **`ILLEGAL_STATE_TRANSITION`（HTTP 409，回执标记 `retryable=false`）**：阶段说得通但目标迁移
  在该状态下不成立（如已停止再停止、终态后操作），原样重发结果不变，不应盲目重试。
  错误信息含判定依据（`Transitions.explain`：源状态、目标状态、允许的目标集合）。

实现上后端开通（可能较慢）在**开通锁**内执行但**不持有状态迁移锁**，因此开通进行中其它请求能
立即看到 CREATING 并被拒绝，而不是被阻塞到开通结束后误当成一次成功启停。开通完成前控制面
绝不会显示 STOPPED/RUNNING，后端也不存在实例，二者始终对得上。

### 开通失败的收敛

- 开通中途失败：记录停留 CREATING、占一份配额，回执为 `FAILED` + `retryable=true`，后端无残留实例。
- **继续重试**：用同一幂等键（以及/或者同一 X-Request-Id）再提交，复用原环境继续开通，
  成功后收敛到 STOPPED，仍只占一份配额、不产生第二个环境。
- **直接清理**：对 CREATING 调 destroy，后端幂等清理可能的残留，收敛到 DESTROYED、配额归还，
  控制面与真实载体都不再有该环境。
- **开通在途时发起销毁**：销毁会等待该环境的在途 `provision` 落定（按环境粒度的开通锁），
  再把后端实例一并清掉才置 DESTROYED——不会出现“列表显示已销毁、后台却留着真实实例”。
  在途开通随后看到记录已是 DESTROYED，不会再把它推进到 STOPPED。
- **既不重试也不清理**：CREATING 滞留超过 `orchestrator.provisioning-ttl`（默认 10m）后由
  `LeaseReaper` 自动清理（`stopCause=PROVISION_TIMEOUT`），配额归还，
  卡住的环境不会永远占着配额收不回来。

## 请求标识与幂等创建

两种“标识”各管一件事，不要混淆：

| 标识 | 传递方式 | 作用 |
|---|---|---|
| 幂等键 `idempotencyKey` | 创建请求 body 字段 | 决定“是不是同一个环境”：同 `(租户, key)` 的重试合并到同一环境 |
| 请求标识 `X-Request-Id` | 变更接口的请求头 | 决定“是不是同一次请求”：用于认领执行权与回查回执结论 |

创建规则（三种标识组合，合并行为各不相同）：

- **带 idempotencyKey**：同一 `(tenantId, key)` 的重试/并发提交返回同一环境，
  后端 `provision` 只执行一次、配额只计一次。带同一幂等键的重试不会被拆成两个实例。
- **不带 idempotencyKey、但带 X-Request-Id**：同一 `(tenantId, X-Request-Id)` 的重试
  同样合并到首次登记的那个环境——第一次开通失败后用同一请求标识重试，复用原环境继续开通，
  不会再登记新记录、再占一份配额，也不会留下永远卡在 CREATING 的孤儿环境。
  换一个新的 X-Request-Id 则是另一次独立创建。
- **两个标识都不带**：每次调用都登记并开通一个**彼此独立**的新环境，
  各自占配额、各自有生命周期；销毁其中一个不会影响其它环境，也不会因为同租户被合并
  （即使第一次失败了，第二次不带标识的创建仍然是新环境）。

共同保证：

- 销毁仍幂等：后端清理失败停留 DESTROYING 可安全重试；已 DESTROYED 再销毁直接返回当前记录；
  抵达终态时配额精确归还一次。
- 若重试到达前环境已被销毁/回收，同一标识的创建重试返回该环境的终态记录，
  不再另开新环境；需要新环境请换新的标识提交。
- 后端 `RuntimeBackend` 所有操作幂等，可安全重试；`destroy` 同时负责清理开通失败的残留。

## 请求回执（Receipt）

创建/启动/停止/销毁都返回回执摘要，并可随时回查：

- 提交时带 `X-Request-Id`：用该标识认领与回查。
- 不带：服务端生成标识并随响应返回（`requestId` 字段），用它同样可以回查（老用法不破坏）。

回执三态（`status`）：

- `PROCESSING`：请求仍在处理中。同一 `X-Request-Id` 的并发提交不会重复执行，立即得到处理中结论，
  稍后用同一标识回查即可（接口不阻塞等待）。
- `SUCCEEDED`：成功。携带 `envId`、结论产生时的 `snapshotState`、以及 `stateChanged`
  （这次请求当初是否真的推动了一次状态迁移）。
- `FAILED`：失败。`retryable=true` 表示可安全重试（后端失败、配额超限、开通未完成、
  环境/租户校验失败等，环境未被改变）；`retryable=false` 表示阶段不成立，重发结果不变。

稳定性保证：

- 同一请求标识反复回查结论稳定；`SUCCEEDED` 与不可重试 `FAILED` 一旦产生即冻结，
  之后该环境发生的别的操作（别的 X-Request-Id）不会改写历史回执的 `snapshotState/stateChanged`。
- 用已成功的 X-Request-Id 再次提交同一操作：直接返回冻结结论，不再调用后端。
- 可重试 `FAILED` 用同一标识重试：成功后该回执从 FAILED 收敛为 SUCCEEDED，此后冻结。
- 同一 `X-Request-Id` 被不同操作类型复用（如先 stop 又 create）返回 `REQUEST_KIND_CONFLICT`（409）。
- 回执按租户隔离；本租户查不到的标识返回 `RECEIPT_NOT_FOUND`（404），不与环境的 404 混淆。

变更接口响应在原环境字段外新增：`requestId`、`receiptStatus`、`stateChanged`；
失败响应体新增 `requestId`。

## 多租户与配额

- 租户由请求头 `X-Tenant-Id` 标识；跨租户访问返回 `TENANT_FORBIDDEN`（HTTP 403），
  与 `ENV_NOT_FOUND`（404）可区分。
- 每租户配额 `orchestrator.max-envs-per-tenant`（默认 5），统计所有非 `DESTROYED` 环境。
  配额以 per-tenant CAS 计数器预留/归还，并发争用不超卖；超限返回
  `QUOTA_EXCEEDED`（HTTP 429），回执标记可重试。

## 租约与自动回收

- 环境记录 `lastActiveAt`，`POST /api/envs/{id}/keep-alive` 续约（仅 RUNNING/STOPPED）。
- `LeaseReaper` 定期扫描（`orchestrator.reaper-interval-ms`，默认 60s）：
  - `RUNNING` 空闲超过 `orchestrator.idle-stop-ttl`（默认 30m）→ 自动停止；
  - `STOPPED` 空闲超过 `orchestrator.idle-destroy-ttl`（默认 2h）→ 自动销毁，容量重新纳入配额；
  - `CREATING` 滞留超过 `orchestrator.provisioning-ttl`（默认 10m）→ 自动清理（含后端残留），
    配额归还；
  - 停留 `DESTROYING` 的残留每轮重试直至终态，不遗漏、不重复计数。
- 回收结果通过 `stopCause` 与手动操作区分：`MANUAL` vs `LEASE_EXPIRED` vs `PROVISION_TIMEOUT`。
- 时间全部来自可注入的 `Clock`，测试用 `MutableClock` 确定性推进，不依赖真实等待。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/envs` | 创建（body: `idempotencyKey` 可选；头 `X-Request-Id` 可选） |
| POST | `/api/envs/{id}/start` | 启动（可选 `X-Request-Id`） |
| POST | `/api/envs/{id}/stop` | 停止（MANUAL，可选 `X-Request-Id`） |
| DELETE | `/api/envs/{id}` | 销毁（幂等，可选 `X-Request-Id`） |
| GET | `/api/envs/requests/{requestId}` | **请求回执回查** |
| POST | `/api/envs/{id}/keep-alive` | 租约续约 |
| GET | `/api/envs/{id}` / `/api/envs` | 查询 / 租户内列表 |

回执体字段：`requestId, operation(CREATE/START/STOP/DESTROY), status(PROCESSING/SUCCEEDED/FAILED),
retryable, envId, snapshotState, stateChanged, failureReason, failureMessage, createdAt, finishedAt`。

失败响应体：`{"reason": "...", "message": "...", "requestId": "..."}`，reason 取值
`ENV_NOT_FOUND / TENANT_FORBIDDEN / QUOTA_EXCEEDED / ILLEGAL_STATE_TRANSITION / BACKEND_FAILURE /
ENV_STILL_PROVISIONING / REQUEST_IN_PROGRESS / REQUEST_KIND_CONFLICT / RECEIPT_NOT_FOUND`。

## 本地验证

```bash
# 全部测试（39 个用例）：创建重试合并、同标识合并不多占配额、开通阶段启停拒绝原因可区分、
# 开通在途销毁收敛、请求回执重试前后稳定，以及原有的并发创建、配额争用、租约回收不回归。
# 测试日志按业务打印（创建区分 / 阶段判定 / 回执 / 回收），可直接看出各用例覆盖的行为。
mvn test

# 启动本地服务
mvn spring-boot:run

# 1) 两次都不带 idempotencyKey：得到两个彼此独立的环境
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' \
     -H 'Content-Type: application/json' -d '{}'
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' \
     -H 'Content-Type: application/json' -d '{}'

# 2) 带同一 idempotencyKey + 同一 X-Request-Id 重试：合并到同一环境
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' \
     -H 'X-Request-Id: demo-req-1' -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"demo-1"}'
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' \
     -H 'X-Request-Id: demo-req-1' -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"demo-1"}'

# 3) 不带 idempotencyKey、只带同一 X-Request-Id 重试：同样合并到同一环境（CI 用法）
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' \
     -H 'X-Request-Id: ci-req-1' -H 'Content-Type: application/json' -d '{}'
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' \
     -H 'X-Request-Id: ci-req-1' -H 'Content-Type: application/json' -d '{}'

# 4) 回查该请求的回执（结论稳定）
curl -s localhost:8080/api/envs/requests/demo-req-1 -H 'X-Tenant-Id: t1'
curl -s localhost:8080/api/envs/requests/ci-req-1 -H 'X-Tenant-Id: t1'
```

关键单测与行为对应：

- `CreateRetryConvergenceTest`（本次修复的核心回归）：只带 X-Request-Id、不带幂等键的重试
  合并到同一环境且只占一份配额；不带任何标识的两次创建（含第一次失败）仍各自独立；
  开通在途时发起销毁，控制面 DESTROYED 与后端无实例始终一致；回执在失败→重试→后续操作
  全过程中结论稳定；CREATING 滞留超时被回收器清理、配额归还。
- `CreateDedupTest`：无标识多次创建各自独立、销毁不连带；同标识重试合并且不多占配额；
  失败后同标识重试只占一份配额。
- `PhaseGateTest`：开通未完成阶段 start/stop 返回 `ENV_STILL_PROVISIONING` 且原因含操作名；
  拒绝后不出现幻影状态；开通失败后重试或直接清理都收敛自洽；普通重复迁移仍是
  `ILLEGAL_STATE_TRANSITION` 且不可盲目重试。
- `ReceiptTest` / `ReceiptConcurrencyTest` / `ReceiptApiTest`：成功结论冻结、后续操作不改写；
  可重试失败收敛；同标识并发只执行一次、其余处理中；回执端点与 404/409 映射；无标识老用法仍可用。
- `ConcurrencyTest` / `EnvironmentServiceTest` / `LeaseReaperTest` / `EnvApiTest`：
  原有并发创建、配额争用、租约回收、HTTP 错误映射不回归。
