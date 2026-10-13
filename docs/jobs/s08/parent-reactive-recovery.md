# s08 里程碑 7C：父循环正式请求超限后的有限补救

状态：**GPT 工程 Review Pass；待用户源码学习；B15 本地提交已获批，待 worker 执行**。任务 `GPT-20261013-S08-PARENT-REACTIVE-001`，实施回信 `DS-20261013-S08-PARENT-REACTIVE-001-R1`，Review 回信 `GPT-20261013-S08-PARENT-REACTIVE-002`。
依赖：7B 自动整理工程 Pass、B14 `8c82914` 已核验，用户经对话澄清后明确继续。
本单留档工程契约；工程 Pass 后再提供架构阅读指南，不要求用户手写实现。

## 1. 需求与设计位置

自动 prepare 使用本地字符估算整理历史，不保证等同于服务端实际上下文窗口。
即使请求前已整理，正式请求仍可能因上下文过长被拒绝。
本轮把已实现的错误分类与 reactiveCompact 接到父循环：仅为符合当前超限判定的失败
尝试一次补救，然后重新请求主任务模型。认证、限流、网络等不命中分类的失败不靠压缩处理。

这是“请求前预防 + 拒绝后的有限恢复”的闭环，不是每个失败都重试，也不是重跑整个任务。
循环拥有失败分流、重试额度和会话采用；压缩器只产生候选；模型仍决定业务行动。
沿用本机课程 s08 的安排：补救后 continue，重新进入已有 prepare，再发正式请求。
空前缀时 reactiveCompact 返回入参的 no-op 是 JadeCode 已裁决行为，不改成强行摘要整段。

## 2. 精确范围

允许 worker 修改：

- `src/main/java/com/jadecode/agent/AgentLoop.java`：正式 complete 的窄 catch、run 内局部补救计数、
  调用 reactiveCompact、带引用保护的内联采用与继续循环，以及直接相关中文 JavaDoc / 行间注释。
- `src/main/java/com/jadecode/context/ContextCompactor.java`：仅 reactiveCompact / isContextOverflow
  说明中“未来的父循环调用方”一类失效描述，改为父循环显式注入路径已接、Main / 子循环未接；
  不改算法、API、import、分类关键词或阈值。
- 新增 `src/test/java/com/jadecode/agent/AgentLoopReactiveCompactionTest.java`。
- 本任务单、总任务单 `docs/jobs/s08/context-compact.md` 的 7C 当前能力 / 结果。
- worker 自己的结果邮箱。

不改既有测试、notes / 用户答案、其它 SOP、Main / 子循环 / CLI、hooks / 工具 / prompt、
LLM 适配器、其它策略 / 存储类、README / pom / 脚本 / 配置。
不引入 helper、策略接口、枚举、回调或新的重试框架，不重新抽历史回填方法。

## 3. 行为与失败契约

1. 保持入口协议校验与 activeRequest 一次捕获，保持现有每次正式请求前 prepare。
2. 仅把正常 `client.complete(new CompleteRequest(...))` 包在 try/catch `LlmException` 中；
   prepare、reactiveCompact 内摘要、主动 compact 摘要、工具与 hooks 失败均不纳入此 catch。
3. compactor 非 null、`ContextCompactor.isContextOverflow(error)` 为 true，且本次正式请求
   尚有 `MAX_REACTIVE_RETRIES` 额度时，才调用 `reactiveCompact(messages, activeRequest)`。
   分类仍只认现有两个关键词，不声称穷尽所有真实超限；不扩词、不按状态码另判。
4. reactiveCompact 正常返回后才采用：同列表不动，不同列表原位 clear / addAll。
   仍使用入口 activeRequest；输入不半提交，调用方会话列表身份不变。
5. 消耗一次补救额度并 continue 回 while 顶部，重新 prepare / 组装正式请求。
   不直接拿摘要 ModelResponse 走主任务处理，不递归调用 run，不 replay 先前工具。
6. 重试计数在本次 run 内保存，正式 complete 成功即归零。
   每个新的正常模型请求各有一次补救机会，不是整个 run 只允许一次；
   下一个 run 不继承额度，不新增实例字段。失败重试尚未得到主任务回复，不推进工具 / reminder / Stop。
7. 即使 reactiveCompact 返回同列表 no-op，也消耗一次额度；可再尝试一次正式请求，
   再次超限就抛第二次实际异常，不无限 continue，不把 no-op 说成恢复成功。
8. 未启用 compactor、非匹配失败或额度耗尽：抛当前那一个异常对象，不包装成成功、
   不改历史、不归档、不额外摘要，不重试。
9. 补救归档 / 摘要失败原样传播，不采用部分候选，不发后续正式请求；已成功落盘保留。
   补救候选成功采用后，重试失败不会回滚已采用历史；也不回滚整个 run 或已完成工具副作用。
10. 五参 / null 路径、主动 compact 成功信号与纯 / 混合策略、工具 / Pre / Post / reminder / Stop
    的次序和计数保持。只补完整父循环这一条能力，不同时装配 Main 或子循环。

## 4. 注释验收

中文 JavaDoc 与行间说明解释：为什么预防仍需要补救、为何只 catch 正式请求、
有限额度的生命周期、no-op 仍消耗机会、异常传播与历史采用的两种边界、
为何请求重试不会重跑工具，以及尚未接入 Main / 子循环的范围。
区分协议硬约束、关键词近似判断与本项目恢复策略，不声称摘要一定缩小到真实窗口。
不顺手修旧 TODO、unused import、History / Reactive 的其它维护项。

## 5. 场景测试

真实 compactor、假 LlmClient、注入阈值和临时目录。保存请求快照，分别记录正式 / 摘要事件，
用固定异常对象核对原样传播；归档应核对完整内容，不只检查有文件。
至少覆盖下列行为（可合理组合或参数化，不靠题量重复）：

1. 超限 → 一次前缀摘要补救 → 正式请求成功：
   顺序、只摘要旧前缀、最新尾部与完整归档、入口请求、列表身份，失败请求不追加 assistant。
2. 不命中分类的认证 / 限流 / 网络失败：零补救、无自动重试，异常原对象与会话保持。
3. 再次超限：只补救一次，第二次异常原对象抛出，已采用候选仍在，计数不无限循环。
4. 空前缀 no-op：零归档 / 零摘要，同列表内容不被 clear 丢掉；两次正式失败后终止。
5. 补救归档失败：首次正式失败后停止，零摘要 / 无第二次正式请求，历史保持并带 cause。
6. 补救摘要失败：固定摘要异常原样传播、完整归档保留、不采用候选 / 不发后续正式请求。
7. 补救后的正式成功产生普通工具调用，随后下一请求又超限：
   新请求额度已重置，可第二次补救；工具只执行一次、交换完整，生命周期 / reminder 不因失败尝试多跳。
8. 同一 AgentLoop 的两次 run：后一个不继承前一个 run 的补救额度或 activeRequest。
9. prepare 内摘要抛带超限关键词的异常：不被当成正式请求超限再次补救；
   抛原对象，零正式请求，成功归档保留。
10. 主动 compact 摘要抛带超限关键词的异常：不嵌套补救、不重跑已完成工具；
    闭合历史保留，无额外正常请求。既有主动 / 自动回归不改。
11. null compactor 的正式超限异常：旧路径原样传播，无补救。

用例门槛应避免首次 prepare 意外提前摘要，或重试 prepare 意外多摘要掩盖分支。
第 1 类保护尾部以实际安全切点为准，不假定所有输入都恰好保留五条。
不测试真实模型摘要质量，不为测试引入运行模式 / 反射 / 新公开入口。

## 6. 验证、Git 与停止点

```sh
mvn test -Dtest=AgentLoopReactiveCompactionTest
mvn test -Dtest='AgentLoopReactiveCompactionTest,AgentLoopAutomaticPreparationTest,AgentLoopManualCompactionTest,AgentLoopTest,ReactiveCompactionTest,ContextOverflowDetectionTest,HistoryCompactorTest,ToolExchangeProtocolTest'
mvn package
git diff --check
```

报告实际数量、失败 / 错误 / 跳过与退出码，不沿用旧 257 当本轮实测。
不 clean、不删除 target / 旧报告 / 临候选副本，不调用真实 API、不读秘密。
当前三份未提交文档是 GPT 状态同步与用户作答，保留；worker 不修改
parent-auto-prepare.md 或任何 notes，不为它们暂存 / 提交。
Git Authorization：**NONE**。B14 授权已执行完，不延续。
完成后写“已实施，待 GPT Review”并回信停止；GPT 复审后才建立本轮阅读指南与另提小提交。

## 7. 非目标与候选优化

本轮不实现用户新提出的“文件未变化时省略重复读取输出”。
它以 Codex 行为的未核实假设为讨论前提，不能当作其实际实现事实。
该候选需要同时判定文件版本、读取范围及下一次请求中旧内容是否仍可见，
还需与摘要 / 归档失效、强制重新读取及会话隔离协调。留待后续专题，不改 read_file。
Main / 子循环、恢复产品流程、会话记忆管理与整章验收、既有维护项也继续留待后续。

## 8. 执行结果（worker `DS-20261013-S08-PARENT-REACTIVE-001-R1`，2026-10-13）

**已实施，待 GPT Review。**

- **循环接入**（`AgentLoop.java`）：把正式 `client.complete(...)` 单独包进 `try/catch (LlmException)`——这是整条
  循环里唯一被捕获的地方，`prepare`、主动压缩摘要、`reactiveCompact` 内摘要、工具与 hooks 都不在其内。
  catch 里先分流：`compactor != null` 且 `ContextCompactor.isContextOverflow(error)` 为真且
  `reactiveRetries < ContextCompactor.MAX_REACTIVE_RETRIES` 才补救，否则抛**当前这个**异常对象；
  补救时先消耗额度（no-op 也消耗），再 `compactor.reactiveCompact(messages, activeRequest)`，
  正常返回后按引用保护原位采用（同引用不 clear），然后 `continue` 回 while 顶部，重新经过自动整理与请求组装。
  额度是 run 局部变量，正式请求成功即归零；失败尝试不追加 assistant、不执行工具、不推进 reminder / Stop。
- **压缩器说明**（`ContextCompactor.java`）：只更新 `isContextOverflow` 与 `reactiveCompact` 中“未来的父、子循环 /
  调用方”这类失效描述，改为“父循环显式注入压缩器时已接入这条恢复链路，Main 与子循环尚未接入”；
  未改算法、API、import、分类关键词或阈值。
- **注释**：新增类头“被拒绝后的有限补救”段与 catch 内 2.1–2.6 行间说明，覆盖为什么预防仍需补救、为什么只捕获正式请求、
  额度生命周期与 no-op 仍消耗、异常传播与“采用前 / 采用后”两种失败边界、重试不重跑工具、以及五参路径不补救；
  `run` 的 `@throws` 同步说明。未顺手修旧 TODO / unused import。
- **测试**：新增 `AgentLoopReactiveCompactionTest` 11 条（真实 compactor + 剧本化假 LLM + `@TempDir`，正式 / 摘要请求
  分别保存快照与顺序）：① 超限 → 一次前缀摘要 → 重试成功（顺序、只摘要旧前缀、尾部原引用、完整归档、入口请求、列表身份、
  失败不追加 assistant）；② 认证失败零补救零重试、原对象抛出、会话不变；③ 再次超限只补救一次、抛第二次异常对象、
  已采用候选仍在；④ 空前缀 no-op 也消耗额度、列表不被 clear、两次失败后终止；⑤ 补救归档失败带 cause、零摘要、
  无第二次正式请求、会话保持；⑥ 补救摘要失败原样传播、完整归档保留、不采用候选；⑦ 补救后成功执行工具、
  下一次请求额度已重置（工具只执行一次、交换完整、无 reminder 跳动）；⑧ 同一 loop 两次 run 各自额度与 activeRequest；
  ⑨ `prepare` 内摘要抛带超限关键词的异常 → 零正式请求、原对象抛出、归档保留（证明 catch 不覆盖整理）；
  ⑩ 主动 compact 摘要抛带超限关键词的异常 → 不嵌套补救、不重跑工具、闭合交换保留；
  ⑪ 五参 null 压缩器路径原样传播、零补救。
- **实测**：新测试 **11 条**；定向 8 文件 = **87 条**（11+7+12+18+9+6+15+9）；`mvn package` = **268 条**，
  失败 / 错误 / 跳过均为 0，BUILD SUCCESS。
- **已知基线（非本轮范围）**：全仓 `git diff --check` 仍报用户题单 `docs/notes/s08/parent-auto-prepare-review.md`
  第 148、166 行的两处既有行尾空格；按要求未修笔记格式。本轮允许文件的空白检查全部干净
  （`git diff --check -- <两个 Java 文件>` 退出码 0，新测试与新任务单无行尾空白）。
- **未做 / 未提交**：不接 Main / 子循环，不做恢复产品流程、会话记忆、文件读取去重候选；既有维护项未动。
  Git Authorization: NONE，本轮无 add / commit，等 GPT Review 后另行获批。

## 9. GPT 独立 Review 与学习交接（2026-10-13）

**Pass：已核验范围内无已知阻塞问题。** 核对实际源码 / diff、完整新测试及相关调用链，
正式请求的 catch 不覆盖 prepare、补救摘要、主动摘要、工具或 hooks；
run 局部额度在正式成功后重置，no-op 仍消耗；采用候选前失败与采用后重试失败的边界符合契约。
失败尝试不会追加 assistant、重复执行已完成工具或推进主任务生命周期。
压缩器仅两处接入状态 JavaDoc 改动，算法未改；Main / 子循环未接入。

- GPT 独立运行新增测试 **11 条**、八文件相关回归 **87 条**、`mvn package` **268 条**；
  三轮失败 / 错误 / 跳过均为 0，退出码 0，BUILD SUCCESS。
- 本轮范围空白检查通过。全仓检查仍有旧用户题单第 148、166 行的既有行尾空格，
  不属于本轮，不修用户答案，也不把全仓检查说成通过。
- **非阻塞注释澄清**：`AgentLoop` catch 内 2.3 的“不发请求”应理解并后续改为“不发正式请求”。
  reactiveCompact 实际会发辅助摘要请求；它不负责主任务请求重试。
  本轮在阅读指南明确纠正这一措辞，不把它当正确知识交接；源码尚未修改，后续由 worker 在明确范围内校正。
- 分类分支的集成测试直接覆盖 401；429 / 网络等不匹配场景由既有分类测试补充，
  不宣称新集成测试逐一覆盖所有错误类型。自动化测试不保证真实摘要质量或真实窗口内成功。
- 新指南：[parent-reactive-recovery-review.md](../../notes/s08/parent-reactive-recovery-review.md)。
  以需求、有限恢复的取舍、请求重试与工具副作用为主；A01–A03 为核心，A04 可后补。
  用户尚未阅读 / 作答，不记为学习通过，不推进依赖它的下一能力或整章验收。
- Review 回信是 INFO / REVIEW_SUPPORT，不是返工或新实现任务；无需 worker 回复。
  5 分钟轮询在收到匹配回信时结束，不继续监视旧 Message-ID。

## 10. B15 小提交方案（USER_APPROVED，待 worker 执行）

目标：保存父循环“正式请求超限后有限补救”的独立检查点；依赖 B14 `8c82914`。
提交标题：`接通父循环上下文超限后的有限补救`。
一笔本地提交，仅下列六文件（实现、测试、契约与必要学习交接不可分开伪装成先后完成）：

1. `src/main/java/com/jadecode/agent/AgentLoop.java`
2. `src/main/java/com/jadecode/context/ContextCompactor.java`
3. `src/test/java/com/jadecode/agent/AgentLoopReactiveCompactionTest.java`
4. `docs/jobs/s08/context-compact.md`
5. `docs/jobs/s08/parent-reactive-recovery.md`
6. `docs/notes/s08/parent-reactive-recovery-review.md`

暂存区为空；以上文档中与当前能力相邻的 GPT 状态记录一并保留。
明确排除 `docs/jobs/s08/parent-auto-prepare.md` 与用户作答
`docs/notes/s08/parent-auto-prepare-review.md`，它们保持未提交；也排除两个 ignored 邮箱、
生成物、机器配置、秘密和仓库外临时副本。不为提交清理这些文件。
检查六文件完整内容、精确 staged 路径 / stat / diff / 空白；全仓既有笔记空格单独报告。
用户于 2026-10-13 明确批准上述六文件的一笔本地提交；授权为 USER_APPROVED。
执行任务 `GPT-20261013-S08-GIT-B15-001`：worker 核对获批快照后精确暂存六路径并提交一次。
不沿用 B14 授权，GPT 不自行 add / commit；worker 完成后回信，由 GPT 核验实际提交。
本方案不授权改源码 / 注释、不 push、不 fetch、不 amend / rebase / reset 或创建引用。
提交审批不阻塞本轮源码学习，也不等待用户答完题；提交完成不等于学习或整章完成。
