# 模块报告：上下文压缩（`context/`）

覆盖：`ContextCompactor`、`HistoryCompactor`、`MicroCompactor`、`SnipCompactor`、`ToolResultBudgetCompactor`、`ToolResultFitter`、`ToolOutputStore`、`TranscriptStore`、`ToolExchangeBoundary`、`CompactionLimits`、`ContextSerialization`。

## 1. 模块职责

把过长的消息历史整理短再发给模型。四层策略按代价递增排列：

1. `ToolResultBudgetCompactor`：最新一批工具结果超预算 → 存盘，历史里留“路径 + 预览”。
2. `SnipCompactor`：消息条数过多 → 归档整段，中段换成一条归档标记。
3. `MicroCompactor`（转存已消费的旧结果）+ `ToolResultFitter`（兜底转存最长结果）。
4. `HistoryCompactor`：只有前几层都不够，才让模型生成事实摘要（唯一调用模型、唯一有损的一层）。

策略类不持有会话状态，父子循环共用同一个 `ContextCompactor` 实例。

## 2. 设计上合理的地方

- **分层代价递增、尽量可恢复**：前三层是确定性的，且都是“先成功落盘，再替换历史”（`ContextCompactor` 类说明 `31-41`，`HistoryCompactor.compactAll` `98-107` 明确“顺序不可颠倒”）。这是压缩模块最值得肯定的设计：有损之前一定先有可回查的归档。
- **最新的工具交换被保护**：`MicroCompactor` 只处理最后一条 assistant 之前的结果（`MicroCompactor.java:58-79`）；`HistoryCompactor.compactHistory` 只摘要“最新一对工具交换”之前的前缀（`143-170`）；注释解释了“模型还没读过的新结果不能被转述失真”。
- **不可变约定明确**：入参不被修改，需要变更时新建列表、只重建真正变化的消息（`ToolResultFitter.withReplacement` `208-215` 与 `192-206` 的逐层重建注释尤其清楚）。
- **可信路径而不是字符串前缀**：`ToolOutputStore.persistedOutputPath` 与 `TranscriptStore.isArchiveMarker` 都要求“从开头逐字匹配外壳 + 绝对路径 + toRealPath 后仍在注入目录内 + 是普通文件”（`ToolOutputStore.java:110-161`、`TranscriptStore.java:129-197`）。工具输出里伪造的 `Full output: ...` 不会被信任。
- **排他创建保证不覆盖**：`saveOutput`/`writeTranscript` 用 `Files.createTempFile`（底层 CREATE_NEW）+ 清洗 ID，同 ID、碰撞都不会覆盖既有文件（`ToolOutputStore.java:51-93`、`TranscriptStore.java:57-85`）。
- **响应式补救有明确边界**：`reactiveCompact` 只在“服务端已拒绝”后跑，且用 `safeTailStart` 保证不把一对工具交换切成半截；`tailStart==0` 时直接返回、不调模型（`ContextCompactor.java:477-502`）。

## 3. 已证实的问题

### ARCH-005（P2，可选优化）：`ContextCompactor` 是纯转发门面，`ToolExchangeBoundary` 是纯转发壳

**现状**：`ContextCompactor`（644 行）中，约 200 行是形如“转发到 Xxx，说明见那个类”的方法（`219-445`），包括 `toolResultBudget`、`snipCompact`、`microCompact`、`fitToolResults`、`compactAll`、`compactHistory`、`summarizeHistory`、`summaryInput`、`writeTranscript`、`saveOutput`、`persistedOutputPath`、`persistedPreview`、`isArchiveMarker`、`estimateChars`、`hasToolUse`、`isToolResult`、`isClosedToolExchange`、`safeTailStart`、`safeHeadEnd`。这些方法的可见性明显是“为了让测试能直接调包内实现”。

`ToolExchangeBoundary`（82 行）更彻底：五个方法全是 `return ToolExchangeProtocol.xxx(...)`（`ToolExchangeBoundary.java:27-81`），类注释也承认“本类只做转发”。

**为什么是问题**：

1. 每个策略方法的签名变化都要在门面上同步改一次，真正的实现反而藏在后面；
2. “测试通过门面调用内部实现”把包内结构固化成了事实 API，后续想重排策略类会连带改测试；
3. 它不提供任何新的可替换性（没有接口、没有多实现），也不减少耦合。

**建议**：把 `ContextCompactor` 收缩成“管线编排 + 装配”，删除纯转发方法；测试改为直接构造对应的策略类（它们已是包私有、可注入 `CompactionLimits`）。`ToolExchangeBoundary` 整体删除，压缩包直接调用 `ToolExchangeProtocol`（`messages` 包本就被 `context` 依赖，不存在环）。收益是去掉约 280 行需要同步维护的表面；代价是要改一批测试的调用点。

### ARCH-021（P2，有依据的潜在风险）：用字符数估算长度、用关键词判断“超限”

**现状**：

- 所有“多长”的判断都用 Java 字符数：`ContextSerialization.estimateChars` 把整段消息序列化成 JSON 再取 `length()`（`ContextSerialization.java:29-31`），阈值如 `contextCharLimit=50_000`（`CompactionLimits.java:44-57`）。
- “是否上下文超限”用异常文本子串匹配：`isContextOverflow` 只看消息里有没有 `prompt_too_long` 或 `too many tokens`（`ContextCompactor.java:625-643`），忽略 HTTP 状态码。

**为什么是问题**：

- 字符数不是 token 数。中文、代码、JSON 转义的 token/字符比差异很大，同一个 50k 字符阈值在英文与中文下代表的有效上下文可能差一倍。阈值偏大 → 压缩没及时发生，服务端拒绝；阈值偏小 → 过度压缩，丢信息。
- 关键词匹配依赖服务端措辞。DeepSeek/Anthropic 换一种错误说法（例如 `context_length_exceeded`）就会漏判，从而**不做补救直接失败**；反之，错误信息里恰好引用这两个词会误判、白白做一次有损压缩。
- 代码注释本身也承认这两点（`ContextCompactor.java:610-617`）。所以这不是“隐藏缺陷”，而是“已知近似”。

**影响条件**：长会话、中文/代码密集的任务、服务端错误信息变化。

**影响范围**：压缩时机、任务成功率与信息保留度。

**证据强度**：已证实（实现即如此）；实际影响程度待验证（V-2/V-3）。

**建议**（分两步，成本不同）：

- 低成本：把 `isContextOverflow` 扩展为“状态码 400/413 + 一组关键词（大小写、复数、下划线/空格归一化）”，并把可配置的阈值从 `CompactionLimits` 暴露到 `AppConfig`。
- 中成本：接入 tokenizer 估算（或使用 provider 返回的 usage）替换纯字符数；至少让阈值按“占模型窗口的百分比”表达，而不是硬编码字符数。代价是引入 tokenizer 依赖与额外计算，需要先评估收益。

### ARCH-024（P3，隐式约定风险）：后台通知必须合并进“最后一条 USER 消息”

**现状**：`SessionRuntime.apply` 处理 `Notifications` 时，如果历史最后一条是 USER，就把通知文本追加进这条消息的 content，而不是新加一条 USER（`SessionRuntime.java:379-388`）。注释解释了原因：“若另加 USER，原工具结果不再是末条，现有压缩器可能漏掉对最新工具结果的保护。这是当前压缩器需要，不是模型协议硬性要求。”（`358-361`）

**为什么是风险**：这条“协议层必须迎合压缩器实现细节”的约定，跨越了 `agent` 与 `context` 两个包，却没有任何类型或断言强制。`MicroCompactor` 用“最后一条 assistant 之前的结果都算已消费”（`MicroCompactor.java:58-79`）来界定保护范围；如果将来有人改了 `MicroCompactor` 的界定方式，或者某处新增一条 USER 消息，最新的工具结果就会被误判为“已消费”并转存，模型当轮看到的就不是完整结果。

**建议**：把这个约束显式化——要么在压缩层提供“受保护区间”的概念（例如给消息打标记或按“距最后一条 assistant 的工具结果”定位），要么在 `apply` 处加断言/测试守护。收益是消除一个跨包隐式契约；代价是需要设计一个不破坏 wire 格式的标记方式。

### ARCH-026（P3，可选优化）：父子共用压缩器与同一归档目录

**现状**：`Main.buildLoop` 创建**一个** `ContextCompactor`，同时传给 `AgentLoop` 和 `SubagentRuntime`（`Main.java:150-152,181-182,224`）。父子的 transcript 与 tool-results 落进同一对目录（`cwd/.transcripts`、`cwd/.task_outputs/tool-results`）。这不是 bug（每次落盘都用排他创建，不会互相覆盖，`docs/notes/optimization/optimization-backlog.md` OPT-002 也澄清了），但目录里看不出“这段归档/这份正文属于哪个会话、哪个子委派”。

**影响**：回查、诊断、未来按会话清理都缺归属信息；`docs/notes/optimization/session-storage-path-design.md` 已经给出按 `sessionId/agentId` 组织的设计建议。

**建议**：按该设计文档引入 `sessionId`（顶层会话）与 `agentId`（子委派），在装配时按归属构造存储目录，但**保留现有排他创建、可信路径、失败语义不变**。注意这是设计建议，尚未裁决路径命名，实施前需明确“旧 marker 的旧绝对路径继续有效”。

### ARCH-038（P2，有依据的潜在风险）：长任务的原始请求可能被 snip 归档掉，目标不再出现在可见历史里

**现状**：`SnipCompactor` 在消息数超过 `snipMaxMessages`（默认 50）时，把“头部 3 条 + 尾部 + 一条归档标记”之外的中段整体替换为 `[N messages archived at <path>]`（`SnipCompactor.java:40-77`）。用户的原始请求在任务开始时是最后一条 USER，但随着多批工具往返，它会退到中段。`task.goal` 只被用于压缩层（`compactor.prepare(messages, activeRequest)`、reactive 摘要）和 `AgentLoop.request` 的参数，**不会被重新插入可见消息**（`SessionRuntime.java:264`、`AgentLoop.java:253-269`）。因此一个超过 50 条消息、但总字符数因为转存而不大的长任务，可能在 snip 之后让模型看不到最初的目标（除非第四层摘要也运行并把 `Current user request` 写进摘要）。

**为什么是问题**：模型可能在“不知道用户原始要求”的状态下继续执行，用工具结果或归档标记当上下文，产出偏离目标的动作。这和 ChatGPT 报告的 ARCH-005 是同一现象（“保存的 goal 未进入正式请求”），本次独立得出，互为印证。

**证据强度**：已证实（路径清晰）；具体触发频率待验证（V-3）。

**建议**：在 snip/reactive 之后，保证“当前目标”一定以一条独立消息（或工具结果末尾的固定区块）出现在可见历史里；最直接的做法是不把含 `activeRequest` 的那条消息纳入可归档中段，或在归档标记后补一条 `[Current user request] <goal>`。注意不能重复追加导致上下文膨胀，也不要改变现有工具交换配对。收益是长任务不丢目标；代价是要定义一个稳定的“目标锚点”。

## 4. 反例与缓和因素

- 不能把“字符数估算”当成错误：课程本身就是按字符数近似，且代码明确写着“不是精确 token 数”。它是**近似实现**而非隐藏缺陷；我把它列为 P2 是因为长期使用时成本与成功率影响真实存在。
- 不能把“父子共用目录”说成覆盖故障：源码与既有优化清单都确认没有覆盖。这里只提“归属与回查”缺口。
- `ContextCompactor.MAX_REACTIVE_RETRIES = 1` 是有意限制（避免无限付费重试），合理。

## 5. 修改顺序建议

1. ARCH-005（去转发门面）保行为重构，风险低、收益直接，适合先做。
2. ARCH-021 低成本部分（错误分类 + 阈值可配）穿插做。
3. ARCH-024 需要先设计“受保护区间”，与线协议无关，但跨包，建议单独任务单。
4. ARCH-026 依赖会话身份设计，成本较高，放在会话身份统一之后。
