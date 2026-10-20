# 模块报告：子代理、技能、待办（`subagent/`、`skills/`、`todo/`）

覆盖：`SubagentRuntime`、`SubagentOutcome`、`SubagentTool`、`SkillLoader`、`SkillDefinition`、`TodoManager`、`TodoWriteTool`。

## 1. 子代理

### 1.1 职责

`SubagentRuntime.run(prompt, context)` 在**全新的消息列表**里跑一个子任务：复用父的 `LlmClient`、`Hooks`、`ContextCompactor` 与工作区，但不读父历史；完成后只把最终文字返回给父（父对话里只保留这次 `task` 的 `tool_result`）。最大 30 轮，三种终态 `DONE/STOPPED/FAILED`。

### 1.2 设计上合理的地方

- **历史隔离干净**：`messages` 与 `activeRequest` 都是 `run` 的局部变量（`SubagentRuntime.java:169-175`），不放在字段上，下一次委派不会沿用旧任务。
- **取消只有一条路**：`checkSessionOpen` 在每个关键步骤前读取父的退出信号（`186,209,214,245,292,311,338`），并把退出报成 `LlmException` 让外层统一收尾。注释解释了“为什么报成 LlmException”。
- **压缩复用父实现**：子代理也跑同一套 `prepare/reactiveCompact/compactAfterBatch`，不是第二套压缩算法。
- **轮次语义清楚**：只有“正式请求真正拿到回复”才 `turns++`，摘要与失败重试不算轮（`247`）；第 30 轮不能再发第 31 次请求。
- **`task` 工具不能嵌套**：子工具表不含 `task`（`Main.java:172-178`），天然限制一层委派。

### 1.3 已证实的问题

#### ARCH-002（P1，实际缺陷）：父执行器与子循环重复实现“请求→工具→压缩→Stop”，且已经开始分叉

**现状**：`AgentLoop.request/executeTools/compactAfterBatch/stop`（`AgentLoop.java:253-352`）与 `SubagentRuntime.run` 内联的循环（`SubagentRuntime.java:184-350`）是两套几乎同构的实现。它们都做：调 `compactor.prepare` → `client.complete` → 超限补救 → 收集 tool_use → 检查重复 ID → 追加 assistant → 执行工具（unknown / PreToolUse / execute / PostToolUse）→ 回灌 tool_result → compact。

但已经有可观察的行为差异：

| 行为 | 父（`AgentLoop`） | 子（`SubagentRuntime`） |
| --- | --- | --- |
| 未知工具的时序 | 先触发 `PreToolUse`（成员 hook 会给 `Error: Unknown tool`），之后执行器还有一层 `tool == null` 兜底（`317-326`） | 先查 `tools.get`，未知即返回 `Error: Unknown tool` 并 `continue`，**完全不触发任何工具 hook**（`295-299`） |
| 退出检查方式 | `ensureOpen()` + `context.isCancelled()`（`315-316,328-330`） | `checkSessionOpen(context)` 抛 `LlmException`（`372-374`） |
| 意外异常处理 | 保留已有结果、给剩余调用补失败说明、把 `failure` 带回运行器（`340-347`） | 不捕获；异常直接向外抛（`313` 处无 try/catch） |
| todo 提醒 | 连续 3 批未更新追加 `<reminder>`（`348-352`） | 无 |
| 轮次上限 | 无 | 30 轮硬上限 |
| `PostToolUse` 参数 | 带 `PARAM_CONTEXT`（`337-338`） | 不带（`323`） |
| `SUBAGENT_TOOL` 事件 | 父不触发（父根本没有 task 的父级概念） | 触发（`325-326`） |

**为什么是问题**：这是“同一份协议有两份实现”的典型风险。任何一次协议变更（例如新增一个 `PreToolUse` 参数、改变未知工具的处理、调整压缩时机）都必须手工同步两处，而两处当前的差异说明同步已经在发生偏移。注释里“子代理压缩测试会立刻失败”的说法只覆盖了 `compact` 常量，不覆盖上述行为。`docs/reviews/architecture/ChatGPT/05-final-report.md` 的 ARCH-010 也独立指出了同一问题，可交叉印证。

**影响条件**：任何修改工具协议、hook 参数或压缩时机的后续开发；不修改时也会因为“读起来像、行为不同”增加理解成本。

**证据强度**：已证实（逐项对照源码）。

**建议**：抽出共享的“执行步骤”，保留两个宿主。具体可拆成两层：

1. `ToolDispatcher`：输入 `(List<ToolUseBlock>, Map<String,Tool>, ExecutionContext, Hooks, ToolOptions)`，输出已配对的结果列表。它统一未知工具、`PreToolUse`/`PostToolUse` 参数、意外异常处理（是否隔离由 `ToolOptions` 决定）、是否需要 todo 提醒。父与子都调用它。
2. `TurnExecutor`（或 `ModelTurn`）：统一“prepare → complete → 超限补救 → 校验回复 → 返回 ToolBatch”，由父与子共同调用；父子差异（轮次上限、是否允许后台、是否发 SUBAGENT_TOOL、失败是否隔离）通过一个小的 `TurnPolicy` 传入。

收益是协议只有一份实现，行为差异变成显式策略；代价是需要定义 `TurnPolicy` 的字段并迁移现有测试。这个重构应当**先于**在其上叠加新机制（s13 团队、s16 workflow）之前完成，否则重复会继续扩大。

#### ARCH-016（P2，有依据的潜在风险）：子代理同步阻塞父会话，没有独立超时或取消

**现状**：`task` 工具在父的执行线程上直接调用 `SubagentRuntime.run` 并等待返回（`SubagentTool.java:96-101`）。子代理唯一的中断途径是父会话关闭（`ExecutionContext.isCancelled`）。子代理内部的每次 `client.complete` 是同步的，没有单次调用超时；30 轮上限也不等于时间上限。

**为什么是问题**：

- 一个卡住的子代理会占住整个会话，让后续排队请求全部等待（队头阻塞），而父任务此刻既不能取消也不能做别的；
- 若第三方 HTTP 客户端不响应线程中断，会话关闭也只能等它自然返回（代码注释在 `AgentLoop`/`SubagentRuntime` 都承认这一点）。

**影响条件**：子任务委派了长时间探索、网络慢、或模型持续产出工具调用直到 30 轮。

**建议**：至少给子代理一个“墙钟预算”（例如可配置的最大秒数），超时后按 `STOPPED`/`FAILED` 收尾并让父拿到明确的失败文本；进一步可考虑把长委派放到后台（返回 `sub_id`、完成后再由会话运行器恢复），但这会引入新的生命周期，属于更大的设计，不建议顺手做。收益是不让一个委派拖死整会话；代价是要定义超时语义与“已发生副作用不撤销”的说明。

#### ARCH-032（P3）：`SubagentRuntime` 的失败路径只覆盖 `LlmException`

**现状**：`run` 只有一个 `catch (LlmException e)` 做统一 `FAILED` 收尾（`356-361`）。自定义工具或 hook 抛出的其它 `RuntimeException` 不会被转成 `FAILED`，而是直接穿过 `SubagentTool.execute` 抛给父执行器——父执行器会把它当成整批失败（与 ARCH-011 相同）。

**影响**：`SubagentOutcome` 文档说“无论走哪条路径都恰好触发一次 END”，但实际对“意外运行异常”并不成立（任务单本身也承认了这一点）。这是文档与实现的边界，不是 bug，但容易误读。

**建议**：在任务单/JavaDoc 里明确“意外运行异常不由本类统一收尾”，或补一个 `catch (RuntimeException)` 做 `FAILED` 上报后再抛。二选一，保持文档与实现一致。

### 1.4 子代理的重叠与互补

- 子代理不读写长期记忆（`Main` 只给父接记忆线），这是**有意的**，避免临时探索结论进用户知识库。合理。
- 子代理不支持后台 bash。与 ARCH-016 一起看：子代理既不能后台化、也不能超时，长任务体验受限。

## 2. 技能（`skills/`）

### 2.1 职责与合理之处

`SkillLoader` 扫描 `skills/<name>/SKILL.md`，提供两层注入：system prompt 只放 `name + description` 目录，完整正文只在模型 `load_skill(name)` 时作为工具结果进入。实现上有几处做得扎实：

- `load` 只查 registry，绝不用模型给的 name 拼路径（`SkillLoader.java:133-140`），从根上避免路径穿越；
- 扫描时先 `toRealPath` 再围栏，越界链接跳过（`80-90`）；
- frontmatter 解析兼容 CRLF、block scalar 里的缩进 `---` 不当作结束符（`154-180`）；
- “目录不存在”是合法空表，真实 IO 故障则抛错，不把故障伪装成空目录（`59-67,102-105`）。

### 2.2 观察

- `SkillLoader` 与 `MemoryStore` 各有一个静态 `ObjectMapper(new YAMLFactory())`（`SkillLoader.java:35`、`MemoryStore.java:39`），与 `Json.mapper()` 并列，共三套 mapper。不是缺陷，但未来如果统一 JSON/YAML 行为，需要记得两处。
- `registry` 是可变字段，`scan()` 会清空重建（`59-61`），但当前只在构造时调用一次；若未来支持热重载，需注意并发（`load` 与 `scan` 未加锁）。
- 技能目录只在构造时扫描一次，运行中新增技能不会自动出现。这是合理的简单选择，但 README/用户文档应说明“新增技能需重启”。

## 3. 待办（`todo/`）

### 3.1 职责与合理之处

`TodoManager.update(ArrayNode)` 做整表替换：元素校验（对象、content 非空、status 合法）、全局至多一个 `in_progress`、上限 20；任何一条不合法就整批拒绝并保留旧列表（`TodoManager.java:41-79`）。渲染文本是模型唯一能看到的形式（`render` `91-110`）。

### 3.2 观察

- **“连续 3 批未更新 todo 就提醒”的逻辑在 `AgentLoop` 里，不在 `TodoManager`**（`AgentLoop.java:348-352`），且子代理没有这套逻辑。这属于“计划表策略”与“循环策略”的边界问题；如果做了 ARCH-002 的 `TurnExecutor`，应把 rounds 计数与提醒一起收进去，避免又出现父子分叉。
- `TodoManager` 的状态是内存态、不持久化、不与会话重启关联；与 s10 的 `TaskStore` 是不同概念，代码与 CONTEXT 都已区分，没问题。
- `TodoWriteTool.inputSchema()` 手写 `maxItems: 20`，注释提醒“须与 TodoManager 上限同步”（`TodoWriteTool.java:60-75`）。这是一个手动同步点，属于 ARCH-010 的特例。

## 4. 修改顺序建议

1. ARCH-002：与工具协议、压缩协议强相关，应作为 s13 之前的一次专项重构，先抽出 `ToolDispatcher` 再抽 `TurnExecutor`。
2. ARCH-016：可与 ARCH-002 同一批或紧随其后（因为 `TurnPolicy` 正好可以承载超时预算）。
3. ARCH-032：文档/收尾一致性的小修，随 ARCH-002 顺手做。
