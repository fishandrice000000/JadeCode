# 模块报告：定时调度（`cron/` 与 `agent/CronProducer`）

覆盖：`CronExpression`、`CronJob`、`CronScheduler`、`CronProducer`、`CronTools`。

## 1. 模块职责

`CronScheduler` 保存定时定义与“待交付”状态并持久化到 `.scheduled_tasks.json`；`CronProducer` 每秒检查并把到期任务交给 `SessionRuntime` 排队；`CronTools` 提供 `schedule_cron/list_crons/cancel_cron`；`CronExpression` 做五段 cron 解析与匹配。

## 2. 设计上合理的地方

- **解析器只管语法、匹配器只管当下**：`CronExpression` 拒绝六段/未知语法/隐式数字（`CronExpression.java:36-89`），`matches` 只回答“这一分钟是否命中”，不启动任务、不读时钟。
- **“交付标记先落盘，再内存排队”**：`pollDue` 先 `commit(next)`（pending=true 写盘）再 `ready.addAll`（`CronScheduler.java:116-132`）。这样即使随后进程崩溃，重启后 `load()` 会把 pending 项重新放进 `ready`（`192`），不会丢任务。
- **pending / lease / acknowledge 三态**：`takeDue` 租借（`138-146`），`acknowledge` 才清 pending（周期）或删除（一次性）（`160-168`），`restore` 放回（`149-152`）。这样“排队成功 / 模型已收到”被拆开，与 ADR 一致。
- **不补跑停机分钟、同分钟去重**：`pollDue` 跳过 `pendingDelivery` 或 `lastFired == 当前分钟` 的项（`123`）。
- **失败有退避与暂停**：`CronProducer.failed` 释放 + 5 秒退避 + 连续 3 次暂停（`CronProducer.java:149-154`），并且**不阻塞人工请求**（人工请求走 `submit`，不经过 `canDeliver`）。
- **持久记录只保存 durable 项**，恢复时拒绝非 durable/重复记录（`CronScheduler.java:183-189`）。

## 3. 已证实的问题

### ARCH-018（P3，可选优化）：一次轮询错误会暂停所有定时投递，且没有显式恢复入口

**现状**：`CronProducer.poll` 捕获 `IOException | RuntimeException` 后设置 `lastPollError`（`94-98`）；`canDeliver()` 要求 `lastPollError == null`（`106-109`），否则不投递。`lastPollError` 只在下一次**成功**的 `poll` 里被清空（`85`）。如果失败是持续性的（例如状态文件被改成不可读），定时投递会一直停摆；用户能看到的只有一条去重后的错误行。另外 `failures >= 3` 的暂停也没有显式恢复入口，只能重启或等 `received` 重置。

**为什么值得改**：定时是自动化能力，静默停摆比明确报错更糟；当前设计靠“错误信息去重 + 不重复刷屏”来换可读性，代价是用户可能不知道定时已经不再执行。

**建议**：给 `list_crons` 或 CLI 增加一个“调度健康状态”（是否暂停、上次错误、连续失败数）的可见查询；或在暂停时提高警告级别/只在状态变化时提示一次并持续保留。不建议自动无限重试，但应让“停了”这件事可被发现。收益是可观测性；代价很小。

### ARCH-036（P3，可选优化）：`lastFired` 用“带偏移的分钟字符串”做去重键，跨时区/夏令时存在边界

**现状**：`pollDue` 生成 `minute = now.truncatedTo(MINUTES).toOffsetDateTime().toString()`（`CronScheduler.java:118-119`），并与持久化的 `lastFired` 字符串做 `equals`。`CronJob` 校验 `lastFired` 可被 `OffsetDateTime.parse`（`CronJob.java:31`）。

**为什么是问题**：如果进程时区跨夏令时切换（或用户改了系统时区后重启），同一“本地分钟”可能对应不同的偏移字符串，从而**重复触发**；反之，如果偏移从 `+08:00` 变成 `Z`，去重的字符串比较会失效。当前五段 cron 是按本地时间匹配的（`ZonedDateTime.now(clock)`），但去重键却带上了偏移。

**建议**：持久化并用“本地日期时间（不含偏移）”或“instant 截断到分钟”作为去重键，二选一并写清语义；同时保留 `lastFired` 的人类可读格式。收益是消除重复触发的一个来源；代价是旧文件的兼容读取。

## 4. 其它观察（不单列编号）

- `CronScheduler` 的所有公开方法都是 `synchronized`，并且明确“一个工作区只由一个 CLI 管理”。这是**已声明**的限制，不是隐藏缺陷；但如果用户同时开两个 REPL，`.scheduled_tasks.json` 会被两边覆盖（ChatGPT 报告 ARCH-013 属同类）。建议在文档里把“不要并发开两个同工作区 REPL”写得更显眼，或加一个简单的锁文件。
- `CronTools` 的 `schedule_cron` 会把 `durable` 默认 true，但**`durable` 不等于“CLI 退出后还会执行”**——工具描述已经写清楚（`CronTools.java:31`），`run` 模式根本不启动调度。这是文档与实现一致的好例子。
- `CronScheduler.list()` 每次 `load()`，`CronProducer.pending()` 也在每次任务执行时 `list()`（`CronProducer.java:119-122`），会重复读文件。量小无碍。
- `MAX_JOBS = 100`、prompt ≤ 16000 字符、状态文件 ≤ 16MB 都有上限，合理。

## 5. 修改顺序建议

1. ARCH-018（健康可见性）：小改动、直接提升自动化可信度。
2. ARCH-036（去重键）：与 ARCH-018 可同批；注意兼容旧文件。
3. 多进程保护：需要用户裁决是否属于支持范围，不擅自加锁。
