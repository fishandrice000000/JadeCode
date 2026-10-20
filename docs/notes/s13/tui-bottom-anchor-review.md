# 输入区布局与会话选择的阅读入口

本轮仅调整前端显示，不改变 Agent 的事件、模型消息、历史压缩或任务调度。
工程自审及离线验证已通过，具体范围与尚未修复项见
[任务单](../../jobs/s13/tui-bottom-anchor-and-resume-list.md)。下面的问题均可选，不阻塞后续使用。

## 阅读顺序

1. `cli/tui/ConversationView.render`：整份画面由消息区和底部区域组成，后者包括状态、输入和页脚。
2. `tui/core/InlineRenderer.render → anchorBottom`，配合 `Frame.bottomStartRow`：
   显示空位放在输入区之前，光标及审批确认位置一起调整，不能往模型历史里塞空消息解决布局。
3. `cli/tui/SessionPicker → tui/components/SelectList`：UUID 用于选择，标题和相对时间用于显示与筛选。
4. `ConversationView.status / entry → turnDuration`：轮次显示格式相同，单工具仍使用 duration；
   计时事实仍来自原来的 SessionRuntime / TuiState，并不在布局时重新开始计数。

以上类均位于 `src/main/java/com/jadecode/`。本轮没有新增永久单元测试；验证使用固定时钟、
终端帧模拟和注入假模型的真实 PTY，不消耗 API 费用。

## UI01（可选）：屏幕上的空位为什么不能进入 Agent 对话？

选择器关闭时，程序在输入区上方补了几行显示空位。如果把这些行追加到 Session 的消息历史，
会给模型请求、日志和上下文统计带来什么本来不该有的变化？哪一层掌握终端高度，
又是哪一层应该决定真正交给模型的内容？

回答：

## UI02（可选）：两条同名会话为什么仍能准确恢复？

列表里两条会话都显示“检查项目 · 刚刚”。用户选择第二条时，程序如何避免把第一条打开？
为什么标题是用户可读信息，不适合作为会话在存储与运行时中的身份？

回答：
