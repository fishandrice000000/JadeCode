# JadeCode 全仓库架构审查：覆盖记录

- 审查开始日期：2026-10-17（Asia/Shanghai）
- 执行者：本次审查由 Pi 会话中的 AI 助手执行。目录名沿用任务单给出的模板 `Deepseek/`，仅表示“本次审查的独立子目录”，不代表另有 DeepSeek Agent 参与。
- 唯一写入位置：`docs/reviews/architecture/Deepseek/`。未修改任何源码、测试、配置、构建脚本或既有文档，未执行构建、测试或项目程序。
- 审查方式：静态只读分析。使用 `read`、`rg`、`find`、`ls`、`git status`、`git log`、`git ls-files` 等只读命令。

## 1. 事实基线

| 项 | 值 |
| --- | --- |
| 仓库 | `/home/fishandrice/Backend/JadeCode` |
| 分支 | `main` |
| HEAD | `f15388d 简化终端审批为 y/n 答复` |
| upstream | `origin/main`（本审查未 fetch，不判断领先/落后） |
| staged | 空 |
| 已有未提交修改 | `docs/notes/s08/offline-acceptance-guide.md`、`docs/notes/s09/memory-recall-review.md`、`docs/notes/s10/task-system-review.md`（保持原状） |
| 已有未跟踪目录 | `docs/notes/other-agents-analysis/`、`docs/reviews/`（后者含本次审查） |
| 规模 | `src/main` 73 个 Java 文件、`src/test` 40 个 Java 文件 |

结论以实际读取的工作区代码为准。审查过程中未改动工作区状态。

## 2. 已有同目录审查

`docs/reviews/architecture/ChatGPT/` 已存在一份同日架构审查（未提交）。本次审查**未复制**其结论，独立从源码建立判断；仅在阶段四做交叉核对并标注异同（见 `05-final-report.md` 第 6 节）。本次报告不复用、不覆盖、不删除既有报告。

## 3. 审查范围与排除规则

纳入审查：`src/main/java/**`、`src/test/java/**`、`pom.xml`、`jade`、`README.md`、`CONTEXT.md`、`docs/adr/**`、`docs/jobs/s12/**`、`docs/notes/optimization/**`、`.gitignore`、`.env.example`（仅读模板，不读 `.env`）。

| 排除项 | 原因 |
| --- | --- |
| `.git/` | 版本数据库；只用只读 Git 命令取基线 |
| `target/`、`dependency-reduced-pom.xml` | 既有构建产物，不能证明当前源码 |
| `.env` | 可能含秘密；遵守“不读取”边界 |
| `.task_outputs/`、`.transcripts/`、`tmp/` | 运行数据与人工验收产物，非产品实现 |
| `docs/notes/other-agents-analysis/` | 其它 Agent 研究资料；本次不对标外部 Agent |
| `.agents/`、`.codex/`、`.vscode/` | 开发工具本地元数据，非产品运行配置 |
| 第三方 JAR / 依赖源码 / 机器级配置 | 不在 JadeCode 仓库范围内 |

## 4. 文件清单与覆盖状态

状态定义：

- **深入**：逐方法阅读，梳理调用方、状态变化、失败分支与注释。
- **基础**：读结构、职责与关键分支，未逐分支证明。
- **已排除**：见上表。

### 4.1 主源码

| 文件 | 状态 | 备注 |
| --- | --- | --- |
| `Main.java` | 深入 | 装配根、hook 实现、提示词、工具注册顺序 |
| `agent/AgentLoop.java` | 深入 | 执行器；request/executeTools/compactAfterBatch/stop/finish |
| `agent/SessionRuntime.java` | 深入 | 会话运行器；consume/execute/runTask/apply/fail/close |
| `agent/SessionInbox.java` | 深入 | 有界 FIFO、revision/wake |
| `agent/SessionEvent.java` | 深入 | sealed 事件与列表复制 |
| `agent/ExecutionContext.java` | 深入 | 任务身份/来源/审批/退出信号 |
| `agent/CronProducer.java` | 深入 | 定时检查线程、退避、交付确认 |
| `cli/ReplCommand.java` | 深入 | REPL 装配与资源顺序 |
| `cli/RunCommand.java` | 深入 | 单次任务与退出码 |
| `cli/ConsoleInput.java` | 深入 | 唯一终端读取入口、y/n 审批、多行输入 |
| `cli/TerminalDisplay.java` | 深入 | JLine 输入/输出重画、System.out/err 劫持 |
| `config/Env.java` | 深入 | `.env` 解析与查询 |
| `config/AppConfig.java` | 深入 | 配置缺省与必填校验 |
| `util/Json.java` | 深入 | 共享 ObjectMapper |
| `util/Ansi.java` | 深入 | 终端色码开关 |
| `messages/Message.java` | 深入 | 消息记录与块筛选 |
| `messages/ContentBlock.java` | 深入 | sealed + Jackson 多态 |
| `messages/TextBlock.java` | 深入 | — |
| `messages/ToolUseBlock.java` | 深入 | ID 构造校验 |
| `messages/ToolResultBlock.java` | 深入 | ID 构造校验 |
| `messages/Role.java` | 深入 | wire 枚举 |
| `messages/ToolExchangeProtocol.java` | 深入 | 配对与历史校验 |
| `llm/LlmClient.java` | 深入 | 接口 |
| `llm/AnthropicSdkLlmClient.java` | 深入 | 唯一 SDK 适配层 |
| `llm/CompleteRequest.java` | 深入 | — |
| `llm/ModelResponse.java` | 深入 | — |
| `llm/LlmException.java` | 深入 | 状态码字段 |
| `context/CompactionLimits.java` | 深入 | 阈值集中 |
| `context/ContextSerialization.java` | 深入 | 序列化/字符估算 |
| `context/ContextCompactor.java` | 深入 | 压缩门面（转发为主） |
| `context/HistoryCompactor.java` | 深入 | 摘要层 |
| `context/MicroCompactor.java` | 深入 | 已消费结果转存 |
| `context/SnipCompactor.java` | 深入 | 归档中段 |
| `context/ToolResultBudgetCompactor.java` | 深入 | 最新批次预算 |
| `context/ToolResultFitter.java` | 深入 | 兜底转存 |
| `context/ToolOutputStore.java` | 深入 | 工具结果落盘与可信路径 |
| `context/TranscriptStore.java` | 深入 | transcript 归档与标记识别 |
| `context/ToolExchangeBoundary.java` | 深入 | 配对切片转发壳 |
| `safety/CommandPolicy.java` | 深入 | 命令行黑名单/破坏性/高危目录 |
| `safety/Hooks.java` | 深入 | 事件表 |
| `safety/PathGuard.java` | 深入 | 高危/越界分类 |
| `safety/PathResolver.java` | 深入 | 逐段符号链接解析 |
| `safety/PermissionGate.java` | 深入 | 硬拒/询问/审批通道 |
| `skills/SkillLoader.java` | 深入 | skills 扫描与两层注入 |
| `skills/SkillDefinition.java` | 深入 | — |
| `subagent/SubagentRuntime.java` | 深入 | 子循环（与执行器分叉） |
| `subagent/SubagentOutcome.java` | 深入 | 三终态 |
| `tasks/Task.java` | 深入 | 任务记录不变量 |
| `tasks/TaskStore.java` | 深入 | 任务图与原子写 |
| `tools/Tool.java` | 深入 | 工具接口与重载 |
| `tools/ToolSchema.java` | 深入 | 平铺 schema |
| `tools/BashTool.java` | 深入 | 同步/后台分派 |
| `tools/ShellCommand.java` | 深入 | 进程组生命周期 |
| `tools/BackgroundManager.java` | 深入 | 后台作业与结果队列 |
| `tools/CompactTool.java` | 深入 | 无副作用信号工具 |
| `tools/TaskTools.java` | 深入 | 六工具适配 |
| `tools/CronTools.java` | 深入 | 三工具适配 |
| `tools/SubagentTool.java` | 深入 | task 工具 |
| `tools/TodoWriteTool.java` | 深入 | — |
| `tools/LoadSkillTool.java` | 深入 | — |
| `tools/GlobTool.java` | 深入 | 工作区遍历 |
| `tools/ReadFileTool.java` | 深入 | — |
| `tools/WriteFileTool.java` | 深入 | — |
| `tools/EditFileTool.java` | 深入 | — |
| `todo/TodoManager.java` | 深入 | 整表替换与渲染 |
| `cron/CronExpression.java` | 深入 | 五段 cron 解析/匹配 |
| `cron/CronJob.java` | 深入 | 定义快照与不变量 |
| `cron/CronScheduler.java` | 深入 | 定义/待交付状态持久化 |

### 4.2 测试源码

| 文件 | 状态 |
| --- | --- |
| `agent/AgentLoopTest.java` | 基础 |
| `agent/AgentLoopAutomaticPreparationTest.java` | 基础 |
| `agent/AgentLoopManualCompactionTest.java` | 基础 |
| `agent/AgentLoopReactiveCompactionTest.java` | 基础 |
| `config/AppConfigTest.java` | 基础 |
| `config/EnvTest.java` | 基础 |
| `context/*Test.java`（11 个） | 基础 |
| `llm/RecordingLlmClient.java`、`llm/ScriptedLlmClient.java` | 深入（测试替身，用于判断可测性） |
| `MainTest.java`、`MainCompactionAssemblyTest.java` | 基础 |
| `messages/MessagesJsonTest.java`、`messages/ToolExchangeProtocolTest.java` | 基础 |
| `safety/*Test.java`（5 个） | 基础 |
| `skills/SkillLoaderTest.java` | 基础 |
| `subagent/SubagentRuntimeTest.java`、`subagent/SubagentRuntimeCompactionTest.java` | 基础 |
| `todo/TodoManagerTest.java` | 基础 |
| `tools/BashToolTest.java`、`CompactToolTest.java`、`FileToolsTest.java`、`LoadSkillToolTest.java`、`SubagentToolTest.java`、`ToolSchemaTest.java` | 基础 |

测试未逐方法证明；仅用于判断“哪些模块有可运行回归、哪些模块没有源码级测试”（见 `04-engineering-quality.md`）。

### 4.3 文档与配置

| 文件 | 状态 | 备注 |
| --- | --- | --- |
| `README.md` | 深入 | 与源码比对，发现计划性条目与当前包的差异 |
| `CONTEXT.md` | 深入 | 术语与实现一致性 |
| `AGENTS.md` | 深入 | 项目规则、验证边界 |
| `docs/adr/0001-*.md`、`0002-*.md` | 深入 | 会话事件化与后台依赖决策 |
| `docs/jobs/s12/*.md`（3 个） | 深入 | 当前章工程事实、验证与限制 |
| `docs/notes/optimization/*.md`（2 个） | 深入 | 既有优化候选（OPT-001..004）与路径设计 |
| `pom.xml` | 深入 | 依赖、toolchain、shade |
| `jade` | 深入 | 启动脚本 |
| `.gitignore`、`.env.example` | 深入 | 忽略规则与秘密模板 |

## 5. 阶段进度

| 阶段 | 状态 | 产物 |
| --- | --- | --- |
| 一：全局架构调查 | 已完成 | `01-global-architecture.md` |
| 二：逐模块深入 | 已完成 | `02-modules/`（10 份） |
| 三：跨模块与工程质量 | 已完成 | `03-cross-module.md`、`04-engineering-quality.md` |
| 四：交叉验证与综合 | 已完成 | `05-final-report.md` |

## 6. 审查方法与局限

- 未运行 Maven、未运行任何测试、未启动 CLI、未调用真实模型。任何“运行期行为”结论要么来自源码静态推理，要么标注为“待验证”。
- 字符数/行号基于 HEAD `f15388d` 的实际工作区文件；引用行号可能随以后提交漂移。
- 未做安全渗透测试或威胁建模；只审查与架构有关的边界。
- 未对标 Codex、Claude Code 等外部 Agent 源码；`docs/notes/other-agents-analysis/` 未作为判断依据。
- 依赖 JDK 实现细节的结论（如 `Map.of`/`Map.copyOf` 迭代顺序）已在正文标注证据强度。

## 7. 待验证事项（需运行才能确定）

| 编号 | 事项 | 为何静态无法定论 |
| --- | --- | --- |
| V-1 | 空字符串 API key 是否真的绕过 fail-fast 并在请求时才失败 | 需要真实 `.env`/环境与一次 SDK 调用 |
| V-2 | 工具数组顺序变化是否实际影响 DeepSeek 端前缀缓存命中率 | 取决于服务端缓存实现 |
| V-3 | 父任务长时间运行是否会出现轮数失控与队头阻塞 | 需要真实长任务与真实模型 |
| V-4 | 记忆召回/提取整库整理的语义质量与费用 | 需要真实模型与真实记忆库 |
| V-5 | 会话关闭时正在进行的辅助记忆调用是否能及时停止 | 依赖第三方 HTTP 客户端对中断的响应 |
| V-6 | 并发/多进程场景下 `.tasks`/`.scheduled_tasks.json` 的行为 | 需要多进程实证 |

以上事项在最终报告中按“待验证”标注，不作为已证实缺陷。
