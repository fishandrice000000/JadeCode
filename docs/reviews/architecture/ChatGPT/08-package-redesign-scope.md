# 包架构重设计：逐项审阅清单

> 最终实施范围已汇总为 [新会话实施提示词](09-package-redesign-implementation-prompt.md)。本文件保留审阅过程，旧的“尚待确认”表述按当时状态理解；最新 A01–A11 与全部排除项以最终提示词为准。

日期：2026-10-17。用户限定下一步只做包架构重设计，不进行方法工程细节修正。本清单供逐项审阅，未授权即实施源码迁移。06/07 中超出下列范围的优化不自动纳入。

## 范围与判断标准

本轮方案涵盖类归属、命名、包间 API、共享契约、装配和资源所有权。若后续实施，为解除包依赖而移动方法、调整参数/结果类型及访问级别属于必要接线；算法、权限规则、重试策略、模型停止判定、文件发布方式和产品交互保持现状。不能用“移动包”掩盖额外行为修复。

以下 A 项为建议的同一架构方案组成部分，供用户逐项确定；B 项为单独选择，默认暂缓。编号用于讨论，不是执行批次或 Git 提交批准。

## A：建议纳入的包架构事项

### A01：确定目标包及类归属

- `agent.AgentLoop` 改为 `agent.AgentExecutor`；agent 只保留执行器及其必要嵌套步骤结果。
- `SessionRuntime/SessionInbox/SessionEvent` 迁入 `session`。
- `CronProducer` 迁入 `cron`，其余现有 cron 类保留。
- `BackgroundManager/ShellCommand` 迁入 `process`。
- `Hooks` 迁入 `hooks`。
- `PathResolver` 迁入 `filesystem`；PathGuard/CommandPolicy/PermissionGate 保留 safety。
- 新增最少的 `execution` 契约和 `bootstrap` 装配包。

其余包先保留。Tool、ToolSchema、SubagentTool、TaskTools 及其它工具适配均留在 tools，按工具职责保持一致归属。CronProducer、BackgroundManager、ShellCommand 暂不额外改名。

### A02：确定 execution 的内容和禁止依赖

ExecutionContext 和从 PermissionGate 抽出的 Approval 归 execution。按用户最新审阅，Tool、ToolSchema 保留 tools；不再以消除包环为由要求迁移它们。压缩控制信号是否抽出另行审阅，不默认增加本轮范围。

execution 允许接口、值对象、校验和纯规则，不拥有可变运行状态，不执行 IO、调度、装配。不得反向依赖 agent/session/tools 的具体实现或 safety。ConsoleInput 实现 execution.Approval，PermissionGate 使用该接口。这里必须把 Approval 从 PermissionGate 中抽出，避免 execution/safety 继续双向依赖。

### A03：解除执行器对 session 的依赖

执行器返回自己的步骤结果，session 负责采用历史；执行器不直接返回 SessionEvent。压缩后的历史采用使用窄回调或明确结果契约，不依赖完整会话事件类型。保留现行采用时机和失败后历史保留方式。

旧 run(messages)/runExisting 入口的兼容责任归 session，已有调用方（包括测试）迁到该入口。建议不在 AgentExecutor 中留下反向调用 SessionRuntime 的桥接；是否保留旧类名兼容适配需要用户另选，因为会影响“agent 只留执行器”和无反向依赖的目标。

### A04：明确 session 拥有的生命周期职责

新任务排队、父历史、当前目标、后台等待及完成判断归 SessionRuntime。任务入口召回和完成后记忆维护的调用时机由 session 决定，算法仍在 memory。cron/process 对象直接注入 session，不再经执行器 getter 转交。只转移责任和依赖，不改变任何记忆选择、提取或整理算法。

### A05：确定 cron 的最小跨包 API

保留投递 CronJob 的现有回调方式；session 将其转成可信定时请求。跨包入口需完整覆盖 canDeliver、pending、received、failed、release，以及 start/close。可以保留具体 CronProducer 的小型公开 API，不强制新建接口。

cron 管轮询、交付状态和退避；session 管请求执行及何时报告收到/未收到。重试次数、退避时间、pending/lease 和首回复确认语义不变，失败和关闭路径也在接口审阅范围内。

### A06：确定 process 的公开入口和数据边界

进程服务接收归属 ID、命令和等待属性，减少对完整 ExecutionContext 的依赖。为 BashTool 确定必要的同步/后台公开调用入口，为 session 确定领取/确认/取消入口；运行进程、线程和内部集合继续封装。

ShellCommand 原为包私有，不能机械迁移后把所有内部成员公开。建议保留类名并只开放必要执行/结果/close 能力，或以已有管理器的小型门面承接；具体选择需在 API 草案中确定。

Completion 的通知文字渲染可移到 session 消费侧，process 只返回命令事实；输出格式与截断策略保持不变。

### A07：写清关闭责任，再决定 AutoCloseable

建议 session 拥有本会话 cron 与进程服务的关闭责任；执行器仅负责自己的可用状态，不代关它不拥有的服务。CLI 拥有终端与输入，停止审批、关闭会话之后最后恢复终端。bootstrap 建立并交付这种所有权，不自行建立第二条关闭链。

需审阅：停止收任务/禁用晚到执行、停 cron/命令、通知 future、等待线程、关闭终端的责任与顺序，保持当前行为。执行器是否仍实现 AutoCloseable 由其剩余生命周期决定。共享 LLM 客户端新增取消/关闭能力不在本轮补做。

### A08：分离工具定义和执行接口

建议 `llm.ToolDefinition` 保存名称、说明和完整 inputSchema 节点；CompleteRequest 接收定义列表，工具实例通过 tools.Tool 供执行器调用。定义由执行器/装配侧转换，不让 execution 反向引用 llm。

这是请求契约的结构调整；本轮不顺带修 SDK 丢失根级 additionalProperties 的现有行为，也不重写 schema 校验。schema 保真修复单独留档，不能因引入完整节点就宣称已修复。节点是否复制作为新契约边界明确，避免额外改变共享实例语义。

### A09：Hooks 独立，但保留当前扩展机制

迁移 Hooks 并更新消费者；普通日志展示实现在 CLI，权限策略在 safety，通过装配注册。保持字符串事件/参数、短路规则和 hook 顺序，不在本轮全面类型化或新增 ToolDispatcher、事件总线。必须授权步骤从普通 hook 链独立，是另一个行为设计，不默认夹带。

### A10：提取 bootstrap，避免生成新的 cli/bootstrap 包环

建议 `bootstrap.JadeCodeFactory` 负责创建和连接组件；Main 保留 picocli 入口，CLI 从工厂取得会话。CLI 提供终端展示/审批适配，工厂通过 hooks/执行契约接收，不能反过来 import CLI 的具体实现，否则会产生 cli → bootstrap → cli。

现有提示词构造可与装配辅助放在 bootstrap，暂不新增 prompts 顶级包。所有生产和离线装配仍复用同一创建路径。

### A11：同步调用方、已有测试和当前文档入口

更新 import、构造调用、公开边界、测试包声明/反射定位和当前阅读路径。已有测试的场景与断言目的保留；不新增测试、改变预期来掩盖行为变化或覆盖用户答案。历史 jobs/notes 保留历史，必要时添加新版入口。

本阶段只列未来迁移的同步事项，不执行构建/测试。未来实施的验证范围和本地提交批次应在源码修改获准后按实际影响另行明确，不能把本清单视为运行或 Git 操作授权。

## B：单独选择，建议暂缓

| 编号 | 候选 | 本轮建议 |
| --- | --- | --- |
| B01 | 子代理复用父执行步骤、抽公共工具执行服务 | 暂缓。保留两套算法，避免扩大方法重构；工具依赖按 B07 的最新裁决保持现状 |
| B02 | LlmSettings 替换宽 AppConfig 依赖 | 可单独选，当前不紧急 |
| B03 | 两份共享假客户端迁入 testsupport | 可随包迁移选择；不把跨测试包引用本身认作缺陷 |
| B04 | context.compaction、存储端口、统一持久化包 | 暂缓，继续保留当前能力内组织 |
| B05 | tools 继续按文件/任务等细分子包 | 暂缓，先把契约和进程职责移出 |
| B06 | ToolNames 集中、更多类重命名、空目录清理、Ansi 移包 | 暂缓，不为目录美观扩范围 |
| B07 | SubagentTool 依赖委派回调，由 bootstrap 注入 SubagentRuntime.run | 用户决定本轮不做。继续直接依赖 SubagentRuntime，不增加委派接口或回调层 |

## C：明确排除的修复

不修路径解析算法及安全规则，不改 SDK schema 转换和停止原因，不改消息裁剪/摘要，不补执行预算，不改文件/记忆发布，不增强辅助请求取消，不改 cron 表达式/重试，不改同步输出截断。也不升级依赖、引入框架、增加单元测试或推动后续章节。

如果未来迁移发现必须改变这些行为才能继续，应单独说明冲突和选择，不能默认扩大任务。

## 架构验收目标

- agent 没有会话/生产者/共享契约类，执行器不依赖 session。
- execution 不反向依赖具体运行器、策略或工具实现。
- Tool、ToolSchema 和所有工具适配保留 tools。当前 tools/subagent 双向依赖按用户裁决保留，不以消除该环为本轮验收要求。
- cron/process 不依赖具体会话运行器；LLM 请求不持有可执行工具对象。
- bootstrap 不依赖 CLI 具体类；资源有唯一、明确的关闭责任。
- 工具名称/参数、交互、持久化路径及格式、状态流转和失败语义保持现状。

本清单需要用户逐项审阅，目前仅为设计范围建议。没有源码变更、构建/测试执行或 Git 提交。


### 最新审阅记录：保留工具归属

用户明确 Tool/ToolSchema 不迁出 tools，SubagentTool 与 TaskTools 一样作为工具适配必须留 tools。已撤回迁移 Tool/ToolSchema 及把 SubagentTool 放进 subagent 的建议。用户随后决定 B07 本轮不做：SubagentTool 继续直接依赖 SubagentRuntime，保留当前调用方式，不增加 Delegation 等回调层。
