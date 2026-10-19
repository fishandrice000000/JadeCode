# Pi 风格 TUI：从 Agent 运行到用户看到的界面

## 先理解需求

Agent 执行、后台命令、队友消息与用户键盘同时活动。旧界面把行编辑、日志重画和半行预览
交给不同路径，单次输出看似正确也不能保证同时工作时光标、草稿与审批不混乱。
本次沿用 Pi regular 的思路：事件进入单一界面线程，组件生成带样式的行，渲染器比较新旧帧。
这不是重写 Agent，也不是让显示队列取代 SessionInbox。

工程结果与验证范围以 [任务单](../../jobs/s13/pi-tui.md) 为准。用户实际终端验收另行记录，
不能因构建或临时 PTY 检查通过就断言所有终端行为正确。

## 推荐阅读顺序

1. `cli/ReplCommand.call` → `cli/tui/TuiApplication.run`：先看终端、会话、输入线程及关闭责任。
2. `cli/tui/TuiController.accept`／`input`／`submit`：用户操作怎样进入现有 Session，如何区分审批焦点。
3. `cli/tui/bridge/StreamingAdapter`、`ExecutionUiBridge` → `TuiState.delta`／`execution`：
   模型增量、工具与成员状态怎样变成显示数据，而不改变模型历史。
4. `ConversationView.render`／`entry` → `tui/core/Frame`／`InlineRenderer.render`：
   一张卡片、光标和点击区域怎样使用同一份布局，何时局部改写，何时整篇重放。
5. `ApprovalBroker.ask`／`answer` → `TuiController.approval`：工具线程等什么，界面线程为什么不能等。
6. 按需阅读 `Editor` 的字素移动、粘贴和软换行，`Markdown` 的语法树排版，以及 `TuiPreferences`。
7. 最后阅读 `AgentExecutor.executeTools` 的只读通知、`SessionRuntime.changeState`／`publishBuffer`，
   对照已有 AgentExecutor、PermissionGate、SubagentRuntime、Main 等定向测试，确认执行契约没有交给前端。

## 关键理解问题

### TUI-01：为什么需要两种队列？

用户忙时输入一段补充，模型也在返回正文。请沿一次实际数据流说明：哪些数据进入界面队列，
哪些进入 SessionInbox？如果 TUI 自己把待发送提示词取出来请求模型，会破坏哪些原有机制？

回答：

### TUI-02：屏幕上看到的内容等于模型收到的历史吗？

工具输出被转存或摘要后，终端仍能展开旧卡片。为什么这不等于模型仍然看到了原文？
这种分离对用户检查 Agent 行为有什么价值，又不能提供什么恢复保证？

回答：

### TUI-03：一份工具结果什么时候能涂成“成功”？

read_file 成功读到一个以 `Error:` 开头的文件；后台 bash 退出码为 0；队友提交 completed。
分别能证明什么？为何普通工具返回使用中性状态，而不是按字符串猜成败？

回答：

### TUI-04：为什么审批不是一条普通提示词？

你正在写下一条任务，队友突然申请操作权限。说明草稿、审批编号、等待 future 的线程如何配合；
为什么两个选项都不默认选中，下一份申请也不能继承上次选择？

回答：

## 可后补讨论

### TUI-05：内联模式为什么可能清空滚动历史？

窗口变窄，或者展开已经滚出可见区的卡片时，为什么普通局部光标移动不足以完成重排？
这个选择与备用屏全屏 UI 各自牺牲了什么？

回答：

### TUI-06：结束显示和停止 Agent 是一回事吗？

本次没有引入单轮取消。为什么不能把流式卡片标为结束，就向用户宣称模型或工具已被停止？
退出整个会话时，审批、Team、命令和终端分别由谁关闭？

回答：
