# 多 Agent 终端展示：可选阅读

这是 s13 的工程支撑，不是新的 Agent 机制；不新增必答学习要求。
工程范围与验证见 [任务单](../../jobs/s13/terminal-presentation.md)。

## 为什么只修改展示

多个独立 Session 可以同时输出。若只有最终回复注明队友身份，工具和计数仍无法归属；
但这个问题不需要改变 Inbox 或模型历史。工厂把相同的身份输出函数交给 Session 和展示 hook，
CLI 再排版，终端最后恢复用户正在输入的内容。三者分别处理归属、可读性和输入保护。

## 推荐阅读顺序

1. `bootstrap/JadeCodeFactory.create / assemble`：找到 Leader/队友输出的包装和 Presentation 安装。
2. `cli/CliHooks.register / logCallHook / summaryHook`：展示日志如何复用同一身份；日志不决定权限或成功。
3. `cli/CliOutput.format / write`：只排版程序标签，保持模型正文和代码缩进，整条输出一起打印。
4. `cli/TerminalDisplay`：JLine 如何同时接管 stdout/stderr、恢复输入，以及退出时恢复原输出。
5. `util/Ansi`：终端不支持颜色、用户禁用颜色或重定向时如何回到普通文字。

临时离线/PTY 检查没有作为永久测试文件保留；已有 Main、装配和 Hooks 测试仍在原位置。

## 可选讨论

### UI-01：展示与 Agent 数据流

队友调用 read_file 后，为什么“给工具日志补一个队友标签”不需要把标签写进 tool_result？
如果把终端排版和颜色也写进模型历史，会对模型读取工具结果和后续历史处理造成什么额外负担？

回答：

### UI-02：可输入不等于任务完成

Agent 正在工作时提示符仍然存在。为什么应由 Reply/通知文字说明进度，而不以提示符出现或
Reply 的颜色推断任务成功？结合常驻队友的异步报告举一个场景。

回答：
