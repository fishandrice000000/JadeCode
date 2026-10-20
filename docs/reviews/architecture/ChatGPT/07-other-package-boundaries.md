# 包设计的进一步考虑

日期：2026-10-17。承接 [执行器、会话、定时与进程边界](06-package-boundaries-2026-10-17.md)。本报告只讨论职责和依赖，不实施源码迁移，不把工程细节风险重新展开成主线。

## 1. Hooks 应从 safety 中分离

Hooks 包含用户请求、工具前后、Stop、子代理开始/结束等扩展点。权限检查只是其中一个使用者；日志、状态展示和要求模型继续也通过它完成。放在 safety 下，会让其它模块为使用通用执行扩展而依赖安全包，也使读者误以为所有 hook 都是安全机制。

建议设立小的 `hooks` 包，保存 Hooks 及必要事件/参数类型。PermissionGate、CommandPolicy、PathGuard 等仍留 safety；CLI 的日志展示实现归 CLI，授权实现归 safety，由装配点注册。不要把 hook 的同步调用与 session 的新任务队列合并成一个通用事件总线，两者有不同所有权和控制语义。

分包时还要明确：普通扩展点与必须执行的授权步骤能否共用一条可短路链。这里需要先确定工具执行契约，不能仅挪走 Hooks 就称为职责分离完成。收益是扩展机制与具体策略各自变化；成本低至中，风险集中在注册顺序和调用参数。

证据：`src/main/java/com/jadecode/safety/Hooks.java:19–41,49–67,92–103`；`src/main/java/com/jadecode/Main.java:208–217,233–354`。

## 2. 共享执行身份不能跟随运行器搬进 session

ExecutionContext 由会话创建，但工具、权限、子代理和后台命令都使用它。若只因创建者属于 session 就把类型也放进去，会重新造成这些底层能力依赖会话。当前类型还直接引用 PermissionGate 内嵌的 Approval，形成 agent/safety 双向依赖。

建议共享身份、来源、只读取消信号以及审批端口放在一个职责很窄的 `execution` 契约包。审批实现和策略仍分属 CLI/safety；会话拥有其生成与生命周期。process 只接收 owner 等需要的值，避免完整上下文越传越远。

这个包不应收纳 SessionRuntime、历史、进程管理或长期任务图。也不要为了凑包立即新建一组没有实际用途的接口。收益是让身份契约独立于宿主；成本中，风险是迁移时丢失可信来源或取消传播。

证据：`src/main/java/com/jadecode/agent/ExecutionContext.java:7,24,44–47`；`src/main/java/com/jadecode/tools/Tool.java:7,74–75`；`src/main/java/com/jadecode/safety/PermissionGate.java:108–111`；`src/main/java/com/jadecode/tools/BackgroundManager.java:96–103`。

## 3. 模型需要工具定义，不需要可执行 Tool 对象

CompleteRequest 目前持有 List<Tool>。这把工具名称、说明和 schema，与 execute 方法、可信执行上下文和具体工具实例一并带入 LLM 请求层。适配器实际上只读取描述，模型不应该获得 Java 执行对象。

建议增加很小的 ToolDefinition 值对象，只含 name、description、inputSchema，放在模型请求契约一侧，例如 `llm.ToolDefinition`。执行器从注册工具生成定义列表交给 LLM；tools 保留工具实例和执行方法。这样 llm 不再依赖 tools 的执行 API。定义内部的 JSON 仍需明确不可变/快照契约，但那是后续实现设计，不在本轮补代码。

messages 当前是模型与上下文共同使用的协议值，不依赖执行器，保留独立包合理。只有一个 SDK 适配器时，没有必要马上建多层 provider/spi/impl 包；等第二个后端或接入实现明显增长后再分。

收益是模型协议与执行能力分离，工具执行变化不传到 SDK；成本低至中，风险是 schema 保真与现有假客户端调用方适配。

证据：`src/main/java/com/jadecode/llm/CompleteRequest.java:5–16`；`src/main/java/com/jadecode/tools/Tool.java:17–75`；`src/main/java/com/jadecode/llm/AnthropicSdkLlmClient.java:131–157`。

## 4. 子代理是另一种执行宿主，共同执行步骤可以复用

父执行器和 SubagentRuntime 都包含模型调用、回复检查、工具批次、hook 和压缩逻辑。包分别存在不是问题，但需要回答：子代理究竟复用 Agent 执行能力，还是独立维护另一套实现？当前选择后者，协议规则已经出现差异。

在“agent 只放执行器”的方向下，建议保留 `subagent` 包负责委派：新建独立历史、限制轮次、确定可用工具、继承来源、输出委派终态；公共模型/工具步骤由 agent 的执行能力提供。子代理没有必要为了复用执行器而创建完整 SessionRuntime，也不应因此继承父队列、cron 或长期记忆维护。

依赖应为 subagent 使用 agent 的执行步骤。父执行器通过 Tool 接口调用 task；具体 SubagentTool 依赖 subagent，通过装配接入。若高层请求已经持有真实 Tool 实例，完整传递依赖仍可能绕回，因此这不是仅移动文件就能保证全图无环，需要与工具定义/执行边界一起检查。

收益是父子共用稳定协议，差异由显式宿主策略表达；成本中，风险是扩大子工具权限或破坏历史隔离。可以先收敛公共步骤，不必现在发明通用 Agent 基类。

证据：`src/main/java/com/jadecode/agent/AgentLoop.java:253–375`；`src/main/java/com/jadecode/subagent/SubagentRuntime.java:166–355`；`src/main/java/com/jadecode/Main.java:168–206`。

## 5. Main 的应用装配与 CLI 展示可以分开

Main 同时承担 picocli 入口、创建全部能力、编写提示词、注册权限和打印展示 hook。当前单一装配根有价值，但 CLI 反向调用 Main.buildLoop，使入口类成为其它模块依赖的应用工厂。

建议保留 Main 处理启动参数，将对象装配集中到一个小的 bootstrap 包，例如 JadeCodeFactory；创建会话、执行器和资源绑定也由这里组织，便于落实资源所有权。终端日志、颜色、子代理横幅仍由 CLI 展示组件实现；bootstrap 只负责将其接上，不包含 println 业务。

提示词是否另建 prompts 包应看是否开始独立维护策略和多种模板，当前两个构造函数不必强制一个顶级包。也不引入 DI 框架，不把每个 hook 拆成一个类。

收益是入口、装配和展示各有落点，后续其它宿主无需依赖 picocli Main；成本中，风险是生产装配与测试装配漂移，因此必须保留一个共用创建路径。

证据：`src/main/java/com/jadecode/Main.java:62–65,113–227,233–354,366–405`；`src/main/java/com/jadecode/cli/ReplCommand.java:31–34`；`src/main/java/com/jadecode/cli/RunCommand.java:42–45`。

## 6. context 的名称和存储边界值得预留，但不急着细拆

context 当前实际主要是历史压缩：门面、多种压缩策略、工具结果转存和压缩快照。长期的“上下文管理”还可能包含指令组装、记忆背景与请求预算，不应都因为名称相关而混进现有压缩器。

如果继续扩充，可把当前策略放入 `context.compaction`，让请求组装承担其自己的职责。也可以在当前阶段继续保留 context 平铺，因为这些类仍围绕单一能力协作，不需要只为目录整齐立即搬迁。

TranscriptStore 当前保存压缩快照，不是持续会话日志；ToolOutputStore 保存可回读的大结果，也不是 MemoryStore。不能只因它们都写文件就搬入一个泛化的 persistence 包。将来 session 真正拥有持续日志时，再确定该日志的存储边界；压缩所需的“先保存后替换”契约仍保留。

收益是防止 context 变成所有背景信息的杂物包；当前收益较低，成本取决于扩展范围。建议在实现新上下文职责时再拆，避免提前建立空层。

证据：`src/main/java/com/jadecode/context/ContextCompactor.java:18–45,89–109`；`src/main/java/com/jadecode/context/TranscriptStore.java:57–84`；`src/main/java/com/jadecode/context/ToolOutputStore.java:51–92`。

## 7. 目前倾向保留的边界

| 包/对象 | 判断 |
| --- | --- |
| todo 与 tasks | 分开。前者是会话内计划，后者是持久依赖图，共同名词不代表同一生命周期 |
| memory、skills | 分开。前者是跨任务知识的提取/选择，后者是用户提供的按需指令资源；不急着统一为 knowledge |
| tasks、cron 内部的规则与文件存储 | 暂保留领域内。可以以后局部区分服务与存储，但不先为每包造 service/repository/impl 三层 |
| tools 的具体适配类 | 保留入口集中。若数量继续增长，再按 files/process/tasks 等子包分组；类数不是首要标准 |
| util.Json、Ansi | 当前很小。Ansi 语义上靠近 CLI，但移动收益低；防止未来 util 持续收纳领域职责即可 |
| safety 的策略与路径检查 | 在 Hooks 分离后仍有共同授权边界；无需现在把每个策略变成顶级包 |

## 建议讨论顺序

先确定 Hooks 归属、共享执行契约、模型工具定义三处，因为它们会直接影响刚确定的 agent/session/process 边界；随后确定子代理如何复用执行器和应用装配如何创建它们；context 及存储细分留到其新职责明确时。

以上均为设计建议，未视为用户已确认全部方案。没有新增问题编号，以免将既有 ARCH-009/010/011/019 的职责设计重复计数；本轮只补充架构分析。


## 8. 交叉核对补充：Tool 契约与具体工具的依赖必须一起解除

> 本节保留早先迁包候选，已被第 9 节的用户审阅更新取代；不再将迁 Tool/ToolSchema 作为当前方案。

本轮引用用户提供的 Deepseek 意见，并重新核对当前源码。其指出的 `subagent → tools.Tool` 与 `tools.SubagentTool → subagent` 包环成立。前文只提示“完整依赖仍可能绕回”，没有确定 Tool 契约的具体归属，目标设计尚不完整。

建议采用“Tool 执行接口迁入 execution、SubagentTool 等具体适配仍留 tools”的方案。execution 的范围由身份/取消/审批补全为共享执行契约，允许值对象、接口、构造校验和纯函数，不拥有运行期可变状态、不做 IO、调度或装配。ToolDispatcher 若承担注册表和真实执行，不属于这个契约包。

但仅搬 Tool 仍不足以解除当前包环，还必须处理两个源码依赖：

1. `Tool.inputSchema()` 的默认实现调用 tools.ToolSchema。若保留此引用，execution 又依赖 tools，而具体工具同时依赖 execution。最小方案是将纯静态 ToolSchema 随契约迁入 execution，或在后续完整 schema 设计中解除默认实现的反向依赖；不能只移动接口声明。
2. 父执行器和 SubagentRuntime 都读取 `CompactTool.OUTPUT`，因此搬 Tool 后仍依赖 tools 中的具体实现。压缩请求信号需要归中立的执行结果约定，由 CompactTool 产生、执行器识别；第一步可保持当前信号语义，之后再评估类型化结果。不要用复制魔法字符串的方法隐藏依赖。

目标依赖补全为：agent/subagent 使用 execution 的 Tool 契约；tools 中的具体适配实现该契约，SubagentTool 使用 subagent；若子代理复用 AgentExecutor，则 subagent 可以依赖 agent，但 agent 不反向依赖 tools 的具体实现。实际工具实例由 bootstrap 注册注入。这样才有可能同时解除 tools/subagent 和 agent/tools/subagent 的传递环。

仍保留 ToolDefinition 的独立职责：LLM 请求仅接收完整名称/说明/schema 数据，不因为 Tool 迁入中立包就重新接收可执行接口。SDK 转换必须保真，JSON 节点需有快照/不可变约定。ToolNames 是否集中按共享需求决定，不把所有工具专用常量都塞入 execution。

另外，接受先前交叉核对中的必要条件：Approval 必须从 PermissionGate 的嵌套接口中抽出，与 ExecutionContext 一起归共享契约；PathResolver 建议归 filesystem；关闭责任先于 AutoCloseable 选择；cron 对外操作须覆盖 canDeliver、pending、received、failed、release 的现有语义（含失败/关闭路径），不强制一操作一接口。LlmSettings、testsupport 和空目录处理保留为低优先级建议，不影响这一轮主边界。

源码证据：`src/main/java/com/jadecode/tools/Tool.java:48–49`；`src/main/java/com/jadecode/tools/ToolSchema.java:17–63`；`src/main/java/com/jadecode/tools/SubagentTool.java:9,21–30`；`src/main/java/com/jadecode/subagent/SubagentRuntime.java:20–21,318`；`src/main/java/com/jadecode/agent/AgentLoop.java:25–26,336`。本次只更新设计，不执行迁移，也不声称所有包环已被消除。


## 9. 最新审阅：保留工具归属，以依赖方式作为另议选项

用户明确：Tool 和 ToolSchema 留 tools；SubagentTool 与 TaskTools 一样属于工具适配，也必须留 tools。采纳这一归属标准，撤回迁 Tool/ToolSchema 和搬 SubagentTool 的建议。execution 当前只安排身份、审批和取消契约。

需要区分工具属于哪个包，与工具依赖哪个具体实现。若继续要求解除 tools/subagent 双向依赖，可以让 SubagentTool 接收一个委派调用端口或回调，由 bootstrap 把 SubagentRuntime.run 接入。这个端口可由 SubagentTool 自己声明为窄接口，运行器通过方法引用连接，工具就无需 import 具体 SubagentRuntime；subagent 仍可使用 tools.Tool。此为独立的待审 API 选择，不表示所有回调或所有工具都需要额外接口。

用户最新裁决：暂不引入这层复杂度，保持 SubagentTool 直接依赖 SubagentRuntime。上述回调设计仅保留为历史候选，本轮不实施；tools/subagent 双向依赖不作为本轮必须消除的事项。最新范围见 08 的 A02、B07 和验收目标。没有源码修改。
