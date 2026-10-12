# s08 里程碑 7B：父循环请求前的自动整理

状态：**GPT 工程复审 Pass；待用户源码学习；本地提交已获批，待 worker 执行**。任务 `GPT-20261012-S08-PARENT-AUTO-001`，实施回信 `DS-20261012-S08-PARENT-AUTO-001-R1`；返工任务 `GPT-20261013-S08-PARENT-AUTO-002`，返工回信 `DS-20261013-S08-PARENT-AUTO-002-R1`；通过回信 `GPT-20261013-S08-PARENT-AUTO-003`。
本单保留工程契约；源码阅读指南和学习问题在代码审查通过后由 GPT 提供，不要求用户据此手写实现。
本任务依赖 7A 职责整理与注释复审 Pass，整章总任务仍为 [context-compact.md](context-compact.md)。

## 1. 先明确需求与场景

Agent 可能连续读取文件、运行测试，但一次也没有请求 compact。工具输出与对话不断增长；不能依赖模型记得请求压缩才维护上下文。

例如一次测试返回超大日志，工具调用和结果已经完整回灌。下一次主任务模型请求之前，应先按既有预算机制将大结果转存为路径与预览；若旧历史仍过大，再逐层整理，必要时生成摘要。

本轮把已实现的 prepare 放到正式请求边界：循环决定什么时候执行，compactor 决定如何整理。主动 compact 的可选策略保持不变，不是自动路径的前置条件。
这与原课程每次请求前调用 prepare 的安排一致；不是新算法，也不声称字符估算能保证请求永不超限。

## 2. 本轮可见成果与职责

- 注入 compactor 的父循环，每次正式 client.complete 之前都会调用 prepare：首次请求、普通工具结果后的请求、Stop hook 强制续跑后的请求。
- 先用原历史捕获一次 activeRequest；后续整理或 synthetic user 消息不改变本次人类请求来源。
- prepare 只返回候选。循环在它正常返回后，带引用保护地原位采用候选，再组装正式请求。
- 小历史允许 no-op，不额外摘要或落盘；既有五参 / null compactor 路径不启用自动整理。
- 压缩失败时，不发起它后面的正式请求，不先清空会话，不重跑已经执行的工具；已有成功落盘保留。

## 3. 精确实现范围

- `src/main/java/com/jadecode/agent/AgentLoop.java`：在 while 内正式请求前接入 prepare 与内联回填；更新依赖、构造器、run 及主流程相关中文注释。
- `src/main/java/com/jadecode/context/ContextCompactor.java`：只更新 compactAfterBatch 中 prepare 尚未接入父循环的失效说明；不得改算法、API、import 或其它方法。
- 新增 `src/test/java/com/jadecode/agent/AgentLoopAutomaticPreparationTest.java`。
- 本任务单：如实记录实施、验证、风险与待 GPT Review 状态。
- `docs/jobs/s08/context-compact.md`：仅在 7A 后续之后增加 7B 链接 / 当前状态，并更新对应 AgentLoop 当前能力说明；不要改历史 Review 结论。
- worker 自己的回信邮箱；不得写上游邮件或 notes / 用户答案。

保持工具 / hooks / todo / Stop 的执行次序和计数、入口校验、主动压缩成功信号及纯 / 混合策略不变。
不重新抽取历史回填 helper；不加枚举、策略接口、回调或泛化请求封装。

## 4. 契约与失败边界

1. 在工具执行过程中不整理历史。正式请求边界的历史应是合法、完整的会话；入口历史仍先校验。
2. 每次 while 迭代，在已有 client.complete 之前直接调用 `compactor.prepare(messages, activeRequest)`；不要只在入口或成功 compact 后调用。
3. 仅在 compactor 非 null 时执行；返回入参本身时不 clear，返回不同列表时原位 clear / addAll，不重绑调用方会话。
4. 摘要专用调用发生在 compactor 内，不再套一次 prepare，也不计算成额外工具轮，不触发循环的工具 / Stop 生命周期。
5. prepare 失败原样传播。其间已生成的预算 / 归档候选不半提交；进入 prepare 时的会话状态仍在。若更早的迭代已经成功采用候选，不承诺回滚整个 run。
6. 此时不调用 reactiveCompact、不添加 catch / 自动重试；字符估算误差与模型超限恢复属于下一单元。
7. 中文 JavaDoc 与行间说明先解释正常请求前整理的需求、为何由 harness 调度、为何不是执行工具中途压缩，再解释候选采用与失败边界；不把自动整理写成每轮都会摘要。

## 5. 场景测试

使用真实 ContextCompactor、注入阈值、假 LlmClient 与临时目录，不调真实模型或读取秘密。至少验证：

1. 小历史 no-op：首次正式请求正常，调用方列表与原消息保留，零摘要 / 零归档，不因同列表回填丢历史。
2. 消息数超 snip 阈值但总字符数仍小：正式请求看到归档标记与合法保留段，证明不能只在总字符数超限时进入 prepare。
3. 首次请求前摘要：构造较大旧文本 + 短当前请求，确保旧结果缩短不能解决；先归档 / 摘要，再正式请求，Current user request 保持原文。
4. 普通工具返回大日志且未调用 compact：第二次正式请求前出现可恢复预算外壳，完整输出已落盘，工具只执行一次，交换 ID / 顺序仍合法。
5. Stop hook 注入较大续跑文本：下一次正式请求前仍整理，但 activeRequest 仍为原用户请求；摘要不额外触发 Stop 或推进工具轮计数。
6. prepare 归档失败：阻止正式请求，异常带 cause，输入会话不被替换，工具无新增副作用。
7. prepare 摘要失败：固定 LlmException 原样传播，成功归档保留，输入会话未清空 / 半提交，无后续正式请求。

假客户端须保存请求快照，清楚区分摘要与正式请求；避免用最后才检查的可变请求引用制造假断言。
既有五参未启用、协议校验、主动 compact、候选失败与生命周期回归必须继续通过；不削弱旧断言或修改无关测试。
阈值应让目标分支确实发生，不把超大当前请求 / 过小阈值导致固定摘要外壳也超限误当成循环接入 Bug。

## 6. 验证与暂停点

定向：

```sh
mvn test -Dtest='AgentLoopAutomaticPreparationTest,AgentLoopManualCompactionTest,AgentLoopTest,ContextCompactorBatchCompactionTest,ContextCompactorApiTest,HistoryCompactorTest,ToolExchangeProtocolTest'
mvn package
git diff --check
```

报告实测数量、失败 / 错误 / 跳过与退出状态；新测试另查行尾空白。
不运行 clean、不删除 target / 旧报告 / 临时候选副本，不调整阈值默认值、依赖或 Java 版本。
完成后回信并停止，GPT 先工程 Review，通过后才提供本轮代码阅读顺序与架构场景题；不宣告整章完成。

## 7. Git 与非目标

邮件第一阶段有用户单独批准的前一单元六文件提交，必须在本轮代码修改前完成；本任务单不属于那六文件，明确排除。
第一阶段提交不是本轮实现后的提交授权。自动整理的新实现与测试在 GPT Review 后另行规划并获批。
不 push、不改历史；上轮 B01–B12 授权不延续。本轮也不改 Main / SubagentRuntime / CLI / prompt / 工具表，不接响应式重试，不实现会话日志索引或恢复产品流程，不处理已有范围外维护项。

## 8. 执行结果

**worker `DS-20261012-S08-PARENT-AUTO-001-R1`（2026-10-12）：已实施，待 GPT Review。**

- **循环接入**：`AgentLoop.run` 的 while 循环内、已有 `client.complete` 之前新增步骤 1——`compactor != null` 时调用
  `prepare(messages, activeRequest)`，并带引用保护地原位采用候选（返回入参本身则不动；否则 `clear` + `addAll`），
  不重绑局部变量、不提前清空、不用 `return` 离开循环。首次请求、普通工具结果之后的请求、Stop hook 续跑之后的请求
  都经过这里；工具执行过程中不整理。步骤注释重编为 1–8，并更新类头（自动整理与主动压缩是两条独立入口）、
  `compactor` 字段说明（null 时也不自动整理）、`run` JavaDoc（轮次含整理；`activeRequest` 不重新提取；
  新增 `IllegalStateException` 说明）。
- **压缩器**：只更新了 `compactAfterBatch` 中“prepare 尚未接入循环”的失效说明，改为“父循环显式注入压缩器时已自动执行，
  Main / 子循环尚未接入”；未改运行逻辑、API、import 或其它方法。
- **测试**：新增 `AgentLoopAutomaticPreparationTest` 7 条，真实 compactor + 假 LLM（按工具表区分正式/摘要请求并保存快照与先后顺序）
  + `@TempDir`：① 小历史 no-op（零摘要零归档、原消息引用保留、列表身份不变）；② 条数超 snip 而字符仍小（marker + 完整 10 行归档）；
  ③ 首次请求前摘要且 `Current user request` 保真；④ 普通工具大结果转存（工具只跑一次、交换 ID 仍一一对应、原文完整落盘）
  ⑤ Stop 续跑后仍整理、请求来源不变、摘要不触发 Stop；⑥ 归档失败带 cause、不发正式请求、会话不半提交；
  ⑦ 摘要失败原样传播、已写归档保留、会话不动。
- **实测**：定向 7 文件 = **66 条**（7+12+18+3+2+15+9）；`mvn package` = **257 条**，失败 / 错误 / 跳过均为 0，BUILD SUCCESS。
  `git diff --check` 退出码 0，新测试无行尾空白。未 clean、未删 target / 旧报告 / 临时候选副本、未调真实模型、未读秘密。
- **未做**：不接 Main / 子循环，不调用 `reactiveCompact`，不加 catch / 重试计数；失败产品策略、会话日志与整章验收未完成。
- **Git**：本单元（7B 实现与测试）未 add / commit；Authorization: NONE，等 GPT Review 后另行获批。

## 9. GPT 工程 Review（2026-10-13）

- **结论**：Blocking（必要测试场景不足与失败边界说明需校正），暂不交用户源码学习。请求前调用位置、入口请求捕获、引用保护采用、失败传播目前没有发现运行逻辑缺陷。
- **独立验证**：定向七文件 **66 条**、`mvn package` **257 条**，失败 / 错误 / 跳过均 0，BUILD SUCCESS；`git diff --check` 通过。通过现有测试不替代场景覆盖审查。
- **R1**：Stop 测试入口长历史在首次请求前已摘要，随后只注入短文本；即使漏掉续跑后的 prepare，现有断言仍可通过。要求短入口首次 no-op、Stop 注入较大文本、第二次正式请求前才摘要，验证顺序、入口请求保真、完整摘要输入与归档、Stop 调用数。注入原文允许转为摘要，不再要求全文常驻。
- **R2**：prepare 返回后的“若之后抛异常……整理没发生过”需限缩为 prepare 自身失败；候选已采用后，正式请求失败不会回滚历史。同步构造器仍写“手动压缩依赖”的旧描述，以及 run 中“前提不同”与正文矛盾的标题；只改相关注释，不引入恢复机制。
- **前一检查点已核验**：B13 `0274083735cb90b1d972cd13d1089dbc0dc753df` 的父提交、精确六路径及六份内容 SHA-256 与获批快照一致。当前 7B 未提交、暂存区为空，不重做 B13。
- **范围与暂停点**：返工仅自动整理测试、AgentLoop 相关注释及 7B jobs；不改算法、其它测试、用户答案或 Main / 子循环，不做 Git 写操作。完成后待 GPT 复审；学习指南与新小提交方案在 Pass 后提供。

## 10. 返工执行结果（worker `DS-20261013-S08-PARENT-AUTO-002-R1`，2026-10-13）

**已按 R1 / R2 修正，待 GPT 复审。**

- **R1（重写 Stop 场景，让它真的能区分实现）**：原用例入口已有长历史，首次请求前就摘要完，
  Stop 只注入短文本，因此“续跑之后跳过 prepare”的错误实现也能通过。现在改为
  `stopContinuationIsPreparedBeforeTheSecondFormalRequest`：
  入口只有一句短请求（首次 prepare no-op）、第一次正式回复短且无 `tool_use`、第一次 Stop 注入
  约 2000 字的纯文本续跑要求（超过 `contextCharLimit = 1000`）、第二次 Stop 返回 null。
  断言：`events = [normal, summary, normal]`（摘要必须发生在**第二次**正式请求之前）、两次正式请求、一次摘要、
  Stop 恰好两次（摘要不进入 Stop 生命周期）；第二个正式请求只看到单条 `[Compacted]`，
  `Current user request` 仍是入口那句短请求、不含 `续跑要求`，且压缩候选小于 `contextCharLimit`；
  摘要请求收到的是“入口请求 + 第一次短回复 + Stop 大文本”三条，角色序列 `user/assistant/user` 与三条正文逐字核对
  （阈值保证摘要输入不被截断）；归档保存同样三条并逐条核对角色与完整正文，不再只看文件存在或行数。
  按指示删除了旧用例“注入原文仍常驻会话”的断言——注入文本允许被摘要。
- **R1 判别力证据（仓库外一次性变异检查）**：把工作树复制到临时目录，只把 `AgentLoop` 改成
  “只在第一次迭代整理”，运行本条用例即**失败**（`expected: <[normal, summary, normal]> but was: <[normal, normal]>`，
  断在顺序断言；同一变异也让“大结果转存”用例失败）。正式源码未做任何变异，检查在仓库外完成；
  该临时目录 `/tmp/auto7b-mutation.Px99D3` 因环境策略禁止删除命令而保留（非仓库内文件，可随时手工清除）。
- **R2（失败边界注释校正，只改注释）**：① prepare 返回后的行间注释不再笼统说“抛异常等于整理没发生过”，
  改为分清两个边界——prepare 自身未正常返回时不采用其间产生的部分候选、内存会话保持进入时的状态；
  一旦采用成功，随后正式请求失败也不撤销已采用历史，更不抹掉已成功落盘的归档 / 结果或已发生的工具副作用，
  且不声称回滚整个 run。② 六参构造器标题与 `@param compactor` 改为“自动整理与主动压缩共用的依赖”，
  并补明工具表**没有**注册 compact 也可以注入它、此时只有自动整理生效。③ `run` JavaDoc 中
  “两次整理的历史前提不同”改为“两条整理路径的历史前提是一致的”，与正文解释对齐。
- **实测**：定向 7 文件 = **66 条**（7+12+18+3+2+15+9）；`mvn package` = **257 条**，失败 / 错误 / 跳过均 0，BUILD SUCCESS；
  `git diff --check` 退出码 0，测试文件无行尾空白。核对“只改注释”：把注释行剥离后与 HEAD 比较，
  `AgentLoop.java` 剩余的非注释差异**只有上一轮已复审的 prepare 调用块**，`ContextCompactor.java` 非注释差异为空。
  未 clean、未删 target / 报告 / 既有临候选副本、未调真实模型、未读秘密。
- **状态**：7B 实现与测试仍未提交（Git Authorization: NONE）；等 GPT 复审与新的提交方案。

## 11. GPT 最终复审与学习交接（2026-10-13）

- **Pass**：读取最新回信、实际源码 / diff、新测试与必要调用方。R1 新场景首次 no-op，Stop 大文本导致第二次请求前摘要，顺序、摘要输入与完整归档逐项核对；它能识别漏掉续跑后的整理。R2 已明确 prepare 失败不采用候选、采用成功后正式请求失败不回滚，构造器职责说明与历史前提标题也已校正。未发现本轮已检查范围的阻塞问题。
- **GPT 独立验证**：自动整理 **7 条**、定向 **66 条**、全量 `mvn package` **257 条**，失败 / 错误 / 跳过均 0，BUILD SUCCESS；`git diff --check` 通过，新测试无行尾空白。未 clean、未删除报告、未调真实 API。
- **证据边界**：worker 的仓库外变异检查是其补充报告，GPT 未重复运行该变异；放行依据是实际测试构造、断言与独立正常验证。临时副本 `/tmp/auto7b-mutation.Px99D3` 据 worker 报告仍保留，未擅自清理。
- **非阻塞说明澄清**：Stop 测试中“压缩候选小于窗口”只验证输出体量，不能由此推断摘要输入未截断；后面的完整角色与正文核对才提供输入完整性证据。已在阅读指南说明，留作后续同文件注释维护，不据此再开返工或自行改源码。
- **学习交接**：[parent-auto-prepare-review.md](../../notes/s08/parent-auto-prepare-review.md) 已提供需求 / 场景 / 取舍、推荐阅读顺序、A01–A03 核心架构问题及 A04 可后补题；答案为空、待用户作答，不改已有笔记。
- **边界**：Main / 子循环、响应式恢复、真实摘要质量与整章真机验收仍未完成；已有维护待办不变。7B 工程 Pass 不等于用户学习通过或 s08 完成。

## 12. B14 小提交方案（USER_APPROVED，待 worker 执行）

目标：保留“父循环每次正式请求前自动整理”的完整工程与学习检查点，依赖 B13 `0274083`。
标题：`接通父循环请求前的自动上下文整理`。

精确六文件：

- `src/main/java/com/jadecode/agent/AgentLoop.java`
- `src/main/java/com/jadecode/context/ContextCompactor.java`
- `src/test/java/com/jadecode/agent/AgentLoopAutomaticPreparationTest.java`
- `docs/jobs/s08/context-compact.md`
- `docs/jobs/s08/parent-auto-prepare.md`
- `docs/notes/s08/parent-auto-prepare-review.md`

理由：循环接入、依赖说明、对应七个场景与必要工程 / 学习交接共同形成一个可验证的小能力；
不把调用、测试与状态拆成误导性的半成品，不纳入其它单元笔记。
提交前复核实际 status、六文件全文 / 内容快照、staged 精确路径与空白错误，运行 `mvn package`；
若用户已填写新题单或文件集合实质变化，先报告，不盲目沿用快照。
暂存区当前为空，不包含两份 ignored SOP 邮箱、生成物、机器配置、范围外文件或仓库外临时副本。
用户于 2026-10-13 明确批准上述六文件的一笔本地提交。授权为 USER_APPROVED：worker 仅精确 add 六路径并创建该 commit，不 push、不 fetch、不改历史；执行任务 GPT-20261013-S08-GIT-B14-001。此批准不授权修改代码、填写笔记、修非阻塞注释或推进下一能力。
