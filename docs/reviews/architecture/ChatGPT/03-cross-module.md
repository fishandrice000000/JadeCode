# 阶段三：跨模块、包依赖与可靠性

> 2026-10-17 更新：用户要求优先审查架构。最新包边界建议见 [架构优先复审](06-package-boundaries-2026-10-17.md)：agent 仅执行器、会话归 session、CronProducer 归 cron、进程能力归 process。下文保留初版诊断；与新职责方案不同处以复审为准。

## 全局判断

JadeCode 的执行所有权比包依赖更清楚。单消费者基本解决了“谁改父历史”，但尚未解决“谁定义共享执行契约”“谁保证输入与输出完整”“谁拥有工作区写入”。下一步架构演进应围绕这几个边界，而不是把同步流程全部换成消息队列。

用户特别提出的 tools 包问题，详见 [包架构专题](02-modules/package-architecture.md)。结论是把进程执行能力移出 tools，保留面向模型的 BashTool、TaskTools、CronTools 等适配。

## 实际包依赖

依据当前产品源码的 `import com.jadecode.*` 建图，只计算包之间的显式 Java import；没有使用构建工具或执行字节码分析。它不是完整的反射/运行时依赖图。

| 包 | 项目内直接依赖 |
| --- | --- |
| Main（根） | agent、cli、config、context、cron、llm、memory、messages、safety、skills、subagent、tasks、todo、tools、util |
| cli | Main、agent、safety、util |
| agent | context、cron、llm、memory、messages、safety、tools、util |
| context | llm、messages、util |
| llm | config、messages、tools |
| memory | llm、messages、util |
| safety | agent、util |
| subagent | agent、context、llm、messages、safety、tools、util |
| tools | agent、cron、llm、safety、skills、subagent、tasks、todo、util |
| cron、tasks | util |
| messages、config、skills、todo、util | 无项目其它包的显式 import |

相互可达的两组为：Main ↔ cli，以及 agent/context/llm/memory/safety/subagent/tools。前者主要来自入口注册与 CLI 回调装配，影响小；后者则与共享类型落点有关。例如 llm 只为声明模型工具而依赖 Tool，Tool 又为执行上下文依赖 agent，agent 又使用 context/llm。这种绕回使本来独立的摘要与记忆也被卷入大环。

不能把这张图直接解读为“所有类严重耦合”。大部分具体类仍有清楚职责。合理改法是拆稳定契约、移动进程基础设施、将执行对象与工具定义分开，见 ARCH-009/011/019；不需要分成十几个 Maven 模块。

## 状态和资源归属

| 状态/资源 | 创建和拥有者 | 更新/销毁边界 | 静态检查结论 |
| --- | --- | --- | --- |
| 父历史、当前目标 | SessionRuntime | 单消费者采用事件；关闭后不续工具 | 所有权清楚，但目标保存在内存不代表模型始终可见（ARCH-005） |
| 人工/定时身份 | 生产者创建 ExecutionContext | 父子传递；模型不能修改 | 来源可靠；跨包落点可改善（ARCH-011） |
| 子历史、轮次 | 每次子委派 | 委派返回后不并入父历史 | 隔离清楚；校验/失败步骤重复（ARCH-010） |
| 后台运行与结果记录 | BackgroundManager | 模型收到后确认删除，失败按归属取消 | 有界、可归还，不抢新请求容量 |
| 同步 shell 进程与 reader | ShellCommand | finally/close/退出 hook | 已做输出排空和进程组清理；截断表达不完整（ARCH-007） |
| cron 定义/pending/lease | CronScheduler | 文件更新、领取、确认、恢复 | pending 持久，lease 仅进程内；不保证业务恰好一次 |
| 长期记忆正文和目录 | MemoryStore | 新增/整理/扫描 | 多文件整理没有崩溃提交点（ARCH-004） |
| 持久任务记录 | TaskStore | 图规则后单文件原子替换 | 不能据此宣称并发互斥（ARCH-013） |
| 模型客户端 | Main 创建，父子与辅助共用 | 当前接口无关闭/取消契约 | 未证明 SDK 泄漏，但维护尾声未继承取消（ARCH-018） |
| 终端与 System 输出流 | CLI 的 TerminalDisplay | try-with-resources 最后恢复 | 当前单 CLI 可解释；未来宿主要注入输出边界 |

## 沿真实链路检查故障

### 从用户目标到第一次模型请求

请求进入 FIFO 后才追加历史、记住原目标、召回记忆。通知随后并入 USER content，避免把通知当作目标；新请求不会插入当前工具批次。召回失败可以降级，但召回磁盘读取仍可能无界。配置校验太晚和辅助取消缺口分别归 ARCH-016/018。

证据：`src/main/java/com/jadecode/agent/SessionRuntime.java:140–151,204–229,322–328,379–387`；`agent/AgentLoop.java:213–231`；`memory/MemoryRecallService.java:77–142`。

### 从模型回复到工具副作用

父执行器在采用回复前查角色和重复 ID；运行器采用完整回复后执行工具。权限拒绝也返回同 ID 结果。意外运行异常会给没拿到结果的调用补失败说明，因此前面工具即使已产生 IO，也不会从头重跑。文件工具的路径算法漏洞、任意 shell 能力和非原子源码写入是这一链上的主要风险，不是工具交换配对本身。

证据：`agent/AgentLoop.java:282–288,307–354`；`agent/SessionRuntime.java:264–282`；详见 ARCH-001/002/017。用户输入错误和系统异常的统一结果类型在 ARCH-011 中处理，不新增“万能异常重试”。

### 从工具结果到上下文替换

转存成功后才替换正文，归档失败时旧历史仍在；摘要请求没有工具，因此不会直接分发摘要中的命令。工具对切点有保护。但是按消息数裁剪不保护当前目标；空摘要也能被当成成功。因此“配对合法”“原文已归档”和“模型仍有足够信息”是三个条件，当前只充分保证前两个的局部路径。

证据：`context/ContextCompactor.java:130–160`；`context/SnipCompactor.java:40–76`；`context/HistoryCompactor.java:98–106,143–169,198–213`。对应 ARCH-003/005/006。

### 从后台完成到模型采用

结果存在管理器中，signal 只唤醒。领取移出 ready、记录仍在 jobs；正式回复后才 acknowledge。定时首回复前失败能归还通知，普通任务失败保留历史并清理所属必等命令。等待先读 revision 再查结果，避免丢唤醒。脱离命令只在下次正常请求作为参考，不唤醒空闲模型。没有发现与 64 项新请求队列争抢完成槽的问题。

证据：`tools/BackgroundManager.java:134–215`；`agent/SessionRuntime.java:263–294,322–348,404–415`；`agent/SessionInbox.java:63–83`。风险主要是总任务无限续跑（ARCH-008）和同步输出缺少截断信息（ARCH-007），不需要重做后台队列。

### 从定时到外部副作用

持久 pending → lease → 入队 → 首个合法正式回复 → 保存确认 → 执行业务工具。确认保存失败会告警，进程内不立即重投；重启可能再送。已取消任务在开始前被跳过。模型若回复不完整，当前也可能确认，属于 ARCH-003；并发 CLI 覆盖状态属于 ARCH-013。不能把确认后工具失败视为自动重投理由，否则可能重复真实副作用。

证据：`cron/CronScheduler.java:116–168`；`agent/CronProducer.java:132–153`；`agent/SessionRuntime.java:210–215,264–282`。

### 从完成到维护、关闭

所有必等结果处理后、Stop 放行，才提取记忆并输出 Completed。维护的可预期 IO 失败不会抹掉主任务结果。退出先禁新工具、关进程、拒队列再中断线程；晚到正式回复仍会检查关闭。辅助记忆没有同样的返回后检查，可能晚到发布或继续调用模型，见 ARCH-018。旧记录整理的删除窗口与 daemon 退出组合，强化 ARCH-004 的优先级。

证据：`agent/SessionRuntime.java:296–309,509–529`；`agent/AgentLoop.java:400–424`；`memory/MemoryManager.java:81–108`。

## 优化之间的依赖与冲突处理

1. **先规定契约，再搬包**：定义工具定义、执行身份、命令事实和停止结果。ARCH-019 的 process 分包与 ARCH-011 共享契约一起规划，但每步保持可构建，不在本轮执行。
2. **路径与发布一起核对**：先明确最终授权目标（ARCH-001），再把文件覆盖换成候选发布（ARCH-017）；否则移动文件可能悄悄改变软链接语义。
3. **目标固定先于预算收敛**：ARCH-005 必须确保目标进入实际请求；随后统一 system、工具与 messages 的预算。不能为满足字符上限把用户限制删掉。
4. **取消先于维护采用**：ARCH-018 明确取消后的采用点；ARCH-004 的代次发布负责即使强制退出也有完整旧代。两者互补，不能互相代替。
5. **共享步骤不吞掉子边界**：ARCH-010 只复用回复验证/工具执行，不让子代理使用父 FIFO、后台队列或 Memory 生命周期。
6. **单写者先于并发平台**：ARCH-013 先把现行约定变成锁约束；没有真实并发需求时不设计分布式认领和任务租约系统。

阶段三跨模块分析完成。问题优先级与综合去重见最终报告，工程和文档证据见下一份报告。
