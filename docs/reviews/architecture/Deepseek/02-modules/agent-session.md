# 模块报告：会话与执行器（`agent/`）

覆盖：`AgentLoop`、`SessionRuntime`、`SessionInbox`、`SessionEvent`、`ExecutionContext`、`CronProducer`。

## 1. 模块职责

`agent` 包是 JadeCode 的心脏，但内部有清晰的三层（与 ADR 0002 的术语一致）：

- **生产者**：`cli`、`CronProducer`、工具批次/后台完成/Stop 续跑。它们只提交 `SessionEvent` 或“有新事情”的信号，不直接改历史。
- **会话运行器**：`SessionRuntime`。拥有 `history`、当前 `Active` 任务、状态机，决定领取/继续/等待/结束/交付确认/失败收尾。
- **任务执行器**：`AgentLoop`。落实模型请求、工具执行、压缩触发；不管会话级排队。

`SessionInbox` 是运行器唯一的新任务入口（有界 64，FIFO）；`ExecutionContext` 是贯穿工具与子代理的“任务身份”协议。

## 2. 设计上合理的地方

- **单写者 + 事件采用**：`SessionRuntime.apply`（`SessionRuntime.java:365-390`）是历史写入的唯一入口，所有生产者都靠它落地。事件记录在构造时复制列表（`SessionEvent.java:42,58,84-87,108`），避免交出去后又被调用方增删。
- **等后台不忙等**：`SessionInbox.revision/awaitChange`（`SessionInbox.java:63-75`）+ `SessionRuntime.awaitResults`（`342-349`）用“先读计数、再查结果、才睡”的顺序避免丢唤醒，且等待期间不轮询模型、不领取下一项任务。
- **关闭顺序有讲究**：`close()` 先标记退出、停定时与命令、清队列，再完成 future，最后才中断 consumer 并 join 2 秒（`508-530`），并解释“完成 future 可能立即执行调用者代码，不能拖延停止工具”。
- **兼容入口复用主干**：旧 `AgentLoop.run(List)` 通过 `SessionRuntime.runExisting`（`478-495`）复用同一 `execute`，没有第二套循环；返回时不关闭 engine，允许调用者继续该历史。
- **审批来源可信**：`PermissionGate.check(toolName, input, context)` 只按 `ExecutionContext.source` 决定能否询问用户，定时任务即使传了 approval 也一律拒绝（`PermissionGate.java:108-112`），杜绝“模型自称用户任务”。

## 3. 已证实的问题

### ARCH-006（P1，有依据的潜在风险）：父任务循环没有任何轮数/调用/成本上限

**现状**：`SessionRuntime.runTask` 是 `while (!closed)`（`SessionRuntime.java:261`），没有 turn 计数；`AgentLoop` 也没有“每任务最多 N 次模型请求 / N 次工具调用”的概念。已存在的唯一抑制手段是“连续 3 批未更新 todo 时追加 `<reminder>`”（`AgentLoop.java:348-352`），它只是提示，不设硬上限。

**为什么是问题**：模型可以持续产出工具调用，任务永不结束。与子代理不同，子代理有 `MAX_TURNS = 30` 硬上限（`SubagentRuntime.java:41`）。也就是说：**子任务有界，父任务无界**——保护恰好装反了方向，父任务才是长期使用中成本与队头阻塞的主要来源。

**影响条件**：模型陷入重复探索/失败重试循环时；自动化（cron）触发尤其危险，因为它无人看着。

**影响范围**：单会话可用性与 API 成本；队头阻塞会使后续排队请求全部延迟。

**证据强度**：已证实（源码无计数）。

**建议**：在 `Active` 上加 `turns`/`toolCalls`/`elapsed`，在 `runTask` 每轮检查，超限时走一条明确的“预算耗尽”收尾路径（写一条 `SessionEvent.Continue` 让模型给出总结后结束，或直接 `fail`，视产品取舍），并把上限做成配置项。收益是可控成本；代价是要决定“超限是否算失败”，以及不能破坏定时交付确认的既有语义。

### ARCH-012（P3，可选优化）：legacy 执行路径与主路径并存

**现状**：`AgentLoop.run(List)`（`198-200`）→ `SessionRuntime.runExisting`（`478-495`）是保留给测试与旧调用者的路径。它内部重复了构造、execute、future join、收尾与 `background.onCompletion` 复位。

**为什么是问题**：一个 agent 有两套入口，会持续诱使新功能只接到其中一套；`runExisting` 还把 `runtime.background.onCompletion` 改回 no-op，属于对共享管理器的全局副作用。

**证据**：`AgentLoop.java:188-200`、`SessionRuntime.java:467-495`。

**建议**：确认 `runExisting` 的真实调用方（目前主要是 `AgentLoopTest` 一类测试），把测试改成直接用 `SessionRuntime`；若确无生产调用，删除该方法并在 `AgentLoop` 上标注 `run` 的兼容边界。

### ARCH-013（P3，可选优化）：`AgentLoop` 的 5 个重载构造器

**现状**：`AgentLoop` 有 5 个构造器逐步加参数（`84-158`），最终 9 参数版本包含 `compactor/memory/background/cron`。

**为什么是问题**：参数顺序一旦弄错（例如把 `background` 与 `cron` 对调）编译器不一定报错，可读性也差。当前 `Main` 只用一个版本，其余重载服务于测试。

**建议**：改成一个 `AgentLoop.Dependencies`（record）或 builder，重载只保留“极简测试用”的一两个。

### ARCH-022（P3，可选优化）：运行器内嵌生产者的定时语义

**现状**：`SessionRuntime` 自己持有 `CronProducer`，并在 `execute`（`210-218`）判断 `canDeliver/pending`、在 `fail`（`405-410`）做 `restore/release/failed/received`。ADR 0002 的意图是“生产者只提交事件、运行器不接管具体入口”，但 cron 的交付确认/退避仍写在运行器里，且 `SessionEvent.Request` 带了 `cronId` 字段。

**影响**：`SessionRuntime` 需要懂 cron 的 pending/lease 语义；任何新生产者（例如未来的 workflow、goal loop）如果也需要“交付确认”，会继续往这个类里加分支。

**建议**：把“交付确认/失败重投”抽象成一个由生产者实现的 `DeliveryTracker` 接口（`received/failed/release/pending`），`Request` 只携带一个 `Runnable onDelivered` / 一个 tracker 引用，运行器不再 import `cron` 包。这样运行器只懂“任务成功开始/失败”两个回调点。

### ARCH-023（P2，可选优化）：历史所有权只靠约定，没有类型级保护

**现状**：`AgentLoop.request(..., Consumer<SessionEvent> events)`（`253-254`）把 `Consumer` 交给执行器，执行器通过 `events.accept(new HistoryPrepared(...))` 间接改历史。`SessionRuntime` 传的是 `this::apply`，但类型上任何 `Consumer` 都能接收，且 `apply` 内部会 `ensureOpen()`；如果将来有人传另一个 Consumer，历史所有权就静默转移了。

**建议**：把回调类型收窄成一个专门的接口（例如 `HistorySink { void replace(List<Message>); void append(Message); }`），只暴露两种受控操作，而不是通用的 `Consumer<SessionEvent>`。收益是“谁能改历史”在类型上可见；代价是所有调用点要改签名。

## 4. 并发与状态一致性观察（未单列编号）

- **future 双完成可容忍**：`active` 是 volatile（`SessionRuntime.java:72`），`close()` 从 CLI 线程读它并完成 future（`519-520`）；`Active` 的其它字段只由 consumer 写。`Active.entry.result()` 可能被 `close()` 完成、随后又被 `runTask` 的成功路径完成——第二次 `complete` 返回 false，不会抛异常。没有发现状态不一致，但这条容忍靠 `CompletableFuture` 语义而非显式设计；如需更明确，可在成功路径前加 `isDone()` 守卫。
- **入库失败原因不分流**：`submit` 在 `offer` 失败时统一抛 `RejectedExecutionException("Session inbox is full or closed")`（`148-150`），用户/日志无法区分“队列满”与“会话已关闭”。属可观测性小问题，不影响正确性。
- **`Active` 与 `entry` 的关系**：`Active` 保存原始 `entry` 与（仅定时任务的）历史快照，注释解释了为什么普通任务不复制长历史（`446-456`）。这是合理取舍。

## 5. 反例与缓和因素

- 不能把“无轮数上限”直接等同于“一定会失控”：多数任务会在几十轮内收敛，todo 提醒也起到一定作用。定级为 P1 是因为后果（成本/队头阻塞）与触发条件（自动化）都比较现实，而不是因为已经观察到失控。
- `SessionRuntime` 偏胖不等于错误：ADR 0002 明确要求“单一消费者拥有历史与当前任务”，把调度权分散出去反而会违背该决策。这里的建议（ARCH-022/023）是**收窄接口**，不是拆散所有权。

## 6. 修改成本与顺序

| 编号 | 收益 | 成本 | 回归风险 |
| --- | --- | --- | --- |
| ARCH-006 | 高（成本可控、避免队头阻塞） | 中 | 中（要定义超限语义，可能影响定时确认） |
| ARCH-023 | 中（所有权显式化） | 中 | 中（签名改动波及 AgentLoop 全部入口） |
| ARCH-022 | 中（运行器不再懂 cron） | 中 | 低 |
| ARCH-012/013 | 低-中（可读性） | 低 | 低 |
