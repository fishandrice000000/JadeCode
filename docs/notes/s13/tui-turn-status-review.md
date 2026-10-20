# 本轮用时：从执行线程到状态栏

工程记录及已确认但尚未修复的显示问题见 [任务单](../../jobs/s13/tui-audit-and-turn-status.md)。

## 阅读顺序

1. `session/SessionRuntime`：Active.startedNanos、execute、turnEnded。
   先看“一轮”何时真正开始与结束，为什么审批/记忆/必等后台算在内、排队不算。
2. `execution/ExecutionNotice`：started、timed、actor。显示通知传递同一执行起点和最终差值，
   不需要另把工具耗时拼起来，也不把单调时钟当作持久会话日期。
3. `cli/tui/TuiState`：TURN_START / TURN_END、turnElapsed、resetTurns。
   活动轮次用当前时间求差，结束后冻结；ID 和 actor 都对应上才更新，恢复不复活旧计时。
4. `cli/tui/ConversationView.status` → `TuiApplication` 的定时重绘。
   状态栏只显示事实，点阵帧与重复绘制不能改变任务进度或增加已完成用时。

以上 Java 路径均在 `src/main/java/com/jadecode/` 下。本轮未新增永久测试，
计时验证使用临时可控时钟输入与假模型，不需要等待真实十几分钟。

## S01：为什么模型请求耗时之和不是整轮耗时？

Agent 先等用户批准，再运行 bash，最后等后台构建与记忆维护。解释为什么只加模型/工具卡上的
耗时会漏算，为什么又不能把“同步 task 子代理用时”与其内部各步都加一次。
这里使用同一轮次的起止时刻，解决的是用户想知道的哪一个问题？

回答：

## S02：会话状态与屏幕上的计数分别由谁负责？

假设模型已经停止输出，但必等后台结果还没返回。为什么状态栏计数应该继续，
却不需要为了更新数字再次请求模型？队友此时结束另一项工作，为什么不能冻结 Leader 的数字？
恢复旧聊天后，能不能拿旧进程记录的 nanoTime 与当前 nanoTime 相减？

回答：

## 补充阅读：恢复提示与显示格式

2026-10-20 的小调整见 [恢复提示与小数显示记录](../../jobs/s13/tui-display-followup.md)。
先读 `session/history/SessionLoader.load` 怎样区分正常恢复与不完整记录，再看
`cli/tui/HistoryViewMapper.restore` 怎样显示实际警告，最后看 `ConversationView.duration/tokens`。
这是显示调整，不恢复旧进程、不更改计时或 token 统计，也没有修改自动压缩和记忆整理策略。

### S03（可选）：恢复对话为什么不等于恢复执行？

resume 后模型能看到旧工具调用、后台命令的启动记录，但上一进程已退出。
为什么不能仅凭这些历史文字就向用户宣称命令仍在运行或直接重跑？删掉固定提示后，
程序仍要保留哪些真正的恢复错误说明？从 SessionLoader 与模型的恢复说明分别找依据。

回答：
