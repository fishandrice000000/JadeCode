# s12：让时间成为 Agent 的任务入口

版本提示：本指南记录 `477b769` 的原始定时接入。当前 `CronRuntime` / `runScheduled` 已由统一会话运行器取代，
请先阅读 [事件化运行指南](session-event-runtime-review.md)。原题号与内容作为历史保留，不据此要求重复执行旧任务。

工程记录：[cron-scheduler.md](../../jobs/s12/cron-scheduler.md)。
来源是本机原课程 `s12_cron_scheduler/README.zh.md` 和 `code.py`。
本章完整实现后交接学习；工程证据与边界见任务单，不替用户完成理解和真实 CLI 验收。

## 1. 要解决的是“谁在到点时发起新请求”

“每分钟检查一次服务”不能只靠模型在回答中说“我会检查”。
模型回复完就可能停止，没人再次发请求，任务就不会开始；s11 的后台 shell 也只会继续执行已经提交的命令。

s12 增加的是时间驱动的任务入口：模型把 cron 和 prompt 交给 Harness，Harness 记录、计时、排队，
空闲时将它作为新请求交给模型。真正读文件、跑命令、作业务判断的仍是模型及其工具循环。

```text
用户授权未来任务 → 模型调用 schedule_cron
    → 保存时间规则与 prompt
    → 计时检查匹配
    → 先保存 pending_delivery + last_fired，再入队
    → 等待当前人工/定时回合结束
    → AgentLoop.runScheduled("[Scheduled] ...")
    → 收到首次合法正式回复 → 确认交付
    → 模型工具调用 → 结果回灌 → 最终答复
```

注意顺序：交付确认发生在工具执行之前。它回答“请求已交给模型”，不回答“测试通过或报告正确”。
与 s10 的 complete_task 也不同：那是模型检查工作后登记业务状态；s12 不自动修改业务任务图。

## 2. 三个职责不要混在一起

- **CronScheduler**：记录定义与待交付状态、匹配当前分钟、保存和恢复；不运行模型或 shell。
- **CronRuntime**：只在 REPL 显式启动计时/交付任务，用一个回合锁保护共享历史；键盘等待在锁外。
- **AgentLoop**：仍处理模型回复、权限 hooks、工具交换、上下文和记忆；新增定时入口的首回复确认与失败恢复。

s11 的完成通知是“旧命令出现了新结果”，s12 的 prompt 是“到期要执行的新任务”。
因此前者仍不唤醒空闲循环，后者会启动新回合；二者不能都当作任意外部文本自动执行。
定时 prompt 的来源是已经授权并保存的定义，不是从工具输出里猜出来的任务。

## 3. 先理解交付语义，再看保存细节

`pending_delivery` 表示到期但尚未确认模型收到，**不是业务运行中/成功**。
`last_fired` 记录触发分钟，避免清 pending 后同一分钟又把它入队。JadeCode 的分钟标记带时区偏移。
一次性任务确认后删除，周期任务确认后清 pending；Agent 仍忙时，同一任务不会按分钟积累无限副本。

为什么先保存 pending 再交付？如果先调用模型，进程在保存前退出，恢复后就不知道它是否曾被交付。
不过即便按当前顺序，也可能在“模型收到后、确认写盘前”崩溃。因此这是**至少一次**，不是恰好一次。
如果定时任务涉及发通知、扣款或部署，业务端仍要处理幂等与授权，不能靠 cron 记录证明只执行了一次。

失败分两侧：

- **首回复前失败**：恢复入口历史快照、归还尚未确认的后台完成通知、将 cron 项重排。
  已写出的压缩归档不删除，已经发生的辅助请求费用不回滚。
- **首回复后失败**：保留已产生的历史/工具记录，不重放整项任务，避免重复副作用。
- **模型收到但确认保存失败**：告警，当前进程保留 pending/lease 不立即重送；重启可能再次交付。

与课程相比，JadeCode 每次只交一项，避免多条定时请求被现有 activeRequest 的末条捕获规则遗漏；
首次交付失败增加 5 秒退避，连续三次暂停本会话自动交付，人工交互仍可用。durable pending 可重启恢复。
这些是本项目的集成取舍，不是 cron 协议要求。

## 4. 推荐阅读顺序

1. [ReplCommand.call](../../../src/main/java/com/jadecode/cli/ReplCommand.java) 与
   [Main.buildLoop](../../../src/main/java/com/jadecode/Main.java)：找运行时的启动/关闭点，以及三工具共享的调度器。
   `run` 不启动定时线程，Main 装配本身不自动调用模型。
2. [CronRuntime.start / poll / deliver / runUser](../../../src/main/java/com/jadecode/agent/CronRuntime.java)：
   从“用户不再输入时谁启动模型”切入，再看人工与定时回合怎样串行，不先研究锁 API。
3. [AgentLoop.runScheduled / runInternal](../../../src/main/java/com/jadecode/agent/AgentLoop.java)：
   沿“新任务 → activeRequest → 首次正式回复 → 确认 → 工具执行”读，注意失败恢复在哪一侧发生。
4. [CronScheduler.pollDue / takeDue / acknowledge / restore](../../../src/main/java/com/jadecode/cron/CronScheduler.java)：
   观察“到期、已取走、确认、失败归还”几种状态，不把清 pending 当作业务成功。
5. [CronTools](../../../src/main/java/com/jadecode/tools/CronTools.java)、
   [CronJob](../../../src/main/java/com/jadecode/cron/CronJob.java)、
   [CronExpression](../../../src/main/java/com/jadecode/cron/CronExpression.java)：按需看模型输入校验与时间匹配。
6. 最后按需看 CronScheduler 的 load / commit：完整候选、先持久化后发布、坏文件不静默丢弃。
   [BackgroundManager.restoreNotifications](../../../src/main/java/com/jadecode/tools/BackgroundManager.java)
   是 s11 接线支撑：恢复通知文本，不重跑 shell。
7. 已有 MainTest / MainCompactionAssemblyTest / AgentLoopTest / PermissionGateTest / BashToolTest
   验证旧能力回归；没有新增 JUnit，临时离线演示的检查范围见 jobs，别将它当成永久完整测试覆盖。

## 5. 架构问题：围绕需求、故障与职责

### C01：为什么“后台命令”还需要另一套“定时任务”？

用户说“每个工作日早上检查服务”，目前模型已经给出最终答复，REPL 正等待输入。
s11 能否独自保证明早开始检查？s12 的哪一层观察时间，哪一层发起推理，谁真正判断服务状态？
再说明为什么“cron 触发”不能直接当作“检查通过”。

回答：

### C02：durable 保存了什么，没有保存什么？

用户设置了 durable=true，然后关掉 JadeCode 一整天。任务会按点执行吗？重启是否补跑每个错过的时点？
如果它在退出前已 pending，恢复后与“只是保存了定义、尚未到期”的任务有什么区别？

回答：

### C03：为什么一次性任务在完整业务结束前就可能从列表消失？

定时任务要求生成报告，模型第一次回复请求 read_file，但报告还没写。
此时一次性定义可能已经删除，这是否表示报告完成？如果改成最后才确认，会怎样改变崩溃后重复执行的风险？
结合“收到任务”和“完成工作”两个事件讨论，不要求复述 catch 分支。

回答：

### C04：时间到了，但用户还在让 Agent 改代码，怎么办？

为什么计时线程可以把任务记为 pending，却不能直接往共享历史追加消息并调用模型？
等待用户键盘输入与正在执行一轮 Agent 有什么区别？若定时任务要求执行需要审批的命令，
为什么不能让后台线程抢读终端里的 y？

回答：

### C05：可后补——至少一次不等于业务只做一次

模型已收到定时任务，但确认状态保存失败/来不及保存就退出。重启后同一任务可能重送。
若任务是“读服务状态”与“向外部系统提交一次不可撤销操作”，需要的业务保护有什么不同？
不需要设计完整生产队列，只说明为什么 Harness 的这份文件不能独自消除重复副作用。

回答：

## 6. 独立真机验收与边界

材料在忽略的 `tmp/s12-cron-check/`，按其中 RUNBOOK 在该目录运行 `../../jade repl`。
先验自动唤醒并生成本地报告，再验定义重启恢复/取消、非法表达式、非主线程审批拒绝。
真实模型调用由用户进行，可能产生 API 费用；验收后取消全部周期定义，避免后续意外运行。

当前不支持完整 crontab 扩展语法、停机执行/补跑、跨工作区集中调度、多个 CLI 并发改同一库，
也不保证所有业务失败自动补做。定时输出可能与 REPL 提示符交错，没有新增富终端渲染系统。
仅在一个存活的 REPL 内提供本章能力，学习与真机完成前不勾选 s12。
