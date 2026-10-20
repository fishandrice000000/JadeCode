# 模块报告：持久任务图（`tasks/`）

覆盖：`Task`、`TaskStore`、`TaskTools`。

## 1. 模块职责

`TaskStore` 是工作区 `.tasks/{id}.json` 的持久任务图：创建 → 加依赖 → 认领 → 完成。`TaskTools` 把它适配成六个模型工具（`create_task/update_task/get_task/list_tasks/claim_task/complete_task`）。`owner` 固定为 `agent`，模型不能填。

## 2. 设计上合理的地方

- **不变量前置**：`Task` 的紧凑构造器校验 ID 格式、标题/描述非空、owner 与状态一致性、依赖 ID 合法且无自依赖/重复（`Task.java:31-50`）。`dependencies` 用 `List.copyOf` 切断调用方引用。
- **先创建节点再连边**：依赖只能在 `pending 且无人认领`时添加（`TaskStore.java:148-150`），新增边前用 `dependsOn` 做环检测（`253-267`），全部边检查通过才保存，失败不留部分依赖。
- **认领检查前置条件**：`claim` 要求所有依赖存在且 `completed`（`175-191`），否则返回 `Blocked by: ...`。
- **完成报告“刚解锁”的下游**：先记录 `readyBefore`，避免每次完成都重复报告已可做的任务（`204-232`）。
- **写入与读取都严格**：`save` 先写临时文件再 `ATOMIC_MOVE` 替换（`269-286`）；`readTask` 逐字段判型、拒绝尾随 token、拒绝文件名与 ID 不一致、拒绝软链/目录（`288-335`）。
- **语义诚实**：多处注明 `completed` 只表示“完成操作被接受”，不代表代码通过验收；`claim` 不启动线程或子代理。

## 3. 已证实的问题

### ARCH-009（P2，重复实现）：与 `CronScheduler`、`MemoryStore` 重复实现“工作区安全文件存储”

**现状**：`TaskStore.save`（`269-286`）、`CronScheduler.commit`（`230-253`）、`MemoryStore.saveNew/replaceRecords`（`136-200,219-318`）各自实现了同一类逻辑：把工作区解析成真实路径作为围栏、拒绝软链/目录、写临时文件、原子替换、失败只清理本次文件、把清理异常挂成 suppressed、对安全敏感目标做“发布前再查一次类型”。

三份实现细节还略有差异：

- `TaskStore.save` 只保护单个文件，天然无“删除+创建”窗口；
- `CronScheduler.commit` 用“payload 是否相等”决定要不要写盘（`232`）；
- `MemoryStore.replaceRecords` 是整库替换，需要额外的快照比对与恢复（见 `02-modules/memory.md` ARCH-033）。

**为什么是问题**：这是最容易“看起来都对了、其实某一份漏了一个检查”的代码。`PathResolver`/`PathGuard` 只服务文件工具，不覆盖这些存储目录；存储的安全不变量没有一个共享的、被测试覆盖的位置。

**建议**：抽出一个包内/共享的 `WorkspaceFileStore`（或 `AtomicFile`）工具：负责“围栏 + 临时文件 + 原子替换 + 清理 + suppressed 归属”。各存储类只提供“序列化内容”和“目标路径”。收益是安全不变量单点维护；代价是迁移期间要逐字节比对行为（尤其 `MemoryStore` 的整库语义不能被单文件工具误用）。注意这与“不修无关问题”的纪律冲突：应作为独立任务单，而不是顺手改。

### ARCH-034（P3，可选优化）：`in_progress` 任务没有“释放/失败/重新认领”过渡

**现状**：状态机只有 `pending → in_progress → completed`。`complete` 要求 `owner` 与原负责者精确一致（`204-211`）；没有 `release`/`fail`/`reopen`。一旦任务被认领但实际无法完成（依赖被删、方案放弃、上下文丢失），它只能停留在 `in_progress`，而 `updateDependencies` 又要求 `pending`（`148`），所以也无法再改依赖。

**影响**：跨会话恢复时，被放弃的 `in_progress` 任务会一直阻塞其下游（下游只要求 `completed`，所以其实不会“阻塞”，但会让用户/模型看到无法推进的节点）。功能上不致命，属于生命周期完整性。

**建议**：增加一个显式的 `release`（回到 `pending`、清 owner）或 `fail`（新状态）操作，并在工具描述里明确“放弃工作”的语义。收益是跨会话恢复更完整；代价是要定义状态并保持旧 JSON 可读。

### ARCH-035（P3，可选优化）：每次操作都全量扫描并重读任务文件

**现状**：`updateDependencies/claim/complete` 都调用 `snapshot()` → `list()`，即列出目录并按顺序读回**全部**任务 JSON（`TaskStore.java:235-241,112-130`）。一次状态变更在 `complete` 里还可能读两次（`snapshot` + `readyBefore` 计算都在内存，但 `list()` 本身只调一次）。

**影响**：几十个任务无感；任务图变大后，每次认领/完成都 O(n) 磁盘读。对“个人项目的工作图”通常够用，属于未来扩展点。

**建议**：如果要优化，引入一个轻量索引（例如单个 `index.json` 汇总 id/subject/status/blockedBy），正文仍按需 `get_task`。不建议现在做。

## 4. 反例与缓和因素

- `TaskStore` 的“按顺序执行、非事务、不保证多进程互斥”已经被任务单与 README 明确声明；多人/多 CLI 同库不是当前支持范围。ChatGPT 报告的 ARCH-013 提到“单写者约定未阻止多 CLI 同库写”，属于同类边界；我没有把它单列成新编号，因为它是**已声明的不支持范围**而非隐藏缺陷，但值得在文档里更醒目地提示。
- `TaskTools` 的 `owner` 固定为 `agent` 是刻意的（单用户），不是缺陷。

## 5. 修改顺序建议

1. ARCH-034（生命周期补全）：低成本、直接改善跨会话恢复。
2. ARCH-009（存储工具抽取）：中等成本、与 cron/memory 同批做更划算。
3. ARCH-035：最后考虑。
