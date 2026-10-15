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

已实现 s01–s07；s08 上下文保护、s09 Memory 与 s10 Task System 已接入 CLI，独立章节验收状态见下方进度。

```
src/main/java/com/jadecode/
├── Main              # picocli 入口:run / repl,装配全部组件
├── cli/              # RunCommand(一次性任务), ReplCommand(交互循环)
├── config/           # Env(.env 解析), AppConfig(缺省值与校验)
├── util/             # Json(共享 ObjectMapper), Ansi(终端色码)
├── messages/         # Message + sealed ContentBlock(text/tool_use/tool_result)
├── llm/              # LlmClient 接口 + AnthropicSdkLlmClient(SDK 隔离层)
├── tools/            # Tool, ToolSchema + bash / read_file / write_file / edit_file / glob / todo_write / task / load_skill
├── safety/           # PathResolver, PathGuard, CommandPolicy, PermissionGate, Hooks
├── todo/             # TodoManager(计划表校验与渲染)
├── subagent/         # SubagentRuntime(嵌套循环 + 消息隔离 + 三终态)
├── skills/           # SkillDefinition, SkillLoader(catalog 常驻 / 全文按需)
├── context/          # ContextCompactor(请求前保护 / 主动压缩 / 超限补救)
├── memory/           # 工作区记忆：召回 / 提取 / 数量触发整理 / 生命周期装配
├── tasks/            # 持久任务图：创建 / 依赖 / 认领 / 完成与解锁，六工具由父循环使用
└── agent/            # AgentLoop(主循环 + todo reminder)
```

仓库根 `skills/<name>/SKILL.md` 存放可被 `load_skill` 加载的真实技能:system prompt 只带 name + description 的目录,完整正文仅在模型调用 `load_skill` 后作为 tool_result 进入消息。

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
mvn -q test                                 # 单元测试
./jade repl                                 # 交互
./jade run "任务"                            # 一次性
```

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
- [ ] s11–s17:逐章实现(见映射表)

Memory 使用工作区 `.memory/`，每次用户任务入口召回一次，真正结束后提取，新增成功后按数量尝试整理。
辅助请求沿用现有客户端，不带工具；记忆维护失败告警，不抹掉已完成的主任务结果。
子代理不自主维护长期记忆。记忆内容的真伪、长期价值与合并质量依赖模型，格式校验不提供语义保证。

Task System 使用 `.tasks/{id}.json`。先创建取得实际 ID，再增加依赖；认领检查前置条件，完成报告刚解锁任务。
`list_tasks` 查看全部进度，`get_task` 读取完整描述。`task` 仍表示子代理委派，不是持久任务创建。
本章按顺序执行，不保证并发抢单互斥，不自动调度、恢复精确执行断点或证明 completed 工作正确。
