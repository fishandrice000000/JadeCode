# TUI 耗时与记忆通知：阅读指南

这不是新的课程机制，而是让界面准确说明 Agent 刚才做了什么：
模型流结束不等于整轮结束，工具返回也不等于业务成功。

## 阅读顺序

1. `session/SessionRuntime.execute → runTask → turnEnded`：一轮包含多个模型请求、工具批次、必要等待和记忆维护；
   总用时从处理开始计算，不从第一段回复出现时计算，也不算 Inbox 排队时间。
2. `agent/AgentExecutor.executeTools`：每把工具单独计时，不把前序工具运行时间算进下一把工具；
   `subagent/SubagentRuntime.run` 同样提供真实工具耗时。
3. `execution/ExecutionNotice.timed / actor` → `cli/tui/TuiState.execution / message / delta`：
   只传已经测好的时间，用 Agent 身份及运行编号找到最终回复；阶段回答仍保留，不冒充最终完成。
4. `ConversationView.duration / entry`：只排版，不重新计时。隐藏 RETURNED 不改变内部执行事实，BLOCKED 等仍显示。

## T01：模型已经说完，为什么还没取得整轮总时间？

模型给出“构建还在进行”的阶段回答后，Harness 等必需的后台命令完成，再请模型核对结果，最后维护记忆。
如果从最后一次模型请求开始计时，或把每次流结束都当作整轮结束，会分别遗漏哪些 Agent 工作？
为什么计时应由 SessionRuntime 提供，而不是让 TUI 根据收到文字的时刻推断？

回答：

## T02：没有 RETURNED，如何判断工具是否成功？

`bash · 49.7s` 返回非零退出码，模型随后给出一个正常的最终说明。
工具处理用时、命令是否成功、Agent 是否完成回复，为什么是三种不同事实？界面能否仅凭 DONE 代替验收结论？

回答：

## 状态

计时已完成工程自审及离线验证。通知分类随后独立交付；真实终端观感仍待用户检查。
