# s08 收尾单元：压缩安全与请求前管线的离线验收

日期：2026-10-14。状态：实施、离线验证与工程自审 Pass；用户真机主路径已验证，R02 模型回读待补。
执行与工程自审：ChatGPT。Git：精确十文件已获批提交为 `d15a4a9`，并核验快照一致；未 push、未改历史。

## 1. 需求与非目标

父子循环及 Main 已接通，但已有测试数量不能替代对各层安全契约的直接验证。
本单元检查“结果转存后原文仍可恢复、切片不拆工具交换、请求前各层按预期衔接”，
补齐存储、批次预算、snip 和 prepare 的必要证据，再交给用户真机验收。

不实施候选路径优化、持续日志、正文去重、Read 缓存、自动清理或 s09。
不重写已有 micro / fit / 循环测试，不改变压缩阈值与策略；不修改用户答案。

## 2. 范围与测试设计

- 新增 ToolOutputStoreTest：UTF-8 全文、同 ID / 清洗碰撞不覆盖、预览与可信引用复用、
  非法 / 越界 / 非普通文件 / 符号链接路径拒绝、写失败不返回假路径。
- 新增 TranscriptStoreTest：完整 JSONL 顺序与转义、不覆盖、严格 marker、路径围栏、
  建目录与序列化失败只清理自己的半成品。
- 新增 ToolResultBudgetCompactorTest：批次与单项边界、最大项优先但不重排结果、
  只处理最新批次、保留 reminder、可信路径复用、不可变性与写失败。
- 新增 SnipCompactorTest：50 / 51 边界、完整归档、头尾多工具交换、交叉切点 no-op、
  可信 marker 幂等、伪造 marker 不绕过归档、失败不改变输入。
- 新增 ContextCompactorPreparationTest：小历史 / JSON 字符边界、预算后 snip、
  micro 后停止与 micro → fit 衔接；已存在的摘要分支测试保留。
- HistoryCompactorTest 补“前缀 + 新鲜尾部”的归档失败与固定摘要异常传播；
  HistoryCompactor 仅校正两处已有事实错误注释，SnipCompactor 仅校正归档对象说明。
- 本任务单、总任务单相关收尾状态，以及新建的 offline-acceptance-guide.md 学习 / 真机验收说明。

新测试都使用临时工作区与假客户端。中文 JavaDoc、行间注释解释 Agent 场景和断言所保障的行为。
不读取秘密、不调用真实 API、不在仓库根创建运行时数据，不依赖权限 chmod 或竞态制造 IO 失败。
若发现会改变策略的契约冲突，记录事实并停止受影响修改，不扩大本单元。

## 3. 验证、自审与交接

先运行五份新增测试及 HistoryCompactorTest，再运行 mvn package，核对报告数量与退出码；不 clean。
工程自审核对实际输入、落盘文件和输出对象图，不以“出现 marker”代替全文恢复验证。
保护既有十份文档编辑及 optimization 目录；只精确更新当前任务涉及的段落，不暂存无关文件。

完成后提供必要阅读顺序与少量可选架构讨论，以及用户独立真机验收步骤。
离线 Pass 不代表真实模型摘要质量、用户理解或整章验收通过；最后仍等待用户执行真机验收。

## 4. 实施与验证记录

新增五份测试共 35 条：ToolOutputStore 10、TranscriptStore 7、最新批次预算 8、snip 6、prepare 4。
HistoryCompactorTest 另增 2 条失败分支，原 15 条增至 17 条；本单元合计新增 37 条。
两份生产文件只改三处注释，没有修改可执行语句、签名、阈值或持久化路径。

实际命令与结果（均退出码 0，未 clean）：

- 首轮四份新增测试：30 条，失败 / 错误 / 跳过均 0。
- 五份新增测试 + HistoryCompactorTest 首轮定向：51 条，失败 / 错误 / 跳过均 0。
  当时 ToolOutputStoreTest 是 9 条；没有把后来追加的 IO 失败用例冒称已在此轮运行。
- 自审补工具正文的真实写入失败 / 半成品清理用例，最窄 ToolOutputStoreTest：10 条全通过。
- 最终 mvn package：331 条、38 个 suite，失败 / 错误 / 跳过均 0，BUILD SUCCESS；
  已与 Surefire XML 数量相互核对。原基线 294 + 本轮 37 = 331。

没有测试失败后扩大实现范围；所有新增行为测试首次运行均通过。
本次构建保留 Maven Shade 的重复资源 / module-info 提醒，未升级依赖或调整打包配置。
本轮文件空白检查无错误；全仓检查仍显示旧用户笔记 parent-auto-prepare-review.md 两处末尾空格，保留原样。

## 5. 工程自审结论与边界

**Pass（本单元范围）**：核对了真实保存正文与 JSONL 内容、输入对象图、调用 ID / block 顺序、
两处工具交换切点、幂等条件、目录 / 软链围栏、半成品清理、异常传播与各层停止条件。
自审由实现者 ChatGPT 完成，不冒称独立 Reviewer；结果不等于代码绝对无错。

确认预算 → snip 归档的是预算后完整候选，大正文由独立结果文件恢复；不错误要求 JSONL 再重复保存正文。
快照不是持续会话日志，当前请求工具与摘要工具表的分离仍由既有接口及测试保障。

范围外观察：BashTool 在等待子进程结束后才读取合并输出，较大输出可能堵满管道并导致等待超时；
本轮没有动态复现或修改该既有工具，也不以它产生大日志作为压缩真机 fixture。
这是另一个工具执行任务的候选，不影响本轮基于 read_file 的压缩契约证据。

离线阶段尚未验证真实模型行为；2026-10-14 用户随后提供了真实 REPL 日志，结果见下节。
尚未验证摘要的一般语义保真、服务端真实超限与重试、长期文件保留 / 崩溃持久性。
这些不能从 331 条全绿自动推出。必要阅读顺序、可跳过讨论与 R01–R05 步骤见
[收尾指南](../../notes/s08/offline-acceptance-guide.md)。不增加新的必答细节题。

## 6. 本地提交方案与执行结果（USER_APPROVED，已执行）

建议一笔：补齐上下文压缩的离线验收。精确十文件：

1. src/main/java/com/jadecode/context/HistoryCompactor.java
2. src/main/java/com/jadecode/context/SnipCompactor.java
3. src/test/java/com/jadecode/context/HistoryCompactorTest.java
4. src/test/java/com/jadecode/context/ToolOutputStoreTest.java
5. src/test/java/com/jadecode/context/TranscriptStoreTest.java
6. src/test/java/com/jadecode/context/ToolResultBudgetCompactorTest.java
7. src/test/java/com/jadecode/context/SnipCompactorTest.java
8. src/test/java/com/jadecode/context/ContextCompactorPreparationTest.java
9. docs/jobs/s08/offline-acceptance.md
10. docs/notes/s08/offline-acceptance-guide.md

依赖既有 B17 ce40069；完整测试与必要注释 / 交接作为同一个验证检查点，未新增运行行为。
master 作业单 context-compact.md 的本轮状态更新与既有用户 / 历史回填编辑一起留在工作区，
不把同文件的范围外旧修改混入本批。AGENTS.md、其余 jobs / 用户笔记、optimization 目录全部排除。
用户初次授权“提交暂存区”，但实际暂存区为空，ChatGPT 未擅自暂存。
用户随后明确批准本节精确十文件的暂存与提交，标题不变；不包含 push 或历史改写。
本轮真机结果与授权状态的必要留档不改变文件集合、代码或提交意图；执行时核对完整 staged diff。

执行核验：提交 `d15a4a9c4aebfcb3e55127d046d33354d713ac1f`，父节点 `ce40069`，
标题“补齐上下文压缩的离线验收”；十文件、1191 插入 / 3 删除。
提交内十文件 blob 与获批暂存快照逐一一致，提交空白检查通过，提交后暂存区为空。
旧文档、用户答案与优化文件保留在工作区；未 push、未改历史，未重复运行无变化的源码测试。

## 7. 用户独立真机结果与文件核验（2026-10-14）

事实来源：用户提供的 ./jade repl 全过程，以及本机对应的转存文件和两份 JSONL。
ChatGPT 只读核验文件，没有代跑真实模型、重做工具调用或删除恢复材料。

- R01 通过：真实 read_file → 正常回答 JadeCode / Java 21，无 bash 或写工具日志。
- R02 自动转存通过：真实完整读取总任务单，产生 64472 字符的工具正文文件。
  文件为 .task_outputs/tool-results/call_00_ET_SHsPFr2vnLrlTOlFUtvw1248-4286852104912074236.txt。
  按 ReadFileTool 的行 join 口径，与当时工作区任务单去掉末尾换行后的内容比较，cmp 退出码 0。
  这是文件全文核验，不冒称模型已通过 read_file 按转存路径回读；用户日志未展示该补验步骤。
- R03 通过：父 compact 恰一次，随后 read_file 继续核对并保持只读目标。
  对应 transcript-576596814799067914.jsonl 含 11 条历史，三个调用与结果 ID 配对，
  其中包括两次 read_file、compact，以及指向 R02 完整正文的路径；不是只保存最终回答。
- R04 通过：子 read_file → compact → read_file → Subagent done，父继续正常转述。
  对应 transcript-9237797156653187554.jsonl 含 5 条子历史（截至 compact 的完整交换），
  两个调用与结果 ID 配对，起始目标为独立子任务 prompt，不是父历史。
  compact 后的继续读取由用户运行日志证明，不错误要求压缩前快照包含未来消息。
- R05 通过：不存在文件正常返回工具错误，未出现 compact / bash / 写工具，REPL 随后接受 q。
  用户日志没有异常栈；没有独立测量进程退出码，亦不是服务端超限真机测试。

日志多次显示“跳过未知响应块: ContentBlock”。当前 SDK 适配只映射 text / tool_use，
其它块按既定实现逐块跳过；日志打印的是 SDK 包装类名，无法据此确定实际 discriminator 或内容。
本次主路径成功，但不推断被跳过的所有信息都无价值；留作适配层独立调查，本批不修。

结论：真实主路径表现通过，文件保存 / 父子归档有独立证据；R02 模型路径回读仍待用户补验。
不修改用户答案、不提前宣称全部真机步骤或 s08 章节完成；README 的章节标记保持不变。

后续裁决（2026-10-14）：用户选择先忽略未知响应块警告，已记入优化清单 OPT-004，后续与流式传输一并调查；
当前不作为 s09 概念与方案阶段的阻塞。R02 模型路径回读仍待补，不因允许继续而改记为通过。
