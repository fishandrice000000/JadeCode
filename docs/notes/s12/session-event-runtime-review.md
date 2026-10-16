# 会话事件化：把“发生了什么”与“现在做什么”分开

工程任务单：[session-event-refactor.md](../../jobs/s12/session-event-refactor.md)。
需求与决策来自本轮 GrillMe Q1–Q18，完整术语见 [CONTEXT](../../../CONTEXT.md)。
工程自审、构建、已有 119 条定向回归与离线检查通过；不是独立 Reviewer 的保证，用户源码学习和真机验收仍待完成。

## 1. 为什么重构

原实现能运行，但人工、定时、后台通知和 hook 续跑分别进入历史，理解一个任务需要跨多个特殊入口。
现在分工为：生产者报告输入，SessionRuntime 决定何时采用及推进任务，AgentLoop 落实模型与工具步骤。
这不是引入外部 MQ，也不是把所有工具异步化。同步执行的工具批次照样返回一个事件，由同一个历史入口采用。

真正改变的产品行为是后台等待：任务可以在构建运行时读文件，但不能因为模型暂时没有工具调用就忽略必需结果。
“等待”保留任务目标与入口记忆背景，只等结果事件，不重复请求模型，也不领取下一目标。
脱离任务的命令则不阻止结束，结果成为后续参考通知；它并不是跨 CLI 生命周期的守护进程。

## 2. 推荐阅读顺序

### 第一遍：只看任务主干

1. [SessionRuntime](../../../src/main/java/com/jadecode/agent/SessionRuntime.java)：先读类说明，再读 execute、runTask。
   沿“领取任务 → 固定目标与召回背景 → 模型回复 → 工具／等待／Stop → 完成”看，不先展开每个依赖。
2. 同文件 apply：所有历史采用集中在这里，区分新请求与继续输入。
3. [SessionEvent](../../../src/main/java/com/jadecode/agent/SessionEvent.java)：回头认识主干实际遇到的事件。
   ModelReply 不是辅助摘要回复；ToolBatch 是完整一批结果，不是一份孤立结果。

### 第二遍：两种继续方式

4. [AgentLoop](../../../src/main/java/com/jadecode/agent/AgentLoop.java)：request → executeTools → compactAfterBatch。
   它不接管会话队列；先校验正式回复，再由运行器采用与确认，之后才执行工具。run(messages) 只是复用同一实现的兼容入口。
5. [BashTool](../../../src/main/java/com/jadecode/tools/BashTool.java) 的双参数 execute，接着读
   [BackgroundManager](../../../src/main/java/com/jadecode/tools/BackgroundManager.java) 的 start、collect、acknowledge、hasRequired。
   然后回到 SessionRuntime 的 deliverResults、awaitResults：识别信号与结果正文分别由谁保管。
6. [SessionInbox](../../../src/main/java/com/jadecode/agent/SessionInbox.java)：新任务 FIFO 与变化信号不是同一个队列内容。
   满队列不能让已经启动的构建永远丢掉结果。

### 第三遍：入口、安全和失败

7. [ReplCommand](../../../src/main/java/com/jadecode/cli/ReplCommand.java)、
   [RunCommand](../../../src/main/java/com/jadecode/cli/RunCommand.java)：两者都提交给运行器，只有 REPL 开 cron。
8. [CronProducer](../../../src/main/java/com/jadecode/agent/CronProducer.java)：poll、received、failed。
   再按需看 CronScheduler 的 takeDue／restore／acknowledge；时间表达式及文件格式没有因事件化重写。
9. [ExecutionContext](../../../src/main/java/com/jadecode/agent/ExecutionContext.java)、
   [PermissionGate](../../../src/main/java/com/jadecode/safety/PermissionGate.java)、
   [ConsoleInput](../../../src/main/java/com/jadecode/cli/ConsoleInput.java)：身份由 Harness 提供，审批答复带 ID，只有 UI 读取输入。
   子代理继承审批身份和只读会话关闭信号，仍有独立历史和原有同步委派边界。
10. SessionRuntime 的 fail、close，接着 BackgroundManager 的 cancelRequired、close，最后看已有相关测试。
    区分“单条命令失败”“模型执行链失败”“整个会话退出”，它们的清理范围不同。

## 3. 源码理解问题

这些问题用于检验设计如何落实，不要求逐行复述，也不重复要求回答已经确认的 Q1–Q18。
可结合源码分批讨论；批阅只在对话中进行，不改写本文件的答案。

### E01：模型暂时无事可做，为什么任务仍可能没有结束？

构建已后台启动，模型读完发布说明后返回“等待构建”。下一项用户请求已经排队。
请沿 runTask／awaitResults 说明谁保留目标、谁等待、什么到来才继续，以及为什么这不是一次新的用户任务。
补充说明入口记忆召回和任务结束后的记忆提取分别应发生几次，而不是每条事件各来一次。

回答：

### E02：统一事件，为什么不能统一当作用户的新指令？

同样最终可能使用 USER 消息的工具结果、后台完成通知、Stop 续跑和人工请求，有哪些不同的生命周期含义？
结合 Request、ToolBatch、Continue、Notifications 与 apply，说明哪个可以换当前目标，哪个只能继续或提供参考。
再说明为何后台完成不能生成第二份相同 tool_use_id 的结果。

回答：

### E03：事件统一后，为什么工具批次仍不能随意拆开？

模型一次要求两个工具，其中一个很快，另一个还没返回。此时又有定时请求及旧后台通知到达。
请说明新目标与通知各在哪里等待，谁保证整批工具交换闭合后才整理历史。
不需要解释集合语法，重点说如果让任意事件直接写历史，Agent 的执行记录和上下文保护会怎样受影响。

回答：

### E04：审批为什么跟着任务走，而不是跟着线程走？

人工请求和定时请求都由同一消费者执行，也都可能委派子代理。
为什么这不意味着它们拥有相同的审批资格？身份由谁创建和传递，模型在工具 JSON 写 source=USER 为什么不起作用？
再用“用户正在排队新请求时，执行器提出审批”解释带审批 ID 的终端命令解决了什么问题。
若批准或子代理模型回复在会话关闭后才返回，为什么还需要检查继承的关闭信号，而不能只依赖线程中断？

回答：

### E05：收到、处理结果、业务成功为什么不是一个确认？

先考虑定时首回复前失败：为何可以恢复任务入口历史并归还通知？
再考虑确认保存失败但工具已经执行：重启后为何仍可能重复？
最后看 BackgroundManager 的 collect／acknowledge：为什么领取完成结果后没有立刻删除所有记录？
说明各自确认的对象，不要求列出异常类或复述所有 catch。

回答：

### E06：一个失败为什么不应该终止所有后台命令？

任务 A 启动一项必等构建和一项明确脱离的命令，随后模型调用无法继续。队列还有任务 B。
请说明 A 的结束、两项命令的清理、B 的执行，以及之后退出 CLI 的影响。
区分“保留真实历史”和“撤销文件副作用”，不要把失败收尾当作事务回滚。

回答：

### E07：可选拓展——收件箱满为什么不应让当前任务永远等待？

新请求已满 64 项，当前任务的必等构建刚好完成。为什么它仍能恢复？
结合 SessionInbox 的变化信号与后台管理器的结果记录，解释“同一消费者”不等于“所有数据争同一个 FIFO 槽位”。
如果完成信号早于消费者开始阻塞，revision 的观察顺序保护了哪种 Agent 行为？

回答：

## 4. 验证边界和下一步

- 已有 12 个测试类共 119 条定向回归，0 failure／error／skipped；没有新增单元测试。
- 临时离线演示用假模型与真实线程／文件／shell，覆盖 12 类场景；不是网络、费用、完整终端交互或所有崩溃时点的证明。
- 最后对子代理与晚到审批的关闭围栏补验两分支，并定向复跑相关 62 条已有回归，全部通过；未再重复整个矩阵。
- 用户真机场景位于被忽略的 tmp/s12-event-check/RUNBOOK.md，尚未执行，不能宣布 s12 整章验收。
- 优先读 SessionRuntime 的 execute、runTask、apply，再沿感兴趣的分支下钻。先理解目标与事件怎样流动，不要求先刷完底层 Java 细节。
