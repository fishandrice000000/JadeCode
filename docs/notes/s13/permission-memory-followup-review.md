# 权限与记忆可靠性：源码阅读指南

这次不增加 Agent 能力，而是让现有能力在普通 shell 路径、较大记忆库和辅助请求失败时仍可用。
工程状态见 [修复任务单](../../jobs/s13/permission-memory-followup.md)。自动化验证不替代真实后端与终端验收。

## 推荐顺序

1. `memory/MemoryManager.prepareSystem / finishTask`：先看召回如何进入 system，维护为何不应改变已生成的任务回答。
2. `memory/MemoryRecallService.recall`：目录逐页选最多五条；失败从全库降级；结果路径与正文预算分别负责什么。
3. `memory/MemoryReply.array / request`：如何读取正文、识别截断、容忍完整代码围栏，为什么不猜一段“看起来像 JSON”的片段。
4. `memory/MemoryExtractionService.extract`、`MemoryConsolidationService.consolidate`：新增候选逐条筛选与整库替换的不同失败策略。
5. `memory/MemoryStore.saveNewDetailed / replaceRecords`，再看 `MemoryLibraryLock` 与 `MemoryTransaction`：短期文件锁、输入快照检查、备份、发布和中断恢复。
6. `safety/checks/ShellCommandParser.parse` → `ShellPermissionCheck.analyze` → `DefaultPermissionRules.ordinary`：解析得到事实，权限策略决定是否询问。
7. `session/SessionRuntime.Active / turnEnded` → `execution/TurnTimer` → `cli/tui/TuiState.turnElapsed`：同一个工作时间如何用于实时和最终显示。
8. `tui/terminal/InputDecoder` → `cli/tui/TuiController` → `tui/components/Editor.moveWord`：按键如何到达编辑器，为什么不能拆开粘贴块或 emoji。

## P01：辅助能力与主任务

Agent 已经生成正确回答，记忆整理模型却返回了坏 JSON。用户应看到什么？哪些事实可以报告为成功，哪些不行？
再考虑：如果是会话日志本身无法持久化，是否还能把它与记忆格式错误一视同仁？

回答：

## P02：文件与模型上下文

一条知识明明还在磁盘，却可能永远不被 Agent 想起，这是为什么？逐页目录解决了什么，又没有保证什么？
库变大后，这个方案会付出哪些请求次数与上下文成本？

回答：

## P03：新增与整理

新增返回三个候选，其中一个字段不合法；整理返回整份库，其中一个字段不合法。
为什么前者可以只跳过坏条目，而后者不能照做？完整 JSON 围栏与截断 JSON 为什么不能采用同样的容忍策略？

回答：

## P04：恢复不等于继续执行

整理中途进程退出后，恢复旧记忆库是否意味着恢复了此前 Agent 的执行现场？
文件锁、快照比较和磁盘备份分别解决哪一种不同的问题？发现外部未知修改时为什么停止恢复？

回答：

## P05：时间与身份

Leader 正在工作，队友却等待用户审批，为什么不能仅凭“屏幕出现审批框”暂停 Leader 计时？
一次性子代理与常驻队友在这里有什么区别？为什么只冻结屏幕数字而不改最终用时也不正确？

回答：
