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
| CREATING | STOPPED / DESTROYING | 开通成功进入 STOPPED；失败可重试或清理 |
| STOPPED | RUNNING / DESTROYING | 启动 / 销毁 |
| RUNNING | STOPPED / DESTROYING | 停止 / 销毁 |
| DESTROYING | DESTROYED | 销毁完成；中途失败停留本状态，可重试 |
| DESTROYED | —（终态） | 任何迁移均被拒绝 |

- 非法或重复迁移（如 RUNNING→RUNNING、STOPPED→STOPPED、终态后任何操作）被拒绝，
  返回 `ILLEGAL_STATE_TRANSITION`（HTTP 409），错误信息中含判定依据
  （`Transitions.explain`：源状态、目标状态、允许的目标集合）。
- 每次迁移的日志都会打印：请求标识（requestId）、envId、租户、迁移前后状态、
  触发原因（cause）与判定依据（basis）。

## 幂等规则

- **创建**：以 `(tenantId, idempotencyKey)` 为幂等键。重试或并发重复提交返回同一环境，
  后端 `provision` 只执行一次，配额只计一次。开通中途失败记录停留 `CREATING`，
  用同一幂等键重试即可继续，不会产生第二个实例。
- **销毁**：幂等。后端清理失败时记录停留 `DESTROYING`，可安全重试；
  已 `DESTROYED` 再次销毁直接返回当前记录。抵达终态时配额精确归还一次。
- **后端操作**：`RuntimeBackend` 所有操作幂等，可安全重试；
  `destroy` 同时负责清理开通失败可能留下的残留实例。

## 多租户与配额

- 租户由请求头 `X-Tenant-Id` 标识；跨租户访问返回 `TENANT_FORBIDDEN`（HTTP 403），
  与 `ENV_NOT_FOUND`（404）可区分。
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
| POST | `/api/envs` | 创建（body: `tenantId`, `idempotencyKey`） |
| POST | `/api/envs/{id}/start` | 启动 |
| POST | `/api/envs/{id}/stop` | 停止（MANUAL） |
| POST | `/api/envs/{id}/keep-alive` | 租约续约 |
| DELETE | `/api/envs/{id}` | 销毁（幂等） |
| GET | `/api/envs/{id}` / `/api/envs` | 查询 / 租户内列表 |

失败响应体：`{"reason": "...", "message": "..."}`，reason 取值
`ENV_NOT_FOUND / TENANT_FORBIDDEN / QUOTA_EXCEEDED / ILLEGAL_STATE_TRANSITION / BACKEND_FAILURE`。

## 本地验证

```bash
# 全部测试（并发创建、重复提交、配额耗尽、非法迁移、租约回收等 18 个用例）
mvn test

# 启动本地服务后手动验证
mvn spring-boot:run
curl -X POST localhost:8080/api/envs -H 'X-Tenant-Id: t1' \
     -H 'Content-Type: application/json' \
     -d '{"tenantId":"t1","idempotencyKey":"demo-1"}'
```

测试日志中可看到每次迁移的 `[requestId] transition env=... tenant=... FROM -> TO cause=... | basis=...`
以及被拒绝操作的判定依据。
