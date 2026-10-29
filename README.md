# JadeCode

当前版本：**1.0.0**。本轮TUI真机验收已由用户确认通过；图片粘贴按批准范围暂缓。详见[1.0.0交付记录](docs/tui-pi-replication/10-node-frontend-migration/handover/release-1.0.0.md)。

个人 CLI coding agent — Java 21 重写 [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 课程(s01–s17)。

**Agency 来自模型,harness 让 agency 落地。** 本项目跟随课程逐章实现 harness 机制:agent loop、工具分发、权限、hooks、todo、子代理、技能、上下文压缩、记忆、任务系统、后台任务、cron、团队协作、MCP、集成、workflow、goal loop。

## 技术栈

| 项 | 选择 |
|---|---|
| 语言 | Java 21(record / sealed / 虚拟线程) |
| 构建 | Maven 单模块,`mvn package` 产出 `target/jadecode.jar` |
| LLM 接入 | Anthropic 消息格式 + 可配置 base URL(**DeepSeek /anthropic 端点**,已实测) |
| 框架 | 无(CLI 形态,不用 Spring Boot);交互 TUI 由固定 Pi 的 Node 前端承担 |
| 持久化 | 用户目录 `~/.jadecode/`，项目状态按真实路径哈希隔离 |

## 目录结构

已实现 s01–s07、s10–s11；其余已接入能力的独立章节验收状态见下方进度。

```
src/main/java/com/jadecode/
├── Main              # 根入口：交互 TUI / --headless / help / version
├── cli/              # 本地/远程客户端、协议/会话桥、Node 前端与 headless
├── service/          # 常驻会话、UDS/SSH 连接、显示投影、审计和工作区占用
│   └── backend/      # Java↔Node 私有 UDS 协议、快照、审批证据、设置唯一写者
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
├── memory/           # 全局记忆：短索引 / 后台回顾 / 受限整理 / 反馈
├── tasks/            # 持久任务图：创建 / 依赖 / 认领 / 完成与解锁，六工具由父循环使用
├── team/             # 常驻队友、独立派工编号、文件邮箱与结果提交
├── mcp/              # 真实 stdio 服务连接、工具发现与文本调用；官方 Java SDK 隔离层
├── workflow/         # 可信预设编排、有界模型步骤、schema 校验、journal 与显式续跑
├── goal/             # 会话目标状态、独立完成判断与同轮继续的决策
├── cron/             # CronProducer 定时生产、时间规则、待交付状态与工作区持久化
├── session/          # SessionRuntime(单消费者、历史、记忆时机、服务关闭) / SessionInbox / SessionEvent
└── agent/            # AgentExecutor(模型请求、工具批次、压缩步骤及嵌套结果)
```

仓库根 `frontend/` 是 Node/Pi 终端前端（固定 vendor、主题/品牌唯一运行资源）；交互客户端由 Java 启动并监督它；Harness 由本机或服务器上的常驻 Agent 服务持有。

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
| s15 | 既有 Harness 集成与整体核对 | `bootstrap/JadeCodeFactory` `session/SessionRuntime` `agent/AgentExecutor` |
| s16 | Workflow Runtime | `workflow/` |
| s17 | Goal Loop | `goal/` |

## 配置

默认从 `~/.jadecode/config/model.json` 读取模型配置，模板见 [model.example.json](model.example.json)。
首次配置时在仓库根执行下面的命令，再编辑文件中的 apiKey、baseUrl、model；目标已存在时创建会失败，直接编辑已有文件即可：

```sh
mkdir -p ~/.jadecode/config
(umask 077; set -C; cat model.example.json > ~/.jadecode/config/model.json)
```

文件默认权限为 `600`（仅当前用户可读写）；加载器只读，不自动迁移 Key 或修改已有文件权限。
JSON 字段为 `apiKey`、`baseUrl`、`model`、`maxTokens` 和可选 `provider`，其中 `maxTokens` 接受 `"auto"` 或正整数。
provider 缺省为 `anthropic-compatible`，保留原端点与模型默认值；文件可省略 apiKey 并通过进程环境提供，显式空白字段会报错。

此后在任意项目目录调用 JadeCode 的启动脚本即可，工作区仍为当前目录。也可用
`jade --model-config /path/to/model.json`（或 `--model-config=...`）选择另一份 JSON；支持交互、headless 和 resume。
相对路径以启动目录为基准，显式文件取代全局文件，不跨文件补 Key。配置在启动时固定，修改后需重启。

JSON 路径下，进程环境变量 `ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL` / `MODEL_ID` / `MAX_TOKENS` / `MODEL_PROVIDER` 覆盖文件，再补所选 provider 的默认值。
改变端点或 provider 时必须在同一环境覆盖中提供 Key，避免借用文件中另一服务的凭据。任何情况下都不读取 `.env`，也不查找项目或父目录中的旧配置。
未指定文件且全局 JSON 不存在时，可仅使用进程环境变量；缺少认证则直接报错。指定文件缺失、JSON 无效或全局文件不可读时明确失败。
详细优先级与验收见 [全局模型配置指南](docs/notes/s13/global-model-config-review.md)。

OpenCode Go 只适配 **`deepseek-v4.1-flash`**。可以单独创建一份模型配置：

```json
{
  "provider": "opencode-go",
  "apiKey": "replace-with-your-opencode-go-api-key",
  "baseUrl": "https://opencode.ai/inference/go/anthropic",
  "model": "deepseek-v4.1-flash",
  "maxTokens": "auto"
}
```

**配置中必须有 `"provider": "opencode-go"`**；只更换 Key、baseUrl 和 model 不会启用 Go 的认证与会话头。
官方 Go URL 若未搭配此字段，启动时会直接提示修正，不向服务端发送缺少会话头的请求。
填写属于 Go 订阅工作区的 Key，文件权限设为 `600`，再用
`jade --model-config /path/to/your-go-model.json` 启动；上面的示例不包含可用凭据，程序不自动覆盖已有配置。
Go 缺省基址为 `https://opencode.ai/inference/go/anthropic`，不要追加 `/v1/messages`；
客户端发送 Bearer、自身 User-Agent 和稳定会话头，利用 [Console 的 Messages 转换](https://opencode.ai/v2/docs/console/harnesses/#base-urls)。
用户已确认本次 Go 切换与交互启动恢复正常；各思考档位的实际效果仍需专项验收，未测试其它 Go 模型。

该入口的 `/thinking` 仅提供 `default / low / high / max`。default 完全省略额外思考参数，
不猜成 high；off/medium/xhigh 明确拒绝，不静默换档。原 `config.json` 若显式设置了不支持的全局档位，
应先改为 default 或可用档位。辅助请求沿用各自原有策略；不支持 off 时保留默认，
小输出额度若被思考耗尽会如实失败，不采用半份审批、摘要或记忆结果。

Go 公共目录目前未提供 token 限额，启动不会借用直连 DeepSeek 的参数。
需要自动压缩或自动最大输出时，在 `config.json` 的 `modelMetadata.overrides["deepseek-v4.1-flash"]`
中填写由服务商确认的 `contextWindowTokens`、`maxInputTokens`、`maxOutputTokens`，各字段为正整数且可省略。
不填时未知输出按下文回退 8000，未知窗口会明确提示自动压缩不可用。
主会话及其辅助请求沿用同一会话 UUID，常驻队友独立；Go 恢复要求原供应商、Messages 基址和模型一致。
无后端身份的旧历史应新建会话后使用 Go。会话格式升为 5，继续读取 1–4；旧二进制不能继续写新格式日志。
离线发行验证命令为 `python3 scripts/verify-release.py --scenario opencode-go`；它只访问本机夹具，不调用真实模型。

`MAX_TOKENS` 缺失、为空或设为 `auto` 时，正式对话（包括队友、一次性子代理）使用已知的模型最大输出额度；
未取得最大输出字段时明确回退到 8000 token，不能用上下文窗口代替输出上限。
也可设置正整数作为显式额度，仍受已知模型最大输出限制；0、负数和其它文本会报错。
JSON 中的 `maxTokens: 8000` 或进程环境中的 `MAX_TOKENS=8000` 都视为显式设置，改为 `auto`（或删除该项）才启用自动策略；重启生效。
摘要、Memory、辅助审批保留各自独立预算，不随正式对话额度放大。

界面与压缩设置统一放在 `~/.jadecode/config/config.json`，完整模板见
[config.example.json](config.example.json)。缺失字段使用默认值，修改后重启生效，非法布尔值、数值或主题会明确报错。
该 `config.json` 不包含 API Key，模型和认证只走独立的 `model.json` / 进程环境。

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
已知最大输出对正式、摘要、记忆及审批请求统一封顶；只有正式请求在 `MAX_TOKENS=auto` 时自动采用上限，
显式额度与内部辅助请求不会被自动提高。
上下文压缩与状态栏共用 Token 投影：优先采用本条历史的正式响应 usage，追加部分本地估算。
状态栏 `ctx ~` 表示包含估算；没有 `~` 表示尚无新增内容的服务端锚点，窗口未知则不展示比例。

旧 `tui.json` 的主题会先备份再迁入新文件；新主题优先，原文件保留但不再写入。
主题通过 `/settings` 的 Theme 子流程选择；只保存 `tui` 白名单，不会清除压缩设置或用户其它字段。旧 `/theme` 命令别名已移除。
消息标题、执行状态与辅助信息分段配色；代码围栏按语言高亮，工具结果折叠偏灰、展开使用正文色。效果与阅读入口见 [终端配色指南](docs/notes/s13/tui-semantic-colors-review.md)。
审批结果在终端右上角显示为紧凑临时浮层，只保留最新一条，五秒后收起；结果标签、工具名、范围与参数摘要分层着色，不追加聊天历史。见 [审批结果浮层指南](docs/notes/s13/approval-result-toast-review.md)。

`thinking.effort` 设置请求的默认思考强度，缺省为 `default`（不额外发参数）。
`/thinking` 打开选项，也可输入 `/thinking low`；保存的是当前会话覆盖，不改全局默认。
设置从下一次请求生效，恢复会话后继续使用；Ctrl+T 仍只控制是否显示思考正文。
当前适配 DeepSeek 官方端点的 `off / low / high / max`（default 对应官方默认 high），
以及 Anthropic 官方 Claude Opus/Sonnet 4.6 的 `off / low / medium / high / max`。
未适配模型或第三方兼容端点只保留 default，不猜参数支持情况。
新队友和一次性子代理继承创建时设置，已有队友不随 Leader 改动；共享前缀摘要沿用该请求设置，辅助审批保持默认。
记忆只需要结构化正文，后端已适配 OFF 时显式关闭思考，否则保持默认。

生产压缩管线是**新鲜单条大结果转存 → Token 检查 → 旧前缀摘要及近期原文 → 必要运行状态 → 正式请求**。
`~/.jadecode/config/config.json` 的压缩设置为：

```json
"compaction": {
  "autoSummaryEnabled": true,
  "reserveTokens": 16384,
  "keepRecentTokens": 20000,
  "summaryMaxOutputTokens": 32768,
  "summaryMaxInputTokens": "auto",
  "largeResultCharLimit": 50000,
  "largeResultPreviewChars": 2000
}
```

严格超过 `模型窗口 − reserveTokens` 才自动摘要；模型最大输出不参与触发预留，
正式请求输出另受实际剩余空间约束。未知窗口时提示自动压缩不可用，不回退到字符门槛。
近期原文按完整消息保留约 20K Token，工具交换整体保留，最新未读交换受保护。
手动 `compact` 复用同一管线；没有可安全摘要的旧前缀时不改历史。

摘要输入 auto 预算为模型窗口减本次摘要输出和 1024 余量，并受已知最大输入封顶；
例如 1048576 窗口、32768 摘要输出时为 **1014784 Token**，包括 system、工具、消息和摘要指令。
优先共享正式请求的 system、工具和消息前缀；超过预算才按完整交换连续分块，覆盖全部待摘要材料。
摘要工具调用、截断或空正文均失败，不采用半份历史，也不隐藏重试。近期原文加摘要若仍放不下则报错。
压缩后携带当前目标、Todo 和待接收后台工作编号；工具/技能目录由当前请求提供，不批量重读文件。

首次读取旧 `compaction.limits` 或课程开关时先校验、备份，再迁移成上述字段，保留其它配置；
旧默认摘要输出 8000 升为 32768，其它显式输出值保留。原课程 snip/micro/fit/聚合预算及响应式实现
保留作显式离线教学验证，不由生产配置启用。`read_file` 仍参与单条结果转存。
本地估算不是精确 tokenizer，无法承诺服务端绝不拒绝；关闭自动摘要时仍检查已知输入/窗口限制。
阅读入口见 [Token 上下文管理](docs/notes/s08/token-context-production-review.md)。

## MCP 与 Fetch

s14 使用官方 Java MCP SDK 接入独立 stdio 服务。Fetch 是现成的 Python 服务，
负责读取公开网页并转换为 Markdown；MCP 服务本身不按调用收费，模型读取正文仍消耗 token。

Docker 沙箱启用时，Fetch 已随沙箱镜像安装，连接不使用宿主 Python venv。
下面宿主安装步骤适用于显式关闭 Docker 接线的运行方式。自定义服务的 command/args
需能在镜像或已挂载工作区中执行；`networkAccess` 控制该服务的网络，内置 Fetch 默认开启，其它服务默认关闭。

先准备 Python 3.10+（含 venv），在 checkout 中执行：

```sh
./scripts/install-fetch.sh
```

脚本按 `scripts/fetch-requirements.txt` 在 `~/.jadecode/mcp/fetch/` 创建固定版本的独立 Python 环境，
不改系统 Python 或用户模型配置。多个项目共用该环境；移动项目无需重建，安装环境本身不能直接搬运。
Fetch 未安装不影响 Maven 构建及其它工具。
脚本面向 POSIX 环境；Windows 原生环境可用 `python -m venv` 与同一 requirements 安装，并配置实际的 `Scripts/python.exe`。

Fetch 默认启用：没有 `mcp.servers` 配置时，JadeCode 自动采用以下定义并提供 `connect_mcp`，
安装完成后无需手动合并配置。旧用户配置缺少该字段时同样生效；重建 JAR 并重启后使用新的默认值。
`config.example.json` 省略 `mcp` 字段以采用默认值；导出的完整模板使用当前用户的全局绝对路径。
下面 `<user>` 需换成实际用户名；如需自定义，可将以下对象写入 `~/.jadecode/config/config.json` 的 `mcp` 字段，保留其它设置：

```json
{
  "servers": {
    "fetch": {
      "enabled": true,
      "command": "/home/<user>/.jadecode/mcp/fetch/bin/python",
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

可在 `./jade` 创建的交互会话中输入：

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

## Workflow

s16 新增 `Workflow` 工具：模型选择宿主登记的流程名称与参数，Java 代码负责固定编排。
当前内置两个预设：

| 名称 | 输入与用途 |
| --- | --- |
| `review-changes` | 变更文本；正确性／安全／性能／风格四维审查，逐条复核与汇总 |
| `code-review` | 完整文件/关联模块与需求资料；正确性／安全／异常资源／职责可维护性审查，核对原文位置、逐条复核、分开报告疑点 |

步骤只分析输入文本，无文件/Shell 工具；模型复核不等于实际编译、测试或问题复现。

在 `./jade` 中可要求：

```text
使用 review-changes Workflow 审查我提供的变更文本，把文本传入 args.changes。
只分析这段内容，不修改项目文件、不安装依赖。返回 run_id 和最终结果。
```

模型工具参数为 `name`、可选对象 `args`、可选 `resume_from_run_id`，不接受可执行代码。
`review-changes` 的 args 要求非空 changes 字符串（最多 128000 字符），可选正整数 budget。
`code-review` 使用 `args.files=[{"path":"标签","content":"完整文件文本"}]`，可选 `args.context` 与 budget。
主会话先读取文件再传入；path 不触发 IO，行号相对于所传 content 从 1 开始。
新预设返回含位置/引用/触发/影响/建议的 confirmed、未确认 unconfirmed、否定/合并数量和 analysis_only=true；
引用不符、上下文不足或复核矛盾都不进入确认列表。确定性去重保留不同问题，不声称能识别所有语义重复。
每维最多 3 条发现；空 confirmed 或运行 completed 都不等于整体审查通过。
最多 16 份文本，每份最多 64000 字符；路径/正文/context 合计最多 128000 字符、8000 行，编号编码后最多 220000 字符。
更详细的字段、边界和真机步骤见 [code-review 指南](docs/notes/s16/code-review-review.md)。
得到真实 run_id 后可要求恢复同一流程并省略 args；原输入、流程版本、模型端点/名称、输出额度和思考设置必须保持一致。
两个预设现使用版本 3：固定参考边界、共用资料、同阶段规则和输出 schema 排在前面，变化的维度和候选排在后面，以改善缓存复用。
版本 1/2 的旧 run 文件保留，升级后续跑会明确拒绝版本不匹配；需要审查时启动新 run。

工具同步等待整体结果，内部默认最多 8 个并发模型尝试、1000 个步骤／真实请求，含结构修正与一层嵌套。
每步沿用已解析的正式输出额度（含模型上限），不再额外压到 2000；思考也可能消耗输出额度。
结构不合法最多修正一次；截断明确报告步骤/max_tokens/有效额度且不缓存，不按格式错误自动重放，普通 Workflow 失败不封锁会话。
使用明确支持的 JSON Schema 子集进行宿主校验，不是服务端强制生成或完整 JSON Schema 引擎。

运行文件放在 `~/.jadecode/workspaces/<workspace-id>/workflows/<run_id>/`，包含 snapshot.json、journal.jsonl、output.json 与 run.lock。
只有已成功落盘的步骤可以复用；恢复缓存命中不再次调用该步骤模型，主会话选择工具和汇总仍可能产生请求。
工具结果 usage 统计本次执行，缺失或仍在途的用量明确标记；budget 是本次执行按报告检查的观察预算，
并行请求可能超额，多次恢复也会重新计数，不是绝对账单上限。总体会话用量另在 WORKFLOW 分类持续记录。
关闭会话会取消所属 Workflow，不自动恢复；崩溃在请求完成与 journal 落盘之间时仍可能重调。
已完成 run 的恢复失败会保留旧成功产物，并返回 output_preserved=true，不能把旧 output_file 当成本次成功。

工程证据见 [s16 任务单](docs/jobs/s16/workflow-runtime.md)，阅读顺序、问题和用户独立真机步骤见
[s16 指南](docs/notes/s16/workflow-runtime-review.md)。
并行取消/共享日志修复与旧会话恢复步骤见 [Workflow 故障恢复](docs/notes/s16/workflow-failure-recovery.md)。

## Goal Loop

s17 新增会话级 `/goal`：主模型准备结束时，独立、无工具的判断请求检查已有对话证据是否满足条件。
未满足则把具体理由送回同一任务继续；原必等后台与 Stop hook 先处理，目标满足后再沿原 Memory/日志路径收尾。

```text
/goal 修复登录问题，直到登录相关测试退出码为 0，并且没有修改测试文件
/goal
/goal clear
```

设置条件即开始工作；每个 Leader 会话一个目标，新条件替换旧条件，忙时排在当前任务之后。
查看与清除本地完成，不调用模型；小写 stop/off/reset/none/cancel 也可用作 clear 别名。
清除只停止 Goal 推动，不撤销已开始的请求/工具，也不取消已排队的新目标命令。
CLI 首轮与无界面模式同样支持，例如 `./jade --headless "/goal 阅读指定文件并准确报告内容，不修改文件"`。

判断沿当前模型客户端，输出额度取正式额度与 512 的较小值，独立计入 GOAL 用量。
只读取最近最多 24000 字符的对话资料，不发送思考/签名；关键证据被压缩/裁剪后不会自动找回。
明确要求 ok/reason/impossible 三个字段；坏结构、截断、拒绝、缺失停止原因或请求工具均明确失败，不自动重试或假成功。
判断器仍可能误判，实际测试和工具证据仍必不可少；Workflow completed 不直接等于 Goal 已达成。

目标驱动的每个大轮次最多 100 个主循环请求步骤、8 次未满足后的自动继续；这些不是 HTTP 尝试或费用上限。
判断错误/达到上限保留 ACTIVE 并交回用户；已满足为 ACHIEVED、无法完成为 IMPOSSIBLE、主动清除为 CLEARED。
无界面未完成失败不打印候选回答，退出码为 1。没有活跃目标时沿原行为，不增加判断请求。
目标状态保存为会话 v3 元数据，兼容读取 v1/v2；旧二进制不保证读新 v3。
恢复聊天只恢复目标状态，活跃目标重新计数/时间，历史总用量保留；不会自动重放旧任务，等待新用户输入。

工程证据见 [s17 任务单](docs/jobs/s17/goal-loop.md)，完整阅读顺序、问题与真机步骤见
[s17 指南](docs/notes/s17/goal-loop-review.md)。

## 持续集成（CI）

[GitHub Actions 工作流](.github/workflows/ci.yml) 在所有分支 push、PR 和手动触发时检查源码，使用两个独立的 Ubuntu 24.04 作业：

- **Java 21 / Maven**：配置 Temurin JDK 21 与 Maven Toolchain，运行 `mvn --batch-mode --no-transfer-progress clean verify`，完成编译、JUnit 测试和 JAR 打包。
- **Node 24 / Frontend**：在 `frontend/` 按顺序运行 `npm ci --no-audit --no-fund`、`npm run build`、`npm run typecheck`、`npm test`，检查 Pi vendor、前端类型与 Node 测试。

推送后到仓库 **Actions → CI** 查看两个作业及每步日志；PR 页面也显示检查结果。手动入口需要工作流先进入默认分支。同一分支/PR 的新运行会取消同事件类型的旧运行。测试不需要真实模型 API Key；依赖缓存只加速下载，不跳过检查。

本阶段只做 CI，尚未发布发行包、部署或配置强制合并规则；Java-only 打包也不代表成套交互发行验收通过。首次 GitHub runner 结果须在实际推送后确认，本地验证记录见 [CI 任务单](docs/jobs/engineering/ci.md)，学习入口见 [CI 阅读指南](docs/notes/engineering/ci-review.md)。

## 运行

```sh
mvn -q package                              # 构建(直接跑即可,无需设 JAVA_HOME)
mvn -q test                                 # 单元测试
./jade                                      # 交互（Node TUI）
./jade "任务"                                # 新会话并提交首轮
./jade --headless "任务"                     # 无界面，stdout 只有最终回答
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

全屏模式支持应用滚轮、PageUp/Down、Ctrl+Home/End、选区复制和右上角浮层搜索（Ctrl+R，兼容Ctrl+Shift+F；上下键或Ctrl+G/Shift+Enter切换匹配并立即定位正文，Enter确认关闭，Esc 关闭）。
`Ctrl+O` 在空欢迎页展开资源，在对话中展开/收起工具卡；`Ctrl+G` 外部编辑器；`Ctrl+V` 读取文字或图片剪贴板（图片保存为临时文件并插入路径，仍只是文本输入）；
`Ctrl+J` 可在不区分 Shift+Enter 的终端换行；`Ctrl+X` 复制选区或最近助手回复；`Ctrl+Z` 暂退到 shell，`fg` 后重绘；`Ctrl+C` 清草稿，500ms 内再按退出；空编辑器 Ctrl+D 退出。
Esc 优先关闭本地菜单/搜索/候选；审批时拒绝当前申请；普通对话中有运行任务时，取消当前轮次（与 `/stop` 共用入口，保留草稿和会话）。空闲时不发送取消。regular 模式不捕获鼠标，滚轮/拖选交给终端。
退出时恢复终端并打印一次 Session/ID/Resume；fullscreen 默认把最终转录重放到主屏，`tui.fullscreenExitOutput=resume-hint` 时只保留恢复提示。

命令只列真实后端：`/settings /name /team /session /hotkeys /copy /new /resume /stop /close /quit /model /thinking /permission /mcp /goal`。
旧 `/help`、`/exit`、`/usage`、`/theme`、`/rename` 别名已移除；未知 slash 留在本地报英文错误并保留草稿，不发给模型。
`/model` 只读；`/mcp` 只读服务/工具清单，无连接/配置/OAuth 按钮；缺取消/树/分叉/模型热切换/扩展管理等后端的入口统一隐藏。
审批以“仅本次/允许此类/拒绝”接管 dock，显示完整真实参数和长期范围，不显示内部 reason；默认无选择；鼠标首次点击只选中，再点同项或按 Enter 才确认；卡片与输入栏同宽，窗口无法安全显示审阅区时不发布批准动作。
新鲜 Esc 拒绝当前审批；未选择时 Enter 和普通文字不批准；旧画面、旧输入水位、resize 或 revision 变化都会使旧批准失效。

颜色尊重 `NO_COLOR`/`TERM=dumb`；只有受信编码器能输出 SGR/OSC8/终端协议，模型与工具正文先净化。
全屏退出/窗口宽度变化可能清空并重放 scrollback，这是终端可寻址边界的正常行为。
Markdown/高亮/Mermaid 由固定 Pi 的 Node 前端渲染；Java 只发送原始正文，不再保留旧 Java/Graal 解析与排版实现。
真实 SSH/IME/图片终端/模型流仍需人工验收。详细限制见 [当前工程收尾报告](docs/tui-pi-replication/10-node-frontend-migration/handover/finalization-report.md) 与 [逐项台账](docs/tui-pi-replication/10-node-frontend-migration/handover/final-acceptance-matrix.md)。

Windows Terminal/VS Code 的 Shift+Enter 与 tmux OSC52 接线见 [终端接线说明](docs/tui-pi-replication/10-node-frontend-migration/handover/terminal-integration.md)。SSH使用OSC52，tmux使用load-buffer -w向当前会话客户端发送；成功写出显示短暂Copied!，客户端最终接收仍取决于终端支持。

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
./jade --permission auto-review
./jade --headless --permission workspace "任务"
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

生产配置默认使用 Docker 运行时沙箱，安装和边界见下文。shell 检查器只解释 `/bin/sh` 的有限语法，
审批不能证明一般程序的真实副作用；容器在执行时限制实际文件、网络和资源访问。
这些档位检查模型调用的操作，不阻止日志、压缩归档和记忆等 Harness 自身的状态保存。
服务统一管理设置写入，规则保存使用进程内互斥；服务之外的独立写者不共享协调。外部手改请停止服务后重启，运行中不热加载。

### Docker 运行时沙箱

首版支持非 root 用户使用本机 Linux Docker daemon（Unix socket、seccomp、cgroup 和递归只读挂载）。
先在 checkout 中准备包含 Java 21/Maven、Node 24、Python、git、rg 和固定 Fetch 的镜像：

```sh
mvn -q package
sh scripts/build-sandbox.sh
```

脚本只向构建器提供 JAR、Dockerfile、init.py 和 Fetch requirements，不上传整个项目。
首次构建需要下载基础镜像和包；运行工具不会自动拉取或构建镜像。缺失镜像、daemon 不可用、
协议不匹配或路径策略无法表达时返回明确错误，不自动改用宿主。镜像在首次使用时固定到本地 sha256 ID。

`sandbox` 设置见 `config.example.json`：默认内存 2048 MiB、2 CPU、256 PID、256 MiB 临时目录，
最多 8 个实例、保留 2 个闲置实例，空闲 600 秒后回收。限额是上限，不是启动预分配。
普通 Workspace/Approve for Me 使用相同工作区边界；Read Only 将工作区挂为只读。
Full Access 明确选择宿主文件/Shell 执行；`sandbox.enabled=false` 显式关闭整套 Docker 接线。
MCP 始终使用独立的只读工作目录服务容器；权限版本变化时关闭旧连接，重新连接后采用新视图。
显式 READ deny/ask 对服务保守遮蔽，不能通过 RPC 临时解除；WRITE 由整目录只读保证。

容器使用非 root UID、只读系统根、cap-drop/no-new-privileges/seccomp，默认断网。
工作区以原绝对路径挂载，模型命令无需字符串替换。已有敏感文件/目录被遮蔽，Git/Agent/编辑器元数据
扣回只读；宿主 socket/FIFO/设备入口被遮蔽。额外访问需要授权；目录级允许不自动开放其中的敏感入口。
读写工具与 Shell 使用同一可见视图；`glob` 可传 `root` 搜索已授权的外部目录。

外部目录在任务目标中明确出现，或同一仓库第二个不同文件被读取时，由 Harness 主动提出任务级只读申请。
批准后逐文件调用复用同一任务环境；额外挂载绑定 run/actor，纯工作区基线可在同一会话跨任务复用。
挂载、网络、保护路径或读写策略变化时使用另一环境。
额外文件、临时写入和联网使用独立的调用级实例。`bash` 可通过 `access_paths=[{"path":"绝对路径","write":false}]`
声明脚本需要的资源，通过 `network_access=true` 请求本次联网。联网使用 Docker bridge，
不是域名/私网白名单，也不会提供模型 Key、宿主 HOME 或凭据。当前任务授权在内存中保存，恢复历史不恢复授权。

镜像层与 `~/.jadecode/workspaces/<项目标识>/sandbox/cache/` 分别保留工具链和项目缓存。
Maven 使用镜像中的全局 toolchains.xml，不读取宿主 settings.xml；Harness 不向模型可写缓存内写配置。
修改工作区和缓存不会因删除容器而回滚。
后台命令单独持有容器，等待结果时文件操作仍可运行；活跃执行和 MCP 服务不按空闲淘汰。
任务结束关闭新入口，已接收的后台操作保留原执行租约至结束；会话关闭主动终止。
超时或输出不完整时销毁所属实例，不自动重放。启动时仅回收同 UID 且确认原进程已结束的 JadeCode 孤儿实例。

限制：不抵抗同权限宿主进程恶意并发替换挂载源，不支持硬链接工作区、逗号/换行挂载路径、整个 HOME/顶层系统目录
或工作区与 Harness 数据目录重叠。显式保护目标不存在或跨符号链接、祖先保护不能精确表达时拒绝执行。
默认敏感入口保护基于每次执行前的目录视图，不能保证阻止当前命令新建所有同名配置；
项目本身可写，也不能据此承诺防止损坏项目源码或后续在宿主执行被修改代码的风险。
日志与 tmpfs 有界，工作区/持久缓存尚无磁盘硬配额；缓存保留供复用，需要单独维护，不随容器回收删除。
容器也不是多租户虚拟机安全边界。任务与验证证据见 [s18 工程任务单](docs/jobs/s18/runtime-sandbox.md)。

### 会话保存与恢复

```sh
./jade --resume                              # 选择当前工作区的历史会话
./jade --resume=<完整 UUID>                   # 直接恢复
# TUI 中使用 /resume <会话名称>，重复时选择
```

REPL 默认新建并保存主会话。`/resume` 打开可筛选选择器，
`/resume <完整 UUID>` 直接切换；当前会话和没有正式记录的空会话不列入候选。
候选仅显示会话标题与距上次使用的时间（刚刚、分钟前、小时前、天前），不显示 UUID 或长时间戳。
内部仍按 UUID 选择，同名同时间的会话也分别保留；相对时间在打开列表时计算。
有运行任务、排队消息、未收后台结果或未关闭队友时拒绝切换，不暗中取消工作；启动选择器 Esc 退出，
会话内选择器 Esc 返回原会话，输入草稿保留。两个入口共用加载器，不支持跨工作区或按任意文件路径加载。

记录保存在 `~/.jadecode/workspaces/<workspace-id>/sessions/<uuid>/`，包括 session.json、events.jsonl 和 session.lock。
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
- [ ] s15:主体集成已具备，整体核对、截断保护与阅读指南已交付；用户理解与独立真机验收待完成
- [ ] s16:Workflow 工具、可信预设、编排、结构校验、运行记录与续跑已接入；工程验证与指南已交付，用户理解和独立真机验收待完成
- [ ] s17:Goal 命令、独立完成判断、同轮继续、失败上限与状态恢复已接入；工程验证与指南已交付，用户理解和独立真机验收待完成

s15 从现有装配、会话事件、工具批次与结束判断串联前面各章，不重复建设主循环。
课程差异、验证与边界见 [s15 任务单](docs/jobs/s15/integrated-harness.md)，
整体源码阅读顺序和问题见 [s15 指南](docs/notes/s15/integrated-harness-review.md)。
正式行动回复若明确以 `max_tokens` 截断，父/队友与一次性子代理会拒绝采用和执行其中工具，
不会把半份回复当正常完成，也不自动提高额度或续写；之前轮次已发生的副作用不回滚。

记忆保存在 `~/.jadecode/memory/`，按真实工作区路径哈希区分项目，并保留全局作用域。
任务入口注入当前项目和全局的短路由（`memory_summary.md` 对应分区），主模型通过 `read_file`
按需阅读 `MEMORY.md` 和 `rollout_summaries/`；召回不再额外请求选择模型，也没有向量检索。
system 只保留稳定参考边界，路由作为带内部来源的参考消息追加；参考不是用户指令。
会话格式为版本 5，可读取版本 1–4；新版写入后不要用旧程序继续写该会话。

用户轮次真正结束、失败或会话关闭中断时，登记持久作业；单纯断开客户端不触发轮次结束。后台从已落盘事件区间读取证据，
排除思考、压缩替代历史、旧记忆、技能正文和后台通知；忙时真实用户补充单独保留。
文件工具读取项目指令时只保留成功/失败标记，不复制正文；被过滤或未观测不能推断为“从未读取”。
整理时还从当前工作区日志区间提取独立的用户原话，与辅助模型总结对照；该证据单独脱敏并受累计预算限制，
缺失或截断明确标记为未知，避免把助手实现选择写成用户要求。
无日志的嵌入调用使用有界快照。输入有预算，超出时明确标记遗漏，不保证无限会话完整进入模型。
提取采用 Codex 的任务回顾提示语义：忠实任务回顾，保留条件和不确定性，不把教学演示泛化成稳定偏好。
仅接受两个字符串字段 `rollout_summary` / `rollout_slug`，同时为空是正常无产出；拒绝额外字段、
围栏、截断、拒答和不完整 JSON。回顾最多 9000 UTF-8 字节，超限拒绝而不静默截断。

新回顾或用户笔记变化才触发独立整理，不再等待十条记录。整理器只有 `read_source` 和 `stage_memory`，
不能执行 Shell 或任意文件写入；正常结束后，Harness 校验候选、来源、作用域和版本，再事务发布
手册、短索引与 `memory_manifest.json`。其它项目分区由程序保留；失败保留旧版与恢复备份。
整理输入优先提供新增来源元数据，旧来源按允许清单读取；可核验的既有全局纠正引用可以跨项目沿用，
未发布的其它项目候选仍不可访问。内容准入依据“能否影响未来行动、能否从代码/Git/项目指令重建”，
保留外部约束、决定理由和明确反馈，排除教程、临时标签及执行流水账。短路由只导航，正文按事实/理由/适用范围归并。
没有知识增量时仅确认处理清单，不重写手册与短索引；已有噪声可以在后续整理中移除。语义判断仍由模型完成。
主会话的 `memory_note(kind, body)` 仅提交 remember/correct/forget 候选，附上后端绑定的真实用户请求，
由整理器核对；工具返回成功只表示候选已入箱。forget 从正式知识中退役，不是抹除来源和备份。

读取反馈来自成功的后端 `read_file`；引用需匹配本轮已读路径，表示模型声明引用，不证明内容正确。
反馈独立追加并按事件 ID 去重，在下一次材料变化的整理中汇总，不因每次读取重写全库。
普通文件工具和可解析 Shell 的正式记忆写入被权限层拒绝，显式读 deny/ask 仍优先。
Docker 模式下任意程序和复杂 Shell 同样受挂载边界约束；显式宿主执行路径仍只有应用内检查。
来源保留日期、任务结果和哈希，提示后续核对易变事实；目前没有按年龄自动淘汰记忆的定时器。

后台维护使用跨进程锁，一轮最多提取两项；失败按 1/2/3 分钟退避，单项最多尝试三次。
失败计数、作业与已提取结果落盘，重启可续办；只处理当前打开项目的作业。
`state/jobs/` 的 `phase=extraction`、`status=done` 只表示提取完成；整理的 `running/retry/exhausted/completed`
另保存在 `state/maintenance-<scope>.json`。只有发布基线已确认才标为 completed，`content_changed` 区分正文变化与正常无变化。
`--headless` 等待日志与作业登记后退出，不保证退出前整理完成；同一工作区下次启动继续维护。
关闭会话停止新发布，不承诺立即取消网络请求；进程被强杀时，任务结束到作业登记之间仍存在窄窗口。
达到重试上限的作业留在 `state/jobs/`，整理退避留在 `state/maintenance-<scope>.json`，需检查原因后人工重置；
目前没有重试管理 UI。索引、手册和维护输入均有限额，超限保留旧库并告警，不静默丢弃资料覆盖。
整理提示词变更会重新评估已有材料；提取作业的失败上限仍需检查原因后处理。

`memory.enabled=false` 关闭自动记忆，不删除文件。使用 `extractionMaxOutputTokens`、
`consolidationMaxOutputTokens`（默认 8000 / 16384）和 `consolidationInputChars`（默认 128000 字符）。
项目内旧记忆目录不读取或迁移；已有而无法识别的全局手册会拒绝初始化，保留其内容。
子代理和队友不自主维护长期记忆；共享客户端允许辅助请求并行于正式请求，可能增加后端并发和用量。
后台正常失败不覆盖主任务结果。提炼质量仍需真实模型与用户验收，离线替身只证明工程契约。
提取提示来源与阅读顺序见 [记忆系统阅读指南](docs/notes/s09/memory-system-review.md)。

Task System 使用 `~/.jadecode/workspaces/<workspace-id>/tasks/{id}.json`。先创建取得实际 ID，再增加依赖；认领检查前置条件，完成报告刚解锁任务。
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
完成通知不复用原 tool_use_id，也不会改 s10 状态。客户端断开不终止命令；明确关闭会话才停止全部所属命令，明确停止当前轮次会停止该轮所属后台。
默认每条命令 120 秒，最多 32 个运行中或尚未确认接收结果的后台命令；通知只保留最多 500 字符摘要并标注截断，
没有完整输出文件，需要可靠产物时由命令显式写入工作区。
宿主 shell 生命周期面向 Linux，依赖 `/usr/bin/setsid` 与 `/bin/kill`，单独的进程组清理不能约束另开 session 的程序。
Docker 模式另有容器内进程收尾与容器删除；宿主强杀/断电后的残留在下次启动核对归属后回收，
不承诺立即清理。课程阅读见 [s11 指南](docs/notes/s11/background-tasks-review.md)。

Cron Scheduler 提供 `schedule_cron`、`list_crons`、`cancel_cron`，仅 Leader 开放，原一次性子代理仍六把工具。
五段 cron 使用进程本地时间；支持星号、星号步进、数值、区间与列表，星期日为 0，日与星期都受限时取 OR。
REPL 到期入队，在 Agent 空闲时主动发起 `[Scheduled]` 回合；人工与定时回合串行访问同一历史。
`recurring` / `durable` 默认 true；持久定义与 pending 状态保存至项目分区 `~/.jadecode/workspaces/<workspace-id>/cron/jobs.json`。
模型首个合法正式回复表示交付确认，不是业务验收；一次性删除，周期项等待下一次匹配。
进程停止后不执行、不补跑停机分钟；`run` 只管理定义，不开启常驻调度。需要停机也执行应使用外部调度服务。
至少一次交付可能在崩溃后重复；确认前失败退避 5 秒，连续三次暂停本会话自动投递，保留 durable 待交付记录。
审批按 Harness 标注的来源而非线程判断；定时任务和其子代理不能人工询问，不能通过 input 自填来源绕过。
Approve for Me 可独立审查普通询问；仍需人工却没有通道时明确拒绝。TUI 使用三项审批，run 对应输入 1/2/3（y/n 为仅本次/拒绝的简写）；
空 Enter 均不作为审批答复。多行提示词中的批准文字不会被单独取出执行。
收件箱最多 64 项新请求；满时明确拒收，定时项保留 pending，后台完成结果不与新请求争抢容量。
交互客户端退出只断开，服务继续持有会话；非交互执行使用 `--headless`，取得结果后关闭专属会话。
一个工作区的定时库不支持多个 CLI 并发写入。会话重构已通过工程检查和获准代跑的真实 CLI 场景；
检查范围、模型未按要求传参的重测及终端修复记录见 [验收补查](docs/jobs/s12/terminal-and-acceptance.md)。
当前学习入口见 [事件化运行指南](docs/notes/s12/session-event-runtime-review.md)；
[原 s12 指南](docs/notes/s12/cron-scheduler-review.md)保留重构前的学习记录。

Team 使用 `spawn_teammate`、`assign_task`、`send_message`、`submit_task_result` 等工具，
最多三个常驻队友；新成员从空历史开始，后续沿用自己的历史，不 fork Leader。
`spawn_teammate.role` 首行为 1–40 字符的英文显示名，后续行为职责，例如：

```text
Explorer
Inspect source and report evidence.
```

`assign_task.wait_for_completion` 默认 true：Leader 可以先做其它工作，收尾前由会话等待本轮必要报告，
报告到达后恢复模型；显式 false 才让该派工脱离本轮等待。交付失败、执行异常、关闭与缺少正式报告均明确通知，
不把队友空闲当作完成。最多 32 项尚未收讫的必要派工，报告有独立接收槽。

每次派工保存原执行编号、任务标题和独立 `scope` 快照；输入使用 `read_paths` / `write_paths` 字符串数组，
缺省为空，路径相对工作区，总路径预算 4096 字符。范围随执行上下文传给子代理与后台命令，
不反复修改 system；当前权限仍由既有 Gate 决定。后续派工不会覆盖历史记录或旧命令的范围。
模型提供短摘要与长正文，`~/.jadecode/workspaces/<workspace-id>/teams/<team-id>/messages/` 保存正文，
`pending/` 保存待投递记录。队友报告默认可以插入并唤起 Leader，普通进度默认不唤起。
`send_message.reply_to` 关联原问题，成功投递后才算答复；`review_task_result` 记录 Leader 对具体报告的核对说明，
不改变现有 completed 满足任务依赖的契约，也不表示自动证明工作正确。

TUI `/team` 查看成员与派工详情，左右切换近期派工、Enter 显式读取完整报告、PgUp/PgDn 滚动；
一行概览显示 `Waiting`、`Pending reports`、`To review` 与 `Open questions`。
界面展示每个成员最近八项加当前执行派工；全部派工与范围仍留在磁盘，不承诺跨进程恢复执行。
队友启用独立压缩与后台，不启用 Memory/cron；Docker 模式下各身份的授权容器共享工作区，
派工文件范围仍是协作约定，不提供队友之间的独立 worktree 或目录写隔离。
交互与 `--headless` 都会等待本轮必要报告；交互客户端断开后会话继续运行，明确关闭会话才关闭全部队友与命令。headless 取得结果后关闭其专属会话。
工程任务见 [s13 任务单](docs/jobs/s13/agent-teams.md)与[Team 协作扩展](docs/jobs/s13/team-coordination.md)，学习入口见 [s13 阅读指南](docs/notes/s13/agent-teams-review.md)。
真实模型的单项与连续三阶段结果见[黑马点评探索评测](docs/jobs/s13/hmdp-multi-agent-benchmark.md)。

## 用户运行数据

JadeCode 的持久数据统一位于用户目录：

```text
~/.jadecode/
├── config/                       # 模型与应用配置
├── service/                      # 常驻服务发现、会话清单和私有审计
├── cache/model-metadata/          # 模型参数缓存
├── memory/                       # 全局记忆，内部区分项目与全局作用域
├── mcp/fetch/                    # 多项目共享的 Fetch Python 环境
└── workspaces/<workspace-id>/
    ├── workspace.json            # 项目真实路径与哈希身份
    ├── sessions/                 # 会话日志、Todo、用量和 Goal 状态
    ├── tasks/                    # 持久任务图
    ├── teams/                    # Team 名册、派工与邮件
    ├── transcripts/              # 压缩前原始消息归档
    ├── tool-results/             # 完整工具大输出
    ├── cron/jobs.json            # 定时定义与待交付状态
    └── workflows/                # Workflow 快照、日志和输出
```

`workspace-id` 为项目真实路径的 SHA-256；同名项目与不同 worktree 使用不同分区，
同一目录的符号链接入口归到同一分区。移动或重命名项目会产生新分区，本阶段不自动重新绑定。
项目源码、`AGENTS.md` 和项目技能仍位于项目目录；shell 和 MCP 的默认工作目录保持项目目录。
终端 IPC、编辑器草稿、剪贴板图片继续使用系统临时目录。

本项目直接采用新布局，不导入旧 `.jadecode/`、`.tasks` 等项目目录，不保留旧路径映射。
旧文件保持原样；新数据的正常跨会话恢复与事务失败恢复继续有效。
模型默认只能读取当前项目的归档、工具结果、邮件正文及 Workflow 最终输出，
显式 deny/ask 规则优先；状态文件通过 Harness 工具写入，通用文件命令不能改写运行分区。
应用权限检查与 Docker 边界共同生效；显式宿主执行路径仍不能限制任意程序的内部访问。
测试注入临时 `JadePaths`；独立 JVM 验证使用专属临时 user.home，不访问真实用户状态。
源码阅读见 [用户数据布局阅读指南](docs/notes/s15/global-runtime-storage-review.md)。


## 本地与远程 Agent 服务

交互与 headless 根入口共用常驻 Java 服务。默认 `jade` 创建会话，已有相同工作区的存活会话时重新连接；
`jade "新任务"` 可以连接该会话并提交新一轮。退出界面只断开，模型、Team、后台命令和 cron 继续由服务持有。
同一会话同时允许一个控制客户端。`/session` 显示实际执行主机、工作区、历史会话 ID 和 Service ID。

```sh
./jade --workspace /absolute/project
./jade --sessions
./jade --attach <Service-ID-or-live-session-UUID>
./jade --stop-session <id>
./jade --close-session <id>
./jade --service-stop

# 远端需要已安装同一成套版本的 jade、配置模型，并准备所需 Docker 镜像。
./jade --remote user@server --workspace /srv/project
./jade --remote user@server --sessions
./jade --remote user@server --attach <id>
```

`/quit` 和正常退出键断开客户端；`/stop` 或普通对话中的 Esc 请求停止当前轮次；`/close` 关闭会话及全部所属资源。
正在审批、无法在编辑器输入命令时，可以从另一个终端使用 `--stop-session` 或 `--close-session`。
`--resume` / `/resume` 用于恢复磁盘历史；连接存活实例用 `--attach`，它不会重建执行栈。
显式更换模型配置或权限启动参数时不会静默覆盖已有会话，请连接后使用对应设置，或先关闭再创建。
headless 保留无人工通道、只输出最终回答的行为；已有交互会话占用工作区时明确拒绝另开 headless 执行。

远程连接使用系统 SSH 的已有非交互认证与主机校验，固定远端命令为 `jade --service-relay`。
工作区、prompt 和配置路径经协议传递，不拼进 shell；配置和模型凭据留在执行机器。
远端 `--workspace` 使用服务器路径；文件名补全也由服务器枚举工作区，不读取客户端同名目录。
两端执行构建握手，不匹配时明确失败；更新程序前应先明确停止旧服务。
服务进程环境在启动时固定，后来 CLI 上的临时模型环境变量不会转发给已运行服务；更换这类环境前请明确停止并重新启动服务。

服务按用户数据根独占启动锁，通过 0700 临时目录内的 0600 Unix socket 接入。
发现文件、会话清单与审计在 `~/.jadecode/service/`，含用户输入与工具参数，不应分享或提交。
同一服务内顶层会话及其 Team/子代理共同持有目录占用；独立会话的相同/嵌套工作区或已知额外可写挂载冲突。
这不阻止编辑器、其他程序、其他服务数据根或 Full Access 脚本访问未声明的宿主路径，也不隔离 Team 内部写入。

首版固定限制：最多 8 份存活会话、32 条握手/控制连接、主会话单轮到 1 小时请求取消、每次人工审批最长等待 1 小时，
每轮已报告输入/输出合计 100 万 token 的软预算（包括辅助请求和队友；在途用量仍如实记账，非计费硬限）。
无控制连接且无队友、后台或待处理输入的会话闲置 30 分钟后关闭；主 Agent 空闲不等于整个 Team 闲置。
每会话最多登记 4096 份提交，每条输入最多 1 MiB；重连显示缓存按约 16M 字符/10000 张卡裁剪并明确提示，
完整已采用历史仍落盘。审批请求在会话内保存，断线只作废旧呈现证据，重连必须重新展示才能答复。

服务重启先核对旧进程身份并回收确切归属的 Docker 容器；无法确认时保留清单并拒绝放行。
旧会话曾可能执行宿主工具时，需要先人工检查残留进程和工作区；审计与原清单保持在 service 目录，
不能把删除清单当成已经停止进程。历史恢复不重新执行旧工具，未知结果保持中断事实。
空隔离工作区、Agent 按需获取仓库/导入快照、结果交付属于后续阶段。
实现与验证边界见 [Agent 服务任务单](docs/jobs/s18/agent-service.md)。
