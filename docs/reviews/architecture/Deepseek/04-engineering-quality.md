# 阶段三：工程质量报告

本报告聚焦“普通工程代码”的组织、抽象、重复、可测试性、文档一致性与构建。跨模块重复清单已在 `03-cross-module.md`，此处不重复罗列，只评价其工程含义。

## 1. 目录与包组织

- 包名与职责对应清楚：`messages`（协议）、`llm`（调用）、`tools`（工具）、`safety`（权限）、`context`（压缩）、`memory`、`tasks`、`cron`、`skills`、`todo`、`subagent`、`agent`（会话/执行）、`cli`、`config`、`util`。
- 单模块 Maven，配置简单；`pom.xml` 的依赖注释说明了每个依赖的用途，toolchain 隔离写得清楚。
- 明显的组织问题只有一个：**跨层协议类型的位置**（`ExecutionContext`、`Tool`、`Hooks` 常量）造成包环与常量重复，详见 `02-modules/package-architecture.md`（ARCH-004）。

**评价**：目录组织整体良好，是本项目最不需要大改的部分。

## 2. 抽象层次与设计模式

### 2.1 合理的抽象

- `LlmClient` 接口 + 单一 SDK 适配实现：抽象有真实边界（隔离第三方类型），不是空壳。
- 压缩层策略类（5 个）职责单一、可注入阈值，`ContextCompactor` 负责编排；分层本身是对的。
- `Tool` 接口 + `ToolSchema` 默认实现：对简单工具是恰当的“提供默认、允许覆盖”。
- 不可变 record + sealed `ContentBlock`：用语言能力替代手工校验。

### 2.2 不够好的抽象

- **纯转发门面**：`ContextCompactor` 约 200 行转发（`219-445`），`ToolExchangeBoundary`（82 行）整体是转发。它们不提供可替换性，只增加同步维护点（ARCH-005）。
- **`AgentLoop` 的 5 个重载构造器**：telescoping constructor，参数多且同类型相邻（ARCH-013）。
- **`Main` 把提示词、hook 实现、装配揉在一起**（ARCH-014）。
- **缺失的抽象**：日志/诊断没有抽象（ARCH-008）；工作区安全文件存储没有抽象（ARCH-009）；工具参数校验没有统一默认实现（ARCH-010）。这三处属于“应该有的抽象”。

**评价**：抽象既不缺也不滥，问题集中在“有抽象但无收益（转发）”与“该抽没抽（横切）”。

## 3. 重复实现

已在 `03-cross-module.md` 第 5 节列出。工程上最该先处理的两项：

1. **回合协议双实现**（ARCH-002）：这是“会随时间恶化”的重复——每加一个机制就多一处要同步。
2. **工作区安全文件存储三实现**（ARCH-009）：这是“安全不变量分散”的重复——最难靠 review 保证。

“常量复制”（`compact`/`todo_write`）与“schema/校验分散”属于低风险但高噪声的重复，适合在包结构调整时顺手收敛。

## 4. 可测试性

### 4.1 做得好的地方

- **注入点设计到位**：`Main.buildLoop(AppConfig, Path, LlmClient)`、`Env.of(Map)`、`AppConfig.fromEnv(Env)`、`ContextCompactor(..., CompactionLimits)`、`CronScheduler(workspace, Clock)` 都允许离线、无网络、小阈值验证。`Clock` 注入尤其关键，让 cron 不需要真等。
- **测试替身**：`ScriptedLlmClient`、`RecordingLlmClient` 让会话/压缩可以在无真实模型下断言请求序列。
- **没有使用 `Thread.sleep`**：`rg Thread.sleep src/test` 无结果，说明测试不靠“睡一会儿看效果”，稳定性好。
- **压缩是测试重点**：`context` 有 13 个测试文件、`agent` 有 4 个，覆盖了四层策略、手动压缩、响应式补救、失败路径。这是项目里验证最扎实的部分。

### 4.2 已证实的缺口

#### ARCH-037（P3，可测试性缺口）：若干新模块没有源码级测试

**现状**：按包统计测试文件数（`src/test/java/com/jadecode/*`）：

| 包 | 测试文件 | 说明 |
| --- | --- | --- |
| `context` | 13 | 覆盖充分 |
| `safety` | 5 | 覆盖充分 |
| `tools` | 6 | 覆盖 BashTool/文件工具/CompactTool/SubagentTool/ToolSchema |
| `agent` | 4 | 覆盖执行器与压缩集成 |
| `messages` / `config` / `subagent` | 2 / 2 / 2 | 基本 |
| `skills` / `todo` | 1 / 1 | 基本 |
| **`memory`** | **0** | 无源码级测试 |
| **`tasks`** | **0** | 无源码级测试 |
| **`cron`** | **0** | 无源码级测试 |
| **`cli`** | **0** | `TerminalDisplay`/`ConsoleInput` 无独立测试 |
| `llm` | 2 | 都是测试替身，不是被测类 |
| `util` | 0 | `Json`/`Ansi` |

`BackgroundManager`、`ShellCommand` 也没有独立测试（`BashToolTest` 只覆盖工具层）。

**为什么是问题**：`memory`、`tasks`、`cron` 都涉及持久化、状态转换与失败恢复，正是最容易“看着对、边界错”的地方。它们的验证目前记录在 `docs/jobs/s12/*` 的离线/真机场景里，但那些场景**没有可重复运行的资产**（README 也说明“不新增单元测试是用户裁决”）。因此回归只能靠人工重跑，成本高。

**缓和因素与边界**：用户已明确“后续不主动新增单元测试”，且历史验证（离线场景、真机验收）确实存在，不能把这里说成“从未验证”。因此这不是要求立刻补测试，而是**指出证据留存方式的缺口**：至少应有两类可重复资产——(a) 纯逻辑的 `CronExpression`/`Task` 不变量/`MemoryStore` 解析可做小单元测试；(b) `BackgroundManager`/`CronScheduler` 的时序可做无 sleep 的注入式验证。是否需要补，应由用户裁决。

**建议**：如果未来允许补测试，优先顺序是 `CronExpression`（纯函数）→ `Task` 不变量（纯函数）→ `MemoryStore.parseRecord`/`memoryFilename`（纯函数）→ `BackgroundManager`（受控 listener）→ `CronScheduler`（注入 Clock）。这些都是低依赖、高价值的点，不涉及真实模型。

### 4.3 可测试性上的结构障碍

- `TerminalDisplay` 在真实终端下会替换进程级 `System.out/err`，测试若在交互终端里运行会被影响（当前测试环境的 `System.console()` 通常为 null，问题被掩盖）。
- `PermissionGate` 直接 `System.out.println`（ARCH-008），测试很难断言“是否打印了 blocked 行”。
- `AgentLoop.executeTools` 依赖 `Hooks` 注册顺序，测试要复刻 `Main` 的注册顺序才能覆盖真实行为；这属于隐性耦合。

## 5. 文档与源码一致性

### 5.1 一致且高质量的文档

- `CONTEXT.md` 的术语（当前执行任务、任务来源、交付确认、生产者/运行器/执行器）与代码结构对得上。
- `docs/adr/0001`、`0002` 与实现基本一致：事件化、单一消费者、后台必等/脱离、审批来源，都能在源码里找到对应。
- `docs/jobs/s12/session-event-refactor.md` 的“实现与自审”一节所列契约（工具批次闭合后才压缩、通知合并、首回复确认、退出清理）与源码一致，并且**诚实标注了边界**（不承诺恰好一次、不能撤销 IO、不能强杀第三方 HTTP）。
- `README.md` 对后台、cron、任务、记忆的行为描述与实现一致（例如“durable 不等于外部守护进程”“通知只保留 500 字符”）。

### 5.2 已证实的不一致

- **README 章节映射列了不存在的包/类名**（`.mailboxes/`、`team/`、`mcp/`、`AsyncEventLoop`、`SystemPromptBuilder`、`RetryPolicy`、`workflow/`、`goal/`），见 ARCH-019。进度列表本身是诚实的，问题是“计划与现状混排”。
- **`FALLBACK_MODEL_ID` 在 `.env.example` 与 README 中被描述为“s15 起使用”，但 `AppConfig` 不读取它**，代码里也没有回退逻辑。属于“宣布了但没实现”，与 ARCH-007 同源。
- **`SubagentOutcome` 文档说“无论走哪条路径都恰好触发一次 END”**，但对“工具/hook 的意外运行异常”不成立（ARCH-032）。任务单自己承认了这个排除，但枚举 JavaDoc 没有写。

**评价**：文档整体质量很高，不一致集中在“未来能力被写成了已有名字”。这属于可低成本修正的问题。

## 6. 构建与依赖

- **toolchain 隔离**：`maven-toolchains-plugin` + `maven-compiler-plugin` + `surefire` 都指定 JDK 21；`jade` 脚本运行时用 JDK 21。这解决了本机默认 Java 8 的现实约束，且没有污染其它项目。
- **shade 打包**：合并 `META-INF/services`（JLine 需要）、设置 Main-Class、排除签名文件。`java -jar target/jadecode.jar` 可直接运行。
- **依赖精简**：anthropic-java + jackson(databind/yaml) + picocli + jline + slf4j-simple + junit。没有多余框架。`slf4j-simple` 只为 SDK 提供实现。
- **缺口**：没有 CI 配置（无 `.github/` 等）。对个人项目可接受，但意味着“构建可复现”完全依赖本地。若长期维护，一个最小的 CI（`mvn -q verify`）会显著降低回归风险。这里不单列编号，因为它属于外部基础设施决策。
- **工作区卫生**：`target/`、`dependency-reduced-pom.xml`、`.env`、`.transcripts/`、`tmp/` 等都在 `.gitignore`，但**实际存在于工作区**。审查未读取其内容；只提醒 `dependency-reduced-pom.xml` 与 `target/` 不应被当作源码依据。

## 7. 代码风格与注释

- **注释质量显著高于平均水平**：类/方法 JavaDoc 解释“谁调用、读什么、写到哪、出错怎么办”，并有 `@throws`、`@param`、边界说明。`docs/jobs/s12` 还记录了“用直白中文重写注释”的过程与词法比对方法。这符合 AGENTS.md 对初学者注释的要求。
- **风格一致**：record/sealed/switch pattern、`List.copyOf`/`Map.copyOf`、`java.util.Objects.requireNonNull` 的用法统一。
- **可改进的小点**：
  - 错误文案中英混杂（`Error: Missing required parameter`、`历史非法:...`、`cron field outside [...]`）。面向模型的部分保持英文是对的，面向内部的可以统一。
  - `AnthropicSdkLlmClient` 用 `System.err.println("跳过未知响应块: " + b.getClass().getSimpleName())`；这条信息在长会话会反复出现（既有优化清单 OPT-004 已记录，用户明确延后）。
- **`Sk...` 之类的注释自证**：多处注释用“测试会失败”来替代类型约束（`ContextCompactor.java:53-55`、`SubagentRuntime.java:57-58`、`TodoWriteTool.java:60-62`）。这不是错误，但暴露了 ARCH-004/ARCH-010。

## 8. 工程质量结论

- **强项**：注释与文档、注入式可测试性、压缩模块的测试覆盖、构建隔离、失败语义诚实。
- **弱项**：横切关注点缺失（日志/存储/校验）、纯转发抽象、回合协议双实现、`memory/tasks/cron` 缺少可重复回归资产、文档里“未来能力”与现状混排。
- **不需要做的**：不必为了“更工程化”引入 DI 框架、Spring、事件总线、缓存层或更细的接口层次；当前问题用“抽一个共享工具类 / 抽一个接口 / 搬一次包”即可解决。
