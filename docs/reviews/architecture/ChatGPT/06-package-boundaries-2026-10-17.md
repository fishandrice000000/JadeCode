# 架构优先复审：执行器、会话、定时与进程的包边界

日期：2026-10-17。根据用户最新要求，本轮优先评价职责划分、模块协作、包边界与依赖方向。此前工程细节 findings 保留，但不将它们的严重程度排序直接当成架构讨论顺序。本文件更新原包架构建议；仅修改审查报告，没有迁移源码或执行构建/测试。

实际报告当前位于 `docs/reviews/architecture/ChatGPT/`，沿用现有位置，不重建或搬回旧日期目录。源码 HEAD 仍为 `f15388d24defe210bb7a82d3b53b437bba7dfe7c`，原有三份笔记修改和研究目录保留。

## 1. 推荐边界

**agent 只承载 Agent 执行能力；session 拥有会话；cron 拥有时间投递；process 拥有进程；tools 负责模型工具适配。**

```text
com.jadecode/
├── agent/
│   └── AgentExecutor.java       原 AgentLoop；请求、工具批次、上下文准备
├── session/
│   ├── SessionRuntime.java      会话历史、当前任务、调度、等待与结束
│   ├── SessionInbox.java        会话的新请求队列与唤醒
│   └── SessionEvent.java        会话入口及历史采用事件
├── cron/
│   ├── CronExpression.java      时间规则
│   ├── CronJob.java             定义与交付状态
│   ├── CronScheduler.java       到期选择、持久状态和领取
│   └── CronProducer.java        轮询、投递、退避与交付确认协调
├── process/
│   ├── BackgroundManager.java   命令登记、结果领取/确认、按归属取消
│   └── ShellCommand.java        进程启动、超时、输出、终止
├── tools/                      BashTool、TaskTools、CronTools 等模型适配
├── execution/                  共享执行契约：身份、审批和取消（Tool 留 tools）
└── 其余现有能力包              保留各自领域职责
```

这是职责目标，不是要求逐文件机械搬迁。agent 中执行结果可作为 AgentExecutor 的嵌套值类型，避免为了传值又把会话事件塞回 agent。execution 暂作为共享契约的候选名称，不将所有运行状态都装进去。

## 2. AgentLoop 为什么应改名，以及 agent 应移出什么

AgentLoop 这个名称暗示“它拥有完整循环并决定继续和结束”。当前完整任务循环实际在 SessionRuntime.runTask：领取任务后的推进、后台等待、Stop 续跑、最终完成都在那里。AgentLoop.request 中虽有局部重试循环，但那不能代表整个 Agent 生命周期。**建议采用 `agent.AgentExecutor`，保留 agent 作为领域名称，类名明确其执行角色。**

不过，执行器目前还持有会话所需的 cron 和后台对象，再通过 getter 交给运行器；它也代理记忆入口和任务结束维护。这些职责不能随改名原样视为已经划分清楚。

建议重新分配：

| 职责 | 建议拥有者 | 原因 |
| --- | --- | --- |
| 正式模型请求、回复检查、工具批次、压缩执行 | AgentExecutor | 属于落实一次 Agent 执行步骤的机制 |
| 当前目标、历史采用、何时继续/等待/完成 | SessionRuntime | 需要完整会话和当前任务状态 |
| 每任务入口召回、完成后记忆维护的调用时机 | SessionRuntime | 生命周期时机由会话决定；记忆算法仍在 memory |
| cron/后台对象的创建和注入 | Main/装配代码 | 不让执行器成为其它服务的转交容器 |
| shell/后台服务的关闭 | 会话宿主确定唯一所有者 | 持有引用不等于拥有资源，避免通过执行器代关全部基础设施 |
| 共享任务身份与取消/审批契约 | 最少的独立执行契约 | tools、safety、子代理均需使用，不应因此依赖会话调度 |

源码证据：`src/main/java/com/jadecode/agent/AgentLoop.java:174–199,229–231,253–288,307–403,422–424`；`src/main/java/com/jadecode/agent/SessionRuntime.java:260–309`。

## 3. 会话移到 session，但还要解除反向依赖

SessionRuntime、SessionInbox 和会话事件属于同一包，原因是它们共同维护一次会话的所有权、排队和推进。没有必要把它们放进含义模糊的 runtime 或 common。

单纯搬这三个文件会留下两条反向依赖：

1. 执行器返回 SessionEvent.ModelReply/ToolBatch/Continue，并通过 Consumer<SessionEvent> 交回历史替换。
2. AgentLoop.run(messages) 直接调用 SessionRuntime.runExisting，导致下层执行器反过来创建上层会话。

具体调整方向：

- 执行器返回自己的步骤结果，例如嵌套的模型回复结果、工具批次结果；SessionRuntime 将这些结果采用为会话历史。执行器不构造“新的会话任务”或“会话通知”。
- 若压缩后必须立即采用新历史，再继续发模型请求，可提供只承担历史采用的窄回调，传替代消息列表或执行器定义的变更值；不把整个 SessionEvent 协议传进执行器。必须保留“已采用压缩后，正式请求失败不自动恢复”的现行语义。
- 旧 run(messages) 是兼容的会话调用入口，应由 session 侧承接，或根据实际调用者规划迁移；不继续让 AgentExecutor 静态回调 SessionRuntime。保留兼容期间可以明确承认依赖尚未完全解除，不能把临时桥接称为目标架构。
- 两包间开放的是少量执行步骤 API，不是把所有包私有方法改 public，也不暴露运行器的可变历史给任意生产者。

收益是执行器能够被会话使用，而不需要知道完整会话实现。代价是需要调整结果载体、可见性及旧调用者；这属于中等范围的架构调整，不是零风险改目录。

源码证据：`src/main/java/com/jadecode/agent/AgentLoop.java:198–199,253–262,288,307,354,370–388`；`src/main/java/com/jadecode/agent/SessionRuntime.java:365–389,478–494`。

## 4. CronProducer 为什么适合归回 cron

从当前职责分工看，CronProducer 被放在 agent 后，形成“cron 负责规则/存储，agent 负责会话侧投递”的划分。它是 SessionRuntime 的包私有协作者，这可以解释当前代码组织；并不意味着它必须属于 agent，也不能据此推断作者历史上的唯一动机。

关键在于实际依赖：它接收 CronScheduler、Predicate<CronJob> 投递回调和错误输出函数，没有直接引用 SessionRuntime 或 SessionEvent。它管理轮询、领取恢复、首回复确认、失败退避和暂停，这些状态变化都围绕 cron 交付。**把它归回 cron 更内聚，已有回调已经足以避免 cron 反向依赖 session。**

推荐关系：

```text
cron.CronProducer --回调交付 CronJob--> session.SessionRuntime
session.SessionRuntime --报告收到/未收到--> cron 的交付协调入口
```

会话负责把 CronJob 转为可信的定时请求，决定何时运行；cron 负责更新交付状态、恢复领取和退避。cron 不调用模型、不改历史；会话不解释表达式或计算 cron 的重试时间。由 REPL 会话宿主控制 start/close 仍然合理，“谁启动它”不决定“它属于谁的包”。

第一步可保留 CronProducer 类名和现有回调，只明确跨包所需的公开入口；没有必要为了这次分包立即新增事件总线或 Producer 接口。若以后重命名，CronDispatcher 能更突出它不仅轮询还协调交付，但当前这不是必要前置。

源码证据：`src/main/java/com/jadecode/agent/CronProducer.java:25–53,61–108,119–172`；`src/main/java/com/jadecode/agent/SessionRuntime.java:107,166–169,210–215,267–269`。

## 5. BackgroundManager 与 ShellCommand 归 process

两者移出 tools 的理由是其职责对模型工具协议没有依赖：ShellCommand 管 OS 进程，BackgroundManager 管命令状态和结果；BashTool 才解释工具参数和回执。会话直接消费命令结果，也说明管理器不是某个工具的内部细节。

还需要处理两处边界：

- BackgroundManager.start 当前接收 ExecutionContext，但实际登记主要需要任务 ID。进程服务接收必要的归属和执行属性即可，避免依赖整个 agent 上下文。
- ShellCommand 和 runForeground 当前是包私有，BashTool 同步子代理路径也直接创建 ShellCommand。跨包后需要决定公开哪个命令执行入口，保持生命周期清楚；不能只为编译把所有内部类型放开。可以把现有同步执行类整理成很小的公开 API，也可以让命令服务封装包私有实现，暂不强制引入额外接口层。

管理器保存 required 属性合理；决定“当前任务是否必须继续等”仍归 session。进程完成事实的模型通知格式也应由消费侧适配，process 不承担用户目标或会话历史。

源码证据：`src/main/java/com/jadecode/tools/BackgroundManager.java:96–121,166–231,255–274`；`src/main/java/com/jadecode/tools/ShellCommand.java:22–43,63–126`；`src/main/java/com/jadecode/tools/BashTool.java:175–179`。

## 6. 目标依赖与架构审查顺序

```text
CLI / Main：装配、启动和确定资源所有权
    |
    v
session --> agent.AgentExecutor --> llm / tools / execution / context
    |                              tools --> execution / subagent / process / 各领域服务
    +----> cron
    +----> process
    +----> memory（决定生命周期调用时机）

agent / subagent / session / tools / safety --> execution（共享执行契约）
cron、process 不反向依赖 session 或 agent 的具体实现
agent 不依赖 session
```

该图聚焦本轮四个边界，不声称已经消除整个项目所有传递依赖。比如 Tool 对象与 LLM 工具定义的混合、父子执行步骤的复用仍是下一层架构议题。

后续架构审查按以下顺序推进：

1. 明确上述五种职责及类的归属，采用 AgentExecutor 的命名。
2. 明确会话与执行器交换什么结果，清除旧兼容入口造成的反向依赖。
3. 明确 cron 的投递/确认端口、process 的执行/结果端口和宿主的资源所有权。
4. 再审视共享执行身份、工具定义与执行对象、父子执行机制之间的依赖。
5. 在边界稳定后，把已有工程问题映射到对应模块中处理；不让局部参数校验、日志和测试数量代替架构判断。

这些建议与用户当前方向一致；接口形状、共享契约包名及兼容入口如何迁移仍是设计候选，没有实施。旧报告 P0–P3 表继续表达风险严重程度，本轮的“先讨论架构”表达工作顺序，两者分开记录。


其余包边界的进一步分析见 [Hooks、执行契约、工具定义、子代理与装配](07-other-package-boundaries.md)。


最新用户审阅更新：Tool、ToolSchema、SubagentTool 均留 tools，撤回 Tool 迁 execution 的候选方案。用户决定本轮不引入委派回调，保留 SubagentTool 对 SubagentRuntime 的直接依赖，不将消除此环作为本轮要求。当前范围以 [08 清单](08-package-redesign-scope.md) 为准；07 第 8 节保留早先候选讨论，第 9 节记录更新。
