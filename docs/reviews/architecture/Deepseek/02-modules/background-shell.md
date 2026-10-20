# 模块报告：后台命令与 shell 生命周期（`tools/ShellCommand`、`tools/BackgroundManager`）

覆盖：`ShellCommand`、`BackgroundManager`，以及 `BashTool` 中与后台相关的分派部分（`BashTool` 整体见工具报告）。

## 1. 模块职责

- `ShellCommand` 是 Linux shell 执行器：`setsid` 建进程组、有界收集输出、超时清理、退出时按进程组终止。
- `BackgroundManager` 持有后台作业表与完成结果队列，提供 `start / collect / acknowledge / restore / cancelRequired / close`，并负责“完成时叫醒会话”的回调。

`BashTool` 把 `run_in_background` 与 `wait_for_completion`（默认 true → `required`）映射到后台管理器。

## 2. 设计上合理的地方

- **区分“启动回执 / 结果取出 / 结果确认”三件事**：`jobs` 保留到 `acknowledge`，`ready` 只放已跑完结果（`BackgroundManager.java:20-26,166-196`）。这让定时任务“第一次模型回复前失败”可以把结果 `restore` 回去（`206-215`），而不必重跑命令。
- **每条后台命令独立 `ShellCommand`**：`start` 为每条命令新建 shell（`103`），所以 `cancelRequired(owner)` 只停“本任务必须等待”的命令，不误杀脱离任务或其它任务的命令（`224-232`）。这是 ADR 0001 的关键需求，代码确实做到了。
- **完成回调在锁外调用**：`run` 先在锁内取 `listener`，再在锁外 `signal.run()`（`145-152`），避免“管理器锁 ↔ 队列锁”互等。注释明确说明理由。
- **通知转义**：`Completion.notification()` 把命令与输出里的 `<>&` 转义（`266-275,284-286`），减少“输出内容伪造通知标签”的空间。
- **进程组清理**：`stopGroup` 用 `/bin/kill -KILL -- -<pid>` 终止原进程组，再兜底 `destroyForcibly`；`Running.stop()` 保证只发一次信号，避免向已回收的 PID 重复发（`138-175`）。这是对僵尸/残留进程的认真处理。

## 3. 已证实的问题

### ARCH-029（P2，有依据的潜在风险）：命令超时固定 120 秒且不可配置、不可按命令覆盖

**现状**：`BackgroundManager` 默认 `timeoutSeconds = 120`（`BackgroundManager.java:54`），`Main` 用默认构造（`Main.java:188`）；`BashTool` 的模型可见参数只有 `command / run_in_background / wait_for_completion`（`BashTool.java:101-104`），没有 timeout。`ShellCommand` 对同步与后台命令共用这同一超时。

**为什么是问题**：真实编码任务的构建、测试、依赖安装、编译常常超过 2 分钟。超时后 `ShellCommand` 返回 `Error: Timeout` 并杀掉进程组（`ShellCommand.java:91-93`），任务只能失败或让模型改跑别的命令。README 也写明“默认每条命令 120 秒”，但没有任何调高途径——只能改源码。

**影响条件**：任何耗时 > 120 秒的命令（大型 `mvn test`、`npm install`、容器构建等）。

**影响范围**：长任务的可行性与用户体验；这是“长期可用”目标下的现实瓶颈。

**建议**：至少把超时做成 `AppConfig` 项（例如 `BASH_TIMEOUT_SECONDS`），并允许 `bash` 工具可选传入 `timeout_seconds`（带上限校验，防止模型设置成无限）。默认值可保持 120。注意：一旦允许调大，退出清理与 `cancelRequired` 的行为不能变，仍要靠进程组 kill。收益是让后台能力真正可用；代价是需要限制最大值并更新工具 schema。

### ARCH-030（P3，可选优化）：后台结果没有完整输出落盘，通知只保留 500 字符

**现状**：`ShellCommand` 最多保留 50,000 字符（`ShellCommand.java:23,114-128`），`BackgroundManager` 的通知只取前 500 字符并标注截断（`32,266-270`），且注释明确“没有完整输出文件”。

**为什么值得改**：与上下文压缩层已经具备的“大结果落盘 + 可信路径回读”能力不一致。后台命令的输出往往正是模型后续需要的内容（例如构建日志尾部），直接丢掉会迫使模型重跑命令。

**建议**：复用 `context` 的 `ToolOutputStore` 语义（或抽出一个共享的“输出落盘”能力），把超过阈值的后台输出写到工作区并给出绝对路径。注意：`context` 目前不依赖 `tools`，需要在包结构（ARCH-004）确定后再安排，避免引入新的环。收益是后台能力与压缩能力对齐；代价是磁盘增长与清理策略。

### ARCH-031（P3）：`ShellCommand` 依赖 Linux 专用绝对路径与外部命令

**现状**：`/usr/bin/setsid`、`/bin/sh`、`/bin/kill` 硬编码（`ShellCommand.java:75,162`），缺 `setsid` 时直接失败而不降级。

**影响**：非 Linux（macOS 无 `setsid`）无法运行。这是已知边界（README、任务单都写了“面向 Linux”）。仅作为“长期可移植性”的提醒，不建议现在改动。

## 4. 反例与缓和因素

- 不能用“命令超时”证明后台机制失败：`wait_for_completion` 的核心语义（当前任务必须处理结果、但不阻塞会话）实现是正确的，且真机验收记录（`docs/jobs/s12/terminal-and-acceptance.md`）已经覆盖了必等恢复、FIFO、脱离通知、退出清理。
- `MAX_PENDING = 32` 看似偏小，但对单用户 CLI 足够；真正的问题是超时，而不是容量。

## 5. 修改顺序建议

1. ARCH-029（超时可配 + 可按命令覆盖）：与“长期可用”目标直接相关，改动局部。
2. ARCH-030（后台输出落盘）：排在包结构（ARCH-004）调整之后，避免新增跨包环。
