# 阶段四：JadeCode 架构审查最终报告

审查日期：2026-10-17 · 基线 HEAD `f15388d` · 只读静态审查

---

## 1. 总体评价

### 1.1 一句话结论

JadeCode 的**运行语义是自洽、诚实、可运行的**；它最主要的短板不在功能，而在**长期维护的结构**与**可靠性余量**。它已经不是一个教学 Demo：单一消费者会话、事件化采用历史、后台/定时/子代理/压缩/记忆这些机制都被认真实现并有失败语义。但同时，它有 **38 项** 可改进点，其中 3 项 P1、18 项 P2、17 项 P3，绝大多数属于“现在能跑、继续加功能就会疼”的类型。

### 1.2 主要优势

1. **状态与职责边界大体清楚**：`SessionRuntime` 拥有历史与当前任务，`AgentLoop` 只做一次回合，生产者为 `cli`/`CronProducer`/工具/后台。`apply` 是历史唯一写入口，配合有界 FIFO 与不忙等等待，避免了“多入口乱改历史”。
2. **失败语义诚实**：全项目反复区分“排队成功 / 模型已收到 / 业务成功”，`bg_id`、cron 交付确认、`task completed` 都不被冒充为业务验收；未启用能力宁可明确报错也不静默 no-op。
3. **文件安全纪律强**：`ToolOutputStore`/`TranscriptStore`/`TaskStore`/`MemoryStore`/`CronScheduler` 都做了排他创建、原子替换、真实路径围栏、可信 marker 形状校验、失败只清理本次文件。
4. **压缩模块是全项目最扎实的部分**：分层代价递增、前三层可恢复、有损前先归档、最新工具交换受保护、不可变约定明确，且有 13 个测试文件覆盖。
5. **注释与文档质量高**：中文 JavaDoc 解释调用位置/边界/失败语义；CONTEXT/ADR/任务单与源码基本一致，并诚实标注边界。
6. **可测试性有真实注入点**：`Main.buildLoop(AppConfig, Path, LlmClient)`、`Env.of`、`CompactionLimits`、`Clock` 注入、`ScriptedLlmClient`；测试不使用 `Thread.sleep`。

### 1.3 主要弱点

| 弱点 | 代表问题 | 后果 |
| --- | --- | --- |
| 回合协议双实现且在分叉 | ARCH-002 | 每加一个机制都要同步两处，行为已出现差异 |
| 长期运行的可靠性余量不足 | ARCH-006/007/011/016/017 | 成本失控、瞬时错误即任务失败、子代理拖死会话 |
| 一个真实的安全边界缺口 | ARCH-027 | 两跳以上符号链接可绕过路径围栏 |
| 跨层协议类型位置造成包环 | ARCH-004 | 常量重复、压缩包靠字符串自证、新机制继续加税 |
| 横切关注点未抽出 | ARCH-008/009/010 | 日志散落、存储三份重复、参数校验四处漂移 |
| 纯转发抽象 | ARCH-005 | 约 280 行需要同步维护的表面，无新增可替换性 |
| 文档把“未来能力”写成现状 | ARCH-019/FALLBACK_MODEL_ID | 误导读者，尤其对初学者 |
| 新模块缺可重复回归资产 | ARCH-037 | memory/tasks/cron/background 的验证只能人工重跑 |

### 1.4 是否存在 P0

**没有发现 P0。** 没有找到会立刻导致数据丢失、越权或核心功能不可用的确定性问题。ARCH-027 是安全事件，但触发需要工作区内预先存在两跳以上符号链接，因此定为 P1 而非 P0。

---

## 2. 最值得优先关注的 5 个问题

1. **ARCH-002（P1）父子回合协议重复且分叉**——这是唯一“会随时间自动恶化”的问题，且已出现可列举的行为差异（未知工具是否进 hook、异常处理、`PostToolUse` 参数、提醒逻辑）。
2. **ARCH-006（P1）父任务没有任何轮数/调用/成本上限**——保护恰好装反：子代理有 30 轮上限，父任务无界；自动化（cron）无人看着时最危险。
3. **ARCH-027（P1）多层符号链接未被完全解析**——路径围栏存在真实绕过路径，且单跳测试会给出错误的安全感。
4. **ARCH-004（P2）跨层协议类型位置造成两个包环**——它是“常量重复、纯转发、契约隐式化”的共同根因，且是后续机制（s13+）继续加税的地方。
5. **ARCH-007（P2）没有 LLM 瞬时错误重试/退避/回退**——状态码已经拿到却没有用，`FALLBACK_MODEL_ID` 宣布了却没实现；长任务因一次网络抖动整体失败。

---

## 3. 完整问题索引（ARCH-001 ~ ARCH-038）

类型：**缺陷** = 已证实的设计/实现问题；**风险** = 有源码依据但影响取决于条件；**优化** = 当前无证实缺陷但有明确替代方案；**待验证** = 静态无法定论。

### P1（3 项）

| 编号 | 类型 | 问题 | 主要证据 | 成本 | 详见 |
| --- | --- | --- | --- | --- | --- |
| ARCH-002 | 缺陷 | 父执行器与子循环重复实现回合协议，且已分叉 | `AgentLoop.java:307-352` vs `SubagentRuntime.java:184-350` | 中-高 | subagent-skills-todo.md, cross-module.md |
| ARCH-006 | 风险 | 父任务无轮数/调用/时间/成本上限 | `SessionRuntime.java:260-310` | 中 | agent-session.md |
| ARCH-027 | 缺陷 | 多跳符号链接未完全解析，路径围栏可被绕过 | `PathResolver.java:67-87` | 中 | tools-safety.md |

### P2（18 项）

| 编号 | 类型 | 问题 | 主要证据 | 成本 | 详见 |
| --- | --- | --- | --- | --- | --- |
| ARCH-001 | 缺陷 | 空 `.env` 值绕过 `required`，`maxTokens≤0` 未校验 | `Env.java:88-106`、`AppConfig.java:31-63` | 低 | cli-config-build.md |
| ARCH-003 | 缺陷 | 工具注册表用 `Map.of/Map.copyOf`，请求里工具顺序不确定 | `Main.java:172,206`、`AgentLoop.java:269`、`SubagentRuntime.java:211` | 低 | cli-config-build.md |
| ARCH-004 | 缺陷 | `agent ↔ tools/safety` 包环，导致常量重复与隐式契约 | `Tool.java:7`、`PermissionGate.java:7`、`AgentLoop.java:67-71` | 中 | package-architecture.md |
| ARCH-005 | 优化 | `ContextCompactor`/`ToolExchangeBoundary` 是纯转发，无新增替换性 | `ContextCompactor.java:219-445`、`ToolExchangeBoundary.java:27-81` | 低-中 | context.md |
| ARCH-007 | 风险 | 无 LLM 瞬时错误重试/退避/回退；状态码未用于分流 | `AgentLoop.java:270-279`、`LlmException.java:42`、`.env.example` | 中 | llm-messages.md |
| ARCH-008 | 缺陷 | 安全/记忆/LLM 层直接用 `System.out/err`，无日志抽象 | `PermissionGate.java:125`、`MemoryManager.java:58,93,96`、`AnthropicSdkLlmClient.java:173` | 中 | tools-safety.md |
| ARCH-009 | 优化 | 工作区安全文件存储逻辑在 3 处重复 | `TaskStore.java:269-286`、`CronScheduler.java:230-253`、`MemoryStore.java:136-318` | 中 | tasks.md, cross-module.md |
| ARCH-010 | 缺陷 | 工具 schema 声明与运行时校验分散、`additionalProperties` 默认不一致 | `ToolSchema.java:33-64`、各工具 `required()` 循环 | 中 | tools-safety.md |
| ARCH-011 | 风险 | 单个工具/RuntimeException 使整批乃至整任务失败 | `AgentLoop.java:340-347`、`SessionRuntime.java:275-280` | 中 | tools-safety.md |
| ARCH-016 | 风险 | 子代理同步阻塞父会话，无独立超时/取消 | `SubagentTool.java:96-101`、`SubagentRuntime.java:184-350` | 中 | subagent-skills-todo.md |
| ARCH-017 | 风险 | 记忆召回/提取/整理不继承会话取消 | `MemoryManager.java:53-109`、`SessionRuntime.java:302,521-528` | 中 | memory.md |
| ARCH-021 | 风险 | 用字符数估算长度、用关键词判断上下文超限 | `ContextSerialization.java:29-31`、`ContextCompactor.java:625-643` | 中 | context.md |
| ARCH-023 | 优化 | 历史所有权靠 `Consumer<SessionEvent>` 约定，无类型保护 | `AgentLoop.java:253-254` | 中 | agent-session.md |
| ARCH-025 | 缺陷 | SDK 转换丢失工具 schema 根级 `additionalProperties:false` | `AnthropicSdkLlmClient.java:131-158`、`TaskTools.java:115` | 低 | llm-messages.md |
| ARCH-028 | 风险 | bash 拦截是子串规则，与任意 shell 能力落差大 | `CommandPolicy.java:13-72` | 需用户裁决 | tools-safety.md |
| ARCH-029 | 风险 | 命令超时固定 120 秒，不可配置/不可按命令覆盖 | `BackgroundManager.java:54`、`BashTool.java:101-104` | 低-中 | background-shell.md |
| ARCH-033 | 风险 | 记忆整库整理“先删旧再建新”，崩溃无恢复提交点 | `MemoryStore.java:281-296` | 中-高 | memory.md |
| ARCH-038 | 风险 | 长任务原请求可能被 snip 归档，目标不再在可见历史 | `SnipCompactor.java:40-77`、`SessionRuntime.java:264` | 中 | context.md |

### P3（17 项）

| 编号 | 类型 | 问题 | 主要证据 | 成本 | 详见 |
| --- | --- | --- | --- | --- | --- |
| ARCH-012 | 优化 | legacy `runExisting` 与主路径并存 | `AgentLoop.java:198-200`、`SessionRuntime.java:478-495` | 低 | agent-session.md |
| ARCH-013 | 优化 | `AgentLoop` 5 个重载构造器（telescoping） | `AgentLoop.java:84-158` | 低 | agent-session.md |
| ARCH-014 | 优化 | `Main` 集中装配/提示词/hook | `Main.java:113-405` | 低 | cli-config-build.md |
| ARCH-015 | 风险 | `Hooks` 顺序/短路/线程安全是隐式契约 | `Hooks.java:92-104`、`Main.java:209-213` | 低-中 | tools-safety.md |
| ARCH-018 | 优化 | 一次 cron 轮询错误暂停所有定时投递，无健康可见性 | `CronProducer.java:94-109` | 低 | cron.md |
| ARCH-019 | 缺陷 | README 映射列出不存在的包/类名与 `.mailboxes/` | `README.md:59-63,15` | 低 | cli-config-build.md |
| ARCH-020 | 优化 | `Message` 缺构造不变量，null 检查散落调用方 | `Message.java:22`、`ContextCompactor.java:568-593` | 中 | llm-messages.md |
| ARCH-022 | 优化 | 运行器内嵌 cron 交付语义，生产者逻辑泄漏进消费者 | `SessionRuntime.java:210-218,405-410` | 中 | agent-session.md |
| ARCH-024 | 风险 | 通知必须合并进最后 USER，依赖压缩器隐式约定 | `SessionRuntime.java:358-361,379-388`、`MicroCompactor.java:58-79` | 中 | context.md |
| ARCH-026 | 优化 | 父子共用归档/工具结果目录，缺会话归属与回查索引 | `Main.java:150-152,181-182`；OPT-002 | 中 | context.md |
| ARCH-030 | 优化 | 后台结果无完整输出落盘，通知仅 500 字符 | `BackgroundManager.java:32,266-270` | 中 | background-shell.md |
| ARCH-031 | 优化 | `ShellCommand` 依赖 Linux 专用路径，无降级 | `ShellCommand.java:75,162` | 低 | background-shell.md |
| ARCH-032 | 缺陷 | `SubagentOutcome` 文档称“恰好一次 END”，意外异常不成立 | `SubagentRuntime.java:356-361`、`SubagentOutcome.java:6-9` | 低 | subagent-skills-todo.md |
| ARCH-034 | 优化 | `in_progress` 任务无 release/fail/reopen 过渡 | `TaskStore.java:175-232` | 低-中 | tasks.md |
| ARCH-035 | 优化 | 任务图每次操作全量扫描重读 | `TaskStore.java:112-130,235-241` | 低 | tasks.md |
| ARCH-036 | 风险 | cron 用带偏移分钟字符串去重，跨时区/DST 边界 | `CronScheduler.java:118-119,207-209` | 低 | cron.md |
| ARCH-037 | 优化 | memory/tasks/cron/background 无可重复回归资产 | `src/test` 包分布 | 由用户裁决 | 04-engineering-quality.md |

（ARCH-037 与 ARCH-019 见 04；ARCH-037 的“是否补测试”需用户决定。）

---

## 4. 架构优化方案（设计级）

### 4.1 方案 A：统一执行协议（解决 ARCH-002 + ARCH-004 + 部分 ARCH-010/011）

**职责重划**：把“一次模型回合”和“一批工具”从两个宿主里抽出来，宿主只负责策略差异。

```
                    ┌─────────────────────────────┐
   execution 包      │ Tool（协议）                 │
  （新建，无环）      │ ExecutionContext（任务身份）   │
                    │ ToolNames（compact/todo_write）│
                    │ Hooks 事件名/参数名            │
                    │ ToolDispatcher（未知/hook/异常）│
                    │ TurnExecutor（请求→压缩→补救→批次）│
                    └──────────────┬──────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                          ▼
  agent.AgentLoop            subagent.SubagentRuntime    （未来 workflow/goal）
  传入 TurnPolicy{允许后台,    传入 TurnPolicy{30 轮上限,
  无轮次上限, 发 PostToolUse   无后台, 不发 SubagentTool}
  带 context}                  ...
```

- `ToolDispatcher.execute(calls, tools, context, hooks, options)` 返回已配对结果；父与子都调用它。未知工具、hook 参数、异常隔离都只有一份实现。
- `TurnExecutor.run(messages, system, activeRequest, policy, events)` 统一 `prepare → complete → 超限补救 → 校验 → 返回 ModelReply/ToolBatch`。
- 差异用不可变 `TurnPolicy` 表达（最大轮数、是否允许后台工具、是否发 `SUBAGENT_TOOL`、意外异常是否隔离、是否在批次后压缩）。

**为什么更合理**：协议单点定义，行为差异显式化；`ContextCompactor` 可直接引用 `execution.ToolNames`，不再靠字符串；父子未来新增机制不会再次分叉。

**代价与风险**：中等规模重构，必须保行为迁移；`TurnPolicy` 一旦设计不当会变成新的“参数垃圾场”。建议先做包搬运（ARCH-004），再抽 `ToolDispatcher`，最后抽 `TurnExecutor`。

### 4.2 方案 B：可靠性护栏（解决 ARCH-006/007/011/016/017/021/029/038）

- **任务预算**：`Active` 增加 `turns/toolCalls/deadline`，在 `runTask` 每轮检查；超限走“让模型总结后结束”或明确失败。配置化默认值（例如 100 轮 / 200 次工具调用 / 30 分钟）。
- **LLM 重试**：`RetryPolicy` 装饰 `LlmClient`，只重试 429/5xx/网络错误，指数退避 + 抖动，上限后可选切 `FALLBACK_MODEL_ID`；**只在请求边界重试**，不覆盖工具执行之后。
- **工具异常隔离**：`ToolDispatcher` 把意外异常转成该调用的 `Error:` 文本，继续同批；`PostToolUse` hook 异常单独处理，不让展示层带崩任务。
- **子代理预算与取消**：给 `SubagentRuntime` 传入墙钟预算与可读取的取消信号；超时按 `STOPPED` 收尾。
- **记忆取消**：把退出信号传进 `MemoryManager`，退出后不再做召回/提取/整理。
- **超长命令**：`BASH_TIMEOUT_SECONDS` 配置 + 工具可选 `timeout_seconds`（带最大值校验）。
- **目标锚点**：snip/reactive 后确保 `activeRequest` 以固定方式保留在可见历史。

**收益**：把“长期使用会疼”变成“有明确上限与恢复路径”。**代价**：需要定义超限/超时的产品语义，且要保证不破坏 cron 交付确认与后台必等语义。

### 4.3 方案 C：横切关注点收敛（解决 ARCH-008/009/010/014）

- `Reporter`（info/warn/error）替代 `System.out/err`；`TerminalDisplay` 改为注入实现，取消进程级流替换。
- `WorkspaceFileStore` 统一“围栏 + 临时文件 + 原子替换 + 清理 + suppressed”；`TaskStore`/`CronScheduler`/`MemoryStore` 只提供内容与路径。
- `Tool` 参数校验默认实现 + `additionalProperties` 口径统一。
- `Main` 拆出 `Prompts` 与 `DefaultHooks`。

**收益**：横切逻辑单点维护、可测、可观测。**代价**：改动面广但语义不变，适合逐个模块小步迁移。

---

## 5. 建议优化顺序

依赖关系（→ 表示“先做左边”）：

```
阶段 0（低风险小修，可立即）
  ARCH-001 配置边界 → ARCH-003 工具顺序 → ARCH-019/ARCH-032 文档一致性 → ARCH-014 拆 Main

阶段 1（结构，先搬包后抽协议）
  ARCH-004 搬执行协议到 execution → ARCH-002 抽 ToolDispatcher/TurnExecutor → ARCH-005 删转发

阶段 2（安全与可靠性，与阶段 1 部分并行）
  ARCH-027 路径解析（独立，优先做） 
  ARCH-006 任务预算 / ARCH-007 重试 / ARCH-011 工具隔离 / ARCH-029 超时可配
  ARCH-016 子代理预算 / ARCH-017 记忆取消 / ARCH-021 超限判定 / ARCH-038 目标锚点

阶段 3（横切与生命周期）
  ARCH-008 日志抽象 / ARCH-009 存储工具 / ARCH-010 schema 校验
  ARCH-033 记忆原子发布 / ARCH-024 目标与通知契约 / ARCH-026 会话归属
  ARCH-018 cron 健康 / ARCH-036 去重键 / ARCH-034 任务生命周期

阶段 4（文档与验证）
  ARCH-037 回归资产（需用户裁决） / ARCH-019 路线图整理
```

排序理由：

- 阶段 0 全部是“改动小、收益即时、风险低”的诊断/可复现性修复，适合先做。
- 阶段 1 必须**先搬包再抽协议**：否则 `ToolDispatcher` 会被放在马上要搬的位置。
- ARCH-027 与 ARCH-006 不依赖结构改动，可以穿插；ARCH-027 优先因为它是安全边界。
- 阶段 2 的项之间没有强依赖，但都应在阶段 1 的 `TurnExecutor` 落地后做，避免把重试/预算逻辑写进将被删除的重复代码。
- 阶段 3 的 ARCH-009/ARCH-033 有交集（都在做“安全落盘”），建议同批；ARCH-026 需要先确定会话身份。
- 阶段 4 的测试补充受用户裁决约束，只做“若允许则如何做”。

---

## 6. 整体设计建议（长期演进方向）

1. **先固化“协议”，再谈“扩展”**：`execution` 包（Tool / ExecutionContext / Hooks 常量 / ToolNames / Dispatcher / TurnExecutor）应成为稳定契约。s13 团队、s16 workflow、s17 goal loop 都应建立在它之上，而不是各自再写一套循环。
2. **承认“长任务”是一等公民**：预算、重试、超时、取消、目标锚点、退出清理，应该作为一组明确的契约，而不是散落在各机制里的注释。这也是“从学习项目变成长期可用工具”的分水岭。
3. **把服务端不确定性当成常态**：provider 会换措辞、会限流、会返回未知块、会拒绝 schema。分类错误（可重试/不可重试/需要压缩）应集中在一处，而不是 `AgentLoop` 与 `SubagentRuntime` 各判一次。
4. **不要引入不必要的复杂度**：当前不需要 Spring、事件总线、DI 框架、缓存层、内容寻址存储或全面异步化。上面三套方案都能用“一个接口 + 一个共享类 + 一次保行为搬包”完成。
5. **保持“诚实报告”的文化**：项目最宝贵的非代码资产是“不把没做的说成做了、不把近似说成精确”。ARCH-019/ARCH-032 这类文档—实现偏差应作为持续纪律，而不是一次性清理。

---

## 7. 交叉验证、反例与自我修订

### 7.1 与同目录既有审查（`docs/reviews/architecture/ChatGPT/`）的对照

本次独立完成分析后才对该报告做交叉核对（只读其最终报告与目录结构）。结论：

| 主题 | 本次编号 | ChatGPT 编号 | 是否一致 |
| --- | --- | --- | --- |
| 多层软链接未解析 | ARCH-027 | ARCH-001（P1） | 一致，独立得出 |
| 父子执行步骤重复分叉 | ARCH-002 | ARCH-010（P2） | 一致性得出；本次因“已可列举行为差异”定为 P1 |
| 父任务无预算 | ARCH-006 | ARCH-008（P1） | 一致 |
| 记忆整库先删后建 | ARCH-033 | ARCH-004（P1） | 一致，本次定 P2（触发需崩溃） |
| 长任务丢原请求 | ARCH-038 | ARCH-005（P1） | 一致，本次定 P2 并补了 snip 路径证据 |
| 空白配置未拒绝 | ARCH-001 | ARCH-016（P2） | 一致 |
| 包结构/共享契约 | ARCH-004 | ARCH-019（P2） | 同向；本次更强调具体环与常量重复 |
| 工具/安全能力落差 | ARCH-028 | ARCH-002（P1） | 一致；本次按“已声明边界”降为 P2 |
| 同步 shell 丢截断标记 | — | ARCH-007 | 本次未单列（属小渲染问题） |
| SDK 丢根级 schema | ARCH-025 | ARCH-009 | 一致 |
| 辅助记忆不继承取消 | ARCH-017 | ARCH-018 | 一致 |
| 任务生命周期无释放 | ARCH-034 | ARCH-012 | 一致 |
| 多 CLI 同库写 | 观察（tasks.md） | ARCH-013 | 本次未单列，视为已声明边界 |

**本次独有、对方未列出的**：ARCH-003（工具顺序不确定）、ARCH-005（纯转发门面/`ToolExchangeBoundary`）、ARCH-008（日志散落，对方只提“hooks 挂普通 hook”）、ARCH-009（三处存储重复实现）、ARCH-011（工具异常升级为任务失败）、ARCH-020（`Message` 不变量）、ARCH-029（命令超时不可配）、ARCH-031（Linux-only）、ARCH-035/036（全量扫描、cron 去重键）、ARCH-037（测试覆盖分布）。

两份报告不存在互相推翻的结论；差异主要在**定级**（本次对 ARCH-002/028/033/038 更保守），已在各处说明理由。

### 7.2 自我反例检查（哪些结论可能被推翻）

- **ARCH-006（父任务无预算一定会失控？）** 不一定：多数任务会自然收敛。定 P1 的依据是“后果 × 自动化触发条件”，不是“已观察到失控”。若用户认为个人使用不需要预算，可降为 P2；但至少应有可观测的计数与一个开关。
- **ARCH-002（分叉是否真的有害？）** 当前差异中，未知工具“不进 hook”在子代理里其实是**更严格**的处理（避免假日志）。所以分叉不全是 bug。真正的问题是“协议有两份”，而不是“每一处差异都是错的”。
- **ARCH-021（字符数估算是否够用？）** 对个人单用户、以英文代码为主的任务，50k 字符阈值可能足够。它的影响依赖任务语言构成，属“有依据的风险”。
- **ARCH-027（是否可被实际利用？）** 需要工作区内存在两跳链接。如果用户的仓库从不会有这种链接，影响有限；但“安全边界”的判定标准不应建立在“假设不会有”上。
- **ARCH-033（崩溃窗口是否值得修？）** 需要大规模整理 + 恰好崩溃。概率低，但后果是整库知识丢失，且同类单文件写入都已是原子的，这里“不齐”显得不一致。

### 7.3 反例：不能推导出的结论

- 不能因为 `SessionRuntime` 大就要求“拆成多消费者”：ADR 0002 明确要求单一消费者，本次建议是收窄接口，不是拆散所有权。
- 不能因为压缩用字符数就否定整个压缩模块：它是全项目测试最充分、失败语义最清楚的部分。
- 不能因为 `docs/notes/other-agents-analysis/` 存在就对标外部 Agent：用户明确限定不对标，本次未使用。

---

## 8. 审查局限与待验证事项

- 本次为**静态只读审查**：未构建、未运行测试、未启动 CLI、未调用模型；所有“运行期行为”结论要么来自静态推理，要么标注为待验证。
- **待验证（不构成已证实缺陷）**：
  - V-1 空 `.env` 值是否真在请求时才失败（ARCH-001 的行为验证）。
  - V-2 工具数组顺序是否实际影响 DeepSeek 前缀缓存命中（ARCH-003 的成本影响）。
  - V-3 父任务是否真的出现轮数失控 / snip 丢目标（ARCH-006/038 的触发频率）。
  - V-4 记忆提取/整理的语义质量与费用（ARCH-017 的实际开销）。
  - V-5 会话关闭时辅助记忆调用能否及时停止（ARCH-017，取决于 HTTP 客户端）。
  - V-6 多进程同库写入行为（tasks/cron/memory 的并发边界）。
- **未充分审查**：`src/test` 未逐方法证明；`docs/jobs/s01–s11` 仅按需抽读；第三方依赖源码未读；`.env` 未读。
- **版本漂移**：行号与文件名以 HEAD `f15388d` 为准，后续提交可能改变；报告不保证代码不变时仍逐行对应。
- **未做的事**：未修改任何非报告文件；未覆盖既有 `ChatGPT/` 报告；未在指定目录外创建文件。

---

## 9. 完成标准自检

| 完成条件 | 状态 |
| --- | --- |
| 建立并核对仓库审查清单 | 完成（`00-coverage.md`） |
| 全仓库覆盖（源码/测试/配置/构建/文档） | 完成；主源码 73 个文件全部深入或基础检查 |
| 全局架构分析 | 完成（`01-global-architecture.md`） |
| 所有主要模块独立分析 | 完成（`02-modules/` 10 份） |
| 跨模块执行链路 | 完成（`03-cross-module.md`） |
| 可靠性架构检查 | 完成（agent/tools/context/memory/cron 各报告 + 03） |
| 文档与源码一致性 | 完成（04 + ARCH-019/032） |
| 重要问题附源码证据 | 完成（每个 ARCH 都有文件/行号或类/方法） |
| 反例与相互推翻检查 | 完成（本文第 7 节） |
| 问题分类与优先级 | 完成（38 项，P0 无、P1×3、P2×18、P3×17） |
| 具体设计级优化方案 | 完成（第 4 节 A/B/C 三套） |
| 解决报告间重复与矛盾 | 完成（跨报告引用同一编号，不再重复展开） |
| 未检查范围与待验证记录 | 完成（00 第 7 节、本文第 8 节） |
| 未修改非报告文件 | 已遵守 |
| 未执行构建/测试/项目程序 | 已遵守 |

**结论**：审查完成，无未完成阶段。唯一需要用户裁决的是 ARCH-037（是否补可重复回归资产）与 ARCH-028（bash 信任模型的产品取舍）。
