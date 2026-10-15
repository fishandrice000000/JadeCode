# s10 工程任务单：把任务进度变成可恢复的状态

日期：2026-10-15。状态：工程自审、离线验证、用户学习与独立真机验收通过；实现已提交 f5acbd8，可进入 s11。
来源：本机 `../learn-claude-code/s10_task_system/README.zh.md` 与 `code.py`。
学习入口：[Task System 概念指南](../../notes/s10/task-system-introduction.md)。

## 1. 需求与上一章的边界

s05 TodoWrite 帮模型维持当前计划，s09 Memory 保存可复用知识。
s10 保存另一类信息：具体任务由谁负责、做到哪一步、前置条件是否满足、重启后怎样继续。
这不是新的记忆类型，也不是现有 task 子代理委派工具的重命名。
原课程本章从 s04 工具基础展开；JadeCode 将按实际现有 s01–s09 能力局部接入，不回退旧能力。
s09 已工程接通但独立真机验收仍待完成，不因进入 s10 自动标记通过。

## 2. 原课程机制（已核对本机代码）

- 工作区 `.tasks/{id}.json`，id 为 task_ 加八位随机十六进制字符，创建时排他写入并有限重试碰撞。
- 记录字段：id、subject、description、status、owner、blockedBy。
- 六个工具：create_task、update_task、get_task、list_tasks、claim_task、complete_task。
- 先创建节点拿到实际 ID，再添加依赖；同一回复里的兄弟工具调用不能使用尚未返回的 ID。
- 添加依赖要求目标 pending 且无人认领，依赖存在，拒绝自依赖与环；重复添加去重。
- 全部前置任务 completed 才能认领；缺失前置任务也不能推断为已完成。
- claim 把 pending 改为 in_progress 并记录 owner；complete 要求 in_progress 且 owner 匹配。
- complete 返回刚解锁的下游，不自动执行或认领；任务完成声明来自工具调用，不证明实现质量。
- get 返回完整描述，list 是目录 / 状态摘要；跨会话通过文件恢复，不恢复完整旧聊天。

## 3. JadeCode 实施前规划

先实现一项完整能力“持久任务图与顺序状态流”，不按随机数 / 文件 helper 分多轮教学。
预计 tasks/ 下的 Task 值、TaskStore，以及六个薄工具适配与 Main 父工具表接入；实际 API 与精确文件
在实施前按现有源码确定。保持 todo_write 与 task 委派能力，不自动让子代理认领任务。
不引入后台执行、调度器、并发锁、真实多 Agent 身份认证、自动重试或跨项目任务数据库。
原课程 owner 是状态标签；本章顺序 claim 不是并发安全 / 安全身份验证，不能冒称“分布式队列”。

存储需要工作区 / ID / 链接围栏、记录形状与状态检查、更新失败不假成功；重要 IO 失败契约与
候选发布策略在实现时明确，不能用当前 MemoryStore 假装任务文件已经可靠支持。
按用户裁决不新增单元测试；以构建、工程自审与必要临时离线检查验证，再由用户真机验收。
详细中文 JavaDoc 与行间注释、jobs 留档、代码阅读指南和精确 Git 审批要求不变。

## 4. 概念阶段交付边界（历史）

概念阶段仅创建资料与本任务单，当时没有 tasks/ 源码、工具注册、运行目录或真实 API 调用。
不因为课程已有 Python 代码就宣称 Java 已实现。用户无需补答 s09 拓展题才可讨论本节。
两份 s10 文档当时没有提交授权，未混入此前 s09 的任何提交；后续实现与提交范围见 §5–7。

## 5. 当前完整能力：持久任务图与顺序状态流

用户已讨论 Task System / TodoWrite、认领与并发的边界；T01 / T02 由 ChatGPT 在对话中讲解并给出参考回答，
不代填笔记，不冒称用户已独立作答。用户随后明确要求继续实施。

允许文件：新增 tasks/Task.java、tasks/TaskStore.java、tools/TaskTools.java；修改 Main.java、README.md、
已有 MainTest.java / MainCompactionAssemblyTest.java 的工具表与 prompt 预期；本任务单、概念指南的状态说明，
新增 notes/s10/task-system-review.md。不修改 AgentLoop、SubagentRuntime、Memory、依赖或权限框架。
本轮开始时用户正在修改 MemoryRecallService，已由用户自行恢复，当前无 diff；用户 s09 答案与研究目录不改。

API / 行为契约：

- Task 是不可变值：id / subject / description / status / owner / blockedBy；wire 状态为
  pending / in_progress / completed，pending 必须无 owner，其余状态要求非空 owner。
- TaskStore(workspaceRoot) 构造不写盘；create / load / list / updateDependencies / claim / complete
  为入口，依赖与状态更新不能通过任意字段覆盖绕过。description 允许空字符串，subject 必须非空。
- 缺库 list 返回空，create 才建 .tasks；ID 必须匹配 task_[0-9a-f]{8}。根目录解析后必须在工作区内，
  记录只允许普通非软链文件；坏 JSON / 身份与字段不一致明确失败，不静默忽略坏任务。
- create 有限重试随机 ID，CREATE_NEW 排他创建，普通写入失败只清理本次新文件。
  旧记录更新先完整编码与写临时文件，再同目录原子替换；失败不原地截断旧文件，不支持原子移动则报错。
  不承诺断电耐久性、恶意竞态防护或并发隔离，不引入锁或任务抢占协议。
- updateDependencies 只增加边，pending 且无 owner 才允许；全部校验后统一保存，拒绝不存在依赖、自己与环，重复边去重。
- claim 要求 pending 且所有前置 completed；缺失前置视为阻塞，不当作已完成。
  工具负责者固定 agent，owner 不是认证凭据；六个工具不接收模型指定的 owner / path / status。
- complete 要求 in_progress 与 owner 匹配；先在快照中计算刚解锁的下游，再保存，
  成功落盘后不再扫描磁盘，避免已完成却因后续列举失败返回含糊的“完成失败”。
- 工具参数严格按 schema 判型，失败以 Error: 回灌模型，不启动子代理、不自动调度、不自动判断代码正确。
  list 展示全部任务的简要状态与依赖，get 展示完整描述；所有工具经现有父循环 hook 与分发表。
- Main 保留 todo_write / task / compact / Memory 与旧行为；父工具表九把增至十五把，子表六把不变。
  prompt 提醒先取真实 ID 再加边、执行前认领与验收后登记，不每轮自动加载整库。

验证：最窄 compile；临时无网络演示真实存储 / 六工具 / Main 接线，重点验证依赖解锁、防环、重启读取、
错误输入与路径围栏；已有两个 Main 测试的定向回归；最终跳过测试的 package。
不新增 JUnit，不调用真实 API，不读取 .env，不在项目生成 .tasks，临时材料结束后清理。
交付后提供主流程阅读指南与少量场景问题；独立真机验收由用户执行。实施阶段未获 commit / push 授权，后续提交批准见 §7。

## 6. 实现、验证与工程自审

已实现 Task / TaskStore / TaskTools，Main 父表注册六工具，父十五把 / 子六把。
原 AgentLoop 工具交换、Stop、Memory 和上下文保护未改；不加入自动调度或自动完成验收。
TaskStore 只暴露受控操作，文件路径、保存、防环等 helper 保持私有；六工具没有 owner / status / path 参数。
详细中文 JavaDoc 与行间注释覆盖生命周期、关键筛选、状态重建、只读快照、路径与普通 IO 失败边界。
阅读入口：[task-system-review.md](../../notes/s10/task-system-review.md)。

实际验证（均无真实网络调用）：

- `mvn -q -DskipTests compile`：基础存储 / 工具实现后退出码 0，接线后再次退出码 0。
- `mvn -q -Dtest=MainTest,MainCompactionAssemblyTest test`：退出码 0，分别 9 / 6 条，
  合计 15 条、0 failure、0 error、0 skipped。只适配已有 prompt / 工具表预期，没有新增测试方法或文件。
- 最终 `mvn -q -DskipTests package`：退出码 0，重建 CLI 产物；该命令不执行测试，不冒称全量回归。
- 临时 TaskSystemDemo：javac 与 Java 执行均退出码 0。初版验证通过后，自审增加悬空库根拒绝与局部注释，
  package 后在全新临时子工作区再次运行，退出码 0；最终演示包含悬空库根检查。
- 实际六工具构造 A–F 图：正常创建 / 读回，显式 owner:null、小写 status 往返正确；
  重复边去重；提前认领、自己 / 间接成环、缺失前置、错误 owner 和执行中改边均拒绝。
  混合合法边与不存在前置的更新整体拒绝，原 JSON 字节不变，没有半批写入。
- A 完成返回 B/C；随后 C 完成只新返回 F，不重复报告 B；B 完成新返回 D/E。
  list 目录不含 description，get 仍有完整说明；重建 TaskStore 后能读取 completed 状态。
- 删除一个临时前置文件后依赖者保持阻塞；数字 subject、额外 owner、ID 穿越、坏 JSON、
  记录软链、越界库根与悬空库根明确拒绝，没有修改库外验证文件。
- 真实 Main.buildLoop + 假客户端：构造不创建 .tasks；首批两次 create 返回运行期 ID，
  下一次请求才据真实 tool_result 加依赖；提前 claim 的 Error 正常回灌，后续认领 A、完成 A 返回解锁 B。
  合计六次正式请求、一次结束后空提取，原最终 assistant 保留；B 仍为 pending，没有被自动执行。

**Pass（工程自审）**：实现后分别重新读取实际源码、Main / 工具调用方与 diff，检查了模型与 Harness 分工、
六工具 schema / 判型、wire 状态、Task 不可变性、防环方向、缺失前置处理、解锁差集、路径与链接围栏，
以及先完整准备再发布、成功后不做额外磁盘扫描的失败语义。由同一实现者完成，不声称独立审查或绝对无 Bug。

边界与风险：

- 没有真实模型 / CLI 独立验收。completed 仍是工具接受的声明，不是工程质量证据；认领不阻止模型绕过任务工具。
- 固定 owner=agent，没有多 Agent 身份隔离、锁或原子条件更新；不要并发修改同一任务库并依赖本章避免重复执行。
- 未动态注入旧记录原子发布失败 / 清理失败、随机 ID 碰撞或不支持原子移动的文件系统；这些由源码检查提供有限证据。
  没有 fsync、崩溃日志或多文件事务，创建新文件时进程中断仍可能留下坏文件，不宣称断电可靠恢复。
- API 不提供删除、重开、释放认领、取消、修改正文或编辑已有边；这是本章有意限定的六工具范围。
  外部修改造成的已有坏图不会自动修复，坏记录会阻止需要完整快照的变更；返回 Error 不代表自动修复了任务。
- 任务库使用全量本地快照；list 是短条目但未限定条目数量。超大结果沿用现有上下文保护，不新增分页 / 索引服务。
- 工作区运行文件受 TaskStore 本地围栏约束，不因此增加权限询问；不是可以防御任意并发恶意路径替换的沙箱。

用户自行恢复了本轮前的 MemoryRecallService 误删，ChatGPT 没有修改该文件。
本轮专用 /tmp 演示目录及其中源码、class、任务记录和软链已清理，没有处理既有临时材料。
本轮期间出现的 s08/offline-acceptance-guide.md 编辑、既有 s09 答案与研究目录均不处理、不纳入提交。
T01 / T02 在对话讲解，不记为独立答题通过；P01–P03 已提供，可分批讨论。章节独立验收与提交状态分别保留。

## 7. 本地提交检查点（USER_APPROVED）

建议一笔 `添加持久任务图与顺序状态管理`，依赖已提交 43d42ce 的现有 CLI 装配，精确十文件：

1. src/main/java/com/jadecode/tasks/Task.java
2. src/main/java/com/jadecode/tasks/TaskStore.java
3. src/main/java/com/jadecode/tools/TaskTools.java
4. src/main/java/com/jadecode/Main.java
5. src/test/java/com/jadecode/MainTest.java
6. src/test/java/com/jadecode/MainCompactionAssemblyTest.java
7. README.md
8. docs/jobs/s10/task-system.md
9. docs/notes/s10/task-system-introduction.md
10. docs/notes/s10/task-system-review.md

三份核心源码、实际接线、原断言适配与必要说明构成同一完整能力，拆掉接线或存储会使当前工具交付不完整。
用户已明确批准上述十文件的精确暂存与本地提交；不 push、不改历史。
不纳入旧笔记 / 答案、研究目录、运行产物、SOP 或机器配置。执行前后核对实际范围和快照，提交 hash 见 Git 记录与本轮对话。

## 8. 用户独立验收与收尾（2026-10-15）

P01–P03 已由用户作答并在对话批阅通过；T01/T02 为对话讲解，不伪称独立作答。
用户在 `tmp/s10-order-report` 运行真实 CLI，完成 A 实现订单统计、B 运行并核对、C 交付说明。
核对了实际源码、运行产物与归档中的工具记录：提前认领 B 被 A 阻塞，A 完成后报告 B/C 解锁，
最终结果为 total_orders=6、paid_orders=4、paid_amount_cents=8000。

用户随后退出并重新启动 `../../jade repl`，仅调用一次 list_tasks 和三次 get_task，
读回原来的 task_d787b24e / task_83bdc2a9 / task_5f40a3b7，状态均 completed，
依赖分别为 [] / [A] / [A]；没有重建任务或执行业务工作。重启恢复已提供独立真机证据。

同次输出的 Memory INVALID_RESPONSE 属于辅助记忆提取失败，未写候选；未知 ContentBlock 噪声仍留待流式传输优化。
不把这些已知旁支现象当作 Task System 失败，也不在本章收尾顺便修复。
运行材料留在忽略的 tmp 下；用户三份既有笔记与研究目录保持原样，不纳入收尾提交。
此前各节的“待验收”是当时记录，以本节最新结果为准；不据此宣称 s08/s09 的独立验收通过。
