# JadeCode 包架构调整：新会话实施提示词

请在 `/home/fishandrice/Backend/JadeCode` 实施下面已经确认的 **A01–A11 包架构调整**。先从实际仓库建立上下文，再完成范围内实现、验证、工程自审和文档同步。普通局部实现选择由你决定，不需要逐项重新确认；涉及超出本清单的行为变化或覆盖用户工作时，停止受影响部分并说明。

本任务是保持现有行为的包架构调整。允许为新边界迁移方法、调整构造注入、结果类型、可见性和调用方；不包含审查报告中其它工程缺陷修复。下面的最新裁决优先于此前报告里的迁包候选和待审表述。

## 一、启动核对与已有工作保护

1. 读取根 AGENTS.md、README、两份本地 SOP，核对完整 git status、相关 diff、staged、近期提交以及实际源码和测试。
2. 本提示词整理时 HEAD 为 `f15388d24defe210bb7a82d3b53b437bba7dfe7c`。这是交接快照，必须重新核对，不 reset、不强制恢复到该提交。
3. 当时已有修改：`docs/notes/s08/offline-acceptance-guide.md`、`docs/notes/s09/memory-recall-review.md`、`docs/notes/s10/task-system-review.md`；未跟踪目录有 `docs/notes/other-agents-analysis/`、`docs/reviews/`。保持用户工作，不能整体暂存、提交或改写。其它未知修改也先核对归属。
4. 审查资料位于 `docs/reviews/architecture/ChatGPT/`，相关的是 06、07、08；但其中保留了已撤回的候选，本提示词才是本轮最终实施范围。不需要阅读其它 Agent 的源码或研究资料，也不执行 B 系列建议。
5. 先在 `docs/jobs/architecture/package-reorganization.md` 建立本轮任务单；若同路径已有内容，先读取并续写合适状态，不覆盖无关记录。记录目标、允许范围、依赖/关闭契约、验证和实际结果。

## 二、不可改变的最新裁决

- **Tool、ToolSchema 留在 tools。**
- **SubagentTool、TaskTools、CronTools 及其它工具适配器都留在 tools。**
- **SubagentTool 继续直接依赖 SubagentRuntime，不引入 Delegation、委派接口、方法引用回调或额外解耦层。**
- 保留当前 tools/subagent 双向依赖，不将“全仓库所有包无环”作为本轮目标。
- execution 只承载当前需要的身份、审批和取消等共享契约，不迁入 Tool/ToolSchema，不顺带集中 ToolNames 或抽取压缩信号。
- 父子执行算法暂不合并，不新增 ToolDispatcher、通用 Agent 基类或统一异步事件总线。

## 三、A01–A11 最终修改清单

### A01：调整包归属和唯一必要的类重命名

| 现有类型 | 目标 |
| --- | --- |
| agent.AgentLoop | agent.AgentExecutor |
| agent.SessionRuntime | session.SessionRuntime |
| agent.SessionInbox | session.SessionInbox |
| agent.SessionEvent | session.SessionEvent |
| agent.CronProducer | cron.CronProducer |
| tools.BackgroundManager | process.BackgroundManager |
| tools.ShellCommand | process.ShellCommand |
| safety.Hooks | hooks.Hooks |
| safety.PathResolver | filesystem.PathResolver |
| agent.ExecutionContext | execution.ExecutionContext |
| safety.PermissionGate.Approval | 独立的 execution.Approval |

agent 只留执行器及必要的嵌套步骤结果，不放会话、定时生产者或共享上下文。safety 保留 CommandPolicy、PathGuard、PermissionGate。其它既有能力包保持原组织；不额外重命名 CronProducer、BackgroundManager、ShellCommand。

### A02：抽出最少的共享执行契约

将 ExecutionContext 迁入 execution；将 Approval 从 PermissionGate 的嵌套接口提取为独立接口，一并放入 execution。ConsoleInput 改为实现 execution.Approval，PermissionGate 和其它消费者同步使用该类型。

保持任务 ID、可信来源、审批与取消回调的现有含义。execution 允许值类型、接口、构造校验和纯规则，不拥有运行期可变状态，不负责 IO、调度或装配；不得反向依赖 agent/session/tools 具体实现或 safety。

Tool、ToolSchema、CompactTool 的控制信号仍按现状留在 tools。不要为消除已接受的包环增加新接口。

### A03：解除执行器对 session 的反向依赖

AgentExecutor 不引用 SessionRuntime 或 SessionEvent。模型回复、工具批次、继续执行等步骤结果由执行器自己的小型结果类型表达，优先使用嵌套类型；session 负责将其采用为会话历史。

压缩历史需要在正式请求前采用时，使用只承担消息列表采用的窄回调或等价的明确结果契约，不把完整 SessionEvent 协议传进执行器。保留现有历史采用时机、工具交换完整性、失败后历史保留和定时首回复前恢复行为，不重写压缩算法。

旧 run(messages)/runExisting 的会话兼容调用归 session，迁移仓库内已有调用方和测试。不要在 AgentExecutor 中留下反向调用 SessionRuntime 的桥接，也不默认新建旧 AgentLoop 空壳；若实际发现仓库外兼容要求或不能直接迁移的调用方，先报告具体影响。

### A04：会话拥有任务生命周期

新任务 FIFO、父历史、当前目标、后台等待、继续/完成/失败判断归 SessionRuntime。入口召回和完成后记忆维护的调用时机移到 session，实际算法和服务留 memory。

cron/process 直接由装配注入 session，不再经执行器 getter 转交。执行器保留请求模型、执行工具批次和压缩等执行步骤。不得改变用户目标提取、记忆、Stop、后台通知和工具交换的现行算法。

### A05：明确 cron 跨包调用面

CronProducer 迁回 cron，保留已有 CronJob 投递回调。session 将其转为可信定时请求；cron 负责轮询、领取恢复、交付状态和退避。

对外入口完整覆盖现有 canDeliver、pending、received、failed、release 语义及 start/close。正常、暂缓、失败、取消和关闭路径均需完成接线；不强制新增接口层。保持首个有效正式回复确认、pending/lease、重试次数和时间不变。

### A06：明确 process 的调用入口与封装

BackgroundManager、ShellCommand 迁到 process，保留现有进程启动、输出、超时、终止和结果管理实现。BackgroundManager 的调用只传必要的任务归属、命令及等待属性，避免依赖整个 agent/session 实现。

公开 BashTool 所需的同步执行/后台启动入口，以及 session 所需的结果领取、确认、恢复、取消与关闭入口。ShellCommand 可以开放跨包真正需要的类/构造/执行/结果 API，内部 Running、进程集合和线程继续封装；不为迁包额外搭建一套工厂/执行接口。

若迁移 Completion 的模型通知文字渲染，将其放到 session 消费侧，process 仅交付命令事实。此职责移动保持现有文字、转义、截断、退出码和通知时机，不借机修输出行为。

### A07：落实资源所有权与关闭链

本会话的 cron 和进程服务由 session 明确拥有关闭责任；执行器不代关它不拥有的服务。执行器可保留自己的关闭状态/AutoCloseable，只要职责明确，不要求机械删除该接口。

CLI 拥有终端与输入：保持审批退出处理，并在会话清理后最后恢复终端。bootstrap 创建并交付这些资源的所有权，不建立第二条重复关闭链。

根据现有代码明确并保留：停止接收请求、禁止晚到执行、停 cron/命令、完成/拒绝 future、等待处理线程和关闭终端的责任及顺序。保留父子取消信号、同步/后台进程清理和现有中断行为，不新增共享 LLM 客户端强取消或关闭能力。

### A08：分离工具定义数据与可执行接口

新增 `llm.ToolDefinition`，仅表示名称、说明和完整 inputSchema JSON 节点。CompleteRequest 改为接收定义列表；执行器和子代理调用方从现有 tools.Tool 生成定义，真实工具实例仍通过 tools.Tool 执行。

ToolDefinition 不持有执行器、会话或具体工具实例，不使 execution 反向依赖 llm。明确节点的只读/快照契约，保持可观察请求行为；无需引入 toolspec 或其它新层。

**本项只做结构拆分。SDK 转换当前丢失根级 additionalProperties 等字段的问题不在本轮修复；保持现有转换行为并在任务单中注明已知限制。不得因保存完整 schema 就报告该缺陷已修复。**

### A09：独立 hooks 包，保留扩展机制

迁移 Hooks 并更新全部消费者。权限策略仍在 safety，普通终端展示实现归 cli，由装配连接。保持字符串事件和参数表、短路语义、权限/日志顺序以及父子触发规则。

不全面类型化 hooks，不将授权从现有 hook 链移到新执行服务，不改变 hook 意外异常语义，也不新增 ToolDispatcher。

### A10：提取 bootstrap 装配

建议使用 `bootstrap.JadeCodeFactory` 作为共用装配入口。Main 保留 picocli 顶层入口；run/repl 从工厂获得执行器/会话及必要的资源绑定，不再调用 Main.buildLoop。

CLI 提供终端展示和审批适配，bootstrap 通过现有 hook/执行契约接收，不 import cli 的具体实现，避免 cli → bootstrap → cli。权限、工具注册和提示词装配沿现有依赖连接；生产与离线验证复用一个创建路径。

提示词构造可随装配辅助放入 bootstrap，不新增 prompts 顶级包。不要引入 DI 框架，也不为每个内置 hook 单独建一个类。

### A11：同步调用方、注释、已有测试和当前文档

更新所有实际受影响的 package/import、构造与方法调用、访问级别、测试包声明、反射定位和当前文档引用。迁移后的源码和修改方法保留准确、充分的中文 JavaDoc 与行间注释，重点讲清新职责、调用链、历史采用及资源所有权。

可以按移动/重命名调整已有测试文件和断言定位，保留原场景与行为断言目的；不新增测试、不删除/禁用既有测试、不修改期望来隐藏行为变化。

README 和本轮任务单同步实际包结构。历史 jobs/notes 保留历史，必要时补新版阅读入口；用户已有答案不覆盖、不批注。审查目录中的无关报告不因源码迁移批量改写或自动提交。

## 四、本轮不做的事项

所有 B 系列均排除：父子公共执行算法、ToolDispatcher、LlmSettings、testsupport 迁移、context.compaction、存储端口/统一持久化包、tools 子包细分、ToolNames 集中、额外类重命名、空目录清理、Ansi 迁包，以及已明确拒绝的委派回调。

也不做任何工程缺陷修复，包括但不限于：路径/软链接算法、命令和路径权限规则、SDK schema 转换、模型停止原因、空摘要、消息裁剪与目标保护、总执行预算、文件/记忆原子发布、辅助请求取消增强、cron 表达式/重试及同步输出截断。

不升级依赖、不改配置语义、provider/模型/认证，不读秘密，不动机器级配置，不调用真实模型或运行实际 Agent 来验收，不推进后续章节。

如果范围内迁移必须触及排除项才能继续，先说明具体冲突，完成可安全推进的部分，不擅自改变现行行为。

## 五、实施、验证和交付

本提示词用于新会话的实际实施；旧架构审查任务的“不改源码、不构建测试”限制只属于此前静态审查，不能据此停在规划。当前会话只是在准备本提示词，并未启动迁移。

1. 核对实际状态，先给出精确迁移/依赖和资源关闭方案并写任务单，随后自主实施 A01–A11，不再逐项询问是否允许搬文件或接线。
2. 按真实依赖组织可理解的修改批次，避免一次引入无关重构。实际源码、方法调用、注释与相关测试要另做工程自审。
3. 在新会话中运行范围内构建和已有相关定向测试：先窄后宽，不新增单元测试，不默认重复全量回归。可以用 `mvn -q -DskipTests package` 检查编译/打包，再按实际影响选择已有测试；统计真实命令、退出码和报告，不把静态分析当测试通过。
4. 检查实际包依赖与公开面：agent 不依赖 session；execution 不依赖具体运行器/工具/安全策略；cron/process 不依赖具体会话；llm 请求不带可执行 Tool；bootstrap 不反向依赖 CLI。明确承认 tools/subagent 环按用户裁决保留，不追求零包环。
5. 检查工具名称/参数、CLI 交互、父子权限、持久化路径和格式、压缩/记忆/后台/定时及失败语义无范围外变化。暂未动态验证的路径如实列出，真机验收仍由用户完成。
6. 遵守 AGENTS 当前的本地提交授权与精确暂存规则：自审和相应验证通过后，仅提交本轮归属明确且独立可构建的修改；不吸收原有笔记、未跟踪研究/审查材料或既有 staged。不要 push 或改写历史。遇到归属/范围冲突停止受影响批次。
7. 最终逐项报告 A01–A11 完成情况、实际类归属/API/关闭链、验证证据、保留的已知限制、Git 状态与本地提交 hash。工程完成、用户理解和真机验收分别记录。

请先从实际仓库核对开始。只实施本提示词的 A01–A11，所有排除项保持在本轮之外。
