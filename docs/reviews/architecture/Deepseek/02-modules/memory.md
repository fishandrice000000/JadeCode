# 模块报告：长期记忆（`memory/`）

覆盖：`MemoryManager`、`MemoryRecallService`、`MemoryExtractionService`、`MemoryConsolidationService`、`MemoryStore`。

## 1. 模块职责

一条完整生命周期：任务入口 `MemoryManager.prepareSystem` 查一次相关记忆并拼进 system；任务真正结束后 `finishTask` 提取新记忆，新增成功再按数量触发整理。

- 召回：先把“目录（name/description/type）”交给辅助模型选择相关索引，再读正文，最多 5 条、正文预算 20000 字符；模型选择失败则用关键词降级。
- 提取：把最近 12 条消息的纯文本交给模型，只保存 `scope=persistent` 且字段完整的候选。
- 整理：≥10 条时把整库交给模型，要求返回“完整替换库”，程序校验后发布。

## 2. 设计上合理的地方

- **“选目录、再读正文”**：`MemoryRecallService.recall` 不把整库正文塞进上下文（`MemoryRecallService.java:83-143`），并且用**同一次快照**固定索引与正文的对应关系，避免“模型给索引、代码重新扫描导致索引漂移”（注释明确）。
- **验证严格、不猜值**：召回/提取/整理的回复都要求“assistant 纯文本 + 完整 JSON + 严格索引范围”；`FAIL_ON_TRAILING_TOKENS` 拒绝尾随文本（`MemoryRecallService.java:153-178`、`MemoryExtractionService.java:136-152`、`MemoryConsolidationService.java:106-127`）。`MemoryStore.parseRecord` 也拒绝把数字/数组强制转成元数据（`MemoryStore.java:440-445`）。
- **不声称语义保证**：注释反复说明“格式校验不证明知识真实”“整理质量依赖模型”，没有把机制说成能力。
- **失败不阻塞主任务**：召回 IO 失败只告警、仍用基础 system 继续（`MemoryManager.java:63-66`）；提取/整理失败不影响已经完成的主任务回复（`105-108`）。
- **文件安全严谨**：`.memory` 库根用 `toRealPath` 围栏、拒绝越界链接、`saveNew` 用 CREATE_NEW 不覆盖、索引原子替换、整理用 `expected` 快照比对防止用旧结论覆盖新库（`MemoryStore.java:77-119,136-200,219-318`）。

## 3. 已证实的问题

### ARCH-017（P2，有依据的潜在风险）：记忆的辅助模型调用不继承会话取消

**现状**：召回发生在 `SessionRuntime.execute` 调 `AgentLoop.taskSystem` 时（`SessionRuntime.java:228`），提取/整理发生在 `runTask` 末尾的 `engine.finish(history)`（`302`）。这两处都没有把“会话是否关闭”传进记忆层；`MemoryManager.prepareSystem`/`finishTask` 只用 `try/catch IOException`（`MemoryManager.java:53-68,81-109`），不检查任何取消信号。辅助调用使用与主任务相同的 `LlmClient`（同一端点、同一模型）。

**为什么是问题**：

- 用户 `q` 退出时，`SessionRuntime.close()` 会中断 consumer 并只等 2 秒（`521-528`）；如果 consumer 正卡在记忆的辅助模型调用里，`join(2000)` 超时后打印“Model/IO still finishing”，但辅助调用仍可能继续消耗 API 并写文件。
- 召回在任务开始前、提取在任务结束后，都属于“用户可能已经不想等”的时刻。

**影响范围**：退出体验、意外费用、退出后仍写 `.memory`（虽不撤销已发生写入）。

**证据强度**：已证实（代码无取消检查）；实际影响待验证（V-5，取决于 HTTP 客户端对中断的响应）。

**建议**：把 `BooleanSupplier cancelled`（或 `ExecutionContext` 的退出信号）传进 `MemoryManager`，在每次辅助调用前后检查；`AgentLoop.finish` 本来就有 `ensureOpen()`，可以扩展成“会话未关闭才做记忆维护”。至少让 `close()` 不必为辅助调用等满 2 秒。收益是退出干净、费用可控；代价是记忆维护变得更“可能被跳过”，需要定义“跳过是否算失败”（建议不算，只记录）。

### ARCH-033（P2，有依据的潜在风险）：整库整理先删旧记录再建新记录，进程崩溃存在无恢复窗口

**现状**：`MemoryStore.replaceRecords` 的发布顺序是：写临时候选 → 重新校验快照 → **逐个删除旧记录** → 创建新记录 → 最后原子替换索引（`MemoryStore.java:281-296`）。如果在这两步之间进程被杀（或断电），磁盘上会同时缺少旧记录与新记录；`catch (IOException)` 里的恢复只覆盖“同一进程内的 IO 异常”，用内存中的 `originals` 重写（`297-310`），对崩溃无效。

**为什么是问题**：这是**用户长期知识库**的整库替换。当前实现已经做了很多（临时目录、两次快照比对、失败清理），但“删除 + 创建”不是一个可崩溃恢复的提交。与之对比，`TaskStore.save` 与 `CronScheduler.commit` 都是“写临时文件 + 原子替换单个文件”，天然没有这个窗口。

**影响条件**：整理过程中进程崩溃/被 kill/断电。

**建议**：把整库发布改成“单文件/单目录的原子切换”——例如把新库写进 `staging` 目录并校验，再用一次 `Files.move(..., ATOMIC_MOVE)` 把 `staging` 改名成正式目录（或维护一个 `current` 指针/版本目录）。如果平铺文件名必须保留，则至少先写一个“新库快照 + 恢复日志”，启动时能完成或回滚。收益是消除整库级数据丢失窗口；代价是需要设计目录布局迁移，且要保证旧 marker 引用的旧路径继续有效（与 OPT-002 的“不移动旧文件”约束一致）。

## 4. 其它观察（不单列编号）

- **辅助调用与主任务共用同一模型**：召回选择、提取、整理各一次调用，都用主 `LlmClient`/模型。README/CONTEXT 都说明“不假设有更便宜的独立模型”。对个人 agent 可接受，但每次任务新增 1–2 次调用是稳定开销；若将来引入更便宜的辅助模型，应在 `AppConfig` 显式配置，而不是靠默认。
- **提取输入是“最近 12 条消息的纯文本”**：如果任务中途发生过历史替换（压缩），最近 12 条可能主要是摘要消息与工具结果，提取信号变弱。当前没有消息来源标记（代码注释也承认“不能凭 role 证明是人类长期意愿”）。
- **召回是任务级一次性**：`taskSystem` 只在任务入口查一次，工具续跑/后台恢复沿用同一 system。这与 ADR 0002 的“入口召回背景跨工具和后台续跑保持稳定”一致，合理。
- **`MemoryStore` 每次读取都重新扫描**：`listRecords` 无缓存（`77-119`），召回、提取、整理、`saveNew` 重建索引都会各扫一次。对几十条记录无感；记录上千后全量读取会成为瓶颈（ChatGPT 报告 ARCH-015 也提到类似增长盲区）。属于未来扩展点，不建议现在优化。

## 5. 修改顺序建议

1. ARCH-017（取消继承）：小改动、与关闭路径强相关，可随会话重构一起做。
2. ARCH-033（整库原子发布）：影响用户长期知识，应在“批量整理”被频繁使用前处理；成本中等。
3. 辅助模型与目录归属：排在包结构（ARCH-004）与会话身份（ARCH-026）之后。
