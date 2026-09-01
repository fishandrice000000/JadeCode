# s01 - Agent Loop

## 一问一答

**Q: 为什么判断循环是否继续,用"消息里有没有 tool_use 块"而不是响应的 stop_reason 字段?**

A: 核心答案:"有没有 tool_use 块"是问题的**事实**,stop_reason 只是同一事实的**摘要**。循环基于事实决策,不基于对事实的报告。四个理由:

1. **无论 stop_reason 说什么,都得扫一遍 content。** 循环前进的唯一路径是:找 tool_use 块 → 执行 → 回填。扫描是必做工作,扫描结果直接当分支条件,就不用引入第二个信号源,也消除了"摘要与事实不一致"的矛盾可能。
2. **对 provider 兼容性差异免疫。** stop_reason 是服务端承诺的枚举,DeepSeek 等兼容端点的实现可能有差异;循环只依赖消息的协议结构(tool_use 块的形状),而这个结构由自己的消息模型反序列化产出,可控。与 s01-7"未知块降级跳过"是同一哲学:只依赖协议里认识的形状。
3. **循环不随新 stop_reason 值而改。** 官方新增停止原因(如 pause_turn / refusal)时,基于块的循环天然无感;基于 stop_reason 的循环,每个新值都要回答"这算不算要调工具"。
4. **内部模型里就没存 stop_reason。** `ModelResponse` 只有 message + usage,`Message` 只有 role + content(s01-2 / s01-4 已定的契约)。循环只依赖内部模型存在的字段,测试假客户端也只需伪造 content 块。

一句话:stop_reason 是给人类看的调试信息,tool_use 块是给循环吃的执行指令。循环吃指令,不读报告。

---

**Q: 既然 execute 使用时 input 必须收窄成 ObjectNode,为什么 ToolUseBlock 的字段不直接声明为 ObjectNode,而是 JsonNode?**

A: 字段类型必须描述"线路上可能出现什么",而不是"我们期望什么"。模型理论上可以回传任何 JSON(对象、数组、字符串),schema 声明只是期望而非承诺(s01-5 的"声明 ≠ 实参")。三个理由:

1. **字段是 ObjectNode 会整条反序列化失败,没有降级空间。** wire 上来了数组/字符串时,Jackson 在反序列化整条响应时就抛异常,连"跳过这一个块"的机会都没有,整轮对话崩溃。JsonNode 让坏输入安全进入内存,处理权交给使用方——AgentLoop 里 `instanceof ObjectNode` 那行就是降级点。
2. **消息模型无权替工具层拍板。** 消息模型是协议层(忠实描述 wire 格式),Tool 契约才是要求层(execute 要 ObjectNode)。协议层保持宽松、要求层保持严格,边界清晰,s02 加新工具时消息模型不用跟着改。
3. **责任不能靠类型"假装消失"。** JsonNode 让检查 + 降级可见地落在使用点;ObjectNode 则把责任藏起来,变成未来的 ClassCastException。与 BashTool"先判空再取 command"同哲学:契约防御发生在边界,且要在代码里看得见。

一句话:JsonNode 是搬运工的诚实,ObjectNode 是使用方的要求,检查写在两者的交界处。
