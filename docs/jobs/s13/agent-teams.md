# s13：独立会话的 Agent Team

## 目标与已确认契约

Leader 与最多三个常驻队友使用相同的 SessionRuntime，各有历史、Inbox、后台管理器和压缩流程。
只有 Leader 创建队友、维护任务 DAG、替队友认领并派工；队友不互相通信，不自动找活。
共享工作目录，不增加沙箱、worktree 或跨进程会话恢复。保留一次性 task 子代理。

通信工具接收模型提供的短摘要与长正文，正文保存到工作区邮箱，摘要和程序生成的路径交给接收方。
每次派工有独立 assignment ID。接收方 Session 决定何时消费，发送者不改接收方历史。
工具批次完整回填后才能插入外部消息；结束前再次检查插入式消息。

## 消息与后台

- Java 使用 allowInsertion / wakeWhenIdle；工具 JSON 使用 allow_insertion / wake_when_idle。
- 新派工 false/true；任务补充、结果和问题 true/true；进展 true/false；空闲状态 false/false。
- 用户忙时输入合并成同一缓冲批次，空输入 Enter 升级原批次为插入式，不重复提交。
- 后台 wait_for_completion 默认 true；另两个字段默认 false。
- 必须等待的结果在大轮次准备结束时允许交付，不受“不提前插入”限制；等待不等于 IDLE。
- Inbox 不是只取队首的严格 FIFO：结束/等待时扫描本轮必等结果，前方普通新任务不阻塞它。
  只清除实际选中的消息，未选中的普通请求继续排队；后台记录在模型确认后才删除。
- 队友启用压缩与后台，不启用 Memory/cron。初始化不复制 Leader 历史。

## 任务、失败与审批

- 提交结果工具同时保存报告、更新状态并通知 Leader；只有 completed 满足依赖。
- 新增 failed / blocked，失败不自动重做业务；Leader 可显式恢复为 pending 再派工。
- 派工失败且未开始时撤销认领；部分提交保留材料、明确报错，可重试提交。
- Inbox 满或接收方关闭时明确失败；旧 assignment 的报告不能更新新 assignment 状态。
- 关闭队友时未完成的派工记 blocked；关闭整个 Leader 会话会关闭所有队友。
- 邮件正文不自动清理；成功交给 Inbox 后只清理 pending 投递记录。
- 原有硬禁止保留；独立辅助模型可审小范围可恢复操作，超权限交给用户，错误/不确定不自动批准。
- 用户审批排队，必须明确 y/n；空 Enter 不再拒绝审批。

## 实现检查点

1. 统一 Inbox 消费、用户缓冲、后台参数和标签。
2. 文件邮箱、任务状态、Team 工具、独立 Session 装配。
3. 辅助审批、生命周期与重复/部分失败审查。
4. 构建、已有相关测试、临时离线脚本检查、源码阅读指南。

不新增单元测试、不发真实模型请求、不吸收用户原有改动。每个本地提交必须可构建。

## 当前状态

设计：已确认。实现：已接通。工程自审与验证结果见下文。
用户理解与独立真机验收：待完成；不能据此标记整章验收通过。

## 工程自审与验证记录

工程自审结论：Pass（同一实现者自审，不冒称独立 Review）。检查了实际装配、消息消费、
工具交换配对、状态转换、派工编号、部分提交重试、关闭与审批来源。

本轮自审修正：

- 后台完成事件也通过 Inbox，不保留旧的运行时直接轮询交付路径。
- 在 Stop/记忆维护后再次核对待插入消息，结束判定与入队使用同一把锁。
- 先展示刚生成的回复，再展示新通知；不覆盖历史回答。
- 即使关闭元数据写盘失败，也先停队友运行时，不因记录失败继续启动工具。
- 重试投递保留原始权限来源，不把旧定时请求升级为用户请求。
- 辅助审批只接受受控单文件删除，保存恢复副本；复杂命令、隐藏路径和外部路径交给用户。

验证命令：

```sh
mvn -q -Dtest=MainTest,MainCompactionAssemblyTest,BashToolTest,SubagentToolTest,SubagentRuntimeTest,AgentExecutorTest,HooksTest package
```

61 条已有定向测试，Failures/Errors/Skipped 均为 0，package 成功。
仅维护两个已有测试中的提示词/工具表预期，没有新增测试方法或测试文件。

临时 Java 离线场景使用假 LlmClient、真实工厂和临时工作区，不读真实配置、不连接网络。
覆盖生产派工/报告/唤起、角色工具表、公共规则与历史隔离、一次性子代理返回、正文保留、
用户缓冲与升级、后台默认等待及空闲唤起、报告重复提交、旧派工拒绝、投递失败撤销认领、
关闭记 blocked、满 Inbox 部分提交重试、三个队友上限、顺序派工、独立审批与 y/n 排队。
临时检查材料在交付前清理，不作为永久测试提交。

静态检查只覆盖本轮文件；用户已有旧笔记的空白错误与删除项保持原样。

## 提交范围

一笔完整本地提交「接通常驻队友与会话消息协作」。运行时、工具与装配相互依赖，作为同一能力提交：

- `.gitignore`、`README.md`
- `src/main/java/com/jadecode/bootstrap/JadeCodeFactory.java`
- `src/main/java/com/jadecode/cli/CliHooks.java`
- `src/main/java/com/jadecode/cli/ConsoleInput.java`
- `src/main/java/com/jadecode/cli/ReplCommand.java`
- `src/main/java/com/jadecode/hooks/Hooks.java`
- `src/main/java/com/jadecode/process/BackgroundManager.java`
- `src/main/java/com/jadecode/safety/PermissionGate.java`
- `src/main/java/com/jadecode/safety/DelegatedApproval.java`
- `src/main/java/com/jadecode/session/SessionEvent.java`
- `src/main/java/com/jadecode/session/SessionInbox.java`
- `src/main/java/com/jadecode/session/SessionRuntime.java`
- `src/main/java/com/jadecode/tasks/Task.java`
- `src/main/java/com/jadecode/tasks/TaskStore.java`
- `src/main/java/com/jadecode/team/TeamMailbox.java`
- `src/main/java/com/jadecode/team/TeamManager.java`
- `src/main/java/com/jadecode/tools/BashTool.java`
- `src/main/java/com/jadecode/tools/TaskTools.java`
- `src/main/java/com/jadecode/tools/TeamTools.java`
- `src/main/java/com/jadecode/util/Ansi.java`
- `src/test/java/com/jadecode/MainTest.java`
- `src/test/java/com/jadecode/MainCompactionAssemblyTest.java`
- `docs/jobs/s13/agent-teams.md`
- `docs/notes/s13/agent-teams-review.md`

排除所有用户既有笔记、删除项、docs/reviews 与运行生成物。不 push，不改历史。
提交后的实际 hash 在对话汇报，任务单随提交本身留档。

## 已知限制

- 没有沙箱、worktree、跨进程 Inbox/历史恢复，不保证任意模型行为正确。
- 辅助审批当前保守地只自动审已保存恢复副本的小型非隐藏文件删除；其它需审批操作升级用户。
- 原有压缩/转存目录布局不在本章优化；后台输出仍只保留现有长度预算。
- `run` 随 Leader 本轮返回关闭整个会话，尚未完成的队友也会关闭；异步协作应使用 REPL。
- 没有真实模型与交互终端的独立验收；61 条定向测试不是全量回归或绝对无 Bug 保证。
