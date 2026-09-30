# Preview Env Orchestrator（本地预览环境编排控制面）

用进程内可替换的运行后端（`RuntimeBackend` / `InProcessRuntimeBackend`）模拟环境实例，
对预览环境提供从创建到回收的全生命周期编排，保证多租户并发下结果一致且可解释。

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

| 当前状态 | 允许的迁移 | 说明 |
|---|---|---|
| CREATING | STOPPED / DESTROYING | 开通成功进入 STOPPED；失败停留 CREATING 可重试或直接清理 |
| STOPPED | RUNNING / DESTROYING | 启动 / 销毁 |
| RUNNING | STOPPED / DESTROYING | 停止 / 销毁 |
| DESTROYING | DESTROYED | 销毁完成；中途失败停留本状态，可重试 |
| DESTROYED | —（终态） | 任何迁移均被拒绝 |

- 非法或重复迁移（如 RUNNING→RUNNING、STOPPED→STOPPED、终态后任何操作）被拒绝，
  返回 `ILLEGAL_STATE_TRANSITION`（HTTP 409），错误信息中含判定依据
  （`Transitions.explain`：源状态、目标状态、允许的目标集合）。

### 按阶段判定操作是否“说得通”（前置校验）

生命周期操作在**调用后端之前**先判断该操作在环境当前阶段是否成立，拒绝原因彼此可区分，
绝不会出现“控制面显示已停止/运行中、后端却没有对应实例”的幻影状态：

| 操作 | 仅在该状态被接受 | 其它状态及拒绝原因 |
|---|---|---|
| start | STOPPED | CREATING → `ENV_NOT_PROVISIONED`；RUNNING → `ILLEGAL_STATE_TRANSITION`；DESTROYING → `ENV_TEARING_DOWN`；DESTROYED → `ILLEGAL_STATE_TRANSITION` |
| stop（手动） | RUNNING | CREATING → `ENV_NOT_PROVISIONED`；STOPPED → `ILLEGAL_STATE_TRANSITION`；DESTROYING → `ENV_TEARING_DOWN`；DESTROYED → `ILLEGAL_STATE_TRANSITION` |
| destroy | CREATING / STOPPED / RUNNING / DESTROYING | DESTROYED 幂等直接返回当前记录 |
| keep-alive | RUNNING / STOPPED | 其它状态 → `ILLEGAL_STATE_TRANSITION` |

- **开通未完成（CREATING）收到停止/启动**：明确拒绝为 `ENV_NOT_PROVISIONED`（HTTP 409），
  环境保持 CREATING，不调用后端、不产生实例；错误信息提示等待开通完成或用同一请求标识重试创建。
- **销毁中（DESTROYING）收到启动/停止**：拒绝为 `ENV_TEARING_DOWN`（HTTP 409）。
- 开通失败卡住的 CREATING 环境有两条自洽收敛路径：
  1. 用**同一请求标识/幂等键重试创建**，开通成功后进入 STOPPED，仍是同一环境、同一份配额；
  2. 直接 `destroy`，后端幂等清理（含可能的开通残留），抵达 DESTROYED 后配额精确归还一次。

- 每次迁移的日志都会打印：请求标识（requestId）、envId、租户、迁移前后状态、
  触发原因（cause）与判定依据（basis）。

## 请求标识与请求回执

- 所有写操作（创建/启动/停止/销毁）都可以通过请求头 **`X-Request-Id`** 显式携带请求标识；
  不传时服务端当次生成一个，并在响应体 `requestId` 字段回填。
- **环境去重键与请求标识解耦**：
  - 调用方显式携带同一个 `X-Request-Id` 的创建重试 → **合并到同一个环境**，后端只开通一次、
    配额只占一份（不会把真正的重复提交拆成两个实例）；
  - 两次创建**都不带**请求标识 → 服务端每次生成唯一标识，得到**两个相互独立**的环境，
    不会因为租户相同而被并成一个，销毁其中一个也不影响另一个；
  - body 里的 `idempotencyKey`（旧用法）仍然有效：以它为环境去重键，同键合并；
    即使两次请求的 `X-Request-Id` 不同，相同 `idempotencyKey` 仍合并到同一环境。
- **回查回执**：`GET /api/envs/requests/{requestId}`（带 `X-Tenant-Id`）返回：
  - `status`：`PROCESSING`（处理中）/ `SUCCEEDED`（成功，终态）/ `FAILED`（失败，可用同一标识安全重试）；
  - `operation`：CREATE / START / STOP / DESTROY；
  - `envId`：本次请求作用的环境；
  - `stateChanged`、`fromState`、`toState`：是否真的改变了状态以及迁移前后状态；
  - 失败时带可区分的 `failureReason` 与 `message`。
- **结论稳定**：成功（SUCCEEDED）为终态，之后无论该环境又发生什么操作，同一请求标识反复回查
  结论都不变；同一标识的重复提交直接复用历史结论，不再调用后端、不重复改变状态。
  同一请求标识若被复用到与首次不同类型的操作，返回 `REQUEST_CONFLICT`（HTTP 409）。
- 回执跨租户隔离：查他人标识返回 `TENANT_FORBIDDEN`（403）；标识不存在返回
  `REQUEST_NOT_FOUND`（404）。旧的不带标识调用方式照旧可用。

## 幂等规则

## 幂等规则

- **创建**：环境去重键为 `(tenantId, dedupKey)`，其中 `dedupKey` 依次取
  显式 `idempotencyKey` → 显式 `X-Request-Id` → 服务端每次生成的唯一值。
  因此“调用方带同一标识的重试/并发重复提交”返回同一环境，后端 `provision` 只执行一次、
  配额只计一次；“两次都不带标识的创建”是两个独立环境。开通中途失败记录停留 `CREATING`，
  用同一请求标识/幂等键重试即可继续，不会产生第二个实例。去重键的检查、登记与配额预留
  在同一个原子块内完成，并发下不重复登记、不多占配额。
- **销毁**：幂等。后端清理失败时记录停留 `DESTROYING`，可安全重试；
  已 `DESTROYED` 再次销毁直接返回当前记录。抵达终态时配额精确归还一次。
- **后端操作**：`RuntimeBackend` 所有操作幂等，可安全重试；
  `destroy` 同时负责清理开通失败可能留下的残留实例。
- **请求回执**：成功结论冻结后同一标识的重复提交不再调用后端；失败结论可用同一标识安全重试。

## 多租户与配额

- 租户由请求头 `X-Tenant-Id` 标识；跨租户访问（环境或请求回执）返回 `TENANT_FORBIDDEN`
  （HTTP 403），与 `ENV_NOT_FOUND` / `REQUEST_NOT_FOUND`（404）可区分。
- 每租户配额 `orchestrator.max-envs-per-tenant`（默认 5），统计所有非 `DESTROYED` 环境。
  配额以 per-tenant CAS 计数器预留/归还，并发争用不超卖；超限返回
  `QUOTA_EXCEEDED`（HTTP 429）。

## 租约与自动回收

- 环境记录 `lastActiveAt`，`POST /api/envs/{id}/keep-alive` 续约（仅 RUNNING/STOPPED）。
- `LeaseReaper` 定期扫描（`orchestrator.reaper-interval-ms`，默认 60s）：
  - `RUNNING` 空闲超过 `orchestrator.idle-stop-ttl`（默认 30m）→ 自动停止；
  - `STOPPED` 空闲超过 `orchestrator.idle-destroy-ttl`（默认 2h）→ 自动销毁，容量重新纳入配额；
  - 停留 `DESTROYING` 的残留每轮重试直至终态，不遗漏、不重复计数。
- 回收结果通过 `stopCause` 与手动操作区分：`MANUAL` vs `LEASE_EXPIRED`。
- 时间全部来自可注入的 `Clock`，测试用 `MutableClock` 确定性推进，不依赖真实等待。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/envs` | 创建（body: `tenantId`, `idempotencyKey`；可选头 `X-Request-Id`） |
| POST | `/api/envs/{id}/start` | 启动（可选头 `X-Request-Id`） |
| POST | `/api/envs/{id}/stop` | 停止（MANUAL，可选头 `X-Request-Id`） |
| POST | `/api/envs/{id}/keep-alive` | 租约续约 |
| DELETE | `/api/envs/{id}` | 销毁（幂等，可选头 `X-Request-Id`） |
| GET | `/api/envs/requests/{requestId}` | 回查请求回执 |
| GET | `/api/envs/{id}` / `/api/envs` | 查询 / 租户内列表 |

写操作响应体在原有字段外包含 `requestId`（调用方传入则原样回填，否则为服务端生成值），
用它即可回查回执。

失败响应体：`{"reason": "...", "message": "..."}`，reason 取值
`ENV_NOT_FOUND / TENANT_FORBIDDEN / QUOTA_EXCEEDED / ILLEGAL_STATE_TRANSITION /
ENV_NOT_PROVISIONED / ENV_TEARING_DOWN / REQUEST_NOT_FOUND / REQUEST_CONFLICT / BACKEND_FAILURE`，
对应 HTTP 状态：404 / 403 / 429 / 409 / 409 / 409 / 404 / 409 / 502。

## 本地验证

```bash
# 全部测试（去重与独立创建、阶段拒绝原因、请求回执、并发创建、配额争用、租约回收等 41 个用例）
mvn test

# 启动本地服务后手动验证
mvn spring-boot:run

# 1) 带同一请求标识的重试 -> 合并到同一环境
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' -H 'X-Request-Id: demo-1' \
     -H 'Content-Type: application/json' -d '{"tenantId":"t1"}'
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' -H 'X-Request-Id: demo-1' \
     -H 'Content-Type: application/json' -d '{"tenantId":"t1"}'   # envId 与上一次相同

# 2) 回查该请求的最终回执
curl -s localhost:8080/api/envs/requests/demo-1 -H 'X-Tenant-Id: t1'

# 3) 两次都不带标识 -> 两个独立环境（响应体 requestId 不同，envId 也不同）
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' \
     -H 'Content-Type: application/json' -d '{"tenantId":"t1"}'
curl -s -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' \
     -H 'Content-Type: application/json' -d '{"tenantId":"t1"}'

# 4) 启动/停止携带各自的请求标识，再分别回查
curl -s -X POST localhost:8080/api/envs/{envId}/start  -H 'X-Tenant-Id: t1' -H 'X-Request-Id: s-1'
curl -s -X POST localhost:8080/api/envs/{envId}/stop   -H 'X-Tenant-Id: t1' -H 'X-Request-Id: x-1'
curl -s localhost:8080/api/envs/requests/s-1 -H 'X-Tenant-Id: t1'
```

测试日志中可看到每次迁移的 `[requestId] transition env=... tenant=... FROM -> TO cause=... | basis=...`
以及被拒绝操作的判定依据与可区分原因（如 `ENV_NOT_PROVISIONED`）。
