# 模块报告：LLM 接入与消息协议（`llm/`、`messages/`）

覆盖：`LlmClient`、`AnthropicSdkLlmClient`、`CompleteRequest`、`ModelResponse`、`LlmException`、`Message`、`ContentBlock`、`TextBlock`、`ToolUseBlock`、`ToolResultBlock`、`Role`、`ToolExchangeProtocol`，以及 `util/Json`。

## 1. 模块职责

- `messages` 定义 JadeCode 自己的消息模型（不是 SDK 类型）：`Message(role, content)` + sealed `ContentBlock`（text / tool_use / tool_result）。
- `llm` 定义调用抽象 `LlmClient`，并提供一个唯一接触官方 Anthropic SDK 的实现 `AnthropicSdkLlmClient`，负责出/入两个方向的类型转换。
- `ToolExchangeProtocol` 是“调用与结果如何配对”的唯一规则来源，压缩、父循环、子循环都调用它。

## 2. 设计上合理的地方

- **SDK 隔离得很干净**：只有 `AnthropicSdkLlmClient` import `com.anthropic.*`（`AnthropicSdkLlmClient.java:7-10`）。内部消息与 SDK 类型在边界处互转，其它模块完全不认识 SDK。这对“未来换 provider”很关键。
- **sealed + switch 穷尽**：`ContentBlock` 是 sealed，适配层用 `switch` 穷尽三种块（`AnthropicSdkLlmClient.java:103-118`），新增块类型会在编译期报错，而不是运行时静默漏掉。
- **ID 不变量前置**：`ToolUseBlock`/`ToolResultBlock` 的紧凑构造器拒绝 null/空白 ID（`ToolUseBlock.java:28-30`、`ToolResultBlock.java:25-27`），校验逻辑集中在 `ToolExchangeProtocol.requireValidToolId`。这使配对判断不需要重复防御。
- **配对规则单一来源**：`isClosedToolExchange`（单对）与 `validateHistory`（整段）都在 `ToolExchangeProtocol`，`context/ToolExchangeBoundary` 只转发，没有第二份实现。
- **未知响应块逐块跳过**：`toResponse` 对非 text/tool_use 的块打印类名并跳过（`AnthropicSdkLlmClient.java:171-174`），不让一个 thinking 块拖垮整轮。这属于合理的向前兼容。

## 3. 已证实的问题

### ARCH-007（P2，有依据的潜在风险）：没有 LLM 瞬时错误的重试、退避或回退模型

**现状**：`AgentLoop.request` 只对“上下文超限”做一次补救重试（`AgentLoop.java:270-279`），其他任何 `LlmException`（网络中断、429 限流、5xx、认证）都直接向上抛，导致整个任务失败。子代理同样如此（`SubagentRuntime.java:212-220`）。

**已有但未使用的信息**：`LlmException` 保存了 HTTP 状态码（`LlmException.java:42-44`），`AnthropicSdkLlmClient` 也确实填入了状态码（`61-67`），但目前除了一个测试断言外没有任何生产代码根据状态码分流。

**配置里也已经宣布了意图**：`.env.example` 与 `README.md` 提到 `FALLBACK_MODEL_ID` 用于“529 连续失败时切换（s15 起使用）”，但当前 `AppConfig` 根本不读取 `FALLBACK_MODEL_ID`，代码里也没有任何回退逻辑。

**影响条件**：任何一次瞬时网络/限流错误都会让一个已经跑了很久的任务整体失败，并可能已经产生文件副作用。

**影响范围**：单次任务成功率、用户体验、长时间运行稳定性。

**证据强度**：已证实（代码无重试路径，状态码未使用，fallback 未实现）。

**建议**：在 `llm` 层引入一个 `RetryPolicy` 装饰器（`LlmClient` 的包装实现），对 429/5xx/网络错误做指数退避 + 抖动，达到上限后再抛；把 `FALLBACK_MODEL_ID` 纳入 `AppConfig` 并在重试耗尽前切换一次。关键约束：**重试必须只在“没有工具副作用”的请求边界发生**——`AgentLoop.request` 只在首次 `client.complete` 处 catch，正好满足，不要把它挪到工具执行之后。收益是成功率与成本可控；代价是需要定义“哪些错误可重试”，并保证重试不触发重复工具执行。

### ARCH-025（P2，实际缺陷）：SDK 转换丢掉了工具 schema 的根级约束

**现状**：`toSdkTool`（`AnthropicSdkLlmClient.java:131-158`）手工把 `inputSchema` 拆成 `type`、`properties`、`required` 三段注入 SDK 的 `Tool.InputSchema`。每个属性的节点是原样塞进去的（`propsBuilder.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()))`），所以**属性内部**的 `pattern`、`minLength`、`maxItems`、`enum`、数组 `items` 都保留；但**根级** `additionalProperties:false` 被丢掉了，因为 SDK 的 builder 只接收三段。

**证据**：`TaskTools.TaskTool.inputSchema()` 与 `CronTools.CronTool.inputSchema()` 都显式设置了 `schema.put("additionalProperties", false)`（`TaskTools.java:113-128`、`CronTools.java:60-64`），而 `AnthropicSdkLlmClient.toSdkTool` 只读 `type`/`properties`/`required`（`136-152`）。也就是说：代码里写的“禁止额外参数”声明，根本没有发到模型；只是运行时 `validate()` 仍然会拒绝额外字段，功能上不会写坏数据，但“给模型的 schema 更严格”这一意图失效。

**影响范围**：模型更容易生成多余字段（随后被运行时拒绝，浪费一轮来回）；不同端点的 schema 兼容性判断会因此与实际不符。

**建议**：如果 SDK 的 `InputSchema` 支持透传额外字段，用 `putAdditionalProperty` 补上根级约束；如果不支持，就在 `ToolSchema`/文档里明确“根级 additionalProperties 仅用于运行时校验，不发送给模型”，避免注释与行为不一致。至少让 `README`/任务单不再声称两端一致。

### ARCH-020（P3，可选优化）：`Message` 缺少构造期不变量

**现状**：`ToolUseBlock`/`ToolResultBlock` 会校验 ID，但 `Message` 的 record 构造器不校验 `content` 非 null、不含 null 元素（`Message.java:22`）。结果是防御性检查散落在使用方：`ContextCompactor.extractActiveRequest` 需要逐块判 null（`ContextCompactor.java:568-593`），`MemoryExtractionService.dialogueText` 也要判（`MemoryExtractionService.java:175-179`）。

**为什么要改**：一个坏的 `Message` 可以一路传到 API 边界才炸，且每次都要在调用方重复写防御。把“content 非 null、元素非 null”放进紧凑构造器，可以让大量 `null` 检查消失，并让 `List.copyOf` 语义统一。

**代价与风险**：`Message` 也是 JSON 反序列化的目标，构造期校验会在反序列化坏数据时立刻抛错——这是想要的行为，但需要确认 `MessagesJsonTest` 与 transcript 读取路径不依赖“部分字段可空”。属于中等规模、低风险的收敛。

## 4. 观察与缓和因素

- `Role` 只有 USER/ASSISTANT，system 是单独的字符串。这与 Anthropic wire 格式一致，不是缺陷。
- `ToolResultBlock.content` 是单个字符串而非块数组，简化了实现；对当前工具集足够。若将来要支持图像/多模态工具结果，需要扩展，属于已知简化。
- `Json.mapper()` 是全局共享 `ObjectMapper`，配置了“忽略未知字段”和“不输出 null”。`CronScheduler.decode`、`TaskStore.readTask`、`MemoryStore.parseRecord` 都在这之上再加严格判型，这是正确的做法（全局宽松 + 局部严格），没有发现配置冲突。
- `isContextOverflow` 的关键词匹配属于 `context` 模块，见 `02-modules/context.md` 的 ARCH-021。

## 5. 修改顺序建议

1. ARCH-007（重试 + fallback）：收益最大，但需要先定义可重试错误集合与“只在请求边界重试”的约束。
2. ARCH-025（schema 保真）：改动局部，先澄清 SDK 能力，再决定“透传”还是“改文档口径”。
3. ARCH-020（Message 不变量）：作为收敛性重构，安排在其它模块稳定后做，避免同时改消息语义与压缩逻辑。
