# A01–A11 包架构调整

> 后续裁决（2026-10-17）：用户指定 PathResolver 改放 `util`。下方首次迁移记录中的 `filesystem` 保留当时事实；当前归属以末尾补充记录和 README 为准。

## 目标与边界

- 依据用户指定的 `docs/reviews/architecture/ChatGPT/09-package-redesign-implementation-prompt.md` 实施；启动 HEAD：`f15388d24defe210bb7a82d3b53b437bba7dfe7c`。
- 只改变包归属、调用边界和资源所有权，保留工具名/参数、提示词、权限、压缩、记忆、后台、定时与失败语义。
- 不执行 B 系列、不修审查中的工程缺陷、不新增测试、不调用真实模型、不读取秘密。
- 原有三份笔记改动、`docs/notes/other-agents-analysis/` 与 `docs/reviews/` 全部保护，不改写/暂存/提交；启动无 staged。

## 数据流与依赖方案

CLI 提供展示 hook → bootstrap.JadeCodeFactory 装配 → session.SessionRuntime 排队/采用历史/调用记忆 → agent.AgentExecutor 请求模型与执行工具 → tools 适配器调用能力服务。

- `execution`：ExecutionContext 与独立 Approval，仅共享值/回调契约，不引用 safety、agent、session、tools。
- `agent`：AgentExecutor 及嵌套 ModelReply/ToolBatch/Continue；不引用 session。压缩采用回调只传 `List<Message>`，回调内先复制列表再替换原 history，正式请求之前即完成采用。
- `session`：SessionRuntime/SessionInbox/SessionEvent；任务入口提取目标和召回记忆，真正结束后维护记忆。同步历史兼容入口也归 session。
- `cron`：CronProducer 与现有 CronScheduler/CronJob；保留 Predicate<CronJob> 投递，开放 start/canDeliver/pending/received/failed/release/close，不引用具体 session。
- `process`：BackgroundManager/ShellCommand；只接收命令、任务归属和等待属性，内部 Running/线程/进程集合保持封装。完成事实由 session 渲染为原通知文字。
- `hooks`：原字符串事件、参数表和短路机制；普通展示实现归 cli，权限检查与装配归 bootstrap。
- `filesystem`：PathResolver；安全分类仍由 safety.PathGuard 执行，路径算法不变。
- `llm`：ToolDefinition 保存名称/说明/完整 schema 快照，CompleteRequest 不再持有可执行 Tool；Tool/ToolSchema/SubagentTool/TaskTools/CronTools 仍在 tools，SubagentTool 继续直接引用 SubagentRuntime，接受 tools/subagent 包环。
- `bootstrap`：统一创建生产/离线会话，接收既有 Hooks 协议的展示适配，不依赖 cli；Main 只保留 picocli 顶层入口。

## 关闭契约

CLI 拥有 TerminalDisplay 与 ConsoleInput。退出时先拒绝待答审批，再由 session 标记 CLOSED，停止 cron、禁止执行器晚到操作、关闭同步/后台命令、拒绝队列/当前 future、中断并限时等待消费者；最后由 CLI 恢复终端。bootstrap 创建后将执行器、cron/process 的关闭责任交给 session，不建立并行的第二条关闭链。共享 LLM 客户端不新增取消/关闭能力。同步 run 仅在尚未 start 的会话中临时借用调用者历史，返回时恢复会话原列表引用，不关闭服务；runExisting 为无服务旧调用方提供同一会话执行入口，借用执行器而不代关。

## 实施与本地提交计划

1. **迁移共享执行契约与基础能力包**：execution/hooks/filesystem/process/cron 类、受影响 import/可见性和已有测试包；保持当前循环可构建。标题：`迁移共享执行契约与基础能力包`。
2. **分离模型工具定义与执行接口**：llm.ToolDefinition、CompleteRequest、SDK 适配、父子请求及已有断言定位。标题：`分离模型工具定义与执行接口`。
3. **划分执行器会话与装配职责**：AgentExecutor、session 类型、bootstrap 工厂、CLI 展示与资源绑定、所有调用方和已有测试、README/新版阅读入口。标题：`划分执行器会话与装配职责`。

每批精确文件集合以实际 diff 留档；只暂存本轮明确修改路径，检查完整 staged diff、空白错误及构建，提交后核对 hash 与剩余状态。不 push、不改写历史。

## 验证计划与已知限制

先构建/最窄已有测试，再按实际影响扩大到父子执行/压缩/装配等已有测试；记录命令、退出码和 Surefire 实际计数。检查依赖与公开面、工具/提示词/持久化契约及关闭路径。最后检查受保护文件哈希。

**保留已知限制：SDK 的 schema 转换仍只映射 type/properties/required，会丢弃根级 additionalProperties 等字段；保存完整 ToolDefinition 不修复此缺陷。** 其它报告中列举的行为缺陷也不在本轮修复。动态真实模型/CLI 真机行为未在本轮验证，用户理解与最终真机验收单独记录。

## 阶段状态

- 启动核对与方案：完成。
- A01–A11 实现：完成。
- 工程自审与验证：Pass（本轮限定范围）；自动化与轻量检查通过。
- 本地提交：前两批已完成；最终职责拆分代码与本单一起提交，实际 hash 见 Git 日志和最终报告。
- 用户理解、最终真机验收：待用户完成。

## 实际验证与结果

后续按批次续写。

### 批次 1：共享契约与基础迁包

- 已完成 ExecutionContext/Approval、Hooks、PathResolver、BackgroundManager/ShellCommand、CronProducer 迁移及消费者接线；cron 跨包入口公开，process 启动只接收 owner ID，Running/进程集合仍封装。
- 工程自审：Pass。仅迁包、独立审批接口、必要可见性与参数投影；权限规则、线程、轮询、重试、进程执行体未改。
- `mvn -q -DskipTests package`：退出 0。初次定向测试退出 0，但发现 target 保留旧包类；以清理后的结果为准。
- `mvn -q clean -Dtest=PermissionGateTest,PathGuardTest,PathResolverTest,HooksTest,BashToolTest,FileToolsTest,SubagentToolTest test`：退出 0，42 tests / 0 failures / 0 errors / 0 skipped。
- 后续批次仍待实现：工具定义拆分、AgentExecutor/session/bootstrap 边界、通知渲染和关闭责任调整。

### 批次 2：模型工具定义

- 批次 1 已提交：`d24c6ce`。
- 新增 llm.ToolDefinition；构造/读取均深复制完整 ObjectNode。Tool.definition 每次正式请求生成数据快照，实际工具仍由 Tool.execute 执行。父子请求、CompleteRequest、SDK 和原有断言定位已同步。
- 工程自审：Pass。llm 不再引用 tools，未更改 SDK schema 映射算法；已知根级字段丢失仍保留。
- `mvn -q -Dtest=AgentLoopTest,SubagentRuntimeTest,ToolSchemaTest,MainCompactionAssemblyTest test`：退出 0，40 tests / 0 failures / 0 errors / 0 skipped；无新增/删除/禁用测试。

### 批次 3：执行器、会话与装配

- 批次 2 已提交：`c06453b`。
- 工程自审结论：**Pass**。实现后另行核对源码、调用方、迁移 diff、注释、已有测试和字节码依赖；同一 Agent 实现并自审，不是独立审查。无已知阻塞项，不保证绝对无误。
- 同步兼容 `SessionRuntime.run(messages)` 临时借用调用者列表，在 finally 恢复本会话列表引用；压缩仍只替换借用列表的内容，失败前的历史采用立即可见。不能与 start 或其它同步 run 并发使用。`runExisting(engine, messages)` 供原无服务调用方使用。
- 请求、工具批次、Stop、后台等待与首次定时确认算法未重写。记忆的 prepareSystem/finishTask 调用迁到 session；memory 服务算法未改。

| 项 | 实际实现与检查结果 |
| --- | --- |
| A01 | AgentLoop → agent.AgentExecutor；SessionRuntime/SessionInbox/SessionEvent → session；CronProducer → cron；BackgroundManager/ShellCommand → process；Hooks → hooks；PathResolver → filesystem；ExecutionContext/Approval → execution |
| A02 | ConsoleInput 实现独立 Approval，PermissionGate 与工具/子代理使用 execution 契约；execution 无运行器/工具/安全策略/LLM 依赖，无 IO 或调度 |
| A03 | AgentExecutor 只交出嵌套 ModelReply/ToolBatch/Continue；request/compactAfterBatch 用 Consumer<List<Message>> 要求同步采用；无 SessionRuntime/SessionEvent 引用或旧空壳 |
| A04 | session 拥有 FIFO、父历史、目标、后台等待及完成/失败状态；在原时机调用记忆；cron/process 由工厂直接注入，不经执行器 getter |
| A05 | CronProducer 的构造、start/canDeliver/pending/received/failed/release/close 为跨包调用面；投递仍用 Predicate<CronJob>；首有效正式回复确认、lease 恢复和 5 秒/3 次退避未改 |
| A06 | BackgroundManager.start(command, owner, required) 与 runForeground 供 BashTool 调用；collect/acknowledge/restore/hasRequired/cancelRequired/close 供 session 消费；ShellCommand 的构造/run/Result/text 必要入口公开，Running/进程集合仍 private；通知渲染迁入 session |
| A07 | CLI 资源声明只保留 display → runtime → input，逆序关闭 input → runtime → display。runtime 标记 CLOSED → cron.close → engine.close → background.close → inbox/future 拒绝 → 消费者 interrupt/join(2000)。工厂不建立第二条关闭链；共享 LLM 不新增关闭/强取消 |
| A08 | llm.ToolDefinition 名称/说明/完整 ObjectNode 数据快照，构造/读取深复制；CompleteRequest 只含定义，真实工具继续执行；SDK 根级字段丢失未修复 |
| A09 | Hooks 独立；字符串事件、参数表、短路和异常传播保持；CliHooks 集中普通展示，工厂先注册成员/权限再安装展示，无 ToolDispatcher |
| A10 | JadeCodeFactory.create 统一生产/离线装配，full overload 接收输出及 BiConsumer<Hooks,Path> 展示安装；bootstrap 无 cli 依赖；Main 仅保留 picocli 入口，提示词辅助函数归 bootstrap |
| A11 | package/import、构造/调用、测试类与反射定位、中文注释、README 和新版架构阅读入口均同步；历史 jobs/notes 与审查材料不批量改写 |

#### 实际验证

所有以下命令退出码均为 0；Maven 测试未新增、删除、禁用或改变行为期望。

| 验证 | 实际结果 |
| --- | --- |
| `mvn -q -DskipTests package`（初步接线） | 编译/打包成功，不宣称执行测试 |
| `mvn -q clean -DskipTests package`（最终源码） | 清理旧类后编译/打包成功，无迁包前遗留类参与 |
| `mvn -q -Dtest=AgentExecutorTest,MainTest,MainCompactionAssemblyTest test` | 33 tests / 0 failures / 0 errors / 0 skipped |
| `mvn -q -Dtest=AgentExecutorAutomaticPreparationTest,AgentExecutorManualCompactionTest,AgentExecutorReactiveCompactionTest,SubagentRuntimeTest,SubagentRuntimeCompactionTest,ContextCompactorPreparationTest,ContextCompactorBatchCompactionTest,ReactiveCompactionTest,PermissionGateTest,BashToolTest,HooksTest,ToolExchangeProtocolTest test` | 106 tests / 0 failures / 0 errors / 0 skipped；与上一组不重叠，最终职责拆分共验证 139 个已有测试 |
| JDK 21 jdeps 对 target/classes 的实际包依赖检查 | agent 无 session 依赖；execution 无 agent/session/tools/safety/llm 依赖；cron/process 无具体会话/执行器依赖；llm 无 tools 依赖；bootstrap 无 cli 依赖；tools/subagent 双向依赖保留 |
| 44 个迁移关键方法的 Java token 比较 | 排除包/类型归属与明确调用接线后方法体一致；覆盖 session 任务推进/失败、工具批次/Stop、工厂权限/提示词、CLI 展示、cron 与 process 生命周期 |
| 通知协议核对 | 与旧实现的全部渲染/转义字符串字面量逐字一致；临时纯函数检查验证 500 字符截断、XML 转义、null 退出码和原通知标签 |
| 临时数据/公开面检查（不运行会话或命令） | ToolDefinition 构造/读取双向深复制，完整根级 schema 保留；ShellCommand 执行入口公开，Running 与进程集合封装 |
| 既有测试方法清单核对 | 原有 331 个 @Test 方法逐名保留；本轮只按风险运行指定子集，未全量回归 |

#### 保留边界与学习状态

- 不修 SDK schema、路径/命令策略、停止原因、压缩/摘要/预算、持久化发布、cron 表达式/重试或同步输出截断等排除项；未升级依赖或改变配置语义。
- 未调用真实模型，未运行实际 CLI/Agent 任务做真机验收。定时轮询的真实时序、终端交互、网络晚到与多线程退出竞态未做新的动态故障注入；使用源码等价检查和已有测试限定本轮证据。
- 包边界复审见 [架构复审报告](../../reviews/architecture/ChatGPT/06-package-boundaries-2026-10-17.md)，原阅读稿已移除。工程完成不代表用户已理解或已独立真机验收，后两项仍待用户完成。
- 提交前已核对原有 50 个受保护文件哈希全部一致，新增文档相对链接有效；提交后在对话再核验。临时检查程序/日志在最终报告前清理，本单保留命令和实际结果。

## 2026-10-17 后续调整：PathResolver 迁入 util

用户明确要求将 PathResolver 放入 util。目的为简化当前包组织，路径解析与安全分类的职责继续分开。

- 范围：PathResolver 及已有 PathResolverTest 迁包，工厂、四个文件工具、PathGuard 与三个已有测试的 import，README 与本记录。
- 契约：解析算法、构造/方法、IO、异常、安全规则和测试场景不变；纠正相关注释中旧 Main 装配位置及把路径解析误写为权限裁决的描述。
- 验证：清理旧包构建产物，运行 PathResolverTest/PathGuardTest/PermissionGateTest/FileToolsTest 现有定向测试并打包；核对类体、既有场景及保护文件哈希。不新增测试，不调用真实模型。
- Git：单批精确暂存上述迁移与接线，标题 `将 PathResolver 迁入 util 包`；不纳入原有笔记/研究材料，不 push。
- 状态：实现完成；工程自审 **Pass（本轮迁包与接线范围）**，构建与定向验证通过；本记录随代码一起本地提交。
- 实际验证：`mvn -q clean -Dtest=PathResolverTest,PathGuardTest,PermissionGateTest,FileToolsTest package` 退出 0；4 组已有测试共 25 tests / 0 failures / 0 errors / 0 skipped，JAR 打包成功。未新增或改变既有测试场景。
- 自审证据：PathResolver 与 PathResolverTest 除 package 声明外逐字一致；9 个调用方类体去除注释/import 后 Java token 一致；源码无旧包引用，JAR 包含 util/PathResolver.class 且无原 filesystem 类。
- 用户已有笔记、研究/审查资料及已提供的架构题单保持原样，51 个受保护文件哈希一致。学习与最终真机验收状态不因这次纯迁包改变。
