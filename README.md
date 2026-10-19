# JadeCode

个人 CLI coding agent — Java 21 重写 [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 课程(s01–s17)。

**Agency 来自模型,harness 让 agency 落地。** 本项目跟随课程逐章实现 harness 机制:agent loop、工具分发、权限、hooks、todo、子代理、技能、上下文压缩、记忆、任务系统、后台任务、cron、团队协作、MCP、集成、workflow、goal loop。

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 | Java 21(record / sealed / 虚拟线程) |
| 构建 | Maven 单模块,`mvn package` 产出 `target/jadecode.jar` |
| LLM 接入 | Anthropic 消息格式 + 可配置 base URL(**DeepSeek /anthropic 端点**,已实测) |
| 框架 | 无(CLI 形态,不用 Spring Boot) |
| 持久化 | 文件(与课程一致:`.tasks/` `.memory/` `.mailboxes/` 等) |

## 目录结构

已实现 s01–s07、s10–s11；其余已接入能力的独立章节验收状态见下方进度。

```
src/main/java/com/jadecode/
├── Main              # picocli 顶层入口：run / repl
├── cli/              # repl 的统一 TUI、会话桥接；run 的独立纯文本输出与审批
├── tui/              # 通用终端、输入解码、样式行、内联差分、编辑器与 Markdown
├── bootstrap/        # JadeCodeFactory：生产与离线共用的装配入口
├── config/           # 模型环境配置、统一 config.json 与界面／压缩设置
├── util/             # Json(共享 ObjectMapper), Ansi(终端色码), PathResolver(共享路径解析)
├── messages/         # Message + sealed ContentBlock(text/tool_use/tool_result)
├── llm/              # LlmClient / ToolDefinition(数据快照) + AnthropicSdkLlmClient(SDK 隔离层)
├── tools/            # Tool / ToolSchema 与工具适配器
├── execution/        # 可信任务来源、Approval 审批及只读运行观察
├── process/          # BackgroundManager(后台 bash / 完成队列) / ShellCommand(进程生命周期)
├── safety/           # PathGuard, CommandPolicy, PermissionGate
├── hooks/            # 字符串事件、参数表和短路 hook
├── todo/             # TodoManager(计划表校验与渲染)
├── subagent/         # SubagentRuntime(嵌套循环 + 消息隔离 + 三终态)
├── skills/           # SkillDefinition, SkillLoader(catalog 常驻 / 全文按需)
├── context/          # ContextCompactor(请求前保护 / 主动压缩 / 超限补救)
├── memory/           # 工作区记忆：召回 / 提取 / 数量触发整理算法
├── tasks/            # 持久任务图：创建 / 依赖 / 认领 / 完成与解锁，六工具由父循环使用
├── team/             # 常驻队友、独立派工编号、文件邮箱与结果提交
├── cron/             # CronProducer 定时生产、时间规则、待交付状态与工作区持久化
├── session/          # SessionRuntime(单消费者、历史、记忆时机、服务关闭) / SessionInbox / SessionEvent
└── agent/            # AgentExecutor(模型请求、工具批次、压缩步骤及嵌套结果)
```

仓库根 `skills/<name>/SKILL.md` 存放可被 `load_skill` 加载的真实技能:system prompt 只带 name + description 的目录,完整正文仅在模型调用 `load_skill` 后作为 tool_result 进入消息。

## 章节 ↔ 包映射

| 课程章节 | 主题 | JadeCode 包 |
|---|---|---|
| s01 | Agent Loop | `agent/` `messages/` `llm/` `tools/BashTool` |
| s02 | Tool Use(分发表) | `tools/`(5 基础工具)+ `safety/PathGuard` |
| s03 | Permission | `safety/CommandPolicy` `safety/PermissionGate` |
| s04 | Hooks | `hooks/Hooks` |
| s05 | TodoWrite | `todo/` |
| s06 | Subagent | `subagent/` |
| s07 | Skill Loading | `skills/` |
| s08 | Context Compact | `context/` |
| s09 | Memory | `memory/` |
| s10 | Task System | `tasks/` |
| s11 | Background Tasks | `process/BackgroundManager` |
| s12 | Cron Scheduler | `cron/` |
| s13 | Agent Teams | `team/` |
| s14 | MCP Plugin | `mcp/` |
| s15 | 集成装配 | `agent/AsyncEventLoop` `context/SystemPromptBuilder` `llm/RetryPolicy`(完整版) |
| s16 | Workflow Runtime | `workflow/` |
| s17 | Goal Loop | `goal/` |

## 配置

```sh
cp .env.example .env   # 填入 ANTHROPIC_API_KEY(DeepSeek key)
```

环境变量沿用课程命名:`ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL` / `MODEL_ID` / `FALLBACK_MODEL_ID` / `MAX_TOKENS`。

界面与压缩设置统一放在 `~/.jadecode/config/config.json`，完整模板见
[config.example.json](config.example.json)。缺失字段使用默认值，修改后重启生效，非法布尔值、数值或主题会明确报错。
配置不包含 API Key，模型和认证仍走上面的环境配置。

`tui` 可设置 `theme`、`thinkingVisible`、`toolsExpanded`、`commandSuggestions`。
旧 `tui.json` 的主题会先备份再迁入新文件；新主题优先，原文件保留但不再写入。
`/theme` 只更新 `tui.theme`，不会清除压缩设置或用户其它字段。

默认压缩管线是**单条结果转存 → 超阈值摘要**：`largeResultEnabled` 与 `autoSummaryEnabled` 为 true，
`batchBudgetEnabled`、`snipEnabled`、`microEnabled`、`fitEnabled`、`reactiveEnabled` 为 false。
各开关与 `compaction.limits` 的全部阈值均可覆盖；关闭自动摘要不影响显式 `compact`。
默认总门槛 300,000 字符、单结果 50,000 字符、摘要输入 400,000 字符、摘要最大输出 8,000 token。
除最后一项外都按 Java 字符数计算，**不是精确 token 或模型窗口保证**，须按实际后端调整。
默认开关参考指定 Claude Code 快照，不代表已复制其缓存共享、硬阻塞线或服务端上下文机制；
当前 JadeCode 的 read_file 仍参与普通结果转存，没有引入 Claude Code 的专用读取例外。

## 运行

```sh
mvn -q package                              # 构建(直接跑即可,无需设 JAVA_HOME)
mvn -q test                                 # 单元测试
./jade repl                                 # 交互
./jade run "任务"                            # 一次性
```

REPL 中 **Enter 提交、Alt+Enter 换行**；支持 bracketed paste 的终端中，粘贴的多行文字保留为同一条提示词，粘贴后还要按 Enter 才提交。
REPL 使用 Pi 风格的主屏内联 TUI：界面线程统一管理消息、工具卡、状态和多行编辑器，
JLine 只负责底层终端，不再使用旧 LineReader／PrintAbove／Status 组合。旧交互模式没有切换入口。
`repl` 需要真实终端；脚本、管道和输出重定向使用 `run`，其输出为纯文本，不启动 TUI。
消息与工具卡标明 Leader、队友或一次性子代理来源；调用和结果显示在同一卡片。
工具普通字符串返回显示为 `RETURNED`，不等同于业务成功，也不靠正文是否以 Error 开头推断成败。
支持 Markdown、基本代码配色、Ctrl+O 全局展开工具、左键展开单卡，以及 Ctrl+T 切换思考显示。
输入 `/` 会立即在编辑器上方显示命令候选，继续输入筛选、上下选择、Tab 补全、Enter 执行，Esc 收起。
深浅主题通过 `/theme` 选择，明确确认后保存到统一配置的 `tui.theme`；`/help` 显示操作说明。
颜色依实际终端支持启用，尊重 `NO_COLOR` 和 `TERM=dumb`；不直接执行正文中的终端控制序列。
Leader 正式请求的正文和接口返回的 thinking 流式更新消息，不再使用独立底部半行预览。
`[Thinking]` 只代表接口提供的可读内容，不保证是完整内部推理；未返回时只显示 `[Reply]`。
队友和一次性子代理不展示 thinking，Memory/摘要/辅助审批也不流式展示；必要思考块仍按协议保留。
流式结束后不重复打印正文；工具等完整响应和参数校验通过才执行。`run` 使用完整回复的文本输出。
Agent 忙时，连续提交的提示词合并为一份非插入式缓冲；输入区为空时再次按 Enter，
将同一缓冲批次升级为插入式，不重复投递。TUI 审批以“同意／拒绝”选项接管输入区并保存草稿，
两个选项都不默认选中，方向键／Tab 选择后 Enter 确认，也可点击；空 Enter 与 Esc 不作审批答复。
Ctrl+C 清草稿，500ms 内再次按下退出整个会话；编辑器为空时 Ctrl+D 退出，非空时向前删除。
Esc 仅关闭选择界面，不提供单轮取消；`q`／`exit` 不再是退出指令。
窗口尺寸、主题或旧卡片改变而需要重排时，TUI 可能清空终端 scrollback 并重放本次界面记录，
包括进入 JadeCode 前的 shell 滚动历史；模型历史压缩不会删除界面记录，但不支持退出后的恢复。
页脚 token 只统计 Leader 正式请求的后端报告，不冒称全会话费用或精确上下文占用。
架构、验证与未验证范围见 [TUI 任务单](docs/jobs/s13/pi-tui.md)，阅读入口见 [TUI 指南](docs/notes/s13/pi-tui-review.md)。

**JDK 版本隔离**:项目通过 Maven Toolchains 声明编译/测试用 JDK 21(注册表在 `~/.m2/toolchains.xml`,被动生效);Maven 自身和机器上其他 Java 8 项目不受任何影响。运行时同样隔离:`./jade` 脚本只在单次进程内指向 JDK 21。

## 进度

- [x] M0:项目骨架(pom / 目录 / 配置 / README)
- [x] s01:Agent Loop + 真实 API 冒烟
- [x] s02:文件工具四件套 + 路径围栏
- [x] s03:权限三道闸门
- [x] s04:Hooks 事件表
- [x] s05:TodoWrite + reminder 注入
- [x] s06:Subagent + task 工具(独立上下文、30 轮上限、三终态)
- [x] s07:Skill Loading(两层知识注入:catalog 常驻,SKILL.md 全文按需)
- [ ] s08:CLI 上下文保护已接入；真机主路径通过，转存路径回读补验仍待完成
- [ ] s09:Memory 存储、召回、提取、整理已接入父 CLI；独立真机验收待完成
- [x] s10:持久任务图与六个任务工具；订单统计实景与重启只读恢复验收通过
- [x] s11:显式后台 bash、请求前完成通知与退出清理；用户学习与 R1–R4 真机验收通过
- [ ] s12:定时交付与持久恢复真机检查通过，事件化场景已获准代跑通过；源码学习与最终章节确认另记
- [ ] s13:独立 Session Team、文件邮箱与结果提交已接入；工程验证见任务单，用户学习与真机验收待完成
- [ ] s14–s17:逐章实现(见映射表)

由 `SessionRuntime` 在任务入口和完成处调用 Memory 服务。Memory 使用工作区 `.memory/`，每次用户任务入口召回一次，真正结束后提取，新增成功后按数量尝试整理。
辅助请求沿用现有客户端，不带工具；记忆维护失败告警，不抹掉已完成的主任务结果。
子代理不自主维护长期记忆。记忆内容的真伪、长期价值与合并质量依赖模型，格式校验不提供语义保证。

Task System 使用 `.tasks/{id}.json`。先创建取得实际 ID，再增加依赖；认领检查前置条件，完成报告刚解锁任务。
`list_tasks` 查看全部进度，`get_task` 读取完整描述。`task` 仍表示子代理委派，不是持久任务创建。
Team 共享同一 TaskStore，实例锁保护本进程认领与状态更新，不保证多进程文件互斥。
新增 failed/blocked；只有 completed 满足依赖，Leader 可显式 reopen_task 后重新派工。
不自动调度、恢复精确执行断点或证明 completed 工作正确。

`bootstrap.JadeCodeFactory` 统一装配生产与离线会话；CLI 安装普通展示 hook，工厂先注册工具成员与权限检查。
会话关闭时先标记 CLOSED、停止 cron、禁止执行器继续，再关闭同步/后台命令，拒绝 future 并限时等待消费者。
CLI 先拒绝待答审批，会话清理后最后恢复终端；共享模型客户端没有新增强取消或关闭能力。
`llm.CompleteRequest` 只携带 `ToolDefinition` 数据快照，完整 schema 在构造与读取时深复制；
SDK 仍只转换 type/properties/required，根级 additionalProperties 等字段丢失的已知限制尚未修复。
`Tool`、`ToolSchema` 和包括 `SubagentTool` 在内的适配器仍在 tools；tools/subagent 的双向依赖按本轮裁决保留。
新版包边界阅读入口见 [架构阅读指南](docs/notes/architecture/package-runtime-review.md)，工程记录见 [A01–A11 任务单](docs/jobs/architecture/package-reorganization.md)。

会话由 `SessionRuntime` 单消费者拥有历史。人工和定时新请求排队，新派工不混入当前任务；
允许插入的补充在完整工具批次后加入历史，插入式优先于非插入式消息。
工具批次、Stop 续跑和所属必等后台结果继续当前任务。普通模型和工具保持同步，
事件化不引入外部 MQ 或跨进程收件箱恢复；当前执行任务也不是 s10 持久任务图中的条目。

Background Tasks 沿用 `bash`：`run_in_background` 省略或 false 仍同步，true 立即返回 `bg_...`。
后台路径新增 `wait_for_completion`，默认 true：模型先做其它工作，再等待完成事件自动恢复当前目标；
等待时不轮询模型、不领取下一任务。false 才明确脱离当前任务。
`allow_insertion` / `wake_when_idle` 均默认 false，模型可以选择提前接收或空闲后主动处理结果。
必等结果在结束/等待阶段可以交付，不受禁止提前插入限制。所有开关必须是真实布尔值；
同步执行时显式传后台通知开关会被拒绝。
完成通知不复用原 tool_use_id，也不会改 s10 状态。退出 CLI 会终止本会话全部命令，包括脱离任务的命令。
默认每条命令 120 秒，最多 32 个运行中或尚未确认接收结果的后台命令；通知只保留最多 500 字符摘要并标注截断，
没有完整输出文件，需要可靠产物时由命令显式写入工作区。
当前 shell 生命周期实现面向 Linux，依赖 `/usr/bin/setsid` 与 `/bin/kill`；进程组清理不是沙箱，
自行另开 session 的程序、SIGKILL 与断电不在保证范围内。课程阅读见 [s11 指南](docs/notes/s11/background-tasks-review.md)。

Cron Scheduler 提供 `schedule_cron`、`list_crons`、`cancel_cron`，仅 Leader 开放，原一次性子代理仍六把工具。
五段 cron 使用进程本地时间；支持星号、星号步进、数值、区间与列表，星期日为 0，日与星期都受限时取 OR。
REPL 到期入队，在 Agent 空闲时主动发起 `[Scheduled]` 回合；人工与定时回合串行访问同一历史。
`recurring` / `durable` 默认 true；持久定义与 pending 状态保存至工作区 `.scheduled_tasks.json`。
模型首个合法正式回复表示交付确认，不是业务验收；一次性删除，周期项等待下一次匹配。
进程停止后不执行、不补跑停机分钟；`run` 只管理定义，不开启常驻调度。需要停机也执行应使用外部调度服务。
至少一次交付可能在崩溃后重复；确认前失败退避 5 秒，连续三次暂停本会话自动投递，保留 durable 待交付记录。
审批按 Harness 标注的任务来源而非线程身份判断；人工任务通过唯一终端输入入口审批，定时任务需要审批时拒绝，
子代理继承来源。TUI 使用明确的同意／拒绝选项，run 需要交互时使用文本 y/n（忽略大小写）；
空 Enter 均不作为审批答复。多行提示词中的批准文字不会被单独取出执行。
收件箱最多 64 项新请求；满时明确拒收，定时项保留 pending，后台完成结果不与新请求争抢容量。
REPL 退出会关闭会话而不是等待队列排空；非交互执行使用 `run`。
一个工作区的定时库不支持多个 CLI 并发写入。会话重构已通过工程检查和获准代跑的真实 CLI 场景；
检查范围、模型未按要求传参的重测及终端修复记录见 [验收补查](docs/jobs/s12/terminal-and-acceptance.md)。
当前学习入口见 [事件化运行指南](docs/notes/s12/session-event-runtime-review.md)；
[原 s12 指南](docs/notes/s12/cron-scheduler-review.md)保留重构前的学习记录。

Team 使用 `spawn_teammate`、`assign_task`、`send_message`、`submit_task_result` 等工具，
最多三个常驻队友；模型提供短摘要与长正文，`.team/<team-id>/messages/` 保存正文，
`pending/` 仅保存待投递记录。队友报告默认可以插入并唤起 Leader，普通进度默认不唤起。
队友启用独立压缩与后台，不启用 Memory/cron；工具层弱隔离不是文件系统沙箱。
请在存活的 REPL 中进行异步 Team 协作；`run` 在 Leader 本轮回复后退出，会关闭尚未结束的队友和命令。
工程任务见 [s13 任务单](docs/jobs/s13/agent-teams.md)，学习入口见 [s13 阅读指南](docs/notes/s13/agent-teams-review.md)。
