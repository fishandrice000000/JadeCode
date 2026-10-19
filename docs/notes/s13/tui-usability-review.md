# TUI 修复阅读入口

这是界面可用性修复，不是重做 Agent 循环。工程自审与离线检查已通过，终端真机体验待用户复核。

## 建议阅读顺序

1. `TerminalSession` 构造器：主屏模式为何应让终端负责滚动与拖选；与 Pi 全屏模式自建选区的区别。
2. `ConversationView.render` → `TuiState.displayActor/teamActive`：可信身份保持不变，只选择适合单人／Team 的显示名称。
3. `Markdown.blocks/table` → `StyledLine.wrap/DisplayWidth`：协议正文不变，把显示副本排成有限宽度的表格。

## 可选思考

TUI-U01：为什么复制／滚动可以交给终端，但不能同时默认截走全部鼠标事件，又只实现卡片点击？

回答：

TUI-U02：同一条工具结果在单人模式隐藏 Leader 时，为什么仍须保留它的 actor ID？Team 启用时需要改变什么，而不应改变什么？

回答：

TUI-U03：模型正在生成表格时，显示树可以反复重排。为什么不应该把排版后的边框、换行再写回模型消息历史？

回答：
