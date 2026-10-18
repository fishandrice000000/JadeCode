# s13：让多个独立会话协作

工程任务与状态见 [任务单](../../jobs/s13/agent-teams.md)。本文用于源码学习，不代替独立真机验收。

## 先理解需求

一次性 `task` 子代理适合“一次调用，拿回答案”：调用方等待，子历史不返回。
Team 则需要一个队友记住自己的工作经过，在多次派工间保持会话，由 Leader 统一安排和审查。
所以本章不是多写一份循环，而是让相同 SessionRuntime 各运行一份，通过文件邮箱传话。

模型决定任务如何拆分、何时需要队友、报告是否足够；Harness 负责身份、投递、工具可用性、
任务状态转换和停止。completed 是受控记录，不是 Harness 替用户证明代码正确。

## 推荐阅读顺序

1. `bootstrap/JadeCodeFactory.create / assemble / teammatePrompt`：先看 Leader、队友和一次性子代理各有什么。
   每个队友拥有 Session、history、Inbox、后台管理器、Todo、Hooks；共享客户端、工作区和 TaskStore。
   队友不复制 Leader 对话、不创建 Memory/cron；根目录 AGENTS.md 作为项目说明加载。
2. `tools/TeamTools.forActor / execute` → `team/TeamManager.spawn / assign`：看身份如何固定，
   Leader 如何先认领任务、保存说明，再交给队友排队。队友忙时不会把 B 当成 A 的补充。
3. `team/TeamMailbox.prepare` → `TeamManager.deliver` → `SessionRuntime.tryReceive`：沿一封信跟踪。
   模型生成 summary/body，Harness 生成路径与身份。正文保留，pending 只负责尚未交付的运输记录。
4. `session/SessionInbox.pollIdle / drain / buffer / promote` → `SessionRuntime.deliverIncoming / runTask`：
   看“插入当前工作”与“空闲时主动处理”为何是两个开关；批次回填后才加通知，模型回复不被覆盖。
5. `TeamManager.submitResult / retry / reopen / shutdown` → `tasks/TaskStore.stop / release`：
   看业务执行与结果提交为何不能一起重试；旧 assignment 为什么不能改新派工的状态。
6. `tools/BashTool` → `process/BackgroundManager.onResult` → `SessionRuntime.backgroundCompleted` →
   `SessionInbox.drain / SessionRuntime.awaitResults`：
   看默认 true/false/false 如何实现“先做其它步骤，准备结束时再等结果”。等待不是 IDLE。
7. `safety/DelegatedApproval.ask` → `PermissionGate` → `cli/ConsoleInput`：
   看辅助审批为何是独立无工具请求、何时升级用户、多个申请如何避免 y/n 串用。
8. `SessionRuntime.close` → `TeamManager.close`：最后检查退出为何不能仅理解为 kill 线程。

## 三种不同的“完成”

- 命令完成：有实际退出码与输出，可能失败。
- 大轮次结束：模型暂时不再继续；终端显示 `[Reply]`，不是整体业务成功证明。
- 持久任务 completed：负责者提交了成功报告，依赖允许继续；Leader 仍需审查质量。

## 当前方案的取舍

- 项目增强：独立同构 Session、两个通知开关、用户缓冲升级、辅助审批、派工编号及部分提交重试。
- 课程思想：常驻队友、文件邮箱、身份与生命周期管理。
- 共享目录只有工具层弱隔离；开放 bash 后不提供恶意文件操作隔离。
- 不恢复跨进程历史/Inbox；旧 Team 目录只是历史材料，不是存活成员。
- 原压缩算法与输出目录布局不在本章重设计；这里只保证完整工具交换后接收消息。
- 辅助审批采用保守范围：单个小文件删除，先保存恢复副本；其它需审批操作交给用户。
  不提供通用 shell 静态分析或强网络取消，恢复副本也不自动删除。

## 学习问题（可分批讨论）

### A01：为什么复用 Session，而不复用历史？

假设 Leader 已读了大量需求，队友只负责核对一个模块。独立会话省去了哪些无关内容？
又会缺少哪些信息？Leader 的派工说明应该补什么，而不是期待队友“自然知道”？

回答：

### A02：一次性委派与常驻协作

同样是“分析一个模块”，什么场景用 task 更合适，什么场景值得创建常驻队友？
比较调用方是否等待、结果怎样返回、下一次任务能否延续上下文，不必逐行复述代码。

回答：

### A03：消息到达不等于立刻调用模型

Leader 正执行一批工具时收到队友报告，为什么先完成工具批次？若当前已经空闲，
报告与普通进度的默认行为有什么不同？这种差别分别避免了什么问题？

回答：

### A04：派工编号保护什么？

第一次派工受阻，Leader 调整条件后重新派工，此时第一轮迟到的成功报告到达。
仅有 task_id 为什么不够？报告可以留下供阅读，但为什么不能直接更新第二轮的状态？

回答：

### A05：提交失败不等于业务失败

队友代码已写完、报告已保存、任务状态已更新，但 Leader 的 Inbox 满了。
此时哪些事实仍成立？模型应该重试哪一步，为什么不能从头再写一次代码？

回答：

### A06：谁负责“真的做完”？

队友报告 completed，但 Leader 发现缺少关键需求。Task System 的记录提供了什么，
又没有证明什么？辅助审批允许了某个操作，是否也意味着代码质量通过？

回答：

## 验证与验收

工程构建、已有定向测试和一次性离线场景的实际结果见任务单。
独立真机验收建议依次做：一个只读队友 → 两项顺序派工 → 报告唤起 Leader →
忙时缓冲/空 Enter 升级 → 队友后台命令 → 受阻后重新派工 → 关闭与审批。
真实模型调用由用户执行；未跑这些真实场景前不宣称 s13 章节验收完成。

### 第一项真机场景：只读队友

在仓库根准备独立工作区，再启动 REPL：

```sh
mkdir -p tmp/s13-team-check
cp README.md tmp/s13-team-check/project-info.md
cd tmp/s13-team-check
../../jade repl
```

输入：

> 创建一个常驻队友，职责是只读核对资料。创建一项持久任务并通过 assign_task 派给它：
> 只用 read_file 读取 project-info.md，确认项目名称和 Java 版本，不调用 bash、不改文件、
> 不委派其它子代理；完成后调用 submit_task_result，摘要写结论，正文写读取依据。
> 你可以先简短回复已派工，但不要提前宣称核对完成。收到报告后阅读报告正文，再向我简短转述。
> 不创建其他队友、定时任务或无关任务。

观察：队友独立执行；Leader 能收到报告；结果提交后任务为 completed；报告正文路径可读。
Leader 先回复不是整个验收结束，不要在报告到达前 q 退出。
任务板和邮箱位于这个 tmp 工作区，不改 JadeCode 源码。后续继续使用同一个 REPL 验证队友复用。
