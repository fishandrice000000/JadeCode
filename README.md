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

```
src/main/java/com/jadecode/
├── Main              # picocli 入口:run / repl
├── cli/              # RunCommand, ReplCommand
├── config/           # Env(.env 解析), AppConfig, Workspace
├── util/             # Json, Ids, AtomicFile, ConsoleIO
├── messages/         # Message + sealed ContentBlock(text/tool_use/tool_result)
├── llm/              # LlmClient 接口 + SDK/HTTP 两实现 + RetryPolicy
├── tools/            # Tool 接口, ToolSchema, 各章工具类
├── safety/           # PathGuard, CommandPolicy, PermissionGate, Hooks
├── todo/             # TodoManager
├── subagent/         # SubagentRuntime
├── skills/           # SkillLoader
├── context/          # Compactor, TranscriptWriter, ToolResultArchive, SystemPromptBuilder
├── memory/           # MemoryManager
├── tasks/            # TaskStore
├── cron/             # CronParser, ScheduledTaskStore, CronScheduler
├── team/             # Mailbox, TeamProtocol, TeammateRuntime, ClaimLock, WorktreeManager
├── mcp/              # McpClient, McpToolPool, McpConfig
├── agent/            # AgentLoop, AgentRuntime, AsyncEventLoop
├── workflow/         # WorkflowRunner
└── goal/             # GoalController, PromptGoalEvaluator
```

## 章节 ↔ 包映射

| 课程章节 | 主题 | JadeCode 包 |
|---|---|---|
| s01 | Agent Loop | `agent/` `messages/` `llm/` `tools/BashTool` |
| s02 | Tool Use(分发表) | `tools/`(5 基础工具)+ `safety/PathGuard` |
| s03 | Permission | `safety/CommandPolicy` `safety/PermissionGate` |
| s04 | Hooks | `safety/Hooks` |
| s05 | TodoWrite | `todo/` |
| s06 | Subagent | `subagent/` |
| s07 | Skill Loading | `skills/` |
| s08 | Context Compact | `context/` |
| s09 | Memory | `memory/` |
| s10 | Task System | `tasks/` |
| s11 | Background Tasks | `tools/BackgroundManager` |
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

## 运行

```sh
mvn -q package                              # 构建(直接跑即可,无需设 JAVA_HOME)
./jade repl                                 # 交互
./jade run "任务"                            # 一次性
```

**JDK 版本隔离**:项目通过 Maven Toolchains 声明编译/测试用 JDK 21(注册表在 `~/.m2/toolchains.xml`,被动生效);Maven 自身和机器上其他 Java 8 项目不受任何影响。运行时同样隔离:`./jade` 脚本只在单次进程内指向 JDK 21。

## 进度

- [x] M0:项目骨架(pom / 目录 / 配置 / README)
- [ ] s01:Agent Loop + 真实 API 冒烟
- [ ] s02–s17:逐章实现(见映射表)
