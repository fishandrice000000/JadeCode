# 专题报告：包结构与共享契约

本报告集中处理“跨层类型放错位置”造成的连锁问题。它不是把“分层”当口号，而是要回答：**当前哪些环是真实的、它们造成了什么可观察代价、怎样调整最省反复。**

## 1. 当前依赖图（只画真实 import 关系）

```
                    ┌──────────────────────────────┐
                    │            Main              │
                    └───┬───┬───┬───┬───┬───┬──────┘
                        │   │   │   │   │   │
        ┌───────────────┘   │   │   │   │   └─────────────┐
        ▼                   ▼   ▼   ▼   ▼                 ▼
      cli ──▶ agent ◀──── safety  context  memory  subagent  tasks  cron  skills  tools
              │  ▲                                        │
              │  └────────────────────────────────────────┘
              └──────────────▶ tools ◀── safety / subagent / tasks / cron / skills / todo
```

关键事实（`rg '^import com.jadecode'` 可复核）：

- `tools/Tool.java`、`tools/BashTool.java`、`tools/BackgroundManager.java`、`tools/SubagentTool.java` → `agent.ExecutionContext`
- `safety/PermissionGate.java` → `agent.ExecutionContext`
- `agent/AgentLoop.java` → `tools.BackgroundManager`、`tools.Tool`、`tools.CompactTool`

于是形成两个真实环：`agent ↔ tools` 与 `agent ↔ safety`。`context` 不反向依赖 `tools`，但它**想引用工具名却不能**，只能把 `"compact"` 写成字符串（`ContextCompactor.java:50-56`）。

## 2. 已经产生的可观察代价

1. **常量被迫复制**：`"compact"` 出现在 `AgentLoop.java:69`、`SubagentRuntime.java:60`、`ContextCompactor.java:56`、`CompactTool.java:31` 的 `name()`；`"todo_write"` 出现在 `AgentLoop.java:67` 与 `TodoWriteTool.java:33`。注释明确写“为避免压缩包反向依赖工具包”。这是包结构直接制造的工作量。
2. **`context` 包靠字符串约定自证**：`ContextCompactor` 注释说“名字不一致时手动压缩的测试会立刻失败”，即用测试替代类型约束。
3. **跨层协议无法收紧**：因为 `ExecutionContext` 在 `agent`，任何“运行器提供、工具消费”的新字段都会继续加宽这个类型，并继续把 `agent` 拉进更多包。
4. **`AgentLoop` 既依赖 `tools`、又被 `tools` 依赖**：导致 `background()` 只能包私有（`AgentLoop.java:186`），可见性被包环牵制。

## 3. 推荐的结构

目标是**单向依赖**：`Main → 宿主层 → 执行层 → 能力层 → 基础层`。

```
基础层：    messages, util, config
能力层：    context, memory, tasks, cron, skills, todo, llm
执行层：    execution（Tool 协议、ExecutionContext、ToolDispatcher、TurnExecutor）
宿主层：    agent（SessionRuntime、AgentLoop、SessionInbox、SessionEvent、CronProducer）
表现层：    cli
装配：      Main
```

具体动作（每一项都可独立、保行为地做）：

1. **把 `Tool` 与 `ExecutionContext` 移到中立的 `com.jadecode.execution`（或 `core`）包**。
   `Tool` 本来就只是“名字/描述/schema/execute”，不属于某个具体能力；`ExecutionContext` 是运行器交给工具的**输入协议**。挪走后：
   - `tools/*` 只依赖 `execution`，不再依赖 `agent`；
   - `safety/PermissionGate` 只依赖 `execution`；
   - `agent` 依赖 `execution` 与 `tools`；
   - `agent ↔ tools`、`agent ↔ safety` 两个环消失。
2. **把工具名常量放到 `execution`（或专门的 `ToolNames`）**：`compact`、`todo_write` 只定义一次，`ContextCompactor`、`AgentLoop`、`SubagentRuntime`、工具类都引用它。环消失后这一步才可行。
3. **`Hooks` 的事件名/参数名一起搬到 `execution`**：它们同样是跨层协议（`agent`、`safety`、`cli`、`Main` 都在用）。目前 `Hooks` 在 `safety` 包，但它的语义早已超出“安全”。
4. **（配合 ARCH-002）在 `execution` 里放 `ToolDispatcher` 与 `TurnExecutor`**：父执行器与子循环共用，协议只剩一份实现。

### 调整后的收益

- 依赖方向单向、可画成无环图；
- 常量与协议单点定义，删除“为避免反向依赖而复制”的注释性妥协；
- 新能力包（memory/tasks/cron/skills）只依赖基础层与 `execution`，不会意外拉进 `agent`；
- 未来 s13 团队、s16 workflow 若需要新能力包，不会继续把 `agent` 变成“什么都懂”的中心。

### 代价与风险

- 这是一次纯搬运 + 少量常量替换的**保行为重构**，但涉及大量文件的 import；需要一次独立的、可构建验证的提交，不能和其它语义改动混在一起。
- 包调整期间要避免顺手改可见性/顺带重构：`AgentLoop.background()` 的可见性变化、`Hooks.trigger` 的参数类型等都应保持原样。
- 与 ARCH-002（抽 `ToolDispatcher`）有依赖关系：先搬包、再抽执行步骤，顺序反过来会在旧包位置创建一个马上又要搬的 `ToolDispatcher`。因此**建议先做第 1–3 步（搬协议），再做 ARCH-002**。

## 4. 与既有审查的关系

`docs/reviews/architecture/ChatGPT/02-modules/package-architecture.md` 的 ARCH-019 也提出“把进程能力从模型工具包分离、把共享契约放到稳定位置”。本次独立得到同向结论，但更强调**两个具体环**与**由此产生的常量重复**这两个可核实代价。两者不冲突。

## 5. 反例与缓和因素

- 必须承认：包环**目前没有造成功能缺陷**，项目能构建、能跑、测试能过。因此这是“可选优化/结构改进”，不是 P0/P1。把它列在同级 P2 是因为后续每次加机制都要为它交税。
- 也不能把“移动包”本身当成质量提升：真正的收益来自“协议单点定义 + 执行步骤复用（ARCH-002）”。单纯搬包而不同步 ARCH-002，收益有限。

---

## 6. 补充：第二轮包设计分析（2026-10-17）

第一版只点出 `agent ↔ tools` 与 `agent ↔ safety` 两个环。之后用 `rg '^import com.jadecode'` 逐包完整提取依赖，发现**实际有 4 个环**，并且根因比“类型放错包”更具体。

### 6.1 完整包级依赖（按实际 import）

```
agent     → context, cron, llm, memory, messages, safety, tools, util
cli       → agent, safety, util, Main
config    → （无）
context   → llm, messages, util
cron      → util
llm       → config, messages, tools
memory    → llm, messages, util
messages  → （无）
safety    → agent, util
skills    → （无）
subagent  → agent, context, llm, messages, safety, tools, util
tasks     → util
tools     → agent, cron, safety, skills, subagent, tasks, todo, util
util      → （无）
```

### 6.2 四个真实环

| 环 | 正向 | 反向 | 证据 |
| --- | --- | --- | --- |
| `agent ↔ tools` | agent 用 `BackgroundManager/Tool/CompactTool` | `Tool`/`BashTool`/`BackgroundManager`/`SubagentTool` 用 `agent.ExecutionContext` | `Tool.java:7`、`AgentLoop.java:34-37` |
| `agent ↔ safety` | agent 用 `Hooks/PermissionGate` | `PermissionGate` 用 `agent.ExecutionContext` | `PermissionGate.java:7`、`SessionRuntime.java:18-19` |
| `tools ↔ subagent` | `SubagentTool` 用 `subagent.SubagentRuntime` | `SubagentRuntime` 用 `tools.Tool`、`tools.CompactTool` | `SubagentTool.java:9`、`SubagentRuntime.java:20-21` |
| `llm ↔ tools` | `CompleteRequest`/`AnthropicSdkLlmClient` 用 `tools.Tool` | `SubagentTool` 用 `llm.LlmException` | `CompleteRequest.java:6`、`SubagentTool.java:8` |

**更正**：`llm → tools` 与 `tools → llm` 这一对在第一版被漏掉了。`CompleteRequest` 直接持有 `List<Tool>`，意味着“LLM 请求模型”依赖“工具的实现包”，这是不合理的耦合方向——LLM 层只需要“工具名 + 描述 + schema”，不需要 `tools` 包里的 20 多个实现类。

### 6.3 根因：`Tool` 接口和它的实现放在同一个“枢纽包”

`tools` 包同时包含三类完全不同的东西：

1. **共享接口**：`Tool`、`ToolSchema`（被 `llm`、`agent`、`subagent` 依赖）；
2. **模型工具适配器**：`BashTool`、文件工具、`TaskTools`、`CronTools`、`SubagentTool`、`TodoWriteTool`、`LoadSkillTool`、`CompactTool`；
3. **进程基础设施**：`ShellCommand`、`BackgroundManager`（后者根本不实现 `Tool`）。

后果是：任何只想用 `Tool` 接口的包（`llm`、`subagent`、`agent`），在源码依赖图里都会被连到 `tools` 的全部下游（`tasks`、`cron`、`skills`、`todo`、`subagent`、`agent`）。这不是编译要求，而是包级依赖图的真实边。**把接口与实现分到不同包**，比单纯“移动 `ExecutionContext`”收益更大。

### 6.4 其它可考虑的包设计点

1. **`tools` 应拆成三块**（对应 6.3）：`execution`（SPI：`Tool`/`ToolSchema`/`ExecutionContext`/`Hooks` 事件名/`ToolNames`/`ToolDispatcher`）、`tools`（只剩实现适配器）、`process` 或 `runtime`（`ShellCommand`/`BackgroundManager`）。这样 `llm → execution` 而不是 `llm → tools`，`subagent → execution` 而不是 `→ tools`。
2. **`safety` 名不副实**：`Hooks` 不是安全（它同时用于展示与 Stop 续跑），`PathResolver` 是文件系统解析而非策略。可考虑拆为 `policy`（`CommandPolicy`/`PathGuard`/`PermissionGate`）、`filesystem`（`PathResolver`）与 `extensions`/`hooks`（`Hooks`）。至少应把 `Hooks` 事件常量搬到 `execution`。
3. **`CronProducer` 位置**：它是“调度生产者”，却放在 `agent` 里，使 `agent` 必须懂 `cron`，也让最近一次会话重构把 cron 交付语义留在了 `SessionRuntime`（ARCH-022）。更合理的做法是让 `cron` 包拥有轮询线程，并通过一个中立接口（例如 `execution.RequestSink`）提交请求，从而不反向依赖 `agent`。
4. **`cli → Main` 是反向依赖**：`ReplCommand`/`RunCommand` import `Main` 只为调用 `buildLoop`（`ReplCommand.java:7`、`RunCommand.java:7`）。这把“表现层”依赖“装配根/服务定位器”。可把装配抽成 `Bootstrap`/`AgentFactory`（独立包），`cli` 依赖它，`Main` 只留 picocli 入口。
5. **`llm → config` 过宽**：`AnthropicSdkLlmClient` 接收整个 `AppConfig`（`AppConfig.java:17`），但它只用到 `apiKey/baseUrl/model`。可传入一个窄的 `LlmSettings`，减少“改配置就碰 LLM 适配器”的耦合面。优先级低。
6. **共享存储的归属**：`ToolOutputStore`/`TranscriptStore` 现在住在 `context`。如果采纳 ARCH-030（后台输出落盘）与 ARCH-026（按会话归属），它们会同时被 `context` 与 `tools`/`process` 使用。届时应把它们提到中立的 `workspace`/`storage` 包，否则会新增 `context → tools` 或 `tools → context` 的环。
7. **`ExecutionContext` 是否需要拆**：它目前把四件事绑在一起——后台结果归属（`id`）、权限来源（`source`）、交互通道（`approval`）、生命周期（`sessionClosed`）。作为工具 SPI 传入是合理的；但如果 `execution` 成为公共契约，可考虑拆成 `ToolInvocationContext`（工具真正需要的）与保留在 `agent` 的会话信息。**建议先观察，不急于拆**：四个字段的共现度很高。
8. **`package-info.java` 全缺**：目前 0 个。对这个以教学和长期维护为目标的项目，给每个包写一段“职责 + 允许依赖 + 阅读顺序”的 `package-info.java` 成本极低、收益直接，还能把“不要新增环”的意图写进源码。
9. **依赖方向没有守护**：没有机制防止后续提交引入新环。可选方案是加一个 ArchUnit 测试（需用户批准测试依赖），或写一个只解析 import 的极简检查。不建议为此改成多模块 Maven——那会与本项目“单模块”的既有决定冲突。

### 6.5 更新后的目标结构

```
基础层：  messages, util, config, skills, todo, tasks, cron
能力层：  execution（Tool/ExecutionContext/Hooks/ToolNames/Dispatcher）
          llm, context, memory, process
宿主层：  agent（SessionRuntime/AgentLoop/SessionInbox/SessionEvent）
适配层：  tools（只放 Tool 实现，依赖 execution + process + 各能力）
表现层：  cli
装配：    bootstrap（AgentFactory），Main 只做 picocli 入口
```

校验标准：把上面的 import 图重新提取一次，应该**没有环**；`llm` 只依赖 `execution/messages/config`；`tools` 不再被 `llm/subagent` 依赖。

### 6.6 不建议做的拆分

- 不要因为 `memory`/`todo`/`skills`/`cron` 只有 3–5 个类就合并或再拆子包；当前粒度合适。
- 不要为“更清晰”引入 `api`/`impl` 双包或 `internal` 包；JadeCode 的接口只有一个实现，分层会变成噪声。
- 不要为了消除 `agent` 的“胖”而拆出多个消费者；ADR 0002 明确要求单一消费者，这里要收窄接口而不是拆散所有权。
