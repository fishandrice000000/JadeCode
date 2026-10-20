# 包架构专题：按职责和依赖重新划边界

> 2026-10-17 更新：用户要求优先审查架构。最新包边界建议见 [架构优先复审](../06-package-boundaries-2026-10-17.md)：agent 仅执行器、会话归 session、CronProducer 归 cron、进程能力归 process。下文保留初版诊断；与新职责方案不同处以复审为准。

本报告响应用户在审查过程中提出的重点：BackgroundManager 和 ShellCommand 放在 tools 下是否妥当。只分析当前 JadeCode，不参考其它 Agent 的包设计，不移动源码。

## 直接判断

**长期维护时，这两个类更适合移出 tools，放入独立的进程执行包。** 目前目录组织可以解释为“与工具有关的所有东西”，短期查找方便；但它已经把模型协议入口、操作系统进程管理、后台结果记录三种变化原因放在一起。新的会话运行器直接依赖 tools 内的管理器，也使 tools 逐渐变成底层能力和上层适配的混合包。

判断依据是职责和调用者，不是类名。BashTool 实现 Tool，定义模型看到的参数、检查布尔值并转换工具返回文字，它属于工具适配。ShellCommand 不实现 Tool，不知道模型 schema、审批或历史，只处理进程与输出。BackgroundManager 也不实现 Tool；除 BashTool 外，它直接服务会话运行器，负责命令登记、容量、归属、通知领取/确认和清理，已是会话依赖的运行基础设施。

## 三者各自应该拥有什么

| 对象 | 当前真正负责的事 | 建议所属与职责 |
| --- | --- | --- |
| BashTool | 工具元数据、输入检查、同步/后台选择、回执文字 | `tools`：继续作为模型协议适配 |
| ShellCommand | Linux 进程组、超时、输出排空、结束与清理 | `process`：进程执行基础能力 |
| BackgroundManager | 命令 ID、运行/未确认记录、结果确认、按归属取消 | `process`：命令服务；不决定 Agent 何时完成 |
| SessionRuntime | 新任务 FIFO、当前目标、等不等、何时继续/失败/结束 | `agent`：会话控制；不启动和清理 OS 进程细节 |
| ExecutionContext | 可信来源、执行身份、审批与取消通道 | 小型共享执行契约包；避免让基础设施依赖具体 Agent 包 |

BackgroundManager 携带 owner 和 required 属性是合理的：命令服务需要按归属检索和取消。但是“当前任务还能不能完成”由 SessionRuntime 判断，不能因为管理器保存了 required 就把整个 Agent 调度职责一起迁入 process。反过来，也不能把 kill、输出 reader 和进程集合挪进运行器。

## ARCH-019：tools 同时装了适配层和进程基础设施

**P2；可选优化；分类：包职责与依赖优化机会。** 不是运行缺陷，不以文件超过某行数作为理由。

### 当前依赖为什么不够理想

现在 Main、BashTool 和 SessionRuntime 都直接依赖 tools.BackgroundManager；tools.BackgroundManager 又依赖 agent.ExecutionContext。这使“模型调用某个工具”和“会话等待一个命令”在包层面捆绑，后来从 CLI 之外使用命令执行器时还要带入 Agent 身份。ShellCommand 的包私有可见性同时被 BashTool 和 BackgroundManager 利用，直接挪文件会破坏访问，说明迁移必须重画公开接口，不能只是改 package 字符串。

### 推荐的具体结构

```text
com.jadecode
├── agent/                 会话推进、历史所有权、父执行器
├── execution/             共享执行契约：身份、来源、取消、审批端口
├── tools/                 Tool 与面向模型的各工具适配
├── process/               shell 执行、后台命令与命令结果
├── safety/                路径/命令授权策略
├── llm/                   模型请求端口和供应商适配
├── messages/              模型消息协议
├── context/               模型可见历史整理
├── memory/ tasks/ cron/   各自的领域规则与文件存储
└── cli/ config/ util/     输入输出、配置及小型公共辅助
```

process 的第一步只需要两个主要角色，不必为每个方法都创建接口：

1. 将现有 ShellCommand 作为明确的公开同步执行能力（例如命名为 ShellExecutor），只公开构造、execute、结果与 close；Running、进程集合和 kill 实现继续私有。BashTool 的同步子代理路径也可以使用它。另一种选择是统一由一个公开命令服务封装包私有 ShellCommand，但这会调整更多生命周期，当前不必强制采用。
2. 将现有 BackgroundManager 迁入 process，继续使用同步执行能力，公开父 BashTool 需要的前台执行入口，以及运行器需要的结果/取消入口。不要把 raw Process、进程组或线程暴露出去。
3. 后台 start 接受命令和明确的归属标识、等待属性，而不是整个 agent.ExecutionContext。工具调用侧负责来源与授权；process 检查自身是否关闭，并管理其启动/关闭竞态。取消和审批的跨层契约与 ARCH-011 同批梳理，不能在迁移时把取消检查丢掉。
4. 将 Completion 中“给模型拼 XML 文本”的职责逐步移到结果通知适配处；process 只交出结构化的命令完成事实。这样 CLI 展示与模型通知可以分别渲染，转义和截断含义统一，处理 ARCH-007 时尤其有收益。

迁移后依赖应为：

```text
Main ──装配──> agent / tools / process / safety
agent ──────> process（取命令事实、取消）
tools ──────> process（启动命令）
agent、tools、safety ──> execution（共享契约）
process ────> JDK；不反向依赖 agent、tools、cli
```

若以后命令执行要支持容器或远程宿主，再在 process 外边界增加可替换端口，届时有真实第二种实现。当前只为去包环而创造 ShellFactory、ProcessRepository 等接口，收益不足。

### 收益、代价和迁移约束

收益是 tools 可以稳定表示模型适配层，进程能力有独立生命周期和结果契约，安全隔离与输出完善有明确落点。影响 Main、BashTool、BackgroundManager、ShellCommand、SessionRuntime 和相关测试引用；成本中。兼容风险在可见性、前台 shell 的退出清理、子代理工作目录和后台 listener；仅移动包不应改变工具名称、JSON 参数、120 秒上限、32 项容量和通知语义。

优先级 P2：现有依赖目前能正常解释，没有证据说明放在 tools 就会执行出错；但它已经跨越多个调用者，工程收益比纯目录美化明确。建议先确定路径/权限与结果契约，再做一次保行为迁移，避免把安全重写、进程替换和包迁移揉成无法审查的一次改动。

### 源码证据

- `src/main/java/com/jadecode/tools/BashTool.java:21,101–109,141–179`：模型适配、参数校验、调用后台和同步进程能力。
- `src/main/java/com/jadecode/tools/ShellCommand.java:22–43,63–126,138–195`：纯进程执行与生命周期；类和 run 当前包私有。
- `src/main/java/com/jadecode/tools/BackgroundManager.java:28–48,96–121,166–231,255–285`：不仅服务工具，也提供会话消费/确认接口；start 当前接收 ExecutionContext，Completion 自带模型通知渲染。
- `src/main/java/com/jadecode/agent/SessionRuntime.java:20–21,101–108,287–294,322–348,404–415`：会话直接消费 tools 包的命令基础设施。
- `src/main/java/com/jadecode/Main.java:168–190,219–227`：子代理与父会话的不同 shell 装配路径。

## 整仓库还存在哪些同类边界

| 现状 | 判断与具体方向 |
| --- | --- |
| tools 中的 TaskTools、CronTools | 放置合理：它们将模型参数翻译为领域服务调用，规则分别留在 tasks/cron。无需随 BackgroundManager 一起全部移走 |
| tools.SubagentTool 与 subagent.SubagentRuntime | 适配与运行职责已分开，方向合理；执行算法重复见 ARCH-010 |
| llm 请求直接持有 List<Tool> | 模型只需名称/描述/schema，不需执行对象；可拆工具定义值对象，见 ARCH-009/011 |
| agent.ExecutionContext 引用 safety.Approval，safety 又引用它 | 属于共享契约放错依赖层；调整后消除该方向的包环，不代表要拆 Maven 模块 |
| Main 同时写装配、内置 hook、提示词 | 单个装配根是优点；随着提示增长可按职责提取 prompt 和终端观察者，但没有理由把每个 hook 抽成类 |
| context 中一个门面加多种内部策略 | 职责基本内聚，内部类包私有有价值。无需把每种压缩策略单独变成顶级包或接口 |
| memory/tasks/cron 各自带文件持久化 | 当前规模可接受。先统一少量发布原语和工作区所有权；有第二种存储需求时再拆端口，不能为了“分层”预设数据库 |
| util.Json 与 util.Ansi | 两个很小的技术 helper，不构成杂物包；Ansi 未来更适合 CLI 展示边界，但当前没有证实的维护障碍 |

## 迁移的检查标准

包迁移应能回答：谁可以启动进程、谁能判断任务结束、谁拥有资源、基础设施是否依赖 Agent、模型是否只看到定义、批准/取消是否依然传递。按这些标准检查比强求所有 import 单向更有价值。ARCH-019 与 ARCH-011 有依赖关系但不是重复：前者处理进程职责落点，后者处理共享执行契约与强制授权入口。

Review：**Non-blocking（明确建议调整）**。这是一项有实际收益的包架构改进，未获授权也未实施迁移。
