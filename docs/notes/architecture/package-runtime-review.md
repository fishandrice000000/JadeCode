# 包边界调整后的完整流程阅读

本轮工程自审 Pass，最终职责拆分的 139 个已有定向测试通过；没有新增单元测试或真实模型调用。
工程记录与全部 A01–A11 结果见 [任务单](../../jobs/architecture/package-reorganization.md)。这不代表用户理解或独立真机验收已完成。

## 需求与设计取舍

例如用户要求“后台构建，再根据报告继续”。模型决定做哪些工具操作；harness 必须记住原始目标、等待必需结果、避免把后来的定时请求插到当前工具批次里，并在退出时停掉所属命令。
如果所有职责都留在一个循环，读代码时很难分清谁拥有历史、谁决定任务结束、谁负责关闭资源。

课程主线仍是模型决策、harness 执行工具。本次 session/agent/bootstrap 的边界是 JadeCode 的工程组织选择，不是唯一正确设计；嵌套步骤结果和窄回调也属于本项目实现选择。
可信来源审批、定时交付确认和后台等待是项目当前机制，不能由模型在 prompt 中自称身份来替代。
工具调用和对应结果的 ID 配对属于消息协议约束；本轮继续保持整批闭合后才压缩。

现在的主流程是：Main 选择 CLI → CLI 提供展示 → JadeCodeFactory 装配 → SessionRuntime 领取任务并记录目标/召回记忆 → AgentExecutor 请求模型/执行工具 → session 采用结果、等待或继续 → 完成后维护记忆。

## 推荐阅读顺序

1. [Main](../../../src/main/java/com/jadecode/Main.java) 与 [RunCommand](../../../src/main/java/com/jadecode/cli/RunCommand.java)/[ReplCommand](../../../src/main/java/com/jadecode/cli/ReplCommand.java) 的 `call`：看到会话从哪里取得、输入如何排队，以及 try 资源的关闭顺序。
2. [JadeCodeFactory](../../../src/main/java/com/jadecode/bootstrap/JadeCodeFactory.java) 的三个 `create` 入口：沿真实工具表、共享压缩器、记忆/进程/定时服务到 SessionRuntime。再看 membershipHook/permissionHook 与 [CliHooks.register](../../../src/main/java/com/jadecode/cli/CliHooks.java)，分清策略和展示。
3. [SessionRuntime](../../../src/main/java/com/jadecode/session/SessionRuntime.java) 的 `submit → consume → execute → runTask`：先画出新请求 FIFO，再跟踪 Active.goal/system/receipts/received，观察等后台时为何不取下一项任务。
4. [AgentExecutor](../../../src/main/java/com/jadecode/agent/AgentExecutor.java) 的 `request → executeTools → compactAfterBatch → stop`，对照 session 的 apply 重载和 adoptHistory：模型回复与完整工具批次怎样进入父历史，压缩为何必须在正式请求前同步采用。
5. 回到 session 的 `activeRequest/taskSystem/finish`、`deliverResults/awaitResults/fail`：识别记忆调用时机、结果取出与确认、首回复前恢复，以及 [SessionInbox](../../../src/main/java/com/jadecode/session/SessionInbox.java) 的 revision/wake 等待机制。
6. 查看 [ExecutionContext](../../../src/main/java/com/jadecode/execution/ExecutionContext.java)/[Approval](../../../src/main/java/com/jadecode/execution/Approval.java)、[PermissionGate.check](../../../src/main/java/com/jadecode/safety/PermissionGate.java) 和 [SubagentTool](../../../src/main/java/com/jadecode/tools/SubagentTool.java)：子代理继承可信身份，仍直接使用 SubagentRuntime，没有新委派接口。
7. 查看 [CronProducer](../../../src/main/java/com/jadecode/cron/CronProducer.java) 的 `poll/received/failed/release`、[BackgroundManager](../../../src/main/java/com/jadecode/process/BackgroundManager.java) 的 `collect/acknowledge/restore/cancelRequired/close`，最后串起 `SessionRuntime.close` 与 CLI 的资源关闭。
8. 查看 [Tool.definition](../../../src/main/java/com/jadecode/tools/Tool.java)、[ToolDefinition](../../../src/main/java/com/jadecode/llm/ToolDefinition.java) 与 [SDK 转换](../../../src/main/java/com/jadecode/llm/AnthropicSdkLlmClient.java)，再按需读 [AgentExecutorTest](../../../src/test/java/com/jadecode/agent/AgentExecutorTest.java)、三份同名前缀的压缩测试和 [MainCompactionAssemblyTest](../../../src/test/java/com/jadecode/MainCompactionAssemblyTest.java)。先看场景与断言目的，再读假客户端细节。

## 核心理解问题

### PKG01：一次完整任务由谁推进

用户要求后台构建，构建未结束时用户再提交“读 README”。从 submit 到 runTask，谁决定第二项任务何时开始？AgentExecutor 为什么不应该拥有这个决定？

回答：


### PKG02：历史由谁采用

自动压缩产生了新列表，随后正式模型请求失败。沿 request → adoptHistory 说明哪一刻原列表内容被替换，为什么回调必须同步完成。普通任务和定时首回复前失败，对这份历史分别如何处理？

回答：


### PKG03：工具批次的失败边界

同一回复有三个工具调用，第二个工具执行后 hook 抛异常。哪一层补齐结果，哪一层写入历史，哪一层结束任务？为什么不能直接重跑整批工具？

回答：


### PKG04：记忆和目标的生命周期

原请求经过工具结果、后台通知、Stop 续跑后，谁仍保存原目标？为什么入口召回只做一次，finishTask 必须等必需后台结果和 Stop 都处理后才调用？区分“时机属于 session”和“算法属于 memory”。

回答：


### PKG05：后台事实与交付确认

一条后台命令已经退出，collect 已取到结果，但正式模型请求失败。为什么 collect 和 acknowledge 分开？定时首回复前失败怎样恢复记录？通知为什么不能再次使用原 tool_use_id 返回结果？

回答：


### PKG06：定时与子代理权限

定时请求要求 task 子代理执行一条需审批的命令。沿 CronProducer → publishCron → ExecutionContext → SubagentTool → PermissionGate，说明来源由谁确定、为何子代理不能借此申请人工批准。模型自填 source 为什么无效？

回答：


### PKG07：退出资源的所有权

用户退出时，仍有待答审批、一个必等后台命令和一个脱离任务的命令。按 CLI → session → cron/executor/process → future/线程 → terminal 的实际顺序说明各自责任；为什么不再让 CLI 另关一次执行器？哪些网络/外部副作用仍不能保证立即停止或回滚？

回答：


### PKG08：结构拆分与行为修复

ToolDefinition 为什么只带数据，schema 为什么在构造和读取时都复制？它保存根级 additionalProperties 后，为什么仍不能说 SDK 字段丢失已修复？tools/subagent 环为何本轮接受，而 agent → session 与 bootstrap → cli 则必须消除？

回答：


## 可后补拓展

### PKG09：同步兼容入口

旧测试通过 SessionRuntime.runExisting 或工厂返回会话的 run(messages) 运行。为什么要临时借用调用者列表，压缩时却仍只能替换内容？为什么不能和 start 后的消费者并发使用？

回答：


## 本轮未证明的路径

未进行新的真实模型、终端或 CLI 任务验收，也没有补齐所有网络晚到、定时和线程退出竞态的动态故障注入。
SDK 根级 schema 丢失及审查报告中的其它排除缺陷均保留。历史题单和用户已写答案不迁改；遇到旧 AgentLoop/Main.buildLoop 路径时，以本指南的新入口定位。
