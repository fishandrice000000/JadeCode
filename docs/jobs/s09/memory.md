# s09 工程任务单：选择性跨会话记忆

日期：2026-10-14。状态：M1–M4 已实现并接入 CLI；工程自审与离线验证见 §15，独立真机验收待完成。
执行方：ChatGPT；学习者与最终决策者：用户。
课程来源：本机 `../learn-claude-code/s09_memory/README.zh.md` 与 `code.py`。
学习入口：[Memory 概念指南](../../notes/s09/memory-introduction.md)。

## 1. 要解决的 Agent 问题

新会话没有旧 messages，但用户偏好、长期反馈、稳定项目事实与资料线索仍可能有用。
本章让 harness 保存经过筛选的知识，并根据新请求取回相关内容；不让用户每次重复提供背景。
不是训练模型，也不是恢复原来的执行现场，更不是把所有 transcript 重新塞入 system prompt。

与 s08 的边界：压缩管理当前有效上下文，归档提供历史证据；Memory 提供跨会话可复用知识。
Memory 可能省略、归纳或过期，不能替代原文归档。召回也占上下文，不能因此忽略预算。

## 2. 概念阶段的状态快照（不代表 M1 交付后仍无实现）

- JadeCode 尚无 memory 包、s09 接口或 CLI 接线；README 也未标记 s09 完成。
- AgentLoop.run 当前在 Stop 不要求续跑时直接返回，没有记忆提取阶段。
- Main 当前为 AgentLoop 提供基础 system 字符串，没有动态记忆背景装配。
- s07 的 SkillLoader 已体现目录与正文分离，但面向人编写的技能；不直接改成可写 MemoryStore。
- s08 真机主路径已有证据，R02 模型按转存路径回读仍待补；允许进入 s09 学习不等于该补验通过。
- 本轮开始前，三笔获批文档提交已执行：4d6daed、282ed33、7fe0c97；工作区与暂存区干净。

## 3. 原课程的数据流与关键契约

新用户请求 → 读取记忆目录 → 选择相关条目 → 有界加载正文 → 作为背景加入本轮 system → 主任务循环。
主任务真正结束（Stop 不再要求续跑）→ 提取候选 → 校验持久性与重复 → 保存记录、重建索引 → 按条件整理。

- 存储：工作区 `.memory/`，每条记录一个 Markdown 文件；YAML frontmatter 含 name、description、type。
- 索引：`.memory/MEMORY.md` 保存条目名称、文件引用与描述，不保存全部正文。
- 类型：user、feedback、project、reference；描述知识用途，不代表更高指令权限。
- 召回：模型从目录选择最多五条；调用异常时课程退回关键词匹配；召回正文有总字符上限。
- 提取：模型只给候选，scope=persistent 才可进入持久性检查；current_task 不保存为跨会话规则。
- 整理：课程达到十条时尝试合并；校验非空、名称不碰撞后再替换，失败尝试恢复快照与索引。
- 记忆作为参考背景，当前请求优先。提示边界是设计约束表达，不宣称彻底解决提示注入或语义误判。

课程目前使用同一个 MODEL 发起辅助请求，并非已装配一个独立的更便宜模型。
它的数量阈值、关键词和文本截断是教学策略，不是保证相关性、长期有效性或故障恢复的通用定理。
Java 实施前应核对每轮具体失败契约，不机械复制删除旧文件再尝试回滚的工程写法。

## 4. 按完整能力推进的章节地图

| 单元 | Agent 可见成果 | 边界 |
| --- | --- | --- |
| M1 已有记忆的选择与召回 | 给定新请求和已保存知识，得到有界、带来源的背景材料 | 不自动提取、不整理旧库；先建立请求侧主干 |
| M2 回合结束后的提取与保存 | 本轮长期偏好可供下一会话使用，临时限制不持久化 | 模型候选必须校验；不在每次工具回复后写记忆 |
| M3 记忆整理 | 重复或纠正信息可整理，失败不假装保存成功 | 先确定保留与失败策略，不附带存储生命周期系统 |
| M4 真实循环装配与验收 | run / repl 的新会话能召回，Stop 续跑不提前提取 | 明确父子作用域，再接实际入口；用户独立真机验收 |

这是初步地图，不授权本轮实现整章。每个单元实施前细化精确文件、API、失败语义、注释、验证与暂停点。
M1 的 Java 小任务已在 §8 定义并交付；其它单元尚未细化，不以本单冒充已确定的全部 API。

## 5. 共同边界与非目标

- 新写 Java 必须有详细、准确的中文 JavaDoc 与行间注释；先解释 Agent 场景和设计意图。
- 当前请求与长期背景区分；不保存秘密、普通工具输出或未经确认的助手猜测作为长期事实。
- 存储须遵守工作区路径与软链围栏；模型不能凭任意路径选择库外文件。
- 单次模型选中索引、格式正确或标注 persistent，不保证知识真实或长期有效。
- 不同时实施 s08 路径重组、持续日志、正文去重、Read 缓存、流式传输或并发协调。
- 不引入向量数据库或远程记忆服务，不修改模型、provider、API Key 或依赖来提前铺完整体系。
- 父子是否共享召回、谁可以自动写入，是接线前需明确的行为边界，不因为共用磁盘而默认全开放。

## 6. 验证、学习与 Git

遵循用户最新裁决：不主动新增单元测试，不追求完满矩阵；已有测试保留。
实现阶段按风险做工程自审、构建与轻量定向检查，真实 API 成本与最终真机验收仍由用户决定并执行。
概念阶段仅核对课程与文档；随后 M1 的编译、临时演示与修改范围见 §8–9。未调用真实模型或创建工作区 `.memory`。
源码交付后给主流程阅读顺序与架构题，不把方法清单或工程检查清单变成必答题。

概念阶段新建的两份 s09 文档当时没有 Git 提交授权，未混入此前三笔提交；后续 M1 五文件授权见 §10。
后续按 M1–M4 的能力检查点另提精确提交方案，不 push、不改历史。

当前单元：M2 留档后，用户明确要求一次完成后续 M3 / M4，不再逐项停下来答题；范围见 §14。

## 7. 提取策略裁决与概念批阅（2026-10-14）

用户明确要求：JadeCode 不移植课程在 should_store_memory 中按临时词匹配来否决保存的机制。
“是否值得长期保存、是否属于临时要求”的语义判断由提取模型完成；harness 不另用关键词充当语义裁判。
仍保留结构、必填字段、类型 / scope 枚举与 persistent 保存资格检查，以及路径、安全写入和名称冲突处理。
scope 的语义分类由模型给出，程序仅执行保存契约；上述检查不证明知识真实或分类永远正确。
本裁决不自动取消召回阶段的关键词降级；它用于辅助模型失败时选择相关记忆，是不同机制，后续单独明确。
保留 §3 的原课程描述作为对照，不把 JadeCode 的新裁决反写成原课程已有行为。

已读取用户实际答案：M01 的持久化 / 上下文可见性区别正确；M02 的过期限制干扰当前任务判断正确，
“把用户提示词做成记忆”需限定为从对话选择可跨会话复用的知识，而非转换或保存所有用户命令。
反馈在对话中给出，不修改用户答案。M03 用户认为题目重复已讲内容而跳过，不记为错误或作答通过，不要求补答。
本轮仅解释、批阅与记录裁决；按用户要求暂停，不实现 M1、不新增测试、不提交 Git。

## 8. M1 当前小能力：已有记忆 → 选择 → 有界背景

用户已澄清提取策略并要求继续。先实现请求侧召回主干，不先铺全章存储写入方法。
当前入口为 MemoryRecallService.recall(currentRequest)：返回背景 JSON 与是否降级，调用方下一阶段再注入 system。

精确允许文件：

- 新建 src/main/java/com/jadecode/memory/MemoryStore.java：只读工作区 `.memory/*.md`，扫描合法记录快照。
- 新建 src/main/java/com/jadecode/memory/MemoryRecallService.java：目录预算、辅助选择、有界加载与关键词降级。
- 本任务单；新建 docs/notes/s09/memory-recall-review.md 阅读指南。
  docs/notes/s09/memory-introduction.md 只同步 M1 已交付的事实与新阅读入口，问题和用户答案保持原样。

API 与失败契约：

- MemoryStore(workspaceRoot) 绑定实际工作区；不创建目录，不写 MEMORY.md，缺库是合法空结果。
- 仅扫描一层 Markdown；排除 MEMORY.md、目录与越界链接，严格识别 UTF-8 / YAML 元数据及非空正文。
  不完整 / 非法记录跳过并给出简短诊断；实际文件系统 IO 故障向上抛 IOException，不伪装成没有记忆。
- 每次召回重新扫描，固定该次目录与原文快照；模型回传索引而不是任意路径，不保存上次召回为会话状态。
- 当前请求最多 4000 字符，发送的 JSON 目录最多 12000 字符，只发送完整条目；选择与降级均只用实际发送的目录。
- 选择辅助请求不带工具，最多一次模型调用、200 输出 token；不修改主会话、不运行 Stop、工具或压缩重试。
- 严格 JSON 整数索引数组：去重、最多五条、保持模型选择顺序；合法 [] 不触发降级。
  LlmException 或格式 / 越界索引失败才退回名称 / 描述关键词排序，结果显式标注 usedFallback。
- 返回带 source / content / truncated 的 JSON；记忆原文（含 frontmatter）总量最多 20000 个 UTF-16 单元，
  截断不拆开代理对。此额度不含 JSON 转义 / 来源字段，不等同精确 token 或整段 system 的长度上限。
- 这轮关键词只用于召回失败降级，不实现提取或按内容词语拒绝保存，不新增持久状态、向量服务或配置体系。

验证与暂停点：先 mvn -q -DskipTests compile；再用临时区域的无网络轻量演示检查
正常选择、合法空选择、降级、目录 / 正文预算与软链围栏；不是新建 JUnit 套件或永久测试矩阵。
临时演示只连接注入的假 LlmClient，不读取 .env / 认证，不在仓库创建 `.memory`，结束后仅清理本轮临时材料。
完成工程自审与中文注释核对后交接源码学习；不接 Main / AgentLoop / 子循环，不自动写入、整理或提交。

## 9. M1 交付、验证与工程自审

已交付两份 Java：MemoryStore.listRecords 提供只读记录快照；MemoryRecallService.recall 完成
目录 → 辅助选择 → 背景 JSON，返回 RecallResult(background, usedFallback)。详见[源码阅读指南](../../notes/s09/memory-recall-review.md)。
没有修改现有 Main、AgentLoop、SubagentRuntime、LLM 适配器、pom、脚本或任何旧测试。

实际验证：

- `mvn -q -DskipTests compile`：实施后、缩窄选择异常捕获后，以及最后包装目录遍历 IO 后均退出码 0。
  这里只宣称编译成功，没有运行单元测试 / mvn package，不把此前 s08 的 331 条报告算作本轮验证。
- 临时 RecallDemo 的 javac 首次失败：假客户端 lambda 直接抛 JSON 受检异常，与 LlmClient 契约不匹配。
  修正临时演示的异常转换后 javac 退出码 0；产品源码编译没有失败，不为修演示扩展生产 API。
- 临时 Java 演示运行退出码 0，无网络、无 JUnit：空库不建目录且模型调用 0 次；合法记录 2 条，坏文件与越界链接拒绝。
  选择请求 tools=0，queryChars=4000，catalogChars=144，bodyVisible=false；[1,0,1] 得到两条按选择顺序的背景。
- 合法 [] 返回空背景、usedFallback=false；模型异常、非数组回复、越界索引均观察到关键词降级，来源为匹配记录。
- emoji 切点演示：记忆原文总量 20000，源文件完整且未变，截断标记 true，没有产生半个代理对。
  第一条少取一个 UTF-16 单元后，剩余一个单元可用于第二条，这是共享原文额度，不是每条各有 20000。
- 超大首条目录不能进入完整条目预算时，背景为空且模型新增调用 0 次，有预算诊断；库根链接越出工作区抛 IOException。
  最后仅新增了延迟遍历 UncheckedIOException → 原 IOException 的包装，复编译通过；未动态制造目录遍历中途 IO 故障。

**Pass（M1 工程自审）**：实现者 ChatGPT 在编写后重新阅读源码、异常边界、输入 / 输出对象与演示结果，
核对索引只对应发送目录、正文不进入选择请求、无工具副作用、合法空选择与失败降级不同、路径围栏及原文件保留，
并核对两份类 / 方法的中文 JavaDoc 与行间解释。不是独立 Reviewer，不保证绝对无 Bug。

已知边界：读取库会在本机读取全部合法记录原文，本轮限制的是模型输入，不是磁盘扫描成本；
目录按文件顺序取能放下的前缀，巨大条目可能使后续条目不可见；关键词降级不理解同义词或完整中文语义。
20k 原文额度不保证整个 JSON / system 不超窗口；提示边界和文件格式校验不保证知识真实、未过期或无法提示注入。
不提供并发快照事务、知识版本管理或自动回查；选择与应用的真实模型行为未验证，循环还没有调用本服务。

本轮专用 /tmp 演示源码、class、生成记录与链接已清理，仅删除本轮材料，不清理用户资料。
用户现有答案与 docs/notes/other-agents-analysis 保留，不删除、不移动、不纳入本任务验证。

## 10. M1 本地提交检查点（USER_APPROVED）

已获批一笔：`添加已有记忆的选择与有界召回`，提交基点 7fe0c97。
精确五文件：

1. src/main/java/com/jadecode/memory/MemoryStore.java
2. src/main/java/com/jadecode/memory/MemoryRecallService.java
3. docs/jobs/s09/memory.md
4. docs/notes/s09/memory-introduction.md
5. docs/notes/s09/memory-recall-review.md

两份实现与 API / 范围说明作为同一完整小能力。概念指南含用户已写答案，批准此范围才随单元留档，绝不改写答案。
排除 docs/notes/other-agents-analysis、ignored SOP、生成物、临时演示与机器配置。只精确 add 与一次本地 commit，
不 push、不改历史。用户已明确批准本节精确五文件的本地暂存与提交，不沿用此前三笔文档授权。
执行前后核对实际 Git 范围与快照；本检查点的实际 hash / 执行结果以 Git 记录及本轮对话为准。

## 11. M2 当前小能力：任务结束后的提取与安全新增

M1 检查点 edd489c 已提交。用户已答 R01，并经对话澄清“描述影响选中、正文粒度影响使用”；
R02 用户选择跳过，R03 以对话讲解截断与回查；不冒称两题书面作答通过，不改已有答案。

本轮入口 MemoryExtractionService.extract(messages)：由未来主循环在 Stop 不要求续跑之后调用，
当前只实现服务，不提前接线、不执行当前学习对话的真实记忆提取。

精确范围：修改 MemoryStore.java（新增写入与索引）；新建 memory/MemoryExtractionService.java；
本任务单；新建 docs/notes/s09/memory-extraction-review.md。MemoryRecallService 与旧笔记不改。
不修改 Main、AgentLoop、SubagentRuntime、pom 或测试，不实施整理、更新旧记录、回查策略或目录优化。

契约：

- 从最近十二条消息取得 user / assistant 纯文本，最多 8000 UTF-16 单元，保留最新尾部，不拆代理对。
  不加入 tool_use / tool_result 正文；不改变输入消息。无文本不调用模型、不创建库。
- 辅助提取只调用一次已有 LlmClient，1000 输出 token、空工具表；已有记忆只发送预算内目录，最多 6000 字符。
  提示要求长期、聚焦的知识与有用描述，排除秘密、工具输出、助手猜测和临时限制；这是模型语义要求，不是关键词规则。
- 完整 JSON 数组才继续；候选要求 name / type / scope / description / body 为合法非空字符串。
  current_task 不保存，persistent 才交给存储；个别坏候选跳过。没有临时词过滤或内容语义裁判。
- 调用失败 / 整体回复非法返回明确状态，不写文件；不伪装成成功存储。真实存储 IOException 向上报告。
- MemoryStore.saveNew 接受已校验的 MemoryDraft 列表，只新增；文件名从名称生成安全 slug，不能由模型指定路径。
  同名 / slug 清洗碰撞 / 既有非法文件 / 目录 / 软链占位均不覆盖，计为未新增；旧知识修正留待 M3 明确。
- UTF-8 Markdown + YAML frontmatter，与 M1 读取格式相容。完成全部本批新记录后重建 MEMORY.md，
  临时索引写完关闭后在同目录原子替换；索引必须为普通非链接文件或不存在。
- 普通 IO 失败尝试清理本批新记录与临时索引，旧记录不变；清理失败保留为 suppressed，不报告假成功。
  不是多文件崩溃事务，不保证断电恢复，不引入锁 / worktree / 日志服务；不自动删除已有库内容。

验证：mvn -q -DskipTests compile 与无网络临时演示，观测 persistent / current_task、真实落盘可由 M1 读取、
同名不覆盖、非法整体回复不写盘、失败传播及本批清理。没有新的 JUnit 套件，不调用真实 provider，不读取秘密。
演示只在专用 /tmp 工作区产生运行材料，结束后清理自己的材料；用户答案与 other-agents-analysis 保留。
交付后先源码学习，不自动开始 M3 / M4。Git 本轮没有提交授权，另提具体范围，不沿用 M1 授权。

## 12. M2 交付与工程自审

已实现 MemoryExtractionService.extract → MemoryDraft → MemoryStore.saveNew，提供结构 / scope 筛选、
新文件排他写入与索引重建。M1 的读取签名保留，库根围栏提为读写共用私有方法，读取行为不变。
服务不接主循环，不自动执行本学习对话的记忆提取，不整理旧记录，也没有临时词过滤。
阅读入口：[提取与保存指南](../../notes/s09/memory-extraction-review.md)。

实际验证：

- `mvn -q -DskipTests compile`：实现后退出码 0；自审同步读写说明、整理 import 后再次退出码 0。
- 临时 ExtractionDemo：javac 退出码 0；无网络 Java 演示退出码 0，未新增或运行 JUnit / mvn package。
- 空输入 SUCCESS / stored=0 / ignored=0，没有创建库；辅助请求 tools=0，工具正文未进入输入，原历史未改变。
- 混合四候选（persistent、current_task、非法类型、slug 碰撞）：实际新增 1、忽略 3。
  UTF-8 正文与 YAML 元数据可由 M1 listRecords 读回；MEMORY.md 指向该新增记录。
- 再次提取相同候选：新增 0、忽略 4，原文件未被覆盖；整体非数组返回 INVALID_RESPONSE，模型异常返回 MODEL_FAILED。
- 直接提供含“本次任务暂时”的合法草稿仍能保存，证明没有关键词否决，不宣称模型误分类已经被程序纠正。
- 使用孤立代理字符触发真实 UTF-8 MalformedInputException：本批先写成功的新记录与后续半成品均清理，
  此前旧记录和索引逐字保持。不是依赖模拟权限或随机竞态的失败演示。

**Pass（M2 工程自审）**：ChatGPT 在实现后重新核对源码、提取提示、状态与 IO 失败边界、YAML 编码、
排他创建、名称碰撞、索引替换和本批清理，并复核演示中的实际文件与输入不变性。
读写 API 均有中文 JavaDoc 与阶段 / 关键分支行间注释。自审来自同一实现者，不声称独立 Review 或绝对无 Bug。

边界：没有真实模型验收、循环接线、自动整理或新 / 旧知识冲突解决；相同名称的纠正不会在 M2 覆盖旧记录。
没有语义重复识别，只按目标文件是否已被占用防覆盖；字段合法、scope=persistent 不保证是真实长期知识。
采样仅最近十二条消息的纯文本，可能漏掉早期偏好；摘要 / synthetic user 的来源未建模，角色不等于真实人类来源证明。
索引原子替换不等于整个记录库的崩溃事务；未动态穷举索引发布失败、清理失败、并发目录变更或不支持原子移动的文件系统。
IO 清理可能留下空库目录；不以自动删除库或全面并发系统解决此问题。

本轮临时演示源码、class 与生成记录 / 索引已清理；不清理用户的旧笔记或研究目录。
旧 M1 用户答案与其已有尾空格保持原样，文件 SHA-256 在本轮前后完全一致。
接下来用户阅读 M2 主流程与讨论设计取舍，不自动推进 M3 / M4。

## 13. M2 本地提交检查点（USER_APPROVED）

建议一笔：`添加长期记忆的提取与安全新增`，依赖 M1 edd489c，精确四文件：

1. src/main/java/com/jadecode/memory/MemoryStore.java
2. src/main/java/com/jadecode/memory/MemoryExtractionService.java
3. docs/jobs/s09/memory.md
4. docs/notes/s09/memory-extraction-review.md

两份实现与必要契约、学习交接作为同一小能力。不纳入 memory-recall-review.md 的用户答案、
other-agents-analysis、SOP、生成物、临时材料或机器配置。只精确暂存与一笔本地提交，不 push、不改历史。
用户已明确批准本节精确四文件的本地提交，不沿用 M1 或此前文档提交授权。
实际 hash / 执行结果以 Git 记录与本轮对话为准；后续 M3 / M4 不混入本检查点。

## 14. 用户授权的 M3 / M4 合并交付

用户要求直接完成剩余 s09 再进入 s10，优先于本单前述逐单元暂停点；不要求先答完 E01–E03。
M2 四文件已按独立授权提交为 47b5f5e；M3 / M4 实施时尚无 Git 提交批准，后续授权见 §16。

目标：CLI 父循环在用户任务入口召回，Stop 真正允许结束后提取，新增成功后尝试十条阈值整理。
不是每次内部模型请求召回 / 提取；工具批次、Stop 续跑与正式请求重试共享一次召回背景。
父循环维护工作区 .memory；子代理不自主提取或召回，需要的知识由父任务显式带入。

实现范围：MemoryStore、MemoryRecallService / MemoryExtractionService 的接线说明、
新增 MemoryConsolidationService / MemoryManager、AgentLoop、Main、README 的当前能力说明，
本任务单与新增 memory-lifecycle-review.md。必要时适配已有 MainCompactionAssemblyTest 的辅助请求分类，
不新增测试方法 / 单元测试文件，不改变原压缩断言，不引入新配置、CLI 参数或依赖。
用户 memory-recall-review.md 答案、研究目录、s08 存储优化与子代理运行时不改。

契约：

- 一次召回生成 run 局部 system，辅助回复不加入主历史 / 工具分发 / Stop。
- 成功新增后才检查整理；少于十条不调用模型，完整 JSON 输入超过 20000 UTF-16 单元则明确跳过。
  整理使用同一客户端、空工具、3000 输出 token；模型返回完整非空整库，最多三十条。
- 保留 / 合并 / 删除的语义由模型决定；没有词语黑名单，也没有独立过期检测或可靠时间顺序。
  整库回复的任一坏条目、空数组、过多条目或安全文件名碰撞均拒绝整批，不能部分过滤后删除旧库。
- 替换前核对模型输入快照、文件类型与快照外目标冲突；先完整写临时候选及索引，
  只替换合法普通旧记录，坏文件 / 链接 / 目录不删除。普通 IO 故障尝试恢复旧内容，恢复失败明确保留。
  索引最后原子发布；不宣称文件库有断电事务、权限元数据恢复或并发隔离。
- Memory IO / 模型 / 坏回复明确诊断，但不抹掉已完成主任务结果。程序错误不 catch-all 隐藏。
  主任务失败不提取；辅助整理失败不撤销先前已经成功保存的提取记录。
- 保留旧五 / 六参 AgentLoop 构造行为；七参才启用 Memory，Main 使用七参装配父循环。
- 同名纠正仍可能被只新增机制跳过；小库或无新增时不整理，不能承诺所有矛盾自动解决。

验证：编译、最小无网络临时演示、已有装配测试的定向回归、跳过测试的 package。
不调用真实 provider，不读取 .env，不在项目工作区生成记忆。用户另做独立真机验收。
暂停点：交付剩余 s09，给精简阅读顺序与真机检查，再开始 s10 概念；本轮不实现 s10。

## 15. M3 / M4 工程交付与验证

已接通：Main → 七参 AgentLoop → MemoryManager → 入口召回 / 完成后提取 / 新增后整理。
新增 MemoryConsolidationService；MemoryStore 提供同包整库替换与普通失败恢复。
旧五 / 六参循环不自动启用 Memory，SubagentRuntime 与原工具表不变。
README 同步当前能力，不把未完成真机验收的 s08 / s09 标记为章节完成。
精简阅读与验收：[memory-lifecycle-review.md](../../notes/s09/memory-lifecycle-review.md)。

实际验证：

- `mvn -q -DskipTests compile`：退出码 0。
- 最终 `mvn -q -DskipTests package`：退出码 0，已重建 CLI 产物；本命令没有执行测试。
- 首次 `mvn -q -Dtest=MainTest,MainCompactionAssemblyTest test`：退出码 1，15 条中 5 failure、0 error、0 skipped。
  根因是旧假客户端把全部空工具请求当作摘要，新的提取请求错误增加摘要计数；不是压缩多执行。
  仅适配该假客户端：识别专用提取 prompt 并回复合法 []，保持原六条用例与压缩断言不变。
  最窄复测 `mvn -q -Dtest=MainCompactionAssemblyTest test`：退出码 0，6 / 0 / 0 / 0。
  首轮 MainTest 原有 9 条全绿；没有新增 / 删除 / 禁用测试，不冒称全量回归。
- 临时 MemoryLifecycleDemo：javac 退出码 0；实际 Main 接线，无网络 Java 演示最终退出码 0。
  初次演示退出码 1：临时断言只捕获 MalformedInputException，而 Files.writeString 实际抛
  UnmappableCharacterException；二者均为 CharacterCodingException，修正演示捕获范围后重新执行通过。
  产品已在破坏旧库之前失败，没有为通过演示改写产品失败语义。
- 九条旧库 + Stop 强制续跑：召回 1 次、正式请求 2 次、最终提取 1 次、整理 1 次，十条 → 一条。
  两次正式请求的 system 相同；辅助回复没有进入主历史，最终 assistant 原回复仍在。
- 新建父循环再次召回已持久化正文；合法空提取不整理。
- 空 / 缺字段 / 清洗名称碰撞的整库输出拒绝，旧记录与索引不变；真实 UTF-8 候选写入失败发生在
  临时准备阶段，旧库仍完整，临时整理材料已清理。
- MEMORY.md 被目录占用：提取 IO 故障告警，旧库不变，主任务原回答仍可展示。
- 主请求 LlmException 不提取；旧五参构造器只执行一次主调用，无自动记忆维护。

**Pass（M3 / M4 工程自审）**：实现后重新核对实际文件、调用顺序、专用 JSON 回复契约、
主历史不变性、空 / 非法候选保护、名称与链接围栏、索引最后发布、普通失败恢复和中文注释。
同一实现者完成自审，不声称独立审查或没有任何潜在 Bug。

未验证边界：未真实调用模型，未动态触发“删除旧记录之后”的发布失败 / 恢复失败，
未测试不支持原子移动的文件系统、断电或并发修改。预检查不是并发隔离；失败恢复不保留权限等元数据。
模型可能错误合并 / 删除；同名纠正可能被只新增层跳过；小库、无新增与过大库不保证自动整理。
system 中召回正文虽有界，但不是服务端准确 token 预算，压缩器的消息估算不包含完整 system。
提取可看到摘要 / synthetic user，不能以 role 证明全是人类确认事实。没有秘密扫描器或语义可信度证明。
Memory 是独立 Harness 写盘路径，不是工具权限审批；当前未提供 CLI 禁用开关或“只读任务禁止自动记忆写入”
的结构化契约，不能仅凭模型没有调 write_file 就宣称进程没有写运行期文件。

用户已表达对四阶段主干的理解并要求跳过进一步逐题停顿；不把未答的 E / R 拓展题记录成通过。
进入 s10 概念不等于 s09 独立真机验收完成；提交完成也与章节验收分开。
最终构建、临时清理与精确提交方案见本轮对话；M3 / M4 已获得 §16 精确范围的提交批准。

本轮 /tmp 专用演示目录及其中源码、class、记录和索引已清理，没有处理任何既有材料。
用户 M1 笔记 SHA-256 保持 1d8337ce11aada3305c9cc8be3914d86cc2bb8ac6b8ccd2cb129566832666cde 不变。

## 16. M3 / M4 本地提交检查点（USER_APPROVED）

建议一笔 `接通长期记忆整理与父循环生命周期`，依赖已提交的 M2 47b5f5e，精确十一文件：

1. README.md
2. src/main/java/com/jadecode/Main.java
3. src/main/java/com/jadecode/agent/AgentLoop.java
4. src/main/java/com/jadecode/memory/MemoryStore.java
5. src/main/java/com/jadecode/memory/MemoryRecallService.java
6. src/main/java/com/jadecode/memory/MemoryExtractionService.java
7. src/main/java/com/jadecode/memory/MemoryConsolidationService.java
8. src/main/java/com/jadecode/memory/MemoryManager.java
9. src/test/java/com/jadecode/MainCompactionAssemblyTest.java
10. docs/jobs/s09/memory.md
11. docs/notes/s09/memory-lifecycle-review.md

整库发布、生命周期接入、既有装配剧本适配与必要文档作为本次用户要求的完整收尾能力。
用户已明确批准上述十一文件的本地暂存与提交；排除用户召回笔记答案、研究目录、s10 两份概念文档、运行产物与 SOP。
不 push、不改历史；独立真机验收另记，不用未答拓展题阻塞提交审批。
执行前后核对范围与文件快照；实际提交 hash / 执行结果见 Git 记录及本轮对话。
