# s12 工程任务单：到时把 prompt 交给空闲 Agent

来源：本机 `../learn-claude-code/s12_cron_scheduler/README.zh.md`、`code.py`。
版本提示：本单保留原始 s12 交付与验证事实；当前会话入口已由 [事件化重构](session-event-refactor.md)替代，
涉及 CronRuntime / runScheduled 的描述按当时实现理解，不能替代当前源码。
状态：整章实现、构建、已有定向回归、离线闭环与工程自审 Pass。2026-10-16 补充用户真实定时交付记录、获准代跑的 CLI 重启恢复及事件化检查；见 [验收补查](terminal-and-acceptance.md)。下文保留原交付时的验证范围，不代表当前仍未做真机检查；用户源码学习与最终章节确认另记。

## 1. 需求与流程

s11 只改变已启动 shell 的执行方式；s12 保存未来要发起的任务与时间规则。
模型通过 schedule_cron / list_crons / cancel_cron 管理记录；计时线程只检查时间并持久化待交付状态，
不直接执行 shell 或调用模型。队列消费者等 Agent 空闲后串行发起 [Scheduled] 用户请求，
仍走现有工具权限、上下文、记忆与后台结果机制。

课程五段 cron：分钟 0–59、小时 0–23、日 1–31、月 1–12、星期 0–6（周日 0）。
支持 *、*/N、N、N-M、逗号列表；当日和星期都不是 * 时，沿课程采用 OR。
使用运行进程本地时区，不依赖模型记时、不承诺停机期间执行、不补跑错过的分钟。
一次性任务也按 cron 匹配，只是在模型成功接收后删除，不表示支持任意自然语言绝对时间解析。

## 2. 交付与失败契约

- durable=true 缺省，记录到工作区 .scheduled_tasks.json；false 只在内存。
- pending_delivery + last_fired 先安全发布，再入队；同分钟不重复排队，未交付任务合并后续触发。
- durable 恢复 pending 记录；坏文件明确报错，不忽略后覆盖。临时文件 + 原子替换，不承诺 fsync 或多进程互斥。
- 空闲后一次交付一项，避免多个定时目标被 activeRequest 的末条请求规则遗漏；人工回合不混入无关到期 prompt。
- 首次合法正式模型回复才确认“收到”，不是“业务成功”。一次性删除，周期清 pending。
- 首次回复前失败恢复历史快照并重新排队；压缩已经写出的文件不回滚，尚未确认的后台通知也放回，避免丢事件。
- 模型收到后写确认失败：保留 durable pending，本会话不立即重复发这项，告警；重启可能重送。
  全流程至少一次，不承诺 exactly-once，命令副作用仍需模型/业务处理幂等。
- JadeCode 对自动交付失败增加 5 秒退避，连续三次首回复前失败暂停本会话自动投递，避免高速无限付费请求；
  人工交互仍可用，待交付记录保留，重启可恢复 durable 项。
- 新 cron 线程不得与人工回合同时改历史；等待用户输入不占用回合锁。定时线程不读取审批输入，沿用非主线程拒绝。
- repl 才启动计时与自动交付线程；run 能保存/列举/取消定义，但退出后不会成为系统调度服务。
- 关闭中断调度、停止命令组，模型晚到回复不得继续派发工具；已发生的 IO 副作用不回滚。

## 3. 允许文件与非目标

新增 cron/CronExpression、CronJob、CronScheduler；tools/CronTools；agent/CronRuntime。
必要接线：Main、AgentLoop、ReplCommand、BackgroundManager；适配已有 MainTest / MainCompactionAssemblyTest 的表与 prompt。
README、此任务单、notes/s12 阅读指南和忽略的 tmp/s12-cron-check 真实场景。
为本章原子发布临时文件增加精确 .gitignore 规则，不忽略既有未知材料。
不改 provider、模型、依赖、权限策略、用户旧笔记、研究资料；不新增单元测试。
不做 s13、多 Agent、系统守护进程、日历全语法、自动自然语言解析、可靠作业平台或 s15 全异步事件总线。

## 4. 验证与交接

先 compile，再用可控 Clock + 临时存储 + 假 LLM 跑离线检查，不调用真实 API。
验证表达式、到期与去重、持久恢复、失败原子性、首回复确认、重排与通知保留、空闲唤醒、串行历史、取消与退出。
只运行已有的相关 Main / AgentLoop / PermissionGate / Bash 回归，最后 package。
工程自审复核数据流、生命周期、回灌协议、落盘顺序、安全边界与中文 JavaDoc/行间注释。
交付完整阅读指南与少量 Agent 架构问题；用户在 tmp 实景运行真实 CLI，不代办最终验收。
本地 commit 按独立完整意图直接执行；不 push、不改写历史、不纳入已有三份笔记和研究目录。

## 5. 实现结果与验证证据

已实现五段规则解析、三工具、immutable CronJob、调度状态/持久化、REPL 空闲交付与串行历史。
父工具表 15 → 18，子表仍 6；Main 绑定同一 CronScheduler 给工具和循环，REPL 显式创建 CronRuntime，
原 run 子命令不启用调度线程。构造不创建 .scheduled_tasks.json 或调用模型。
关闭保护阻止晚到正式回复继续派发工具/发起超限摘要；已开始的 IO 不能回滚。
后台通知只在首回复前失败时归还，不重跑已完成命令；原手动/自动压缩与 Memory 主流程保留。

实际验证（没有新增 JUnit，没有真实 API 调用）：

- 基础规则/记录/工具阶段 `mvn -q -DskipTests compile` 退出码 0。
  接线首次编译发现 CronRuntime 遗漏 checked LlmException 传播/捕获；修复时一次误放 catch 到 poll，
  编译再次指出该位置不抛此异常。随后准确放到 deliver，人工入口声明向 REPL 传播，最窄 compile 退出码 0。
- 首次 48 条定向回归退出码 1，2 failures / 21 errors，多项统一报运行期
  `Unresolved compilation problems: ToolExchangeProtocol ... Json ... CompactTool`。
  javap 证实当时 AgentLoop.runInternal 的 class 方法体是直接 new Error 后抛出，而非源码实现。
  保留首次纯文本失败报告到本轮临时验证目录，未把后续失败逐个当产品 Bug 修补。
  具体是谁写入异常 class 未证明，不据此断言某个 IDE/进程；清理构建产物后正常恢复。
- `mvn -q clean -Dtest=MainTest,MainCompactionAssemblyTest,AgentLoopTest,PermissionGateTest,BashToolTest test`：
  干净构建及最终工程修正后均退出码 0。最终 9/6/18/8/7，共 48 条，0 failure、0 error、0 skipped。
  仅适配已有 prompt / 工具表断言，没有新增方法、删除/禁用测试或绕过错误。
- `mvn -q -DskipTests package`：初版及最终修正后均退出码 0，重建 target/jadecode.jar，不冒称全量回归。
- 临时 CronDemo 使用真实线程、文件、进程和 Main 装配，只替换 LLM 与 Clock。
  javac 退出码 0；初版及最终 JAR 分别在全新临时子工作区执行，Java 退出码均为 0。
  后续补查只重跑存储/工具分支，不重复已通过的退避等待场景。
- 规则与状态：周五 09:00，表达式 `*/5 9 1 * 5` 在并非 1 日时仍匹配，验证日/星期 OR；
  同分钟反复 poll、取走与确认后都不重复；前进五分钟可再触发。
  非法分钟/星期、零步进、尾逗号、六字段拒绝；错误布尔拒绝，三工具真实创建/列举/取消及 wire 字段往返正确。
- 持久化：只恢复 durable 定义与 pending，session 项不恢复；取消不复活。
  状态文件改为目录时取消发布失败，内存候选不采用，恢复原文件后原记录仍可读；坏 JSON 原样保留、软链拒绝。
  确认保存失败保留 pending 和本进程 lease，不立即重复交付；恢复文件并重建调度器能取回同一 ID。
- 首回复边界：模拟第一次正式请求失败，历史恢复入口快照，后台完成通知在下一次尝试仍可见；
  成功时 onReceived 恰好一次且先于主任务工具副作用，最终工具交换校验通过。
- 生产接线：真实 Main + 假模型，在无人输入情况下自动两次正式请求，read_file 成功，
  `rm missing-s12-demo-file` 需要审批但自动线程被拒，未抢终端输入；一次性项确认后删除，辅助记忆返回空提取。
- 串行/失败：定时模型请求阻塞期间人工请求等待，两者模型调用最大并发数为 1；
  首回复连续失败每次至少退避 5 秒，三次后暂停，人工仍能请求；durable pending 重建后可再取。
- 关闭：模拟模型不响应中断，close 后才返回含工具调用的回复，实际工具执行次数为 0，pending 未误确认。
  不以此证明能撤销已发生的工具 IO 或强行中止任意第三方 HTTP 客户端。
- 真机 fixture 已置于 tmp/s12-cron-check，只准备 services.csv 与 RUNBOOK，不预写 scheduled-report.md、
  不创建真实定时定义、不替用户启动真实模型。输入只读核对为总数 3 / ready 2 / blocked 1。

## 6. 工程自审与边界

**Pass（同一实现者自审，非独立 Reviewer）**：实现后重新核对实际源码、Main/REPL/Loop/权限/通知调用方与 diff，
检查职责分离、schema 与原始 JSON 判型、时间匹配、原子发布、队列/lease/确认、首回复前后失败语义、
人工/定时串行、非主线程审批、关闭和晚到回复，以及面向初学者的中文 JavaDoc 与行间解释。
自审补充相对退避的 nanoTime 用法、坏状态文件的去重告警/暂停投递、合法最大记录的可读回大小上限，
以及退出期间不再发辅助摘要/提取的检查；最终 JAR 已重新验证。

Non-blocking / 未验证：没有真机模型验收；没动态注入每种原子移动失败、断电、全部清理失败或所有关闭竞态。
不保证多 CLI 并发写同一文件、恶意路径替换、完整 crontab 扩展、时钟回拨/夏令时下恰好一次，
也不提供进程退出后的定时服务/错过时点补跑。记录上限 100，prompt 上限 16000 Java 字符，规则上限 256 字符。
自动失败暂停是保留重送可能性的策略，不承诺停机/暂停时仍继续请求或业务最终一定成功。
定时输出可能与输入提示符交错，未扩展富终端 UI。原 s09 Memory INVALID_RESPONSE、未知 ContentBlock 与其它优化未处理。

本轮临时 Java 源码、class、失败报告、验证状态与软链结束前清理；保留供用户验收的 tmp/s12-cron-check。
用户既有 s08/s09/s10 笔记和研究目录原样保留，不纳入本轮提交。学习入口：
[cron-scheduler-review.md](../../notes/s12/cron-scheduler-review.md)，尚无用户答案，不冒称理解或整章验收通过。

## 7. Git 范围

s11 收尾独立提交 26f4eff，不混入 s12 实现。s12 采用一笔完整可构建提交
`添加定时任务与空闲回合交付`，精确十五文件：

1. .gitignore（仅本章原子发布临时文件规则）
2. README.md
3. docs/jobs/s12/cron-scheduler.md
4. docs/notes/s12/cron-scheduler-review.md
5. src/main/java/com/jadecode/cron/CronExpression.java
6. src/main/java/com/jadecode/cron/CronJob.java
7. src/main/java/com/jadecode/cron/CronScheduler.java
8. src/main/java/com/jadecode/tools/CronTools.java
9. src/main/java/com/jadecode/agent/CronRuntime.java
10. src/main/java/com/jadecode/agent/AgentLoop.java
11. src/main/java/com/jadecode/Main.java
12. src/main/java/com/jadecode/cli/ReplCommand.java
13. src/main/java/com/jadecode/tools/BackgroundManager.java
14. src/test/java/com/jadecode/MainTest.java
15. src/test/java/com/jadecode/MainCompactionAssemblyTest.java

这些文件共同完成从模型创建规则到空闲交付、确认/失败与退出的闭环；没有依赖未提交源码才能成立的半成品。
按当前持续本地提交授权直接执行，核对完整 staged diff、精确范围和空白后提交；不 push、不改历史。
