# s12 后续工程方案：统一会话事件与任务生命周期

日期：2026-10-16。
状态：实现、构建、已有定向回归、离线检查与工程自审 Pass；源码阅读指南已交付，用户学习与独立真机验收待完成。原始 s12 的历史工程结果见 [原任务单](cron-scheduler.md)，不能将原验收记录当成本轮新行为的真机验收。

## 1. 目标与非目标

首要目标是让任务生命周期容易理解，以统一事件处理为手段。解决人工请求、定时任务、后台通知、工具结果与 Stop 续跑分别接入所造成的入口分散和职责交叉，不为形式上像 MQ 而全面异步化。

这是 JadeCode 的局部架构改进，不声称属于原课程的唯一正确设计，也不据此推进或完成 s13–s17。

不引入外部 MQ、会话或收件箱的跨进程恢复、工具并发、任务抢占、父子任务统一调度、守护进程、完整事件溯源或恰好一次执行。普通模型和工具调用可以保持同步；已有压缩与记忆算法、Task System 存储和协议格式不重写。

## 2. 已确认的整体职责

- 生产者只提交事件，不直接修改会话历史。来源包括终端、计时器、工具批次、后台命令完成及 Stop 续跑。
- 会话运行器是单一消费者，拥有历史、当前目标和执行状态，负责调度、事件采用、交付确认、失败及关闭。
- 任务执行器落实模型、工具及压缩等执行流程，不管理会话级任务队列或定时重投。
- 内部同步事件可以交回同一个处理入口，不要求每一步换线程。不得让消费者阻塞等待一个必须由它自己继续消费才能产生的结果。
- 模型协议中的 Message 是事件采用后的上下文表示，不用 role=user 代替事件来源、执行身份或权限信息。
- 当前执行任务身份与 cron 定义 ID、bg_id、tool_use_id、s10 持久任务 ID 各有职责，不能互相替代。

本任务单仅规定职责，不提前固定新类数量、所有方法名或内部集合实现。

## 3. 调度、等待与完成契约

1. 人工与定时新请求按实际入队顺序领取，不插队、不抢占。同一时刻只有一个当前执行任务。
2. 工具结果、Stop 续跑与当前任务的必等后台结果继续推进当前目标，不作为新请求排队。
3. 当前任务等待后台结果时仍占据执行位置；终端可以接收后续请求，但后续任务不开始执行。
4. Bash Tool 增加后台参数 wait_for_completion，后台路径省略时默认 true。true 仍立即返回启动回执，只表示当前任务结束前必须处理最终结果；false 明确脱离当前任务。两个标记都必须是真实 JSON boolean；仅同步执行却显式传 wait_for_completion 时拒绝。没有后台管理器或 Harness 上下文的后台调用明确失败，不伪造回执。
5. 多个必等命令逐批恢复，在安全请求边界合并已到达结果，不强制全部等齐，也不为每条事件单独调用模型。
6. 等待期间不轮询模型。结果到达后继续原任务，不重新确立目标或重复入口记忆召回。
7. 模型给出无工具调用的回复不再单独构成完成依据：还需满足 Stop 不要求续跑、没有尚未处理的必等结果。只在真正结束时执行原有记忆提取与整理。
8. 等待期间的文字可展示为进展，并明确等待状态，不标作最终完成。完成不是对业务语义正确性的证明。
9. 脱离任务的结果变为带原任务和命令身份的会话级通知，不恢复已结束任务，不主动唤醒空闲模型；下一次正常请求在安全边界带入，不替换当前目标。

## 4. 消息、安全和失败契约

- 历史采用集中管理。工具结果仍与对应调用按既有协议配对；整批结果完整回灌后，才处理后续请求和批次压缩。
- 后台启动回执已闭合原工具调用，最终完成是关联后台事件，不再构造同调用 ID 的第二份 tool_result。
- 压缩器继续处理合法消息历史，保留最新工具交换的既有约束，不为队列改写压缩算法。摘要或转存成功写出的文件不随内存恢复删除。
- 当前目标与入口召回背景跨工具和后台续跑保持稳定。辅助模型回复不进入主任务工具分发或 Stop。
- Harness 设置可信任务来源，人工任务允许交互审批，定时任务需要交互审批时拒绝。模型不能改写来源，子代理继承审批边界。终端输入只有一个读取入口，审批请求及答复要关联，不能让输入线程与权限闸门各读一份 stdin。
- 保留命令拒绝表、路径围栏、工具成员检查及失败关闭原则；事件化不将权限决定改为模型可忽略的普通文本。
- 单条后台命令失败或超时仍作为结果送给模型，不由 Harness 自动重跑或直接判定整个任务失败。
- 已开始任务的模型执行链在既有补救后最终失败，报告并结束本次执行，终止仍在运行的必等命令，释放位置；明确脱离任务的命令继续。保留真实历史、诊断和已发生的 IO，不冒称全局事务回滚。
- 定时任务首次有效正式回复确认交付，首回复前失败沿用既有有限退避，首回复后不自动重投整个业务。确认落盘失败的告警及 pending/lease 边界不能在重构中被静默吞掉。
- 首回复前失败不能丢掉已领取但尚未被模型确认接收的通知，也不能在重投时重复追加任务提示。候选历史采用与待处理事件的归属由运行器统一管理，具体实现不能散回各生产者。
- 退出 CLI 停止派发并终止本会话全部后台命令，包括脱离任务的命令；不排空待执行请求，不跨重启恢复命令。关闭后晚到回复不得再发起新工具，已发生副作用不撤销。

## 5. 工程落地的默认边界（随完整方案确认）

- 收件箱有容量限制：满时明确拒绝新的人工请求，定时任务保留待交付状态，不能先确认后丢弃。已经启动命令的完成结果不能因为普通请求队列已满而被静默丢弃。
- cron 退避不阻塞人工请求；既有同分钟去重、pending 合并与取消语义保持，不新增多进程写入能力。
- 子代理保持独立历史与现有同步委派，只适配可信任务来源和审批传递，不开启子会话后台命令或把父子挂到同一消费者。
- run 使用相同执行机制完成单项任务后退出，不因为复用运行器而开启持续 cron 调度；repl 显式拥有运行器的启动和关闭。
- 沿用当前命令超时、输出摘要与截断策略，不顺便实现完整后台输出落盘或长期服务生命周期。
- 原有 run 等公共调用路径按实际调用方决定兼容适配；不为兼容留下两套生产调度逻辑。

## 6. 允许范围及必要依赖

实施时先读取完整 status/diff，再确定精确提交路径；下表是职责范围，不是提前批准暂存所有文件。

| 范围 | 预期修改 |
| --- | --- |
| agent/AgentLoop、CronRuntime 及必要的新事件/运行器类型 | 收敛入口、历史所有权与任务生命周期，去除被统一机制取代的定时专用执行包装 |
| cli/ReplCommand、RunCommand、Main | 生产者、结果展示、统一输入与启动关闭装配 |
| tools/BashTool、BackgroundManager、必要的 ShellCommand | 新参数、所属任务、逐批完成与按任务终止必等命令；不能用关闭整个管理器误杀无关脱离命令 |
| safety/PermissionGate、必要的 Hooks 参数接线 | 从线程身份转为可信任务来源审批，集中读取终端输入 |
| cron/CronScheduler 的交付适配 | 接入新收件箱，保留表达式、持久化格式和既有失败原子性 |
| Tool、SubagentTool、SubagentRuntime 的必要接口适配 | 仅传递可信执行上下文与审批身份，不全面重构普通工具或子循环 |
| 已有相关测试 | 适配必要的入口、schema、装配断言，保留原有验证，不新增单元测试 |
| README、jobs、notes、CONTEXT、ADR | 同步实际行为和源码阅读路径，不覆盖用户答案 |

默认不修改 pom、依赖、Java 版本、provider、认证、机器级 Codex 配置、归档路径、s10 任务图或无关优化。

## 7. 验证、工程自审与学习交接

先构建和最窄已有回归；按实际风险运行相关 AgentLoop、BashTool、PermissionGate、Hooks、Main 装配、子代理及压缩回归，不默认全量。不得新增单元测试、删除或禁用既有失败。

在临时工作区使用假 LLM 和真实线程/文件/进程进行必要的离线检查，不调用真实模型：

- 两类请求 FIFO、等待期间新请求不插入、必等结果主动恢复且无模型轮询。
- 多个结果逐批与合并、失败与超时送达、脱离通知不主动唤醒及不改变当前目标。
- 人工审批路由与定时拒绝、子代理来源继承、终端输入不竞争。
- 工具批次闭合后再压缩、入口召回不重复、未完成任务不提前提取记忆。
- 首回复确认及退避、确认失败保留、入队失败不丢定时任务、满队列不丢完成结果。
- 模型最终失败清理所属必等命令而不误杀脱离命令、退出全部清理、晚到事件不复活已结束任务。

实现后另做工程自审，核对实际源码、调用方、diff、注释、已有测试及上述契约。中文 JavaDoc 和行间注释同时验收，重点说明事件归属、状态变化和失败边界。临时材料按精确路径清理，真实 CLI 验收由用户执行。

工程放行后再提供完整主流程阅读指南及 Agent 架构问题。现有用户答案不改写，不将文档草案或离线通过当成用户理解、真机通过或 s12 章节验收。

## 8. Git 与当前状态

设计检查点为 `5461562 记录会话事件化与后台等待的架构决策`，只含 CONTEXT、两份 ADR 和本任务单初版。
实现作为一笔完整检查点 `统一会话事件消费与后台任务等待`，包含本单后续更新、下列源码、已有断言适配及必要说明，不 push、不改历史。

精确实现文件：

- README.md
- docs/jobs/s12/cron-scheduler.md、docs/jobs/s12/session-event-refactor.md
- docs/notes/s11/background-tasks-review.md、docs/notes/s12/cron-scheduler-review.md（仅添加历史版本提示）
- docs/notes/s12/session-event-runtime-review.md（新增阅读指南）
- src/main/java/com/jadecode/Main.java
- src/main/java/com/jadecode/agent/AgentLoop.java
- src/main/java/com/jadecode/agent/CronRuntime.java（删除，被通用机制替代）
- src/main/java/com/jadecode/agent/CronProducer.java、ExecutionContext.java、SessionEvent.java、SessionInbox.java、SessionRuntime.java（均为 agent/ 下新增文件）
- src/main/java/com/jadecode/cli/ConsoleInput.java（新增）、ReplCommand.java、RunCommand.java
- src/main/java/com/jadecode/context/ContextCompactor.java（只同步入口捕获的注释）
- src/main/java/com/jadecode/cron/CronScheduler.java（只同步职责注释，格式与算法未改）
- src/main/java/com/jadecode/safety/Hooks.java、PermissionGate.java
- src/main/java/com/jadecode/subagent/SubagentRuntime.java
- src/main/java/com/jadecode/tools/BackgroundManager.java、BashTool.java、SubagentTool.java、Tool.java
- src/test/java/com/jadecode/MainTest.java、src/test/java/com/jadecode/safety/PermissionGateTest.java

保留用户已有的 s08/s09/s10 笔记修改和 other-agents-analysis 目录，不暂存。tmp 验证材料、JAR、class 与运行期状态不加入 Git。

讨论依据：[术语表](../../../CONTEXT.md)、[ADR 0001](../../adr/0001-background-task-completion-dependency.md)、[ADR 0002](../../adr/0002-session-event-processing-boundary.md)。

## 9. 实际实现与工程自审

SessionRuntime 的 execute／runTask 是唯一任务执行主干，apply 是历史采用入口。SessionInbox 只存最多 64 项新请求，后台完成正文仍在 BackgroundManager 的有界队列，只发送变化信号；等待时不领取其它任务。普通同步步骤直接返回事件，不为统一形式额外换线程。

AgentLoop 的 request 返回已校验的正式回复；运行器采用、确认定时交付后才调用 executeTools。工具批次完整回灌后才执行 compactAfterBatch。旧 run(messages) 借用同一主干，不启动计时线程，也不在返回时关闭整个执行器。runScheduled 和 CronRuntime 已删除，不保留第二套生产调度。

BackgroundManager 给每条后台命令绑定执行 ID、必等标记与独立 ShellCommand 实例；无需修改 ShellCommand 本身即可按任务取消。collect 领取不等于确认：结果保留拥有权与容量直到正式模型回复；定时首回复前失败可原样归还，不能通过字符串伪造后台事件。

ExecutionContext 通过 Tool 的兼容重载与 hook 参数传递来源；普通工具继续委托原 execute。子代理只传递身份与审批通道，不共享父历史或收件箱。PermissionGate 不读 stdin；ConsoleInput 统一处理任务与带 ID 的审批，run 没有审批时也不多读输入。

最终边界复核补齐了父子共享的只读会话关闭信号：子代理的模型回复晚到、超限错误晚到或审批晚到时，均在新模型／工具步骤前复核。父执行器也在审批返回后复核，防止关闭后才返回的批准启动工具。信号由 Harness 提供，不进入工具 schema；不引入父子共享队列，也不能撤销正在进行的 IO。

**Pass（同一实现者的单独工程自审，不冒称独立审查）**：核对代码、调用方、diff、注释与相关验证，未发现已知阻塞问题。自审修正了“任务快速失败或执行中参数错误可能被 CLI 误报拒收”、关闭时先禁止执行再完成队列 future 的顺序，以及无错误消息时的 cron 告警；同步修正旧入口与线程审批注释。单次 run 的空请求在装配前拒绝，不读取配置或调用模型。

边界：没有新增完整会话恢复、外部 MQ、抢占、无限命令等待或后台输出文件；仍受原命令 120 秒与 Linux 进程组能力约束。不能撤销已发生 IO，不能强制终止所有第三方 HTTP 客户端，也不承诺 SIGKILL／断电／逃离进程组后的清理。终端提示与异步进展可能交错，未引入富终端 UI。未知 ContentBlock、Memory INVALID_RESPONSE 和其它优化保持原边界。

## 10. 实际验证与交接

没有新增 JUnit 方法／文件，没有真实 API 调用，也没有读取 .env、机器认证或密钥。

- `mvn -q -DskipTests compile`：退出 0。
- 最窄四类回归：AgentLoopTest 18、BashToolTest 7、PermissionGateTest 8、MainTest 9，共 42，全部通过。
- 扩展已有回归：AgentLoopAutomaticPreparationTest 7、AgentLoopManualCompactionTest 12、AgentLoopReactiveCompactionTest 11、SubagentRuntimeTest 11、SubagentRuntimeCompactionTest 19、MainCompactionAssemblyTest 6、ToolSchemaTest 5、HooksTest 6，共 77，全部通过。
- 最终执行 `mvn -q clean -Dtest=AgentLoopTest,BashToolTest,PermissionGateTest,MainTest,AgentLoopAutomaticPreparationTest,AgentLoopManualCompactionTest,AgentLoopReactiveCompactionTest,SubagentRuntimeTest,SubagentRuntimeCompactionTest,MainCompactionAssemblyTest,ToolSchemaTest,HooksTest test`：退出 0，12 类／119 条，0 failure、0 error、0 skipped。不是全量回归。
- 最终 `mvn -q -DskipTests package`：退出 0，生成 target/jadecode.jar；没有用跳过测试替代上面的实际回归。
- 最后补充单次 run 的空提示装配前拒绝后，定向重跑 MainTest 9 条与 package，均退出 0；`java -jar target/jadecode.jar run ''` 输出“任务描述不能为空”、退出 1，属于预期拒绝路径，没有进入配置装配或真实模型调用。
- 最终子代理取消围栏修正后，定向重跑 SubagentRuntimeTest、SubagentRuntimeCompactionTest、MainCompactionAssemblyTest、AgentLoopTest、PermissionGateTest，共 62 条，0 failure／error／skipped；随后 package 退出 0。临时 CancelDemo 补验“不响应中断的子代理晚到回复”和“会话关闭后才批准的工具”，均不能产生副作用，子代理 FAILED 恰好一次；编译与运行退出 0。
- 临时 SessionDemo 以 JDK 21 编译，真实模型被假客户端替代：初版 8 场景、扩展版 12 场景全部退出 0；最后关闭顺序调整后只定向重跑关闭与晚到回复两分支，退出 0，没有重复整个矩阵。
- 场景包括：两个结果逐批恢复及 FIFO、脱离通知不唤醒、退出码 7 与 1 秒超时、严格布尔参数、模型失败仅取消所属必等命令、关联审批与跨线程人工来源、真实 Main 的定时父子来源与合法空记忆提取、64 项满队列不丢完成结果／cron 保留 pending、5 秒退避不挡人工及确认先于工具、单次 run 不乱读 stdin、确认写盘失败留 lease 并可在新调度器恢复、关闭后模型忽略中断也不能执行晚到工具、退出清理必等与脱离命令。
- 本轮未出现编译、回归或离线演示失败；一次重复目标的补丁格式被编辑工具拒绝且未修改文件，拆分补丁后继续，不将其作为运行期失败。
- 真机场景 tmp/s12-event-check 仅准备脚本、发布说明与 RUNBOOK；`sh -n` 退出 0，未启动脚本、未预写任何报告或 release 门闩，真实验收仍由用户执行。

本轮临时源码、class、验证状态及生成物在交付前清理，保留明确用于用户验收的 tmp/s12-event-check。学习入口为 [session-event-runtime-review.md](../../notes/s12/session-event-runtime-review.md)，从 SessionRuntime.execute／runTask／apply 开始。旧指南只添加版本提示，未改写用户答案；本单的工程 Pass 不替代用户理解或 s12 整章验收。
