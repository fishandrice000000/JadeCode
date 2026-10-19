# 会话恢复：恢复模型知道的事情，不重演工具做过的事情

## 需求与整体过程

退出 CLI 后，内存消息、队列和线程会消失，但模型之前写过的业务文件不会消失。
resume 解决的是“让新运行时重新知道此前发生了什么”，不是把整个工作区回滚、
重启旧后台任务，或者再执行一次日志中的命令。

新请求/完整回复/工具结果 → SessionRuntime 正式采用 → 顺序日志 → 退出 →
SessionLoader 重建有效历史 → 按当前配置创建运行时 → 用户提交新请求。

模型可见历史与界面显示记录是两种视图：压缩可以替换前者，不必删除后者。
持久任务图、长期记忆和 cron 仍是当前工作区的独立数据，不随对话回到旧时刻。

工程状态与验收证据以 [任务单](../../jobs/s13/session-resume.md) 为准。
最终真实 CLI 验收与用户理解另行记录，不以工程构建通过替代。

## 推荐阅读顺序

1. [ReplCommand](../../../src/main/java/com/jadecode/cli/ReplCommand.java)、
   [TuiController](../../../src/main/java/com/jadecode/cli/tui/TuiController.java)：两个恢复入口与用户操作。
2. [SessionHost.open](../../../src/main/java/com/jadecode/cli/SessionHost.java)：先准备目标，再冻结/替换旧运行时。
3. [SessionRuntime](../../../src/main/java/com/jadecode/session/SessionRuntime.java)：restore、apply、record、freezeForSwitch、close。
4. [SessionLoader](../../../src/main/java/com/jadecode/session/history/SessionLoader.java)：顺序重放、压缩替换和末尾交换修复。
5. [SessionStore](../../../src/main/java/com/jadecode/session/history/SessionStore.java)、
   [SessionJournal](../../../src/main/java/com/jadecode/session/history/SessionJournal.java)：发现、文件锁、日志和损坏尾部处理。
6. [HistoryViewMapper](../../../src/main/java/com/jadecode/cli/tui/HistoryViewMapper.java)：为何显示历史不等于模型历史。
7. [WorkspacePaths](../../../src/main/java/com/jadecode/config/WorkspacePaths.java)、
   [WorkspaceDataMigration](../../../src/main/java/com/jadecode/bootstrap/WorkspaceDataMigration.java)：目录布局与旧引用兼容。
8. 必要时阅读已有 MainCompactionAssemblyTest、ToolExchangeProtocolTest、TodoManagerTest；
   本功能没有为补矩阵新增永久单元测试，临时离线/PTY 检查见任务单。

## R01：为什么不能重放工具？

模型调用 write_file，文件已写完，但进程在工具结果进入日志前崩溃。
下一次 resume 能确定哪些事实，不能确定哪些事实？为什么“日志没结果”不意味着
“文件还没写”？补中断结果后，新一轮应如何继续这项工作？

回答：


## R02：两种历史各给谁看？

一段很长的工具输出已经参与摘要。用户恢复会话后仍希望查看原对话，
但模型不应该再次收到那段长正文。日志里的 REPLACE_HISTORY 如何同时满足这两个需求？
为什么不能直接把 TuiState.entries 当成下一次请求历史？

回答：


## R03：恢复什么，不恢复什么？

旧记录里写着“队友正在构建，用户已批准删除临时文件”。恢复后，队友和批准还有效吗？
与之相比，当前 .jadecode/tasks 中的任务、业务文件和长期记忆如何处理？
为什么这些信息不能一律从旧对话里恢复？

回答：


## R04：为什么切换不只是检查 IDLE？

界面看到 IDLE 的同时，cron 可能正要投递新工作。仅在 UI 线程检查 isBusy 后
替换运行时有什么问题？SessionHost 与 SessionRuntime 各自负责哪部分，
为什么“加载目标失败”与“关闭旧运行时后失败”不能声称具有完全相同的恢复保证？

回答：


## R05：磁盘和内存意见不一致怎么办？

工具结果已经取得，日志却因为磁盘故障无法保存。Harness 为什么保留内存结果，
同时禁止继续执行？如果一边告警一边照常继续，下一次 resume 会对模型造成什么误导？

回答：


## R06：旧路径与旧环境

旧摘要引用 .task_outputs/tool-results/...，现在文件已迁到 .jadecode/tool-results。
为什么保留历史正文并提供受限的只读兼容，而不是全文替换聊天里的所有路径？
这种兼容为什么不自动应用到 bash 和写入工具？

回答：


## 用户独立 CLI 验收建议

1. 先退出工作区旧版 CLI，再启动新版本；观察迁移，没有冲突才继续。
2. 新建会话，告诉模型一个仅用于验收的标记，等该轮结束后复制界面中的 UUID，再正常退出。
3. 用 `./jade repl --resume <uuid>` 恢复，不输入提示词时不应重跑旧操作。
4. 发新请求让模型引用该标记，随后新建另一个 REPL，验证 `/resume` 列表和空闲切换。
5. 模型工作时尝试切换，应明确拒绝；结束后再试。不要为了验收故意执行不可恢复的业务操作。

恢复会话不会回滚工作区，也不会使旧后台进程、队友或审批重新有效。
