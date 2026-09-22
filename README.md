# Preview Environment Orchestrator（本地预览环境编排控制面）

一套**本地**预览环境编排控制面：用进程内可替换的运行后端（`RuntimeBackend`）模拟真实环境实例，
在同一 JVM 内完整实现环境「创建 → 启动 → 停止 → 销毁」的生命周期，并在多租户并发请求下保证
**状态一致、配额不超卖、重复提交不多开、租约可自动回收**。

---

## 1. 运行与本地验证

需要 JDK 21+、Maven 3.9+。

```bash
# 编译
mvn -DskipTests compile

# 运行全部测试（26 个，含并发、配额、失败重试、租约回收、REST 集成）
mvn test

# 只跑某一类场景
mvn -Dtest=ConcurrentCreationAndQuotaTest test      # 并发创建/配额超卖
mvn -Dtest=LeaseReclaimTest test                    # 确定性时间推进 + 租约回收
mvn -Dtest=FailureRetryTest test                    # 中途失败重试与残留清理
mvn -Dtest=EnvironmentApiIntegrationTest test       # REST/错误码/请求标识

# 启动服务（默认关闭自动回收器，便于观察）
mvn spring-boot:run

# 打开定时自动回收（生产式运行）
java -jar target/*.jar --orchestrator.lease.enabled=true
```

### 快速手动验证（HTTP）

```bash
# 创建（必须带 Idempotency-Key）
curl -sS -X POST http://localhost:8080/api/tenants/t1/environments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: create-1' \
  -d '{"image":"my-app:latest"}'

# 查询 / 列表
curl -sS http://localhost:8080/api/tenants/t1/environments
curl -sS http://localhost:8080/api/tenants/t1/environments/<envId>

# 停止 / 启动 / 销毁
curl -sS -X POST http://localhost:8080/api/tenants/t1/environments/<envId>/stop    -H 'Idempotency-Key: stop-1'
curl -sS -X POST http://localhost:8080/api/tenants/t1/environments/<envId>/start   -H 'Idempotency-Key: start-1'
curl -sS -X POST http://localhost:8080/api/tenants/t1/environments/<envId>/destroy -H 'Idempotency-Key: destroy-1'
```

每个响应/错误都会回传 `X-Request-Id`（也可由客户端通过该请求头指定）。

---

## 2. 生命周期状态机

```
                 create
   (不存在) ───────────────► CREATING ──成功──► RUNNING
                               │                  │  ▲
                               │失败              │  │ start（从 STOPPED/FAILED 恢复）
                               ▼                  │  │
                            FAILED ◄──────────────┘  │
                               │  ▲                  │ stop
                    destroy /  │  │ start 重试       ▼
                    (任意非终态)│  │               STOPPING ──成功──► STOPPED
                               ▼  │                  │                 │
                            DESTROYING ◄────destroy──┴────destroy──────┤
                               │                                       │
                               │失败                                   │（空闲超时）
                               ▼                                       ▼
                            FAILED （残留资源，可重试）          自动/手动销毁
                               │
                  成功 deprovision ▼
                            TERMINATED（终态，配额已归还）
```

### 状态

| 状态 | 含义 |
| --- | --- |
| `CREATING` | 已接受创建、正在 provision + start |
| `RUNNING` | 实例进程运行中，占用一个租户配额 |
| `STOPPING` | 正在停止进程（资源仍保留） |
| `STOPPED` | 进程已停止，后端资源仍在，可快速 `start` |
| `STARTING` | 正在从 STOPPED/FAILED 恢复运行 |
| `DESTROYING` | 正在释放后端资源 |
| `TERMINATED` | 终态：资源已释放，配额已归还 |
| `FAILED` | 某次后端操作中途失败；环境仍被跟踪，可用**相同操作重试**收敛 |

### 允许的迁移

| 操作 | 合法源状态 | 目标 |
| --- | --- | --- |
| create | 不存在；`CREATING/FAILED`（重试） | `RUNNING` |
| start | `STOPPED`、`STARTING`、`FAILED` | `RUNNING` |
| stop | `RUNNING`（回收器还可重试 `FAILED+STOP_FAILED`） | `STOPPED` |
| destroy | **任意非终态**（含所有 in-flight 与 `FAILED`） | `TERMINATED` |

- 非法迁移（如对 `RUNNING` 再 start、对 `STOPPED` 再 stop、对 `TERMINATED` 再操作）一律
  **拒绝**，错误码 `INVALID_STATE_TRANSITION`，消息中给出判定依据。
- 被拒绝的迁移**不改变状态、不写状态历史**。
- 每次成功迁移都会在 `transitions` 审计历史中记录 `(from, to, reason, at)`。

### 迁移原因（可解释性）

`CREATE_REQUESTED`、`START_REQUESTED`、`STOP_REQUESTED`、`DESTROY_REQUESTED`
以及三个失败原因 `CREATE_FAILED`、`START_FAILED`、`STOP_FAILED`、`DESTROY_FAILED`，
和两个**自动租约**原因 `IDLE_STOP_LEASE`、`IDLE_DESTROY_LEASE`。

> 手动停止与租约自动停止在状态历史和 `lastReason` 上始终可区分：
> `STOP_REQUESTED` vs `IDLE_STOP_LEASE`；销毁同理（`DESTROY_REQUESTED` vs `IDLE_DESTROY_LEASE`）。

---

## 3. 幂等与去重规则

- **所有写请求必须携带 `Idempotency-Key`**（请求头，或创建请求体中的 `idempotencyKey`）。
- 幂等作用域为 `(操作类型, 租户/环境)`：
  - 创建：`create:<tenantId>`，环境 ID 由 `tenantId + key` **确定性派生**；
  - start/stop/destroy：`<op>:<envId>`。
- `IdempotencyStore` 对每个 key 加监视器，保证：
  1. **并发重复提交只执行一次**：其余请求在 key 上等待并拿到同一份结果
     —— 无论多少并发重试，**绝不会产生第二个环境实例**（见 50 并发测试）。
  2. **成功结果被缓存**：重复提交（含网络重试）原样返回同一个 envId，不再触碰后端。
  3. **确定性拒绝被缓存**：如 `INVALID_STATE_TRANSITION`、`TENANT_MISMATCH`，
     同一 key 重放得到相同错误码，且不会再产生副作用。
  4. **瞬时失败不缓存**：`BACKEND_FAILURE`、`QUOTA_EXCEEDED` 直接抛出、不固化结果，
     允许随后重试（配额恢复后即可成功）。
  5. **作用域冲突可区分**：同一个 key 被用于不同操作时返回 `IDEMPOTENCY_CONFLICT`。

---

## 4. 多租户隔离与配额

- 每个环境记录 `tenantId`；跨租户的 start/stop/destroy 返回 `403 TENANT_MISMATCH`，
  跨租户 GET 返回 `404 ENV_NOT_FOUND`（不暴露资源是否存在）。
- 配额按**非终态环境**计数：创建被接受时**先原子预留**（CAS，见 `QuotaService`），
  只有真正 `TERMINATED` 后才**精确释放一次**。
- 并发争抢同一份配额时，超出者得到 `429 QUOTA_EXCEEDED`，**不会超卖**
  （24 并发 / 配额 2 的测试中恰有 2 个成功、其余全部得到该错误码）。
- 创建中途失败时环境处于 `FAILED`，配额仍被占用（残留被跟踪）；重试使用同一预留，
  **不会二次计数**；销毁成功后才归还。
- 默认配额 `orchestrator.quota.default=2`，可通过配置或 `QuotaService#setQuota` 覆盖。

---

## 5. 租约与自动回收

规则全部是关于时间的**纯函数**（`LeasePolicy`），系统不直接读墙钟，而是注入
`TimeSource`；测试中使用可手动推进的 `TestClock`，因此时间相关用例**确定性复现、零真实等待**。

- `RUNNING` 环境自**最后一次活动**起空闲超过 `orchestrator.lease.idle-stop-millis`（默认 30s）
  → 自动停止，原因 `IDLE_STOP_LEASE`。
- `STOPPED` 环境自停止时刻起保留超过 `orchestrator.lease.idle-destroy-millis`（默认 60s）
  → 自动销毁并归还配额，原因 `IDLE_DESTROY_LEASE`。
- 活动时间只在 create / 手动 start / 手动 stop 成功时刷新；**自动迁移本身不刷新活动时间**，
  保证回收判定单调。
- `LeaseReaper` 每次 tick 至多回收一个环境，可反复调用直至清空；
  默认通过 `orchestrator.lease.enabled=false` **关闭**定时任务，
  置为 `true` 时按 `orchestrator.lease.interval-millis`（默认 5s）调度。

### 失败可重试、无残留

- 后端通过故障注入模拟「半成品」：`provision` 可能已部分分配资源后失败、
  `deprovision` 失败时资源保留。
- 任何中途失败都把环境置为 `FAILED` 并写明失败原因，后端资源与配额都保持可追溯。
  - 用**同一个幂等键重试 create/destroy** 即可收敛，配额绝不重复计数。
  - 回收器在后续扫描中发现 `FAILED + 资源仍存在` 的环境会**自动重试清理**，
    扫描期故障返回 `RETRY` 而不是中断，最终不留遗漏、不留重复计数。

---

## 6. 并发模型（为什么不会互相覆盖）

| 竞争对象 | 机制 |
| --- | --- |
| 同一个环境的并发迁移 | 每个 envId 一把 `ReentrantLock`，同环境串行、不同环境并行 |
| 同一份租户配额 | `AtomicInteger` + CAS 预留，失败立即拒绝，无超卖 |
| 同一幂等键的重复提交 | key 级监视器 + 完成态/进行中态，重复请求等待并共享同一结果 |
| 回收器与用户请求 | 同样获取每环境锁；用户 destroy 与自动回收不会交叉覆盖 |

---

## 7. 可观测性：判定日志

控制面对每次判定打印结构化日志（SLF4J + MDC），包含**请求标识、迁移前后状态与判定依据**：

```
requestId=test-illegalTransitions op=START tenant=t1 env=env-9d7a… RUNNING -> RUNNING basis=[REJECTED: environment is already RUNNING; duplicate transition]
requestId=dup-worker-15 op=CREATE tenant=t1 env=env-9d7a… CREATING -> RUNNING basis=[backend provision+start succeeded]
requestId=reaper-1010000 op=IDLE-STOP tenant=t1 env=env-9d7a… RUNNING -> STOPPED basis=[idle lease expired; auto-stopped]
```

- HTTP 请求：`RequestIdFilter` 读取/生成 `X-Request-Id` 放入 MDC；
- 单元测试：`TestRequestIdExtension` 为每个测试注入 `test-<方法名>`，并发工作线程各自带
  `dup-worker-N` / `quota-worker-N` / `transition-worker-N`。日志同时进入 surefire 报告。

---

## 8. 错误码与 HTTP 映射

| ErrorCode | HTTP | 场景 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | 缺少 image / Idempotency-Key / tenantId |
| `ENV_NOT_FOUND` | 404 | 环境不存在（或属于其他租户的 GET） |
| `TENANT_MISMATCH` | 403 | 跨租户操作他人环境 |
| `INVALID_STATE_TRANSITION` | 409 | 非法/重复的状态迁移 |
| `IDEMPOTENCY_CONFLICT` | 409 | 幂等键被不同操作复用 |
| `QUOTA_EXCEEDED` | 429 | 租户配额耗尽（瞬时，可重试） |
| `BACKEND_FAILURE` | 503 | 后端实例操作失败（瞬时，可重试） |

错误响应体：`{"requestId": "...", "errorCode": "...", "message": "..."}`。

---

## 9. 代码结构

```
domain/      EnvState、TransitionReason、PreviewEnvironment（含审计历史）
backend/     RuntimeBackend（可替换接口）+ InProcessRuntimeBackend（含故障注入）
store/       EnvironmentRepository（并发 Map）
quota/       QuotaService（每租户 CAS 配额）
idem/        IdempotencyStore（并发去重/重放）、IdempotencyOutcome
lease/       LeasePolicy（时间纯函数）、LeaseReaper（定时/手动 tick）
service/     EnvironmentOrchestrator（状态机 + 锁 + 判定日志，控制面核心）
api/         EnvironmentController、Dtos、GlobalExceptionHandler
config/      RequestIdFilter、SchedulingConfig
time/        TimeSource / SystemTimeSource（测试用 TestClock 替换）
```

## 10. 配置项（application.properties）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `orchestrator.quota.default` | `2` | 每租户默认环境配额 |
| `orchestrator.lease.enabled` | `false` | 是否启用定时自动回收 |
| `orchestrator.lease.interval-millis` | `5000` | 回收器 tick 间隔 |
| `orchestrator.lease.idle-stop-millis` | `30000` | RUNNING 空闲多久后自动停止 |
| `orchestrator.lease.idle-destroy-millis` | `60000` | STOPPED 保留多久后自动销毁 |
