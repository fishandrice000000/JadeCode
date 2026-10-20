# 审批全文阅读与消息排版的阅读入口

本次修复已工程自审，结果与实际验证见 [任务单](../../jobs/s13/tui-audit-fixes.md)。
下面均为可选学习问题，不要求为使用修复后的界面重做旧题；用户真机观感仍待复核。

## 先理解所解决的问题

Agent 可能请求一条很长的命令或写入一份长文件。用户看到几行预览，不等于知道自己批准了什么。
界面必须说明哪些内容未显示，并允许读到完整理由、参数与授权范围；执行时则仍使用原操作，
不能把当前页或为了排版重建的文字交给工具。Markdown 同样属于显示，不是模型消息的改写器。

## 推荐阅读顺序

1. `cli/tui/TuiController.approval` 与 `safety/approval/ApprovalRequest`、`cli/tui/bridge/ApprovalBroker`：
   阅读按键不作答，批准仍绑定申请 ID 和原始参数，且先检查选项已实际显示。
2. `cli/tui/ConversationView.approval` 与 `TuiState.resetApprovalView`：可用高度怎样决定一页几行，
   过小窗口为何没有批准动作，新队首为何要重置旧选项及阅读位置。
3. `tui/components/Markdown.blocks → wrapPrefixed → code`：正文先换行，再给每条显示行加标记；
   代码的终止换行与用户额外留的空行分别怎样处理。
4. `SelectList.selectedRow`、`TuiController.commandInput`、`DisplayWidth.ellipsize` 与
   `ConversationView.footer`：选中位置、候选收起及窄屏信息不再各自猜测。

上述 Java 类均在 `src/main/java/com/jadecode/`。验证使用已有测试与临时假模型，不增加永久测试。

## TF01：读到的页面与被批准的操作为什么必须分开？

用户在长命令尾页选择“仅本次”。谁负责保存完整待执行参数，谁负责保存当前显示偏移？
如果界面缩小后按钮看不见，为什么不能仅凭此前的高亮继续接受 Enter？

回答：

## TF02：为什么排版修复不应改变 Agent 的工具交换？

同一条模型消息可能同时含代码块和工具调用。修好代码竖线或列表缩进时，
为什么不应修改 Message 或重写 tool_use 参数？沿显示通知与执行器的不同路径找依据。

回答：
