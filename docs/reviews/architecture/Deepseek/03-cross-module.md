# 阶段三：跨模块分析

本报告只写“单独看一个模块看不到”的东西：状态由谁拥有、异常怎么跨边界传播、并发与资源谁负责、几个机制如何拼在一起、哪些逻辑在多个模块重复。

## 1. 状态所有权地图

| 状态 | 拥有者 | 其它模块如何接触 | 风险 |
| --- | --- | --- | --- |
| `history`（消息列表） | `SessionRuntime`（consumer 线程） | 只能通过 `apply(SessionEvent)`；`AgentLoop.request` 通过 `Consumer<SessionEvent>` 间接改 | 所有权靠约定，见 ARCH-023 |
| 当前任务 `Active`（goal/system/rounds/received/receipts） | `SessionRuntime` | `AgentLoop` 通过参数接收 `activeRequest/taskSystem/previousRounds` | 无 |
| `background` 作业与结果 | `BackgroundManager` | `SessionRuntime` 通过 `collect/acknowledge/restore/cancelRequired` | 无 |
| 定时定义与 pending 状态 | `CronScheduler` | `CronProducer` 租借/确认；`SessionRuntime` 通过 `CronProducer` 间接 | 运行器内嵌 cron 语义，见 ARCH-022 |
| 长期记忆 | `MemoryStore`（磁盘） | `MemoryManager` 串联三服务 | 取消不继承，见 ARCH-017 |
| 持久任务图 | `TaskStore`（磁盘） | `TaskTools` 适配 | 生命周期不完整，见 ARCH-034 |
| Todo | `TodoManager`（内存） | `TodoWriteTool` | 提醒计数在 `AgentLoop`，见子代理报告 |
| 压缩落盘 | `ToolOutputStore`/`TranscriptStore`（磁盘） | `ContextCompactor` 持有 | 父子共用目录，见 ARCH-026 |
| 终端/输出 | `TerminalDisplay`（进程级 `System.out/err`） | 全项目 `println` 间接使用 | 全局可变状态，见 CLI 报告 |

**总体判断**：状态所有权在“谁改历史、谁改命令表”这两点上清楚；在“日志输出、配置查询、工具名常量”这些**全局/隐式状态**上散落。这正是包结构报告（ARCH-004）与日志报告（ARCH-008）要解决的。

## 2. 完整执行链路的关键交接点

### 2.1 后台完成 → 会话恢复

`BackgroundManager.run` 完成后：放进 `ready` → 锁外调用 `listener`（`inbox::wake`）→ `SessionRuntime.awaitResults` 被唤醒 → `deliverResults` 取出 → `apply(Notifications)` 合并进最后一条 USER → `continue` 顶部重新请求模型。

这里有一个**跨模块的设计契约**必须被同时理解，否则容易改坏：

- `collect` 只“取出”，`acknowledge` 才删除（`BackgroundManager.java:155-196`）；
- `Notifications` 必须**合并**进最后一条 USER，而不是新增 USER（`SessionRuntime.java:358-361,379-388`），因为 `MicroCompactor` 用“最后一条 assistant 之前的结果都算已消费”来界定保护范围（`MicroCompactor.java:58-79`）。

也就是：`agent` 的写历史方式，被 `context` 的保护算法反向约束。这是 ARCH-024 记录的核心。它目前靠注释维系，没有断言或类型保证。

### 2.2 定时交付 → 模型确认 → 落盘

`CronProducer.poll` → `CronScheduler.pollDue`（pending 写盘）→ `takeDue`（lease）→ `publishCron`（入队）→ `SessionRuntime.execute`（`canDeliver/pending` 复核）→ 模型首次正式回复 → `cron.received` → `CronScheduler.acknowledge`（写盘确认）。

这条链的每一段都做了正确的“先持久化再前进”，但它要求三处对“什么是首次正式回复”有完全一致的理解：`SessionRuntime.runTask` 在 `apply(reply)` 之后、工具执行之前标记 `received`（`266-270`），并且明确“查记忆或生成摘要不算”（`AgentLoop` 的辅助调用不产生 `ModelReply`）。这个定义目前只写在注释里，没有一个共享的“这是一个正式回合”的类型来承载。若未来 `AgentLoop` 增加新的“内部模型调用往返”，必须记得它不能触发 `received`。属于与 ARCH-022/ARCH-023 同源的“接口不够显式”。

### 2.3 异常如何跨边界传播

| 抛出点 | 类型 | 跨过哪些边界 | 最终处理 |
| --- | --- | --- | --- |
| `LlmClient.complete` | `LlmException` | `AgentLoop.request` → `SessionRuntime.runTask` → `execute` | 任务 future 失败；定时项首回复前回滚并重投 |
| 压缩摘要失败 | `LlmException` | `HistoryCompactor` → `ContextCompactor` → `AgentLoop` → 同上 | 同上 |
| 落盘/归档失败 | `IllegalStateException` | `ToolOutputStore/TranscriptStore` → `ContextCompactor` → 同上 | 同上（内存历史不变） |
| 工具/`PostToolUse` 异常 | `RuntimeException` | `AgentLoop.executeTools` 捕获成 `ToolBatch.failure` → `runTask` 抛出 | 整任务失败，见 ARCH-011 |
| 工具正常失败 | 返回 `"Error: ..."` 文本 | 作为 `tool_result` 交给模型 | 模型决定下一步（不失败） |
| 子代理模型失败 | `LlmException` | `SubagentRuntime` → `SubagentTool.execute` 捕获 → 返回 `"Error: ..."` | 父拿到工具结果，任务继续 |
| 子代理其它异常 | `RuntimeException` | `SubagentRuntime` → `SubagentTool.execute` | 穿过父 `executeTools` → 整任务失败 |
| 记忆 IO 失败 | `IOException` | 记忆服务 → `MemoryManager` 捕获 | 只告警，不失败主任务 |
| 后台命令失败 | 返回 `Result` | `BackgroundManager.Completion` | 作为通知给模型（等待完成 ≠ 等待成功） |

**结论**：异常语义整体上是“可预期失败变文本、意外异常变任务失败、辅助能力失败只告警”，这个分层是清楚的。两个值得改进点已经分别记录：`ARCH-011`（工具异常不该升级为整任务失败）与 `ARCH-032`（子代理只对 `LlmException` 统一收尾，其它异常穿到父层）。

另外有一个**跨模块的隐形风险**：`SubagentTool.execute` 捕获 `LlmException` 并返回 `"Error: ..."`，但父执行器又把该文本当作普通工具结果。于是一个“子代理彻底失败”（例如父会话已关闭）在父看来只是一条普通 `tool_result`，模型可能据此再试一次。当前 `checkSessionOpen` 抛出的是 `LlmException`，所以父会话关闭时**不会**在这里被识别为“应当终止”。不过父执行器在工具执行前后都会检查 `context.isCancelled()`（`AgentLoop.java:315-316,328-330`），所以下一次工具前会中止。这个组合目前是对的，但依赖两条路径同时生效，理解门槛高。建议在 ARCH-002 的 `ToolDispatcher` 里把“取消导致的失败”与“普通工具失败”区分开。

## 3. 并发与资源

### 3.1 并发模型

- **单写者**：只有 `jade-session-consumer` 线程改 `history`；主线程/定时线程只入队或 `wake`。
- **volatile 字段**：`SessionRuntime.closed/state/active`、`AgentLoop.closed`、`CronProducer.closed/failures/nextAttempt/lastPollError`。使用一致。
- **锁**：`SessionInbox`（自身）、`BackgroundManager`（自身）、`ShellCommand.processes`（`processes` 对象）、`CronScheduler`（方法级 `synchronized`）、`ConsoleInput`（自身）。没有发现嵌套锁顺序不一致的路径；`BackgroundManager.run` 明确“先取 listener、锁外调用”来避免与 inbox 锁互等。
- **虚拟线程**：`ShellCommand` 的读输出线程（`ShellCommand.java:86-87`）、`BackgroundManager` 的后台命令线程（`107`）、`SubagentRuntime` 若被放后台（目前没有）。都是 daemon 语义（会话退出时由各自 close 清理），合理。

**未被保护的假设**：
1. 同一工作区只有一个 CLI 进程（任务图、cron、记忆三处都这么说）；
2. 父与子不会真正并发执行（子代理同步跑在父线程上）；
3. `ContextCompactor` 可被父子共享是因为“不会并发调用”。

其中第 2 点一旦被 ARCH-016 的“子代理后台化”打破，第 3 点也会失效（`ContextCompactor` 本身无状态，但 `ToolOutputStore` 落盘的“同 ID 复用”依赖串行）。这两条应在做任何异步化之前明确写进契约。

### 3.2 资源所有权

| 资源 | 创建者 | 关闭者 | 备注 |
| --- | --- | --- | --- |
| `scheduled tasks` 线程 | `CronProducer.start` | `CronProducer.close`（由 `SessionRuntime.close` 调用） | daemon |
| consumer 线程 | `SessionRuntime.start` | `SessionRuntime.close`（interrupt + join 2s） | daemon |
| 后台命令进程组 | `BackgroundManager.start` | `ShellCommand.close` / `BackgroundManager.close` | 退出时全部清理 |
| JVM shutdown hook | `ShellCommand.run`（首次） | `ShellCommand.close` | 每个活跃 shell 一个 hook |
| JLine 终端 / `System.out/err` | `TerminalDisplay.open` | `TerminalDisplay.close` | 恢复原流 |
| LLM HTTP 客户端 | `AnthropicSdkLlmClient` 构造（SDK 内部） | **没有显式关闭** | 进程退出时由 JVM/SDK 处理；`SessionRuntime.close` 不关客户端 |

`AnthropicSdkLlmClient` 没有 `close()`，`AgentLoop.close` 也不关它。对一个 CLI 进程来说问题不大，但它是**唯一没有被生命周期管理的资源**。若将来支持长驻或多客户端，需要补一个 `AutoCloseable`。我把它列为观察而非编号问题，因为当前进程模型下没有泄漏证据。

## 4. 安全边界如何跨模块拼装

一个工具调用要穿过：`AgentLoop.executeTools` → `Hooks(PRE_TOOL_USE)` → `Main.membershipHook`（成员）→ `Main.permissionHook` → `PermissionGate.checkWith` → `CommandPolicy`/`PathGuard`（→ `PathResolver`）→ 可能的 `ConsoleInput.ask`。

跨模块的边界结论：

- **信任来源正确**：是否能询问用户由 `ExecutionContext.source` 决定，不由线程/模型决定（`PermissionGate.java:108-112`）。
- **成员检查与执行器双重兜底**：未知工具既被 hook 拦、也被执行器兜底（`AgentLoop.java:319-326`）。这保证测试环境不注册 hook 时也不会执行未知工具，但两份逻辑要同步（ARCH-002/ARCH-010）。
- **路径围栏有一个真实缺口**：`PathResolver` 不解析多跳符号链接，导致 `PathGuard` 的“是否在工作区内”判断可能偏保守/偏宽松（ARCH-027）。这是跨 `safety` 与 `tools` 的问题，单看任一模块都不明显。
- **bash 是能力最强、规则最弱的工具**：`CommandPolicy` 的子串规则无法覆盖等价写法（ARCH-028）。它同时是自动化场景下最危险的工具。
- **审批输入不参与排队**：`ConsoleInput` 保证只有终端线程读 stdin，审批答复与任务输入被严格区分。这是一处做得很好的跨模块协作。

## 5. 跨模块重复实现清单

| 重复内容 | 位置 | 编号 |
| --- | --- | --- |
| “prepare→complete→超限补救→工具批→压缩→Stop”整条回合 | `AgentLoop` 与 `SubagentRuntime` | ARCH-002 |
| 未知工具/hook 参数/异常补结果 | 同上 | ARCH-002、ARCH-011 |
| 工作区安全文件读写（围栏+临时文件+原子替换+清理） | `TaskStore`、`CronScheduler`、`MemoryStore` | ARCH-009 |
| 必填/类型校验与 schema 声明 | 每个工具各写一遍 + `ToolSchema` | ARCH-010 |
| `"compact"`、`"todo_write"` 常量 | 4 处 / 2 处 | ARCH-004 |
| JSON 严格读取（`FAIL_ON_TRAILING_TOKENS` + 逐字段判型） | `TaskStore`、`CronScheduler`、`MemoryStore`、记忆三服务 | 观察（模式好但分散） |
| YAML mapper | `SkillLoader`、`MemoryStore` | 观察 |
| 代理对安全的字符串截断 | `MemoryRecallService.prefix`、`MemoryExtractionService.dialogueText` | 观察 |
| 检查会话是否关闭 | `AgentLoop.ensureOpen`、`SessionRuntime.ensureOpen`、`SubagentRuntime.checkSessionOpen` | 观察（三种实现/三种异常类型） |

其中前三项是最值得优先收敛的：它们都是“改一次要改多处、漏一处不报错”的类型。

## 6. 跨模块结论

1. **要理解 JadeCode，必须同时理解三条隐式契约**：①历史必须合并通知而不是新增 USER（迎合压缩器）；②“正式模型回复”是定时交付确认的唯一信号；③工具协议在父子两处各有一份实现。这三条都没有类型承载，是当前架构最主要的“知识负担”。
2. **可靠性余量不足**：父任务无预算（ARCH-006）、无瞬时重试（ARCH-007）、工具异常升级为任务失败（ARCH-011）、子代理无超时（ARCH-016）、记忆不受取消约束（ARCH-017）。这些都不是“现在坏了”，而是“长期用会疼”。
3. **安全边界有一个真实缺口**（ARCH-027）和一个已声明的弱边界（ARCH-028）。
4. **收敛顺序应是**：先统一工具/回合协议（ARCH-002 + 包结构 ARCH-004），再补可靠性（ARCH-006/007/016/017），最后做横切（ARCH-008/009/010）。
