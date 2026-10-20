# 阶段一：JadeCode 全局架构

本报告只回答三个问题：JadeCode 由哪些部分组成、它们怎么协作、整体设计哪里值得肯定、哪里值得改。具体问题编号与证据在后续模块报告和最终报告中。

## 1. 一句话概括

JadeCode 是一个单进程、单消费者的 CLI coding agent：`Main` 负责装配，`SessionRuntime` 作为唯一的会话运行器拥有历史并串行推进任务，`AgentLoop` 负责“一次模型请求 + 一批工具 + 必要时压缩”，`SubagentRuntime` 在独立历史上跑子任务，外围则由 `tools`、`safety`、`context`、`memory`、`tasks`、`cron`、`skills` 等能力包支撑。

## 2. 模块划分（按包）

| 包 | 职责 | 关键类型 | 依赖方向 |
| --- | --- | --- | --- |
| `com.jadecode`（默认包） | 装配根：读配置、建工具表、注册 hook、拼 system prompt、picocli 入口 | `Main` | 依赖几乎全部包 |
| `agent` | 会话与执行核心 | `SessionRuntime`（运行器）、`AgentLoop`（执行器）、`SessionInbox`、`SessionEvent`、`ExecutionContext`、`CronProducer` | → `context/llm/memory/messages/safety/tools/cron` |
| `cli` | 终端交互 | `ReplCommand`、`RunCommand`、`ConsoleInput`、`TerminalDisplay` | → `agent`、`Main` |
| `config` | `.env` 解析与运行配置 | `Env`、`AppConfig` | → 无（被 Main 使用） |
| `messages` | 模型 wire 模型 | `Message`、`ContentBlock`（sealed）、`ToolExchangeProtocol` | → `util` |
| `llm` | 模型调用抽象与 SDK 隔离 | `LlmClient`、`AnthropicSdkLlmClient`、`CompleteRequest`、`ModelResponse`、`LlmException` | → `messages/tools/config` |
| `tools` | 工具实现与进程基础设施 | `Tool`、`BashTool`、`ShellCommand`、`BackgroundManager`、文件工具、`TaskTools/CronTools/SubagentTool/...` | → `agent`（`ExecutionContext`）、`safety`、`tasks`、`cron`、`skills`、`subagent`、`todo` |
| `safety` | 权限与路径围栏、事件表 | `CommandPolicy`、`PathGuard`、`PathResolver`、`PermissionGate`、`Hooks` | → `agent`（`ExecutionContext`）、`util` |
| `context` | 四层上下文压缩 | `ContextCompactor` + 5 个策略类 + 2 个存储类 | → `llm/messages` |
| `memory` | 跨会话记忆 | `MemoryManager` + 召回/提取/整理服务 + `MemoryStore` | → `llm/messages` |
| `tasks` | 持久任务图 | `Task`、`TaskStore` | → `util` |
| `todo` | 当轮计划表 | `TodoManager` | → 无 |
| `subagent` | 子任务循环 | `SubagentRuntime`、`SubagentOutcome` | → `context/llm/messages/safety/tools` |
| `skills` | 技能目录与两层注入 | `SkillLoader`、`SkillDefinition` | → 无 |
| `cron` | 定时定义与交付状态 | `CronExpression`、`CronJob`、`CronScheduler` | → `util` |
| `util` | 共享工具 | `Json`、`Ansi` | → 无 |

## 3. 依赖关系与两处结构性环

整体是“`Main` → 各能力包 ← `agent`”的星形接线，职责大体清晰。但有两条真实的包级环：

```
agent ──(? 执行上下文与事件)──▶ tools ──(ExecutionContext)──▶ agent
agent ──(Hooks/PermissionGate)──▶ safety ──(ExecutionContext)──▶ agent
```

- `tools/Tool.java`、`BashTool`、`BackgroundManager`、`SubagentTool` 都引用 `com.jadecode.agent.ExecutionContext`；而 `agent/AgentLoop` 又引用 `tools.BackgroundManager`、`tools.Tool`、`tools.CompactTool`。
- `safety/PermissionGate` 引用 `agent.ExecutionContext`（作为审批来源判定依据）。

这两个环不是“设计者故意分层”，而是因为 `ExecutionContext` 这个**跨层协议类型**被放在了 `agent` 包。它直接导致了两个可观察后果：

1. 常量无法共享：`"compact"` 在 `AgentLoop`、`SubagentRuntime`、`ContextCompactor`、`CompactTool` 里各写一遍，注释明确写“为避免压缩包反向依赖工具包”；`"todo_write"` 在 `AgentLoop` 和 `TodoWriteTool` 里各写一遍。
2. `context` 包无法直接引用 `tools` 的概念，只能靠字符串约定，并在 `ContextCompactor` 里转写“名字不一致时测试会失败”的自证说明。

相关证据：`src/main/java/com/jadecode/tools/Tool.java:7`、`agent/AgentLoop.java:67-71`、`subagent/SubagentRuntime.java:60`、`context/ContextCompactor.java:50-56`、`safety/PermissionGate.java:7`。

## 4. 关键执行链路

### 4.1 启动与装配（`Main.buildLoop`）

`Main.buildLoop`（`Main.java:136-228`）一次创建：路径/命令权限对象、`TodoManager`、`SkillLoader`、`Hooks`、共享 `ContextCompactor`、`MemoryManager`、`TaskStore`、`CronScheduler`、父工具表和子工具表、`SubagentRuntime`、`BackgroundManager`，最后按固定顺序注册 10 个 hook 并 new `AgentLoop`。装配本身无 IO、无线程、无模型调用（有注释保证）。

### 4.2 一次用户任务的完整链路

```
ReplCommand/RunCommand
  └─ SessionRuntime.submit(prompt, approval)
       └─ ExecutionContext.create(USER, approval, () -> closed)
       └─ SessionInbox.offer(Request + CompletableFuture)     // 有界 64，立即返回
  （consumer 线程）
  SessionRuntime.consume → take → execute(entry, append=true)
       └─ 触发 UserPromptSubmit hook；history.add(请求)
       └─ task.goal = AgentLoop.activeRequest(history)        // 校验历史 + 记住原请求
       └─ task.system = AgentLoop.taskSystem(goal)            // 查一次记忆拼 system
       └─ runTask(task)
            while (!closed):
              ① 取已完成后台结果 → apply(Notifications)
              ② AgentLoop.request()：prepare 压缩 → LlmException 超限则 reactiveCompact 重试一次 → client.complete
              ③ apply(ModelReply)；定时任务首次正式回复即确认交付
              ④ acknowledge 已交付的后台结果
              ⑤ 有工具调用 → AgentLoop.executeTools → apply(ToolBatch)
                             → 失败则 throw；否则 compactAfterBatch → apply(HistoryPrepared)
              ⑥ 无工具调用：再取一次后台结果；还有必等结果 → WAITING_BACKGROUND 等待 → continue
              ⑦ Stop hook 要求继续 → apply(Continue) → continue
              ⑧ engine.finish(history) 提取记忆 → output “[Completed]” → 完成 future
```

### 4.3 工具批次执行

`AgentLoop.executeTools`（`AgentLoop.java:307-352`）对整批调用逐个：查退出 → 触发 `PreToolUse`（成员检查 + 权限 + 日志，按注册顺序短路）→ 取工具 → 再查退出 → `tool.execute(input, context)` → 记录结果 → 触发 `PostToolUse`。未知工具在 hook 与执行器里各兜底一次。任何 `RuntimeException` 会中止整批，并为剩余调用补 `Error: Tool batch interrupted...`，然后在 `SessionRuntime.runTask` 里让整个任务失败。

### 4.4 后台命令

`BashTool`（有 `BackgroundManager` 时）把 `run_in_background=true` 的调用交给 `BackgroundManager.start`，立即返回 `bg_xxxx`，并把 `wait_for_completion`（默认 true）映射成 `required`。后台虚拟线程跑完把 `Completion` 放入 `ready` 并调用 `onCompletion` 监听器（`inbox::wake`）。`SessionRuntime.deliverResults` 只“取出”，`acknowledge` 才删除记录；定时任务首回复前失败时 `restore` 放回。退出时 `BackgroundManager.close` 停全部命令（含脱离任务的）。

### 4.5 定时交付

`CronProducer` 每秒 `poll`（daemon 线程）→ `CronScheduler.pollDue` 把到期定义标记 pending 并写盘 → `takeDue` 租借 → `SessionRuntime.publishCron` 入队。任务执行时 `canDeliver()`/`pending()` 复核，模型首次正式回复后 `received()`→`acknowledge()`（周期项清 pending，一次性删除）。首回复前失败 `failed()` → 释放 + 5 秒退避 + 连续 3 次暂停。

### 4.6 子代理

父的 `task` 工具在同线程调用 `SubagentRuntime.run(prompt, context)`：新建独立消息列表，复用同一 `LlmClient`、`Hooks`、`ContextCompactor`，循环结构与父类似但为独立实现（30 轮上限、无后台 bash、不读写长期记忆），只把最终文字作为 `tool_result` 返回父。子工具表 6 把，不含 `task`。

### 4.7 上下文压缩

`ContextCompactor.prepare` 四层串联：①最新一批工具结果预算（超限存盘留预览）→ ②消息条数过多则归档中段 → ③micro（转存已消费结果）+ fit（兜底转存最长结果）→ ④让模型生成事实摘要。前三层确定性、可恢复；第四层有损但先写 transcript。手动 `compact` 工具在整批结果回灌后才触发压缩。超限补救走 `reactiveCompact`（保留最近若干条 + 前缀摘要）。

### 4.8 关闭

`ReplCommand`/`RunCommand` 的 try-with-resources 逆序关闭：`ConsoleInput` → `SessionRuntime.close`（标记 closed → `cron.close` → `engine.close`（停后台命令）→ `inbox.close` → 完成当前 future → 中断 consumer 并 join 2 秒）→ `AgentLoop.close` → `TerminalDisplay.close`（恢复 System.out/err 与终端）。

## 5. 全局评价

### 5.1 值得肯定的设计

- **单一消费者 + 显式事件**：历史只由 consumer 线程修改，工具结果、后台通知、Stop 续跑、定时请求通过 `SessionEvent` 统一采用，避免了“多个入口各改历史”。这与 ADR 0002 的目标一致，代码里也能追到。
- **失败语义诚实**：多处明确区分“排队成功 / 模型已收到 / 业务成功”，不把 `bg_id`、`cron` 交付确认、`task completed` 冒充业务验收；未实现能力写明确占位而不是静默 no-op（例如 `AgentLoop` 构造时校验“注册了 compact 却没有压缩器”直接报错）。
- **文件安全纪律强**：`ToolOutputStore`、`TranscriptStore`、`TaskStore`、`MemoryStore`、`CronScheduler` 都做了排他创建、原子替换、真实路径围栏、可信 marker 形状校验、失败只清理本次文件。这些不是“看着安全”，而是有明确不变量。
- **不可变与 sealed 用得克制**：`Message`/`ContentBlock`/`CronJob`/`Task`/事件记录大多是 record，`ContentBlock` 是 sealed，`switch` 穷尽能在编译期发现漏处理。
- **可测试性有注入点**：`Main.buildLoop(AppConfig, Path, LlmClient)` 与 `Env.of(Map)`、`ScriptedLlmClient` 让压缩/会话能在无网络、无真实目录下验证。
- **面向初学者的中文注释**：类/方法级注释解释调用位置、边界与失败语义，明显高于一般个人项目。

### 5.2 全局结构上的主要弱点

1. **两个核心类偏胖，职责偏集中**：`SessionRuntime`（531 行）同时管队列、状态机、cron 语义、后台等待、历史采用、失败回滚、记忆收尾与兼容入口；`AgentLoop`（426 行）同时管请求重试、压缩编排、工具分发与 legacy `run`。这本身不致命，但让“改一处要懂全部”成为常态。
2. **父子两条执行链重复且分叉**：`AgentLoop` 与 `SubagentRuntime` 各自实现“请求→工具→压缩→Stop”，目前已在若干处出现行为差异（详见 `02-modules/subagent-skills-todo.md`）。这是最值得优先消除了的维护风险。
3. **跨层协议类型的位置造成包环**：`ExecutionContext` 决定了 `agent ↔ tools`、`agent ↔ safety` 两个环，并派生出“常量不能共享”“压缩包靠字符串约定”等连锁的隐性契约。
4. **抽象层存在“纯转发”**：`ContextCompactor` 有约 200 行是把包内实现转发给外部/测试；`ToolExchangeBoundary` 是把 `ToolExchangeProtocol` 再包一层。它们不带来新的可替换性，只扩大了需要同步维护的表面。
5. **横切关注点没有抽出来**：日志（`System.out/err` 散落到 `safety`、`memory`、`llm`、`Main`）、工作区安全文件存储（三处重复）、工具 schema 校验（每个工具各写一遍）、LLM 瞬时错误重试（完全没有）都属于“当前能跑，但长期维护成本高”的部分。

### 5.3 与本阶段的结论

- 当前架构在 **功能与安全语义** 上是自洽的，没有发现会立刻导致数据丢失或越权的确定性问题；主要问题集中在 **可维护性、职责边界、可靠性余量**。
- 最值得投入的方向是：先拆掉父子执行链的重复与分叉（ARCH-002）、把跨层协议类型挪到稳定位置（ARCH-004），再补可靠性余量（轮数/成本预算 ARCH-006、瞬时错误重试 ARCH-007）与横切关注点（日志 ARCH-008、存储 ARCH-009）。
- 完整问题索引与优先级见 `05-final-report.md`；跨模块细节见 `03-cross-module.md`。
