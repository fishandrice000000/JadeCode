# s11 工程任务单：慢命令后台运行，结果在请求边界返回

日期：2026-10-15。来源：本机 `../learn-claude-code/s11_background_tasks/README.zh.md` 与 `code.py`。
当前状态：实现、工程自审、验证与 R1–R4 独立真机验收通过；用户已在对话确认理解并宣布 s11 完成。

## 1. 需求、整体流程与非目标

模型启动独立的慢命令后，还应能读说明、检查代码，而不是整条 Agent Loop 卡在 bash。
模型显式选择 bash(command, run_in_background=true)，Harness 在主线程完成既有 PreToolUse 检查，
登记 bg_id 并立即返回启动回执。工作线程只执行 shell 和登记结果，不碰历史、不调用模型或 hooks。
父循环在下一次正式请求前收集完成事件，作为独立 USER 文本通知加入历史；启动的 tool_use 已有唯一 tool_result，
完成时不能再冒充同一工具调用的第二份结果。通知只是外部结果数据，不是用户新任务。

与课程一致：不猜 install/test 等关键词；不新增工具；不主动唤醒空闲循环；不自动等待所有任务完成。
不实现定时器、持久队列、s10 任务自动调度、完整输出文件、跨重启恢复或 s12 能力。
子代理维持同步 bash，避免子历史退出后遗失通知；父工具表仍十五把，子六把。

## 2. 范围与契约

- tools/ShellCommand.java：父同步与后台共享的进程执行，工作区 cwd、120s 超时、UTF-8 合流与有界输出；
  并发排空管道，修正旧 Bash 先等后读的大输出阻塞。Linux setsid 隔离进程组，正常完成、超时与关闭时清理。
- tools/BackgroundManager.java：单父循环所有的内存任务与完成队列；start / collect / close，最多 32 个未收集任务，
  唯一 bg_id、running → completed/failed；只在 collect 消费一次通知，关闭后不接受启动。
- tools/BashTool.java：兼容旧构造器，新增父管理器装配；布尔参数必须真 boolean，缺省或 false 同步。
  没接后台管理器的子工具不声明该参数，显式 true 返回不支持，不静默同步。
- AgentLoop / Main：同一管理器注入父 bash 与父循环，入口先捕获真实 activeRequest 再收集通知；
  通知先进入已闭合历史，再走 compactor.prepare，辅助摘要与记忆不会自己消费队列。
- CLI run / repl：会话结束 close，不按每个用户轮次 close；异常与 JVM 正常关闭也清理命令组。
- README、现有 MainTest 的精确 prompt 预期、jobs 与新 notes 指南同步；不新增 JUnit。

同步输出沿用最多 50,000 个 Java 字符的旧上限；后台通知摘要最多 500 字符，截断时明确标注。
这不是可靠日志或完整输出存储。
非零退出、超时、IO/中断均失败；启动回执不代表命令成功，completed 只表示 exit 0，不证明业务正确。
登记/线程启动失败在工具回复中返回 Error；实际 shell 启动在工作者中，失败表现为后续 failed 通知。
已开始的进程副作用不回滚。XML 风格通知对 command/正文转义，不把格式当安全沙箱。
进程组清理为 Linux 平台的生命周期措施，不能约束自行另开 session 的命令，也不承诺 SIGKILL/断电后清理。

## 3. 验证、交付与 Git

先 compile，再临时离线 Java 演示真实管理器与真实 Main + 假模型的完整闭环（无 API 费用）。
覆盖后台非阻塞、主循环继续读文件、成功/非零/超时、一次消费、权限阻断、错误布尔、工作区与退出清理；
按风险运行现有 Bash / Main / AgentLoop 相关测试，最后 package，不新增测试矩阵。
临时验证程序结束清理，只保留忽略的 tmp/s11-background-report 真实验收场景与指南，由用户调用真实 CLI。

工程自审与验证后直接按依赖本地提交，无需再次审批；push 与改写历史仍未授权。
不纳入三份用户笔记、other-agents-analysis、运行产物、机器配置与忽略的 SOP。
交付 notes 完整阅读顺序和少量架构场景问题；实现、工程 Pass、用户理解与真机验收分别记录。

## 4. 实现与实际验证

已接通全部本章路径：显式 boolean → 原权限 hooks → 启动回执 → 工作者执行 → 完成队列 →
正式请求边界通知 → 原上下文整理与模型调用。主线程保持串行管理历史，异步只发生在进程 IO。
父 bash 同步/后台共用会话所有者；子 bash 显式绑定 cwd、保持同步；父/子工具数量仍为 15/6。
主循环兼容旧五/六/七参构造；生产使用八参，CLI 的 try-with-resources 关闭整场会话。
相关代码有中文 JavaDoc 与行间注释；源码学习入口为
[background-tasks-review.md](../../notes/s11/background-tasks-review.md)。

实际命令与证据（均无真实模型请求）：

- `mvn -q -DskipTests compile`：退出码 0。
- `mvn -q -Dtest=BashToolTest,MainTest,MainCompactionAssemblyTest,AgentLoopTest,PermissionGateTest test`：
  初版及工程修正后的定向回归均退出码 0；最终分别 7/9/6/18/8 条，合计 48 条，
  0 failure、0 error、0 skipped。只适配已有 prompt 断言和旧注释，没有新增 JUnit 文件/方法。
- `mvn -q -DskipTests package`：初版及最终修正后均退出码 0，重建 target/jadecode.jar；不冒称全量测试。
- 临时 BackgroundDemo：javac 退出码 0；初次与最终 JAR 在不同干净临时子工作区执行，均退出码 0。
  实际执行真实 shell，不使用假 process；只把 LLM 换成不联网的脚本客户端。
- 管理器检查：gate 控制的后台任务在 gate 创建前返回 ID、collect 为空；放行后只通知一次，
  XML 正文正确转义。非零 exit 7 为 failed，1 秒超时的 sleep 为 failed；错误布尔/command 类型被拒绝。
  工作区 pwd 正确；五十万字符输出能排空管道、同步只保留五万字符；后台通知截断有明示。
- 真实 Main 接线检查：同批启动后台再 read_file；gate 未放行前真实读结果已回灌，
  证明并非“等慢命令结束后才继续”。随后请求收到 completed 通知，最新 ToolResultBlock 仍保留，
  每次正式请求与最终历史均通过 ToolExchangeProtocol；sudo 后台命令被原 PreToolUse 拒绝，无文件副作用。
  第二次 run 能收上一场任务的完成事件，入口 activeRequest 捕获不受通知污染；合计五次正式假模型请求。
- 生命周期检查：直接 close、普通 shell 完成但仍有后代、子 JVM 正常退出未显式 close、SIGTERM 四条路径，
  均确认所属命令组不再执行。Linux 僵尸等待系统回收不算仍执行，不能声称本程序能负责 init 的回收时机。
- 真实验收材料：`sh -n tmp/s11-background-report/build-report.sh` 退出码 0；只读 awk 核对输入为
  services=3 / ready=2 / blocked=1。没有提前生成验收 report.txt，没有替用户运行真实 CLI/API。

## 5. 工程自审与未完成边界

**Pass（同一实现者的工程自审，不是独立 Review）**：实现后重读源码、分发/权限/压缩调用方与完整 diff，
检查 schema、真假布尔、先审批再异步、唯一回执、结果发布与一次收集、工作区绑定、输出排空、
非零退出与超时、通知合并、activeRequest 捕获、Stop/Memory 辅助调用和关闭所有权。
自审将多路径重复组终止改为每条命令的一次性 stop，避免正常收尾/finally/close 重复按旧 PID 发信号；
修正后的 JAR 已重跑离线演示。并将注释具体写成批次预算与摘要尾部保护，不误称 Micro 也只看末条。

Non-blocking / 有意未做：后台只在内存，不可靠投递、不主动唤醒、不阻止提前 Stop，关闭丢弃未消费通知。
通知截断不支持追溯完整 stdout；业务产物应由命令显式保存。本地进程组清理不是权限沙箱，不保证逃离原组、
SIGKILL/断电，且依赖 Linux 系统工具。不动态验证缺 setsid/kill、资源耗尽、32 条上限并发压力或所有关闭竞态；
这些路径只有源码检查，不能据已有检查保证绝对无 Bug。没有修改旧 CommandPolicy 的规则覆盖能力。
未知 ContentBlock、Memory INVALID_RESPONSE、会话存储优化与其余章节均不在本次范围。

临时 BackgroundDemo 源码/class/运行目录报告前清理；保留 `tmp/s11-background-report` 的用户验收材料。
用户旧 s08/s09/s10 笔记与 other-agents-analysis 不改、不提交。
用户尚未完成本章源码 Review/答题或独立真机验收，因此 README 不勾选 s11 完成。

## 6. 本地提交计划（持续授权，非逐笔审批）

两笔前置文档检查点已完成：b3d887a 调整整章交付与本地提交工作流；c0da246 记录 s10 实景与重启验收。
本章一笔完整实现提交 `添加后台命令与请求前完成通知`，精确十二文件：

1. src/main/java/com/jadecode/tools/ShellCommand.java
2. src/main/java/com/jadecode/tools/BackgroundManager.java
3. src/main/java/com/jadecode/tools/BashTool.java
4. src/main/java/com/jadecode/agent/AgentLoop.java
5. src/main/java/com/jadecode/Main.java
6. src/main/java/com/jadecode/cli/RunCommand.java
7. src/main/java/com/jadecode/cli/ReplCommand.java
8. src/test/java/com/jadecode/MainTest.java
9. src/test/java/com/jadecode/tools/BashToolTest.java
10. README.md
11. docs/jobs/s11/background-tasks.md
12. docs/notes/s11/background-tasks-review.md

执行器、管理器、工具适配和循环/退出接线形成同一完整能力；已有断言适配与必要文档随实现保存。
不使用完整工作区之外的未提交源码支撑构建，不纳入旧答案、研究目录、tmp、生成物、忽略的 SOP 或机器配置。
精确 staged 核验后执行，hash 以 Git 记录为准；不 push，不改写历史。

## 7. 用户独立真机验收进度（实施已提交 cb37bf8）

- R1/R2：用户真实 REPL 先收到 bg_0001 启动回执，再 read_file 发布说明；下一次用户请求报告
  completed 通知并读取 report.txt。ChatGPT 只读复核实际文件为 services=3 / ready=2 / blocked=1；
  模型正确区分命令完成与整个版本可发布。正常启动、继续工具工作和跨用户轮次通知符合预期；
  终端日志本身没有逐次参数/时序快照，不单独以它证明精确并行时间。
- R3：bg_0002 故意 exit 7，后续用户输入触发通知，模型报告 failed、退出码 7 和原输出。
  该次没有新增工具 hook，累计工具结果数仍为 4，符合不重跑、不修复、不轮询要求。
- R4 尚未验收：首次指令的 shutdown-check.pid / shutdown-unexpected.txt 文件名含 shutdown，
  被 CommandPolicy.checkDenyList 的 command.contains 规则在 PreToolUse 阶段拒绝。
  没有 bg_id，不能记为后台清理失败或通过；这是验收材料与既有粗粒度规则不匹配。
  已修正忽略的 tmp/s11-background-report/RUNBOOK.md，使用 bg-exit-check.pid / bg-exit-unexpected.txt，
  不修改权限策略、不运行真实模型。等待用户按修正指令启动后台检查并退出 REPL。

两条已知旁支现象仍按原范围处理：未知 ContentBlock 待后续优化，Memory INVALID_RESPONSE 是辅助提取失败，
不能因此宣称 Memory 正常，也不把它误当成本章后台任务失败。用户源码理解与 R4 完成前不勾选整章验收。

## 8. 收尾结果（进入 s12 前同步）

R4 修正版产生 bg_0003，首次只读核对时 PID 181947 仍在 sleep，未出现意外输出；
用户随后退出并要求复查，PID 已不存在，距启动超过 90 秒仍无 bg-exit-unexpected.txt。
结合用户执行的退出操作，退出清理行为符合预期，R1–R4 独立真机验收通过。

用户在对话 Review 了占位 tool_result、通知合并与位置式压缩保护，正确概括了
“模型决定后台命令 → 管理器执行 → 内存暂存完成结果 → prepare 前收集并发送模型”的主流程；
已补充启动回执与只收已完成事件、不唤醒空闲循环的边界。用户明确宣布本章完成并要求进入 s12。
学习确认来自对话，不冒称 B01–B04 已在笔记逐题作答，不改写用户答案；可后补问题不阻塞推进。
之前“待学习/待 R4”是阶段历史，以本节最新结论为准。旁支优化与 s08/s09 验收不因本章收尾自动完成。
