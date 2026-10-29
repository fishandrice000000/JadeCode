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

**退出界面只断开客户端。** 会话中的模型、队友、后台任务和 cron 继续由常驻服务持有；`/close` 才关闭会话及所属资源。headless 执行取得结果后关闭其专属会话，stdout 只输出最终回答；默认权限为 Approve for Me，仍需人工授权时拒绝，不读取 stdin。

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

容器不回滚已经写入的项目文件，也不提供工作区／缓存磁盘硬配额。Team 的派工路径是协作约定，队友共享工作区；它不提供队友间的独立 worktree。

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
./jade --workspace-id <id> --download-artifact <artifact-id> --output ./result.zip
```

上传快照不包含 Git 历史，当前不支持符号链接／硬链接布局；获取仓库不自动处理子模块或 LFS。成果下载不自动应用补丁、提交或推送。关闭会话保留工作数据，删除需显式执行工作区删除操作。

显式删除时，若 Docker 在私有缓存中留下 root 所属的空挂载占位，会用断网、无额外 capabilities 的 UID 0 维护容器清理空目录和零字节占位。它只绑定被删除工作区的缓存子目录，不执行模型命令；普通 Agent 工作容器仍使用非 root 身份。

## 开发与验证

后端是 Maven 单模块，前端位于 `frontend/`；运行协议位于 `protocol/`。Pi 固定源码、项目补丁、原生模块和许可证保留在 `frontend/vendor/pi-tui/`。

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
