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
| 持久化 | 工作区 `.jadecode/`，按 tasks / memory / teams 等用途分目录 |

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
├── execution/        # 可信任务来源、运行上下文及只读运行观察
├── process/          # BackgroundManager(后台 bash / 完成队列) / ShellCommand(进程生命周期)
├── safety/           # 权限档位与 Gate；checks/ 解释参数，rules/ 匹配规则，approval/ 协调审批
├── hooks/            # 字符串事件、参数表和短路 hook
├── todo/             # TodoManager(计划表校验与渲染)
├── subagent/         # SubagentRuntime(嵌套循环 + 消息隔离 + 三终态)
├── skills/           # SkillDefinition, SkillLoader(catalog 常驻 / 全文按需)
├── context/          # ContextCompactor(请求前保护 / 主动压缩 / 超限补救)
├── memory/           # 工作区记忆：召回 / 提取 / 数量触发整理算法
├── tasks/            # 持久任务图：创建 / 依赖 / 认领 / 完成与解锁，六工具由父循环使用
├── team/             # 常驻队友、独立派工编号、文件邮箱与结果提交
├── mcp/              # 真实 stdio 服务连接、工具发现与文本调用；官方 Java SDK 隔离层
├── cron/             # CronProducer 定时生产、时间规则、待交付状态与工作区持久化
├── session/          # SessionRuntime(单消费者、历史、记忆时机、服务关闭) / SessionInbox / SessionEvent
└── agent/            # AgentExecutor(模型请求、工具批次、压缩步骤及嵌套结果)
```

仓库根 `skills/<name>/SKILL.md` 存放可被 `load_skill` 加载的真实技能:system prompt 只带 name + description 的目录,完整正文仅在模型调用 `load_skill` 后作为 tool_result 进入消息。

## 章节 ↔ 包映射

| 课程章节 | 主题 | JadeCode 包 |
|---|---|---|
| s01 | Agent Loop | `agent/` `messages/` `llm/` `tools/BashTool` |
| s02 | Tool Use(分发表) | `tools/`(5 基础工具)+ `safety/checks` |
| s03 | Permission | `safety/PermissionGate` `safety/rules` `safety/approval`（后续重构为四档权限） |
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
| s14 | MCP Plugin | `mcp/`、`tools/ToolCatalog` 与 MCP 工具适配器 |
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

`tui` 继续支持 `theme`/`thinkingVisible`/`toolsExpanded`/`commandSuggestions`，并新增 `mode`（fullscreen/regular）、fullscreen 滚动条/复制/滚轮、硬件光标、编辑器/输出间距、候选数量、主题配对、外部编辑器、键位覆盖等非秘密显示设置；旧字段和未知字段保留，保存只 patch TUI 子树。
`modelMetadata` 控制启动时的官方参数查询：默认开启，缓存 24 小时，最多等待 2000ms。
当前只自动识别 Anthropic／DeepSeek 官方 HTTPS 端点，第三方代理不猜查询接口，也不向其它主机转发凭据。
参数按端点和模型分开缓存在 `~/.jadecode/cache/model-metadata/`，不存认证或原始响应。
缺少字段、模型 ID 无匹配、查询／缓存故障时沿用现有预算；过期缓存不冒充当前官方数据。
DeepSeek 官方目录先匹配精确 ID，再兼容官方声明的旧名称 `deepseek-v4-flash`／
`deepseek-v4-flash-vision-exp` → `deepseek-flash`；只匹配参数，不改生成请求中的 MODEL_ID。
内部参数来源记录目录匹配名称，区分目录没有模型与模型存在但缺少限额字段。
TUI 输入 `/model` 只显示当前模型、思考强度和已知的窗口／输入／输出限额，未知字段省略；
来源仍保留在内部参数资料中，不常驻展示。`run` 用 `[Model]` 通知显示已知限额。
需要手动补充时，在 `modelMetadata.overrides` 下按精确模型 ID 写对象，例如：

```json
{"your-exact-model-id": {"contextWindowTokens": 131072, "maxOutputTokens": 8000}}
```

手动值是用户提供的信息，不标成官方事实；不能扩大已经取得的官方上限。
已知最大输出对正式、摘要、记忆及审批请求统一封顶，但不会把请求自动提高到最大额度。
上下文窗口单位是 token，压缩器目前仍按字符估算；本轮不把两者等同，仍保留原压缩阈值。

旧 `tui.json` 的主题会先备份再迁入新文件；新主题优先，原文件保留但不再写入。
主题通过 `/settings` 的 Theme 子流程选择；只保存 `tui` 白名单，不会清除压缩设置或用户其它字段。旧 `/theme` 命令别名已移除。

`thinking.effort` 设置请求的默认思考强度，缺省为 `default`（不额外发参数）。
`/thinking` 打开选项，也可输入 `/thinking low`；保存的是当前会话覆盖，不改全局默认。
设置从下一次请求生效，恢复会话后继续使用；Ctrl+T 仍只控制是否显示思考正文。
当前适配 DeepSeek 官方端点的 `off / low / high / max`（default 对应官方默认 high），
以及 Anthropic 官方 Claude Opus/Sonnet 4.6 的 `off / low / medium / high / max`。
未适配模型或第三方兼容端点只保留 default，不猜参数支持情况。
新队友和一次性子代理继承创建时设置，已有队友不随 Leader 改动；摘要和辅助审批保持默认。
记忆只需要结构化正文，后端已适配 OFF 时显式关闭思考，否则保持默认。

默认压缩管线是**单条结果转存 → 超阈值摘要**：`largeResultEnabled` 与 `autoSummaryEnabled` 为 true，
`batchBudgetEnabled`、`snipEnabled`、`microEnabled`、`fitEnabled`、`reactiveEnabled` 为 false。
各开关与 `compaction.limits` 的全部阈值均可覆盖；关闭自动摘要不影响显式 `compact`。
默认总门槛 300,000 字符、单结果 50,000 字符、摘要输入 400,000 字符、摘要最大输出 8,000 token。
除最后一项外都按 Java 字符数计算，**不是精确 token 或模型窗口保证**，须按实际后端调整。
默认开关参考指定 Claude Code 快照，不代表已复制其缓存共享、硬阻塞线或服务端上下文机制；
当前 JadeCode 的 read_file 仍参与普通结果转存，没有引入 Claude Code 的专用读取例外。

## MCP 与 Fetch

s14 使用官方 Java MCP SDK 接入独立 stdio 服务。Fetch 是现成的 Python 服务，
负责读取公开网页并转换为 Markdown；MCP 服务本身不按调用收费，模型读取正文仍消耗 token。

先准备 Python 3.10+（含 venv），在 checkout 中执行：

```sh
./scripts/install-fetch.sh
```

脚本按 `scripts/fetch-requirements.txt` 安装固定版本服务到 `.jadecode/mcp/fetch/`，
不改全局 Python 或用户配置。该目录已由工作区运行数据规则忽略。
Fetch 未安装不影响 Maven 构建及其它工具；重新 clone 或移动目录后，在新位置重新执行脚本，不能搬运 venv。
脚本面向 POSIX 环境；Windows 原生环境可用 `python -m venv` 与同一 requirements 安装，并配置实际的 `Scripts/python.exe`。

Fetch 默认启用：没有 `mcp.servers` 配置时，JadeCode 自动采用以下定义并提供 `connect_mcp`，
安装完成后无需手动合并配置。旧用户配置缺少该字段时同样生效；重建 JAR 并重启后使用新的默认值。
`config.example.json` 与新生成的默认配置保持一致。
如需自定义，可将以下对象写入 `~/.jadecode/config/config.json` 的 `mcp` 字段，保留其它设置：

```json
{
  "servers": {
    "fetch": {
      "enabled": true,
      "command": ".jadecode/mcp/fetch/bin/python",
      "args": ["-m", "mcp_server_fetch"],
      "startupTimeoutSeconds": 15,
      "callTimeoutSeconds": 30,
      "maxResultChars": 20000,
      "readOnlyTools": ["fetch"]
    }
  }
}
```

显式配置的 `mcp.servers` 整表替换默认表；设为 `{}` 可关闭全部 MCP，或在完整 Fetch 定义中设 `enabled: false`。
默认启用只登记工具入口，服务仍由模型按需连接；不在启动时下载依赖、启动 Fetch 或访问网页。
含路径分隔符的相对 command 和 cwd 按当前工作区解析；裸 command 使用 PATH，args 不经过 shell。
该配置可用于其它工作区，但每个工作区需准备对应相对路径的环境，或配置本机已安装服务的绝对路径。
模型只能按已启用名称调用 `connect_mcp`，不能提交启动命令或自动下载依赖。
连接完成后的下一次请求会出现 `mcp__fetch__fetch`；同一回复中猜测尚未发现的名字会被拒绝。

可在 `./jade repl` 中输入：

```text
连接 fetch 服务，读取 https://example.com，概括页面内容并附来源。每次最多读取 5000 字符。
```

Fetch 参数支持 `url`、`max_length`、`start_index` 和 `raw`；默认返回上限 5000 字符。
宿主限制显式 max_length 为 1–10000，支持按 start_index 分段；服务器输出还受 maxResultChars 限制。
`readOnlyTools` 是用户配置的原始工具名白名单，不信任服务自报的 readOnlyHint；其它外部工具默认需要确认。
HTTP(S) URL 不能包含用户名密码，Fetch 保留默认非公网地址限制；代理环境按白名单传入，不传模型 API Key。
正文作为外部参考数据，不能据此增加用户授权。

Leader 和常驻队友各自按需连接；一次性子代理暂不接入 MCP。关闭会话会关闭所属连接，恢复历史不恢复旧进程。
连接和调用失败形成明确工具错误，不自动重放；SDK 关闭最长等待约 10 秒，未确认退出时提示检查进程。
若代理 DNS 把公网域名解析成 `198.18.x.x` 等假地址，Fetch 的默认非公网保护会拒绝请求，
即使浏览器能打开也可能发生。需要用户在代理侧为目标域名配置真实解析；不应默认用 `--allow-private-ips` 关闭保护。
本机已验证 `https://detectportal.firefox.com/success.txt` 可读取，`example.com` 因上述环境原因被拒绝；
用户选择保持代理配置，详见任务单。其它机器应按自身 DNS 与网络状态验收。
第一版只支持 stdio、文本工具，不支持 HTTP/OAuth、resources/prompts、sampling、elicitation、热更新或非文本结果。
工程记录见 [s14 任务单](docs/jobs/s14/mcp-fetch.md)，源码阅读见 [s14 指南](docs/notes/s14/mcp-fetch-review.md)。

## 运行

```sh
mvn -q package                              # 构建(直接跑即可,无需设 JAVA_HOME)
mvn -q test                                 # 单元测试
./jade repl                                 # 交互
./jade run "任务"                            # 一次性
```

TUI 默认使用 Pi 原生 fullscreen，也可在 `/settings` 切换为 regular；两种模式共用编辑器、消息、工具卡和命令，不保留旧底锚内联模式。
浏览器/脚本/管道请用 `run`，其输出仍是纯文本，不初始化 TUI。`repl` 需要真实终端。
**Enter 提交；Shift+Enter、Ctrl+J 或反斜杠 Enter 换行；Alt+Enter 在当前无独立 follow-up 接口时不绑定。**
括号粘贴的整段正文保留为同一条提示词，粘贴中的回车不会提交；粘贴后仍需按 Enter。
编辑器按完整字素移动/删除，支持 Pi 的词/行移动、撤销（Ctrl+-）、kill/yank（Ctrl+U/K/Y、Alt+Y）、100 项去重历史；
超过 10 行或 1000 字符的粘贴折叠为顺序编号的 `[paste #N …]` 标记，提交时展开。
Tab 只应用候选；slash 命令的 Enter 应用后执行，文件路径的 Enter 只应用。异步候选带草稿版本校验，过期结果丢弃。

消息保留 JadeCode 批准的 Request/Reply/Thinking 卡片容器和真实状态（Streaming/Done/Incomplete/Historical），
工具卡按可信工具身份使用专用 renderer：bash 显示 `$ command` 与最后 5 个视觉行，read 收起时不显示正文，
write 显示前 10 行并标 `Preview · not executed`，edit 显示片段 diff 且明确未执行，其他工具/MCP fallback 显示前 10 行。
`RETURNED` 保持中性，不等于业务成功；BLOCKED/ERROR/NOT_RUN/INCOMPLETE 保留真实细分。Ctrl+O 展开工具，Ctrl+T 切换思考正文。
整轮计时来自执行线程的单调时钟：审批等待暂停，结束后冻结；真实耗时显示为 `29.0s`。状态栏 spinner 每 80ms 更新。
页脚显示目录/分支/会话名、真实 token/R/W 缓存/context 估计/权限与模型；token 使用大写 K，例如 100000→`100K`，不把缺报告当 0，也不显示无来源的费用/订阅。

全屏模式支持应用滚轮、PageUp/Down、Ctrl+Home/End、选区复制和搜索（Ctrl+F；Enter/Shift+Enter 前后匹配，Esc 关闭）。
`Ctrl+O` 同时展开工具卡与欢迎资源；`Ctrl+G` 外部编辑器；`Ctrl+V` 读取文字或图片剪贴板（图片保存为临时文件并插入路径，仍只是文本输入）；
`Ctrl+X` 复制选区或最近助手回复；`Ctrl+Z` 暂退到 shell，`fg` 后重绘；`Ctrl+C` 清草稿，500ms 内再按退出；空编辑器 Ctrl+D 退出。
Esc 只关闭本地菜单/搜索/候选，不是 Agent 单轮取消。regular 模式不捕获鼠标，滚轮/拖选交给终端。
退出时恢复终端并打印一次 Session/ID/Resume；fullscreen 默认把最终转录重放到主屏，`tui.fullscreenExitOutput=resume-hint` 时只保留恢复提示。

命令只列真实后端：`/settings /name /session /hotkeys /copy /new /resume /quit /model /thinking /permission /mcp`。
旧 `/help`、`/exit`、`/usage`、`/theme`、`/rename` 别名已移除；未知 slash 留在本地报英文错误并保留草稿，不发给模型。
`/model` 只读；`/mcp` 只读服务/工具清单，无连接/配置/OAuth 按钮；缺取消/树/分叉/模型热切换/扩展管理等后端的入口统一隐藏。
审批以“仅本次/允许此类/拒绝”接管 dock，完整显示理由、真实参数和长期范围；默认无选择，窗口无法安全显示审阅区时不发布批准动作。
Esc/空 Enter/普通文字不答复审批；旧画面、旧输入水位、resize 或 revision 变化都会使旧批准失效。

颜色尊重 `NO_COLOR`/`TERM=dumb`；只有受信编码器能输出 SGR/OSC8/终端协议，模型与工具正文先净化。
全屏退出/窗口宽度变化可能清空并重放 scrollback，这是终端可寻址边界的正常行为。
当前 Markdown 使用 Java 解析/排版（固定 GraalJS bundle 尚未交付），因此标题/列表/表格等视觉可用，但尚未宣称与 Pi 的 marked/highlight.js/Mermaid 完全一致；
真实 SSH/IME/图片终端/模型流仍需人工验收。详细限制见 `docs/tui-pi-replication/05-handover/design-deviations.md` 与 `acceptance-results.md`。

架构、验证与未验证范围见 [TUI 任务单](docs/jobs/s13/pi-tui.md)，阅读入口见 [TUI 指南](docs/notes/s13/pi-tui-review.md)。

### 权限档位与规则

`/permission` 打开四档菜单，页脚显示当前档位；默认 **Workspace**。

| 档位 | 普通行为 | 需要额外授权时 |
| --- | --- | --- |
| Read Only | 已识别读取/查询允许；写入、任务管理和一般程序先确认 | 用户决定 |
| Workspace | 普通工作区读写、构建允许；删除、越界和复杂 shell 先确认 | 用户决定 |
| Approve for Me | 基础行为同 Workspace | 独立辅助模型审查普通询问，不确定/失败转人工；敏感操作仍问用户 |
| Full Access | 普通操作免询问，包括普通删除和越界 | 显式 deny/ask、根目录/设备保护及强制人工项仍有效；需询问的项直接拒绝 |

```sh
./jade repl --permission auto-review
./jade run --permission workspace "任务"
```

会话内 `/permission read-only` 等命令只改变本次 CLI；`/permission default workspace` 同时保存启动默认。
TUI 切入 Full Access 需要再确认；显式 CLI 参数视为主动选择。Leader、队友与一次性子代理共用当前权限状态，
切换影响后续许可判定，不撤销已执行操作、不停止已经启动的命令。`/resume` 沿用当前 CLI 状态，不恢复旧临时批准。

选择“允许此类命令”将界面显示的范围写入 `~/.jadecode/config/config.json` 的 `permissions.rules`，
绑定当前工作区；单文件建议精确到操作和路径，shell 建议是字面 argv token 前缀与 cwd，不是任意字符串前缀。
复合命令、重定向和不能可靠解释的语法不生成命令建议；保存失败则不执行，不能把失败伪装成已授权。
仅本次只授权原操作，拒绝只形成对应调用的 tool_result，不自动结束大轮次。

配置还支持 `permissions.defaultProfile`、`permissions.disabledDefaults`。规则的 effect 为 `ALLOW/ASK/DENY`，
kind 为 `TOOL/PATH/COMMAND`；工作区用绝对路径，空字符串表示用户明确设置的跨工作区规则。
deny → ask → 默认硬保护/强制人工 → allow → 档位普通策略，前者优先；权限规则字段、类型与编号严格检查。
默认保护编号与配置示例见 [权限任务单](docs/jobs/s13/permissions.md)，学习见 [阅读指南](docs/notes/s13/permissions-review.md)。

本轮没有 Linux 沙箱。shell 检查器只解释 `/bin/sh` 的有限语法，无法证明脚本/一般程序的真实副作用，
被允许的程序仍以当前用户权限运行；Read Only 与 Workspace 是应用内审批策略，不是操作系统隔离保证。
这些档位检查模型调用的操作，不阻止日志、压缩归档和记忆等 Harness 自身的状态保存。
配置写入有进程内互斥，不支持多 CLI 同时修改规则；外部手改在下次启动读取，运行中不热加载。

### 会话保存与恢复

```sh
./jade repl --resume                         # 选择当前工作区的历史会话
./jade repl --resume <完整 UUID>             # 直接恢复
./jade repl --resume "会话名称"              # 名称匹配，重复时选择
```

REPL 默认新建并保存主会话。`/resume` 打开可筛选选择器，
`/resume <完整 UUID>` 直接切换；当前会话和没有正式记录的空会话不列入候选。
候选仅显示会话标题与距上次使用的时间（刚刚、分钟前、小时前、天前），不显示 UUID 或长时间戳。
内部仍按 UUID 选择，同名同时间的会话也分别保留；相对时间在打开列表时计算。
有运行任务、排队消息、未收后台结果或未关闭队友时拒绝切换，不暗中取消工作；启动选择器 Esc 退出，
会话内选择器 Esc 返回原会话，输入草稿保留。两个入口共用加载器，不支持跨工作区或按任意文件路径加载。

记录保存在 `.jadecode/sessions/<uuid>/`，包括 session.json、events.jsonl 和 session.lock。
完整消息、末条合并、压缩/回滚替换和实际 Todo 快照持续保存；不逐 token 存流式片段。
恢复沿用原会话 ID，等待新输入，不重跑旧工具，也不恢复旧后台进程、队友、Inbox 或审批授权。
正常恢复不再显示固定的范围提示；日志写入中断、工具结果缺失等实际问题仍会提示。
当前环境重新提供模型、项目规则、工具与权限；持久任务/记忆/cron 和业务文件保持当前磁盘状态，不回滚。
`run` 仍是一次性执行，不生成可 resume 的主会话日志；旧压缩快照不能补回从未保存的完整对话。

模型使用压缩后重建的有效历史，TUI 则保留压缩前的已记录主会话内容。
历史工具卡只标记 HISTORICAL，不伪造耗时/业务成功；完整队友/子代理显示过程及未记录的旧用量不补造。
新增用量记录会随原会话继续累计；旧版未保存的用量无法追溯，失败/断流也可能缺少后端报告。
`/name 新名称` 保存便于查找的名称，`/resume 名称` 也支持匹配；名称不参与目录计算，UUID 仍唯一。
启动不固定显示 UUID；退出恢复终端后显示当前名称、ID 和恢复命令。
末尾未闭合工具交换会补“结果未记录，操作可能已执行”，不自动重试。
中间坏记录拒绝恢复；末尾半行先保留 recovery 副本再处理。日志写入失败停止后续操作并告警。
日志强制写入本地文件，但工具副作用与日志不是事务，不承诺断电或硬件故障下完整恢复。
当前单日志上限为 256 MB，达到上限前停止追加并提示开启新会话；尚未实现自动日志清理或无限大日志加载。

会话目录采用私有权限，可能包含提示词、工具输出及接口返回的思考，不能提交或分享这些运行数据。
会话写锁约束协作的 CLI 写者，不是数据目录防篡改沙箱，也不让工作区任务库/cron 自动支持多进程并发。
工程记录见 [resume 任务单](docs/jobs/s13/session-resume.md)，源码学习见 [resume 阅读指南](docs/notes/s13/session-resume-review.md)。

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
- [ ] s14:真实 MCP stdio、动态工具与 Fetch 已接入；工程证据见任务单，用户理解与独立真机验收待完成
- [ ] s15–s17:逐章实现(见映射表)

由 `SessionRuntime` 在任务入口和完成处调用 Memory 服务。Memory 使用工作区 `.jadecode/memory/`，每次用户任务入口召回一次，真正结束后提取，新增成功后按数量尝试整理。
辅助请求沿用现有客户端，不带工具；记忆维护失败告警，不抹掉已完成的主任务结果。
`memory.enabled=false` 可停用自动记忆，不删除已有文件。召回/提取/整理最大输出分别由
`recallMaxOutputTokens` / `extractionMaxOutputTokens` / `consolidationMaxOutputTokens` 设置，
默认 2048 / 8000 / 16384 token；已适配关闭思考的后端，记忆请求只要求结构化正文。
整理输入 `consolidationInputChars` 与提取的完整目录 `extractionCatalogChars` 默认均为 128000 字符，
超限明确跳过，不截掉后半库后覆盖或假装完整去重。大库召回逐页选择，最终最多注入五条；
页数增加会增加辅助调用，失败时从全库关键词降级，不永久排除字典序靠后的记录。
合法 `[]` 在召回/提取中不告警，整理仍不允许用空数组清库；
格式、空正文、截断和拒答分别显示固定原因，不回显原始对话或思考，也不自动重试。
完整 JSON 外包一层 json 代码围栏可接受，解释文字夹带数组、尾随内容和截断回复仍拒绝写盘。
子代理不自主维护长期记忆。记忆内容的真伪、长期价值与合并质量依赖模型，格式校验不提供语义保证。
记忆操作使用库文件锁；坏编码按条跳过、长名称按 UTF-8 字节限长、不同名撞路径分开保存。
整理前的旧/新正文和索引保存在 `.jadecode/memory/.memory-backup-*/`，发布中断后在下次库操作恢复；
遇到未知外部修改不覆盖。备份不自动清理、不恢复原权限等元数据，也不声称解决任意断电或外部竞态。
人工审批等待（含排队）不计入本轮计时；模型/后台/记忆维护仍计入。

Task System 使用 `.jadecode/tasks/{id}.json`。先创建取得实际 ID，再增加依赖；认领检查前置条件，完成报告刚解锁任务。
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
包边界复审见 [架构复审报告](docs/reviews/architecture/ChatGPT/06-package-boundaries-2026-10-17.md)，工程记录见 [A01–A11 任务单](docs/jobs/architecture/package-reorganization.md)。

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
`recurring` / `durable` 默认 true；持久定义与 pending 状态保存至工作区 `.jadecode/cron/jobs.json`。
模型首个合法正式回复表示交付确认，不是业务验收；一次性删除，周期项等待下一次匹配。
进程停止后不执行、不补跑停机分钟；`run` 只管理定义，不开启常驻调度。需要停机也执行应使用外部调度服务。
至少一次交付可能在崩溃后重复；确认前失败退避 5 秒，连续三次暂停本会话自动投递，保留 durable 待交付记录。
审批按 Harness 标注的来源而非线程判断；定时任务和其子代理不能人工询问，不能通过 input 自填来源绕过。
Approve for Me 可独立审查普通询问；仍需人工却没有通道时明确拒绝。TUI 使用三项审批，run 对应输入 1/2/3（y/n 为仅本次/拒绝的简写）；
空 Enter 均不作为审批答复。多行提示词中的批准文字不会被单独取出执行。
收件箱最多 64 项新请求；满时明确拒收，定时项保留 pending，后台完成结果不与新请求争抢容量。
REPL 退出会关闭会话而不是等待队列排空；非交互执行使用 `run`。
一个工作区的定时库不支持多个 CLI 并发写入。会话重构已通过工程检查和获准代跑的真实 CLI 场景；
检查范围、模型未按要求传参的重测及终端修复记录见 [验收补查](docs/jobs/s12/terminal-and-acceptance.md)。
当前学习入口见 [事件化运行指南](docs/notes/s12/session-event-runtime-review.md)；
[原 s12 指南](docs/notes/s12/cron-scheduler-review.md)保留重构前的学习记录。

Team 使用 `spawn_teammate`、`assign_task`、`send_message`、`submit_task_result` 等工具，
最多三个常驻队友；模型提供短摘要与长正文，`.jadecode/teams/<team-id>/messages/` 保存正文，
`pending/` 仅保存待投递记录。队友报告默认可以插入并唤起 Leader，普通进度默认不唤起。
队友启用独立压缩与后台，不启用 Memory/cron；工具层弱隔离不是文件系统沙箱。
请在存活的 REPL 中进行异步 Team 协作；`run` 在 Leader 本轮回复后退出，会关闭尚未结束的队友和命令。
工程任务见 [s13 任务单](docs/jobs/s13/agent-teams.md)，学习入口见 [s13 阅读指南](docs/notes/s13/agent-teams-review.md)。

## 工作区运行数据

JadeCode 自己的运行数据统一保存在工作区 `.jadecode/`：tasks、memory、teams、transcripts、
tool-results、approvals/backups 与 cron。压缩归档与工具输出仍按原用途保存，不改变命令工作目录。
机器级 `~/.jadecode/config/config.json` 和模型参数缓存不搬进工作区，`.env`、Git、IDE 和外部 Agent 配置也不移动。

升级后首次装配会迁移明确识别的旧数据位置，请先退出该工作区的旧版 JadeCode。
迁移先检查冲突与软链，不覆盖新位置，不改历史正文；中途失败保留已经移动的数据，后续可继续。
read_file 对已迁移的旧正文路径提供只读兼容，普通写入和 bash 命令不会自动重定向。
新旧两处同时有数据或旧数据包含软链时停止并报错，需要人工确认，不能自动合并。
迁移不扫描其它工作区、不恢复旧执行现场，也不删除无法识别的文件。
