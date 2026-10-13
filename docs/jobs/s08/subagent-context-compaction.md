# s08 里程碑 7D：子代理的上下文压缩与有限恢复

状态：**GPT 工程复审 Pass；必要阅读说明已提供；B16 小提交已获批待 worker 执行，下一项为 Main 装配**。实施回信 `DS-20261013-S08-SUBAGENT-COMPACTION-001-R1`，补测试回信 `DS-20261013-S08-SUBAGENT-COMPACTION-002-R1`，复审回信 `GPT-20261013-S08-SUBAGENT-COMPACTION-003`。
任务 `GPT-20261013-S08-SUBAGENT-COMPACTION-001`。Git Authorization：**NONE**。
依赖：父循环自动 / 主动 / 响应式压缩已工程 Pass，B15 `5e70fd2` 已核验。
用户选择跳过上一轮题目并要求继续，不把未答题记成学习通过。

## 1. 需求、场景与职责

父代理把“查清某模块并修复测试”委派出去，子代理从新的 prompt 开始工作。
全新上下文降低初始负担，却不能阻止子任务自己读文件、运行构建而积累历史。
只给父循环压缩，会留下“主会话能持续，长子任务仍超限中断”的缺口。

本轮给子会话接入已有 prepare、批次闭合后的 compactAfterBatch 和有限 reactiveCompact。
不是重新实现四层策略，也不把父会话塞进子会话。共享的是无会话状态的策略 / 存储依赖，
不共享 messages、activeRequest、重试计数或工具批次状态。

新增架构重点是“子任务运行边界”：
子任务目标与父用户目标不是同一个字符串；辅助摘要不属于子代理的一次行动；
一次可恢复请求失败不能过早结束委派，也不能吃掉一次成功行动额度。
这是 JadeCode 将本地 s08 主循环策略接入已有 s06 子代理的组合；
本机 s08 code.py 未提供这套子循环生命周期，不冒充原课程已有实现。

本轮完成后暂停 Review，下一项才接 Main。CLI 本轮仍未启用压缩。

## 2. 精确允许文件

- `src/main/java/com/jadecode/subagent/SubagentRuntime.java`：显式注入、三条入口、局部额度与针对性收尾，中文说明。
- 新建 `src/test/java/com/jadecode/subagent/SubagentRuntimeCompactionTest.java`：启用路径与边界测试。
- `src/main/java/com/jadecode/context/ContextCompactor.java`：仅 reactiveCompact / isContextOverflow
  两处“只有父循环 / 子循环尚未接入”的调用方状态 JavaDoc，改父子显式注入路径已接、Main 尚未装配。
- 本单与 `docs/jobs/s08/context-compact.md`：7D 当前状态 / 执行结果和直接相关接入能力状态。
- worker 自己的结果邮箱。

不改父循环、既有测试、SubagentTool、Main、CLI、hooks / 安全策略 / 工具 / prompts、
压缩算法或阈值、README、pom、脚本、配置、其它 jobs / notes / 用户回答。
不抽通用循环 / 重试框架，不引入新 mode、事务、锁、并发机制或额外公开 API。
不修父循环 2.3 注释或其他既有维护项。

## 3. 装配、目标和隔离

1. 保留五参构造器，委托新增第六参数 ContextCompactor 的构造器；null 为原有不压缩路径。
   注册 compact 却注入 null 时，仿父循环在构造时明确拒绝；注入而不注册 compact 合法。
   不隐式创建客户端、目录、存储或全局单例，不修改 Main。
2. 每次 run 仍从新建列表和一条 user(prompt) 开始，activeRequest 就是入口 prompt 原文，
   不用父循环的 extractActiveRequest、不从后续工具结果或 Stop 注入重新提取、不存为实例字段。
   不顺手改变既有 prompt 参数校验 / 空白语义；SubagentTool 参数契约本轮不动。
3. 多次 run 可共用 compactor，但历史、目标、轮数、补救额度与 compact 标志都在各次 run / 批次内。
   对外返回仍是终态文本，不返回子历史、compaction marker 或 transcript 路径充当最终答案。

## 4. 三条入口的接入契约

- **请求前**：每次正式 complete 之前 prepare（含首次、工具回灌后、Stop 续跑后、补救重试前）。
  正常返回才按引用保护原位采用；同列表 no-op 不 clear，不采用半候选。
- **主动**：只有已知 compact 工具、未被 Pre 拦截、实际执行返回精确 CompactTool.OUTPUT 才置本批标志。
  仍走原有 Pre / 执行 / Post / SUBAGENT_TOOL 顺序，全部结果回灌闭合以后，
  才传本批所有 toolCalls 到 compactAfterBatch；复用纯 / 混合策略，不在子循环再写分类。
  未知、被拦或非精确输出不触发。标志每批重建，不跨下一轮或下一次 run。
- **响应式**：只有正式 complete 的 LlmException 进入恢复分流：compactor 非 null、
  isContextOverflow 命中且尚有 MAX_REACTIVE_RETRIES 额度，才消耗额度、reactiveCompact、采用并 continue。
  不匹配 / 耗尽抛当前异常；no-op 也消耗；正式成功后额度归零。
  复用分类与策略，不扩关键词，不把 prepare / 主动摘要 / 补救摘要 / 工具 / hooks 纳入恢复 catch。
- 所有候选在成功返回后才采用；成功落盘保留，已有候选采用与工具副作用不回滚。
  辅助摘要不走子任务工具分发 / Stop / SUBAGENT_TOOL。

## 5. 30 轮与生命周期

原 for 轮次可改为 while + 显式 turn：只有正式 complete 成功取得回复才消耗一轮。
失败重试和辅助摘要不消耗；成功后即使 Stop 强制续跑，也仍消耗该轮。
到第 30 次成功回复：普通工具批次仍按既有顺序执行闭合，必要主动压缩完成后再判耗尽；
允许该次无工具回复正常 DONE，不多发第 31 次正式业务请求。
失败尝试没有主任务回复，不追加 assistant、不重跑工具、不触发 Stop 或 SUBAGENT_TOOL。

hooks 正常工作的前提下：

- 每次 run 一次 START；成功恢复时不提前 END(FAILED)，不是新起一个子代理。
- 正常最终答复 DONE、轮数耗尽 STOPPED、不可恢复的正式 / 摘要错误 FAILED，各恰一次 END。
- 新增 prepare / 主动 / 补救 IO 失败（IllegalStateException 带 cause）也要 END(FAILED) 一次再抛，
  不返回假摘要。重复工具 ID 的既有 FAILED 契约保持。
- END output 沿用既有 Error: + 异常消息；原异常对象传播（IO wrapper 的 cause 保留）。
  SubagentTool 对 LlmException 的既有转换不动，不擅自改变其他未检异常的父级处理。
- 不 catch 所有 RuntimeException / Exception；不借本轮改变旧工具或 hooks 的未检异常语义。
  hook 自己抛异常的可靠收尾不在本轮保证内，不新增二次捕获 / 重试。

建议组织：外层仅 catch LlmException 统一 END(FAILED)，内层正式 catch 只负责恢复分流；
重复 ID 保留专门失败收尾；三个 compactor 调用位置的 IO 错误各在窄范围收尾。
移除被外层接管的旧 LLM END，避免重复事件。不要用 finally 无条件触发 END，
也不要让外层“收尾 catch”变成对所有摘要失败再次补救的“恢复 catch”。
若另有更简单但满足同一边界的组织可使用，说明理由，不新增泛化框架。

## 6. 场景验证设计

真实 compactor + 假 LlmClient + 内存工具 + TempDir；捕获外层列表快照与正式 / 摘要事件。
按行为组织，可组合相关场景，不强求测试数量。至少覆盖：

1. 五参 / null 旧路径回归，compact 未注入早拒绝；注入无 compact 可自动整理。
2. 大工具结果后 prepare 真正改变下一次请求；Stop 注入大文本也在下一请求前整理。
3. 纯 compact 整段摘要；混合批次先全回灌再摘要、保留完整近期交换；hooks 次序正确。
   被拦 compact、伪成功输出、下一批标志清空，不误触发摘要。
4. 正式超限 → 摘要补救 → 成功：没有提前 END，最终 DONE 一次，工具不重跑。
   成功后下一请求可再次补救；no-op / 再次超限 / 非匹配失败各保持有限与原对象传播。
5. **额度判别用例**：先 29 个成功工具回复，再一次超限、一次补救摘要，然后第 30 个成功最终回复。
   必须 DONE，不把失败或摘要计为一轮；另一个持续工具场景仍恰 30 次成功回复后 STOPPED，
   不能依靠“最终 done 文本”单独证明计数正确。
6. prepare / 主动 / 补救的摘要失败与归档失败：FAILED 恰一次、异常 / cause 正确，
   不发后续业务请求、成功归档保留、部分候选不采用；摘要异常即使有超限关键词也不嵌套补救。
7. 同一个 runtime / compactor 连续两个不同 prompt：目标与历史不串；Stop 文本不顶替入口子目标。
   可加父子共享 compactor 的实际调用链，证明父级只收到最终文本而非子历史（不强制额外公开入口）。
8. 启用路径的重复 ID 拒绝，工具未执行、END 一次；未知工具仍先短路且不打工具 hook。

核对完整正文 / 协议配对 / 归档，而不只检查计数、前缀或有文件。
假客户端不能保存会被原位清空的 live 列表来冒充请求快照；阈值隔离策略分支。
已有测试原样保留，新增测试不得削弱断言或使用反射 / 正式运行测试开关。

## 7. 注释、验证、Git 与停止点

中文 JavaDoc 与行间注释同时解释：子任务的数据流、共享依赖与隔离状态、
子目标保留、三种触发职责、30 轮单位、恢复与收尾两个 catch 的区别、
失败采用边界与已发生的工具副作用。准确区分辅助摘要和正式请求，不照搬“不发请求”的错误措辞。

```sh
mvn test -Dtest=SubagentRuntimeCompactionTest
mvn test -Dtest='SubagentRuntimeCompactionTest,SubagentRuntimeTest,SubagentToolTest,AgentLoopReactiveCompactionTest,AgentLoopAutomaticPreparationTest,AgentLoopManualCompactionTest,ContextCompactorBatchCompactionTest,ReactiveCompactionTest,ToolExchangeProtocolTest'
mvn package
git diff --check
```

报告实际数量与失败 / 错误 / 跳过及退出码，不沿用基线 268 当作新增后的实测。
全仓旧用户题单空格单独报告，另检查本轮允许文件；不修用户格式。
不 clean、不删除 target / 报告 / 仓库外候选副本、不调真实 API、不读秘密。
Git Authorization NONE：B15 已执行完，不 add / commit / push 或改历史。
完成后 jobs 写“已实施，待 GPT Review”并回信停止；学习指南由 GPT 工程放行后提供。
Main 装配、真机验收、存储 / snip / 预算专项测试缺口、记忆与 READ-DEDUP 继续后续交接，
不因本轮交付宣告整章完成。

## 8. 执行结果（worker `DS-20261013-S08-SUBAGENT-COMPACTION-001-R1`，2026-10-13）

**已实施，待 GPT Review。**

- **装配**（`SubagentRuntime`）：保留五参构造器并委托新增的六参（末参 `ContextCompactor`，null = 不压缩）；
  工具表注册了 compact 却没注入压缩器时构造期抛 `IllegalArgumentException`（与父循环一致）；
  不注册 compact 也可注入压缩器（那时只有自动整理与补救生效）。不隐式创建客户端 / 目录 / 存储或全局单例。
- **三条入口**：① 每次正式 `complete` 之前 `prepare` 并按引用保护原位采用（首次、工具回灌后、Stop 续跑后、
  补救重试前都会经过）；② 批次闭合后才把本批全部 `toolCalls` 交给 `compactAfterBatch`（纯 / 混合分类仍由压缩器做），
  批次标志只认"已知 compact 工具 + 未被 Pre 拦截 + 实际执行 + 输出精确等于 `CompactTool.OUTPUT`"，每批重建；
  ③ 只有正式 `complete` 的 `LlmException` 进恢复分流，命中超限分类且额度未耗尽才消耗额度、`reactiveCompact`、
  采用后 `continue`；no-op 也消耗；正式成功后额度归零（run 局部变量，不跨 run）。
- **状态隔离**：`messages`、`activeRequest`（入口 prompt 原文，不用父循环的 `extractActiveRequest`、
  不从工具结果或 Stop 注入重提取）、已消耗轮数与补救额度全部是 run 局部；两次委派互不串味。
  对外仍只返回终态文本，不返回子历史 / marker / transcript 路径。
- **30 轮与收尾**：改为 `while + turns`，只有正式请求真正拿到回复才 `turns++`；失败重试与辅助摘要不占轮次，
  成功后即使 Stop 强制续跑也仍占那一轮。第 30 次成功回复若是最终文本即 DONE；若还在调工具，
  工具照常执行并闭合后以 STOPPED 收尾，不发第 31 次业务请求。START 每 run 一次；
  DONE / STOPPED / FAILED 各 END 一次；内层 catch 只做恢复分流，外层统一 `catch (LlmException)` → END(FAILED)，
  三个 compactor 调用位置的 `IllegalStateException`（带 cause）在窄范围 END(FAILED) 后原样抛；
  重复 ID 沿用既有 FAILED 契约；未 catch 所有 RuntimeException，未用 finally 无条件 END。
- **压缩器说明**（`ContextCompactor.java`，仅注释）：`isContextOverflow` / `reactiveCompact` 两处"只有父循环 /
  子循环尚未接入"改为"父、子循环的显式注入路径都已接入，Main 尚未装配压缩器"。
- **测试**：新增 `SubagentRuntimeCompactionTest` **15 条**（真实 compactor + 剧本化假 LLM + 内存工具 + `@TempDir`），
  覆盖装配与旧路径 / 未注册 compact 的自动整理 / 大结果转存 / Stop 大文本后的整理与子目标保真 /
  纯 compact 整段摘要 / 混合批次与 hooks 次序 / 被拦与伪成功不误触发 / 标志跨批清空 /
  超限补救后仍 DONE（不提前 END） / 非匹配与额度耗尽原对象传播 / **29+1+1 的轮次判别用例** /
  连续工具回复恰 30 轮后 STOPPED / prepare 与补救的摘要与归档失败 END(FAILED) 恰一次 /
  带超限关键词的摘要异常不嵌套补救 / 主动压缩失败不重跑工具 / 两次委派目标与历史不串 /
  启用路径的重复 ID 与未知工具短路。
- **判别力证据（仓库外一次性变异）**：把工作树复制到 `/tmp/subagent7d-mutation.qgjyRf`，
  只把"失败尝试也算一轮"写进恢复分支，轮次判别用例立即失败
  （`expected: <最终结论> but was: <Subagent stopped after 30 turns ...>`）。正式源码未被变异；
  该临时目录因环境策略禁止删除命令而保留（仓库外）。
- **实测**：新测试 **15 条**；定向 9 文件 = **81 条**（15+11+4+11+7+12+3+9+9）；`mvn package` = **283 条**，
  失败 / 错误 / 跳过均为 0，BUILD SUCCESS。
- **已知基线（非本轮范围）**：全仓 `git diff --check` 仍报用户旧题单第 148 / 166 行的既有行尾空格，未修；
  本轮允许文件（两个 Java + 新测试）空白检查干净（曾发现并修掉自己新写注释里的一处行尾空格）。
- **未做 / 未提交**：Main 装配、真机验收、存储 / snip / 预算专项测试缺口、记忆与 READ-DEDUP、
  父循环 2.3 注释均未处理；Git Authorization: NONE，本轮无 add / commit。

## 9. GPT 首轮独立 Review（2026-10-13）

**Blocking：验证未达到原任务 §6 的要求；不是已发现的运行 Bug。**
GPT 完整读取新运行时、实际 diff、775 行新测试、压缩器说明与调用方。
三条入口、局部状态、正式请求窄 catch / 外层 LLM 收尾、三个窄 IO 收尾、
第 30 次成功回复的 DONE / STOPPED 分支与原异常传播暂未发现阻塞运行错误。
两个私有 END 上报方法只是本类 payload 复用，可接受，不要求内联或改统一 wrapper。

GPT 独立执行：新增测试 **15 条**、原指定九文件回归 **81 条**、全量 **283 条**；
失败 / 错误 / 跳过均为 0，退出码 0，BUILD SUCCESS。
本轮 Java / 新测试 / jobs 空白检查通过；全仓旧用户题单两处空格仍保留，未修用户笔记。
worker 的仓库外变异实验未由 GPT 复跑，不将其称为 GPT 独立验证。

### 必须补齐的判别与收尾证据

1. **no-op 有限补救**：R1 测试实际用三轮工具制造可摘要前缀，没有直接测空前缀。
   增加短历史首次 / 再次超限：仅两次正式请求、零摘要 / 归档，第二异常原对象、完整历史仍在、FAILED 一次。
2. **同一 run 新请求重置额度**：现有恢复用例只发生一次超限；两次委派测试也没有发生超限。
   增加“超限补救 → 成功工具回复 → 新正式请求再超限补救 → 最终 DONE”，
   确认两次补救和工具只执行一次，START / END 一次。
3. **另外两个独立 IO 收尾分支**：只有 prepare 的归档失败已测。
   补主动 compact 与 reactiveCompact 的归档失败：有 cause，零摘要 / 无后续正式请求、
   FAILED 一次，已执行工具不重跑。不要把摘要失败测试当成 IO catch 的覆盖。
4. **精确成功文案的拒绝分支**：现有“伪成功”是普通 bash 返回 CompactTool.OUTPUT，
   不能证明名为 compact 的工具返回其它文字不会触发。补 compact 的非精确输出场景。
5. **完整内容与生命周期断言**：混合用例只检查下一请求消息数；归档多处只数行。
   在现有用例中核对调用 / 结果 ID、角色、顺序、完整正文和归档 JSON 的结构相等，
   不是只数行 / 查前缀；在恢复与两次委派用例中真正记录 START，并核对 END outcome。
   “两次 START”原注释目前没有对应断言；SUMMARY_MAX_TOKENS 的用途说明也不能声称已断言输出额度。

不额外扩展原课程内容；这些仍是 §6 已要求的工程证据，不转成用户必答题。
完成以上补证后复审，才能交学习说明、规划提交或推进 Main。

## 10. 限定范围返工（SUBAGENT-COMPACTION-002）

只改新建的 `SubagentRuntimeCompactionTest.java`、本单的执行 / 验证记录、
总任务单 7D 的直接状态记录及 worker 邮箱。
生产 Java 已核对，返工保持不变；不得重构运行时 / helper / 算法、修改旧测试或 notes。
复用现有假客户端和真实 compactor，新增少数判别场景并加强现有断言；不重建整套测试框架。
不再复制工作树或做额外变异实验，不创建新临时验证材料，不清理现有仓库外副本。
若补测揭示真实实现错误，报告失败与最小修复范围，停止，不在本任务内猜测扩大生产修改。

复用 §7 三轮验证命令，实际数量以新结果为准，不沿用 15 / 81 / 283；
在回信逐项映射上述五组证据到测试名与关键断言，缺项如实说未完成。
Git Authorization NONE，未放行新提交；实现、测试通过与用户学习状态分开。
本轮不提供重复机制题单；后续交接以必要阅读入口和新增架构取舍为主。

## 11. 返工执行结果（worker `DS-20261013-S08-SUBAGENT-COMPACTION-002-R1`，2026-10-13）

**只改测试与本节记录,生产 Java 未动**(`SubagentRuntime.java` / `ContextCompactor.java` 的 SHA-256
在返工前后均与首轮快照一致)。测试从 15 条增到 **19 条**,并对既有用例加强断言。五组证据映射:

1. **空前缀 no-op** → `emptyPrefixNoOpEndsAfterTheSecondOverflow`:一条 prompt 的短历史,首次超限触发
   `reactiveCompact` 返回入参(no-op),第二次正式请求再超限即抛**第二次那个异常对象**;
   断言恰两次正式请求、零摘要 / 零 transcript、两次请求看到的都是同一份入口历史(未被清空)、
   START 一次、END(FAILED) 一次且 output/outcome 正确——不是靠脚本耗尽或循环碰巧退出。
2. **同 run 内额度重置** → `budgetResetsForTheNextRequestWithinTheSameRun`:三轮工具撑出可摘要前缀 →
   第一次超限补救 → 成功回复**仍带工具** → 下一次正式请求再超限补救 → 最终 DONE;
   断言两次摘要补救、工具共执行 4 次(两次失败尝试一次都没跑工具)、START / END 各一次、
   事件序列 `normal×4, summary, normal, normal, summary, normal`。
3. **另外两处 IO 收尾** → `compactAndReactiveArchiveFailuresEndFailedOnceWithoutExtraRequests`:
   (a) 主动 compact 的归档失败(阈值放宽,确保失败发生在 `compactAfterBatch` 而不是 prepare)与
   (b) 补救里的归档失败分别用"目录位置被普通文件占住"制造,断言 `IllegalStateException` 带 cause、
   零摘要、无后续正式请求、已执行工具次数不增加、FAILED 恰一次。prepare 的归档失败仍在原用例中保留。
4. **compact 非精确输出** → `compactNameWithUnexpectedOutputDoesNotTriggerCompaction`:已知工具名就是
   compact 且 Pre 放行,但返回非 `CompactTool.OUTPUT` 的文字 → 零摘要、零归档,工具执行一次、
   Pre/Post/SUBAGENT_TOOL 顺序照常,结果按调用 ID 回灌完整正文。
5. **完整内容与生命周期断言(加强既有用例)**:
   - 混合批次:下一次请求核对角色(`assistant` / `user`)、调用 ID 与结果 ID 的顺序、以及两条结果的完整正文,
     并 `assertSame` 尾部 assistant 对象;归档与期望消息列表**按 JSON 结构相等**核对(含最新交换)。
   - 纯批次:摘要输入与归档都按结构等于"prompt + assistant(compact) + tool_result"三条;
     新增 `assertSummaryShape` 真正断言摘要请求**不带工具表**且使用注入的输出额度(修正此前只声明未断言)。
   - 响应式成功:记录并断言 START 一次、END(DONE) 一次;归档按结构等于补救前的 7 条历史;
     重试请求按角色序列 + 首对调用/结果 ID 核对(安全切点把整对交换拉进尾部,保留 6 条)。
   - 两次委派:真正记录 START 并断言 2 次(此前只有注释)。
   - 失败用例补 END outcome / payload 断言(额度耗尽、带关键词的摘要失败、补救摘要失败)。

**实测**:`SubagentRuntimeCompactionTest` **19 条**;原指定九文件回归 **85 条**
(19+11+4+11+7+12+3+9+9);`mvn package` **287 条**,失败 / 错误 / 跳过均为 0,退出码 0,BUILD SUCCESS
(不沿用 15 / 81 / 283)。本轮文件无行尾空白;全仓 `git diff --check` 仍只有用户旧题单第 148 / 166 行
两处既有空格,未修。未 clean、未删 target / 报告 / 仓库外临副本,未调真实 API。

**未完成 / 未做**:未新增额外的变异实验(按本轮要求);「响应式失败路径的归档内容结构核对」仍以
行数与"摘要调用次数为 0"间接体现,未做结构相等(失败时归档不存在或由带 cause 的 IO 失败产生);
Main 装配、真机验收、专项测试缺口与既有维护项均未处理。Git Authorization: NONE,本轮无 add / commit。

## 12. GPT 最终复审（2026-10-13，SUBAGENT-COMPACTION-003）

**Pass：原阻塞的关键判别与收尾缺口已解除，检查范围内无已知阻塞运行问题。**
已读取四个新增场景、增强的成功 / 失败断言与相关辅助方法；两份生产文件 SHA-256
与 R1 完全一致，未借补证重构实现。no-op、同 run 两次恢复、两处 IO 收尾和非精确输出拒绝
有直接测试；角色 / ID / 正文 / 完整成功归档与 START 断言落实。

GPT 独立执行新测试 **19 条**、同九文件回归 **85 条**、全量 **287 条**，
失败 / 错误 / 跳过均 0，退出码 0，BUILD SUCCESS。本轮范围空白检查通过；
全仓旧用户题单两处空格不属于本轮，不修格式、不冒称全仓检查通过。

**非阻塞维护与报告澄清**：辅助摘要失败的现有归档断言仍只数行，未独立比较 JSON 完整内容。
这不是“失败路径没有归档可比”：`manualAndReactiveCompactionFailuresEndFailedOnceWithoutRerunningTools`
的响应式摘要失败发生在归档成功之后，七行归档确实存在。
源码采用顺序、共享归档写入器、成功分支结构比对及已有压缩器回归已核验；本次不因这项额外断言
再开启一轮返工，记录为后续维护，不称测试矩阵已全面覆盖。
§11 / worker Issues 中“失败时归档不存在或 IO 失败”的概括不适用于此摘要失败用例，以本节事实澄清为准。

提供[子会话压缩阅读说明](../../notes/s08/subagent-context-compaction-review.md)，不再设置重复的
计数 / catch / 异常类必答题；一个可选架构讨论留待用户愿意时展开，不作为推进 Main 的门槛。
用户理解不因测试通过而自动记为验证通过；CLI、真实摘要质量和整章验收仍未完成。
复审回信为 INFO / REVIEW_SUPPORT，无需 worker 回复，也不授权下一功能或 Git 写操作。

## 13. B16 小提交方案（USER_APPROVED，待 worker 执行）

目标：保存子会话上下文压缩与生命周期集成的独立工程检查点。
依赖 B15 `5e70fd25c28ebfc3b2ad55731f82b00713e0df08`；一笔本地提交。
提交标题：`接通子代理上下文整理与有限恢复`。
精确六文件：

1. `src/main/java/com/jadecode/subagent/SubagentRuntime.java`
2. `src/main/java/com/jadecode/context/ContextCompactor.java`
3. `src/test/java/com/jadecode/subagent/SubagentRuntimeCompactionTest.java`
4. `docs/jobs/s08/context-compact.md`
5. `docs/jobs/s08/subagent-context-compaction.md`
6. `docs/notes/s08/subagent-context-compaction-review.md`

实现、对应集成测试、契约与必要阅读说明组成一项完整能力，避免 Main 的后续接线混入同一检查点。
总任务单保留相邻的 B15 核验 / 学习节奏记录，不伪造逐轮历史。
暂存区为空。排除两份 parent-auto-prepare 与两份 parent-reactive-recovery 的 jobs / notes
既有修改（含用户编辑），它们保持在工作区，不修、不恢复、不暂存。
也排除 ignored 邮箱、构建产物、机器配置、秘密和所有仓库外临时副本。
提交前精确核对 staged 路径 / stat / 完整 diff / 空白与获批内容；不宽泛 add。
用户于 2026-10-13 明确回复“批准这笔本地提交”，授权上述六文件的一笔本地提交。
执行任务 `GPT-20261013-S08-GIT-B16-001`，USER_APPROVED；worker 核对六文件获批快照后精确暂存并提交一次。
GPT 不执行 Git 写操作；完成回信后核验实际 hash、父节点、文件集合及提交内容。
仅计划本地 commit，不含 push / fetch / amend / reset / rebase / squash 或创建引用。
不等待可选讨论作答，不将提交等同学习或章节完成。
留档后下一项按总任务单 §7.2 接 Main，不重写压缩策略或扩展记忆功能。
