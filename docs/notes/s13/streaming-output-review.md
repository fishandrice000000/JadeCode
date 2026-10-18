# 流式输出与 thinking：源码阅读

## 先理解需求

等待完整回复才显示会让用户不知道模型是否仍在工作。流式展示让可读片段提前出现，
但“看到了半段回复”不代表模型已经完成决策，也不能拿半段工具 JSON 去执行命令。

本轮只给 Leader 的正式请求显示正文与 thinking。队友、一次性子代理和辅助请求不展示思考。
隐藏与丢弃是两回事：签名、redacted 数据以及工具续跑需要的完整 thinking 仍属于消息协议。
这里展示的是后端愿意返回的内容，可能是摘要，不是自行获取未公开的内部推理。

工程范围与验证见 [任务单](../../jobs/s13/streaming-output.md)。本材料不是新的课程章节，
也不把下面的可选讨论作为继续 s13 学习的门槛。

## 推荐阅读顺序

1. `SessionRuntime.runTask` 与 `AgentExecutor.request`：完整响应何时才采用，为什么屏幕上的片段不进 history。
2. `LlmClient.complete(request, stream)` 与 `ResponseStream`：保留同步调用，只为本次请求增加展示；旧测试客户端仍可用。
3. `AnthropicSdkLlmClient.complete / validateToolJson`：SDK 如何拼 thinking、文本和工具参数；为什么还要校验原始工具 JSON。
4. `ThinkingBlock / RedactedThinkingBlock` 与适配器的 `toContentBlockParam / toResponse`：展示信息与协议保存信息的区别。
5. `JadeCodeFactory.create / assemble`：为什么共享客户端不会把队友输出串到 Leader；为什么辅助请求仍静默。
6. `CliOutput.Streaming` 与 `TerminalDisplay.preview / ScreenReader`：完整行只写一次、半行如何显示、输入如何保护。
7. `Message.hasOnlyTextAndThinking` 及三个 Memory 服务的 JSON 检查：只解析正文，思考和工具不成为记忆候选。

按需查看已有 MessagesJson、AgentExecutor、HistoryCompactor、SubagentRuntime 与 Main 装配测试。
本轮新增风险通过临时假 SSE/PTY 检查，不新增永久单元测试。

## 可选问题

### ST01：流式展示是否改变模型决策时机

用户已看到“准备写入文件”，工具参数还只到 `{"path":`。为什么 Harness 必须继续等待？
如果这时断流，屏幕、消息历史和真实文件系统应分别是什么状态？

回答：

### ST02：隐藏思考和删除思考

队友不向用户展示 thinking，为什么仍可能要把它和签名放回下一次工具续跑请求？
为什么不能把 thinking 当成普通 TextBlock 交给 Memory 的 JSON 解析？

回答：

### ST03：一条回复为何有两种使用方式

正文已经逐步显示，完整 ModelResponse 仍需交给 Session 做什么？
为什么“不要重复打印”和“不要再写入历史”不是同一个决定？

回答：

## 用户真机检查

先退出旧 REPL（会关闭现有队友），再用新构建启动；不要求恢复之前的队友。

1. 发送一条需要几句话回答、明确不调用工具的提示，确认文字在完成前逐步出现，结束时不整段重放。
2. 接口若返回 thinking，应看到 Leader 的 Thinking；若没返回，只有 Reply 也合法，不据此补造推理。
3. Leader 输出期间输入半段文字，确认保留；可再粘贴多行，检查仍需 Enter 提交。
4. 需要时沿已有只读派工场景检查队友：工具和最终报告仍可见，队友 thinking 不出现。

真实后端事件与终端观感尚待用户验收；没有新增思考开关或自动修改模型配置。
