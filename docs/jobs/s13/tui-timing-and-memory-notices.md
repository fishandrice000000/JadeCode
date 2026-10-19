# TUI 时长与记忆通知

## 目标和契约

- 工具标题将 49670ms 显示为 49.7s，依次支持 ms/s/m/h/d，不增加年等更大单位。
- 隐藏普通工具结束状态 RETURNED，保留 RUNNING、BLOCKED、ERROR 等有用状态；不把返回当成业务成功。
- 最终 Reply 显示 DONE 和本次大轮次总耗时：从消费者开始处理到该轮结束，包含记忆、模型、工具和必等后台，
  不包含此前的 Inbox 排队时间。阶段回答生成完不等于任务结束，不提前标注完整大轮次耗时。
- 耗时由执行线程的单调时钟计算，TUI 不根据第一段文本到达时间猜等待或思考耗时。
- 普通记忆新增/跳过/整理通知不再标 Warning；提取失败、召回降级、IO 故障仍告警。
- 耗时只作显示，不改变模型消息、工具结果、权限、持久任务或日志恢复，不为旧 HISTORICAL 卡伪造时间。

## 范围与验证

复用 execution 的只读通知及 cli.tui 显示状态；MemoryManager 接收正常/告警两个输出函数，
生产装配绑定已有带 Agent 身份的通道，避免用 System.err 代表所有记忆消息。
保留旧构造器；普通 run 的默认正常通知走 stdout、告警走 stderr。

只做本轮必要接线与说明，不改记忆筛选/存储策略，不新增永久单元测试，不调用真实模型或读取凭据。
采用构建、已有窄范围测试、临时假模型和显示检查；用户负责实际终端观感。
不处理原有笔记、文档删除和 docs/reviews/；验证和自审通过后精确本地提交，不 push。

## 本地提交计划

1. `完善工具与整轮回复的耗时显示`：AgentExecutor、SubagentRuntime、SessionRuntime、ExecutionNotice，
   以及 TuiState、TuiController、HistoryViewMapper、ConversationView、README、本任务单和阅读指南。
   计时数据与对应显示必须一起提交；不包含记忆输出通道的改动。
2. `区分正常记忆通知与维护告警`：MemoryManager、JadeCodeFactory，及 README、本任务单、阅读指南的对应说明。
   这是独立的通知分类修复，不改变记忆是否保存的判断。

两笔均只本地提交，不 push、不改历史。开始时 staged 为空；原有笔记修改、三项文档删除和 docs/reviews/ 均排除。

## 阶段状态与验证

- 计时实现、自审：Pass。工具从各自开始处理时计时，包含自身审批，不包含前序工具排队；
  整轮使用运行编号找回最终 Reply，阶段回答、失败回复与历史恢复不伪造整轮耗时。
- 已有定向回归：AgentExecutorTest 18、AgentExecutorManualCompactionTest 12、MainCompactionAssemblyTest 6、
  SubagentRuntimeTest 11，共 47 条，失败/错误/跳过均为 0。
- `mvn -q -DskipTests package`：完整工作区与第一笔提交的独立暂存快照均通过；
  独立快照不包含第二笔记忆通知修复，证明计时提交可以单独构建。
- 临时离线程序验证了毫秒至天的格式、隐藏 RETURNED/保留 BLOCKED、跨轮次防串卡、失败不补耗时，
  以及流式/非流式的真实 SessionRuntime 流程。工具执行与记忆维护均计入最终回复总用时。
- 记忆通知分类实现、自审：Pass。MemoryManager 原先将普通成功也写入 System.err，
  RoutingPrintStream 按 stderr 统一显示 System / Warning，导致正常结果看起来像失败。
  现在由装配注入正常/告警通道，保留真实 Agent 身份；不修改记忆提取与保存契约。
- 临时离线检查共 28 项通过，包括正常新增、同名跳过均走普通通知、非法提取回复仍告警、
  经过 ExecutionUiBridge 后标签分别为 Memory / Warning。前述真实循环检查同时覆盖流式/非流式。
- 计时已本地提交 `f3f7306`；第二笔仅含通知分类及对应说明。没有吸收原有工作区编辑。
- 不新增永久测试、不调用真实模型。实际终端的观感由用户验收，不把离线检查当作真机验收。

## 后续调整：简化记忆通知

按用户要求，跳过候选的普通通知只保留 `未新增记忆，跳过候选 N`，删去括号中的推测原因。
不改保存判断与跳过数量；临时假模型返回 current_task 候选，已核对输出为精确的简短文案。
这一处按 MemoryManager + 本任务单单独本地提交；工具审批计时另作一笔，不纳入原有工作区改动。
