# JadeCode

[![CI](https://github.com/fishandrice000000/JadeCode/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/fishandrice000000/JadeCode/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Node ≥22.19.0](https://img.shields.io/badge/Node-%E2%89%A522.19.0-green)

**基于 Java 21 的终端 coding agent，支持工具执行、多 Agent 协作与本地／远程会话。**

JadeCode 将模型的决策连接到文件、Shell、权限审批与任务状态：模型决定下一步行动，Harness 负责执行、约束和记录。Java 后端持有会话与 Agent，基于 Pi 的 Node 前端提供交互终端。

这是一个从 [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) s01–s17 课程出发的个人学习项目，当前源码版本为 **1.1.0**。功能与平台边界见下文。

## 能做什么

| 能力               | 用途                                                         |
| ------------------ | ------------------------------------------------------------ |
| 终端交互           | 流式回复、Markdown／代码高亮、工具卡片、搜索、权限审批与设置 |
| 文件与 Shell 工具  | 阅读代码、检索文件、修改内容、运行命令与接收后台结果         |
| 权限与 Docker 沙箱 | 四档权限、显式审批，以及默认的容器文件／网络／资源边界       |
| 多 Agent 协作      | 一次性子代理、常驻队友、派工、消息和结果核对                 |
| 长任务支持         | 上下文压缩、长期记忆、持久任务、Workflow 和 Goal Loop        |
| 本地与远程工作     | 常驻会话、历史恢复、SSH 连接、独立工作区和成果下载           |
| 可扩展工具         | 按需连接 MCP stdio 服务、加载工作区自定义 Skill              |

例如，在项目目录启动后可以输入：

```text
检查登录流程，复现失败原因，修复后运行相关测试并报告结果。
```

模型是否正确完成任务，仍需结合实际 diff、工具结果和测试判断。

## 配置与历史格式

当前 `config.json` 必须声明 `"version": 1`，仅接受当前 Token 压缩字段；可选设置仍可局部覆盖。
启动读取不再迁移 `tui.json` 或旧 `compaction.limits`。可参考 `config.example.json` 手动更新配置，程序不会改写或删除旧文件。
会话清单和日志仅接受 v5；自定义主题必须声明 `"schemaVersion": 2` 并包含 dark/light 双变体，缺少颜色角色仍使用语义继承。
Workflow 使用 `workflow-runner-v2` 缓存身份；旧缓存不能恢复，需新建流程运行，原文件保留。

## 快速开始

### 1. 准备环境

当前默认运行路径面向 **Linux**，需要：

- **JDK 21、Maven**：后端构建与运行；Maven Toolchains 必须登记 JDK 21。
- **Node ≥ 22.19.0、npm**：构建及运行交互前端；CI 使用 Node 24。
- **Docker Engine**：默认沙箱使用本机 Unix socket，要求当前用户有访问权限；构建镜像需要网络。
- **Git**：获取源码；远程连接另外需要系统 SSH 和已配置的非交互认证。

```sh
git clone https://github.com/fishandrice000000/JadeCode.git
cd JadeCode
```

在 `~/.m2/toolchains.xml` 中登记 JDK 21；已有配置时合并条目，`jdkHome` 替换为本机实际目录：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides><version>21</version></provides>
    <configuration>
      <jdkHome>/absolute/path/to/jdk-21</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

启动脚本默认使用 `/usr/lib/jvm/java-21-openjdk-amd64`。目录不同时，设置 `JAVA21_HOME` 指向实际 JDK 21；它不会修改系统默认 Java：

```sh
export JAVA21_HOME=/absolute/path/to/jdk-21
```

### 2. 构建应用与沙箱

```sh
# 首次先下载 Maven 构建依赖；成套构建的 Maven 阶段使用离线模式。
mvn -q package

# 安装锁定的 npm 依赖，构建前端和配对 JAR，装配发行目录。
node scripts/build-release.mjs

# 准备默认运行工具所需的 Docker 镜像。
sh scripts/build-sandbox.sh
```

交互模式要求 JAR 与前端来自同一次成套构建。单独执行 `mvn package` 会重新生成未配对 JAR；之后需要再次运行成套构建。`--help`、`--version` 和 headless 模式不依赖前端。

成套产物位于 `dist/jadecode/`，包含启动脚本、JAR、前端及第三方许可证。移动整个目录后可使用其中的 `jade`；执行机器仍需 Java、交互模式所需的 Node，以及对应 Docker 镜像。需要从任意目录调用时，将发行目录加入 `PATH`。

### 3. 配置模型

从仓库模板创建全局模型配置；目标已存在时下面的创建命令会失败，请编辑已有文件：

```sh
mkdir -p ~/.jadecode/config
(umask 077; set -C; cat model.example.json > ~/.jadecode/config/model.json)
```

编辑 `model.json` 中的 `apiKey`、`baseUrl`、`model` 与 `maxTokens`。配置使用 **Anthropic Messages** 格式；默认 provider 为 `anthropic-compatible`，文件权限应为 `600`。`maxTokens` 支持 `"auto"` 或正整数；自动模式在模型上限未知时回退到 8000。

OpenCode Go 当前只适配 `deepseek-v4.1-flash`，配置必须显式包含：

```json
{
  "provider": "opencode-go",
  "apiKey": "replace-with-your-opencode-go-api-key",
  "baseUrl": "https://opencode.ai/inference/go/anthropic",
  "model": "deepseek-v4.1-flash",
  "maxTokens": "auto"
}
```

Go 的此模型采用与 DeepSeek 官方相同的限额。默认使用 [DeepSeek 官方模型文档](https://api-docs.deepseek.com/api/list-models/)的参数快照（2026-10-31 核对）：总窗口 1,048,576 token，最大输出 393,216 token。输入与输出共用窗口，官方未单列固定的最大输入；模型页会说明这一约束。自动压缩沿用既有窗口预留策略，`maxTokens: "auto"` 使用已知输出上限并按当前剩余空间收紧。快照随代码维护，不是启动时的实时查询；关闭 `modelMetadata.autoDiscover` 后仅使用手动覆盖值，覆盖值可收小已知上限。

普通任务默认不设累计 token 预算；累计用量仍会记录，Leader 与各队友按各自上下文窗口进行摘要压缩。

`--model-config /path/to/model.json` 可选择另一份配置。进程环境中的 `ANTHROPIC_API_KEY`、`ANTHROPIC_BASE_URL`、`MODEL_ID`、`MAX_TOKENS`、`MODEL_PROVIDER` 覆盖文件；用环境变量切换端点或 provider 时，必须同时提供对应 Key。程序不读取 `.env`。

模型配置在服务启动时固定。更换配置或相关环境变量前，先明确停止已有服务，再启动新服务。

### 4. 开始任务

```sh
./jade                                      # 打开交互终端
./jade "阅读当前项目，概括入口与关键模块"      # 提交首轮任务
./jade --workspace /absolute/project         # 指定工作区
./jade --headless "检查当前 diff，报告问题"    # 无界面执行
./jade --resume                              # 选择磁盘历史会话
```

仓库内的 `./jade` 使用当前目录作为默认工作区。切换到其他项目时，可使用 `--workspace`，或从该项目目录调用发行版 `jade`。

## 常用操作

| 操作                   | 入口                                                        |
| ---------------------- | ----------------------------------------------------------- |
| 提交／换行             | Enter；Shift+Enter 或 Ctrl+J 换行                           |
| 停止当前轮次           | 普通对话中 Esc 或 `/stop`；Esc 优先关闭菜单或拒绝当前审批 |
| 展开工具／切换思考显示 | Ctrl+O／Ctrl+T                                              |
| 设置／权限／思考强度   | `/settings`、`/permission`、`/thinking`               |
| 查找界面正文           | fullscreen 模式 Ctrl+R                                      |
| 查看队友／当前会话     | `/team`、`/session`                                     |
| 新会话／历史恢复       | `/new`、`/resume`                                       |
| 断开界面／关闭会话     | `/quit`／`/close`                                       |

**退出界面只断开客户端。** 会话中的模型、队友和后台命令继续由常驻服务持有；`/close` 才关闭会话及所属资源。headless 执行取得结果后关闭其专属会话，stdout 只输出最终回答；默认权限为 Approve for Me，仍需人工授权时拒绝，不读取 stdin。

后台命令启动后，Agent 可以继续同一轮次中的其他工作，正常结束前必须采用结果。停止当前轮次会解除该轮等待，已接纳的后台命令仍可继续；其结果会在后续轮次的安全边界加入上下文，不会在空闲时自动发起模型请求。`/close` 会停止所属命令。忙时输入先缓冲，空回车可将原缓冲升级为当前轮次的补充。

Team 派工均等待成功、失败或受阻报告；通信与报告在安全边界插入。成功报告先进入 `submitted`，Leader 用 `review_task_result` 接受后才变为 `completed` 并满足依赖；拒绝则回到 `pending`，保留旧报告与返工意见，由新 Assignment 重派。没有未交付工作、待验收报告及待处理补充时才正常结束。只有显式派工会启动空闲队友，普通消息不自动创建轮次。cron 暂不接入工具注册和运行时。

```sh
./jade --sessions
./jade --attach <id>
./jade --stop-session <id>
./jade --close-session <id>
./jade --service-stop
```

## 权限与运行边界

| 档位              | 行为                                                          |
| ----------------- | ------------------------------------------------------------- |
| Read Only         | 允许已识别的读取；写入、任务管理和一般程序需要确认            |
| Workspace（默认） | 普通工作区读写和构建允许；删除、越界和复杂 Shell 先确认       |
| Approve for Me    | 基于 Workspace，由辅助模型审查普通询问；不确定时交回人工      |
| Full Access       | 普通操作免询问，文件与 Shell 使用宿主；显式规则和硬保护仍生效 |

默认 Docker 模式限制文件可见范围，容器默认断网；额外路径或网络按操作申请。缺少镜像、daemon 不可用或边界无法表达时会报错，不自动退回宿主执行。Full Access 或显式 `sandbox.enabled=false` 会改变执行边界。

容器不回滚已经写入的项目文件，也不提供工作区／缓存磁盘硬配额。Team 的 `assign_task.scope` 必须明确提供 `read_paths`／`write_paths`（工作区相对路径）：前者只读挂载，后者可写挂载，其余项目路径不可见；空列表不代表全部权限。范围根必须已存在且无符号链接别名，新文件应分配已存在的父目录。可用 `read_paths:["."]` 配合局部 `write_paths` 允许读全项目、只改指定目录。队友及其子代理、后台命令即使在 Full Access 下仍必须使用 Docker；关闭沙箱时拒绝这些文件／Shell 操作。队友仍共享工作区，不提供独立 worktree；重叠写入仍需 Leader 协调。

越界调用会以 `scope_request` 通知插入 Leader 当前轮次，Leader 通过 `resolve_scope_request` 选择 `approve_once`、`deny` 或 `ask_user`；超出用户许可范围仍需人工批准。一次许可绑定原操作、派工、路径与权限版本，不修改原范围或自动重放命令。Shell 的额外路径须明确声明 `access_paths`，未声明的脚本内部访问由容器拒绝。MCP 与工作区导入／环境准备／发布尚未接入逐派工隔离，受限队友须请 Leader 代办；技能注册表和 Harness 提供的精确只读邮件属于运行时资料。

## 配置与扩展

非秘密应用设置位于 `~/.jadecode/config/config.json`，字段示例见 [config.example.json](config.example.json)，包括 TUI、权限、上下文压缩、记忆和沙箱。运行中设置由服务统一保存，外部手改配置前应先停止服务。

- **MCP**：当前支持 stdio 文本工具。默认 Fetch 已包含在沙箱镜像中，按需连接；关闭 Docker 接线的宿主模式可用 `sh scripts/install-fetch.sh` 准备独立 Python 环境。自定义定义放在 `mcp.servers`；显式定义整表替换默认表，空对象关闭全部服务。
- **Skill**：项目不提供预装内容。可自行在工作区放置 `skills/<name>/SKILL.md`；名字和简介进入目录，正文由 `load_skill` 按需加载。没有该目录时正常运行。
- **Goal Loop**：`/goal <完成条件>` 开始目标驱动任务，`/goal` 查看，`/goal clear` 清除。完成判断基于模型读取的证据，仍可能误判。
- **Workflow**：内置 `review-changes` 和 `code-review`，用于对提供的文本进行多维审查与复核；流程内没有文件或 Shell 工具，不能代替编译和测试。

会话、记忆、任务、邮件、压缩归档及 Workflow 数据存放在 `~/.jadecode/`，项目分区按真实工作区路径哈希隔离。数据可能包含提示词和工具输出，分享前需自行检查。移动项目目录会产生新的分区，不自动迁移旧记录。

## 远程与独立工作区

远端需准备相同成套构建版本的 `jade`、模型配置和 Docker 镜像，并让 SSH 非交互会话能找到 `jade`。连接使用系统 SSH 的认证与主机校验，模型凭据留在执行机器。

```sh
./jade --remote user@server --workspace /srv/project
./jade --remote user@server --sessions
./jade --remote user@server --attach <id>
```

独立工作区提供持久任务目录，可获取仓库、导入快照、准备 Python 环境并发布成果；它要求开启 Docker，禁止 Full Access：

```sh
./jade --isolated "获取指定仓库版本，检查并修复问题"
./jade --managed-workspaces
./jade --workspace-id <id> --artifacts
./jade --workspace-id <id> --download-artifact <artifact-id> --output ./presentation.pptx
```

上传快照不包含 Git 历史，当前不支持符号链接／硬链接布局；获取仓库不自动处理子模块或 LFS。关闭会话保留工作数据，删除需显式执行工作区删除操作。

Agent 在文件生成完成后调用 `publish_result`，例如 `{"paths":["output/presentation.pptx"],"summary":"产品介绍 PPT"}`。返回的 `artifacts` 数组中，每个文件都有独立的 `artifactId`、`filename`、`size` 和 `sha256`；用户按编号下载原文件。发布只复制选定文件，后续修改工作文件不会改变已发布版本，不生成 Git 补丁、变更清单或自动 ZIP。目录不能直接发布；需要打包时，先由 Agent 生成压缩包，再发布该文件。

下载中断后，重新执行相同成果编号及本地目标路径的命令，可利用保留的 `.part` 文件续传；最终长度及 SHA-256 匹配后才生成正式文件，不覆盖已有目标。发布不占用整个工作区的执行屏障，应先完成文件生成再发布。当前不兼容旧的 ZIP 成果登记格式，既有本地文件不会自动清理。

显式删除时，若 Docker 在私有缓存中留下 root 所属的空挂载占位，会用断网、无额外 capabilities 的 UID 0 维护容器清理空目录和零字节占位。它只绑定被删除工作区的缓存子目录，不执行模型命令；普通 Agent 工作容器仍使用非 root 身份。

## 开发与验证

后端是 Maven 单模块，前端位于 `frontend/`；运行协议位于 `protocol/`。Pi 固定源码、项目补丁、原生模块和许可证保留在 `frontend/vendor/pi-tui/`。

Java 使用根目录 `java-formatter.xml` 固定 Eclipse Formatter 配置：四空格缩进、120 列换行，展开构造器、方法与分支，保留现有注释段落排版。安装 VS Code 的 Red Hat Java 扩展后，用 VS Code 打开 `JadeCode.code-workspace`，编辑器即使用同一份配置；已有文件夹级 Formatter 设置时，也需指向此配置及 `JadeCode` profile。

命令行直接使用扩展内置的 Eclipse JDT Formatter 与运行时，不改变项目的 Java 21 Toolchain，也不需要启动编辑器。脚本自动查找本机 `.vscode-server`、`.vscode` 和 `.vscode-insiders` 下的扩展；可用 `JADECODE_JAVA_EXTENSION` 指定扩展目录。

```sh
# 格式化指定文件；所有新增或修改的 Java 文件提交前均需执行并检查 diff。
python3 scripts/format-java.py src/main/java/com/jadecode/Main.java
python3 scripts/format-java.py --check src/main/java/com/jadecode/Main.java

# 格式化 Git 跟踪的全部 Java 文件（包含 scripts/ 中的适配器）。
git ls-files -z -- '*.java' | xargs -0 python3 scripts/format-java.py
```

```sh
# 后端：编译、单元测试与 JAR 打包
mvn --batch-mode --no-transfer-progress clean verify

# 前端：在 frontend/ 中依次执行
npm ci --no-audit --no-fund
npm run build
npm run typecheck
npm test
```

GitHub Actions 分别检查 Java 与前端，不调用真实模型，也不自动发布发行包。自动化检查不覆盖所有真实终端、SSH、IME、剪贴板和模型服务组合；原生 Windows／macOS 的完整运行路径尚无支持承诺。

欢迎通过 [Issues](https://github.com/fishandrice000000/JadeCode/issues) 报告问题，附复现步骤、版本和脱敏日志；提交变更时说明行为与验证结果。项目持续演进，架构和协作约定见 [AGENTS.md](AGENTS.md)。

## 来源与致谢

- [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)：Agent Harness 课程与学习起点。
- [Pi](https://github.com/badlogic/pi-mono)：终端前端基础；固定副本的许可证见 [Pi LICENSE](frontend/vendor/pi-tui/LICENSE)。
- Anthropic Java SDK、MCP Java SDK，以及构建文件列出的其他依赖；成套产物附带前端依赖许可证。

## 执行与扩展契约

CLI 的交互与 headless 模式都通过常驻服务创建会话。会话负责历史、队列与 Todo 提醒；每次子任务创建自己的 AgentExecutor，复用模型请求、工具执行和压缩步骤，保留独立历史、思考快照与 30 轮上限。正式请求和摘要用量按实际 Agent 归属统计。

自定义工具提供完整 `inputSchema()`，通过 `executeOutcome(input, context)` 返回正文和明确的错误标志。模型客户端统一使用 `complete(request, stream)`；无需展示时传 `ResponseStream.NONE`。运行消息分别携带身份、标签、正文与错误状态，正文中的标签文字不参与身份解析。

权限审批继续接入 `PreToolUse` Hook：首个非 null 返回值阻止工具执行；`Stop` 首个非 null 返回值要求继续。内置通知广播全部订阅者并忽略返回值，自定义事件保留原有短路规则。生产 Token 压缩与课程字符压缩分别由 `ProductionCompactor`、`CourseCompactor` 实现，通过 `ContextCompactor` 接入执行器。
