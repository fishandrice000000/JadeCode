# s11：让慢工具等待与 Agent 决策分离

版本提示：本指南保留原 s11 实现与学习记录。当前后台等待语义和会话入口已在 s12 后续重构中调整，
阅读现有源码请以 [事件化运行指南](../s12/session-event-runtime-review.md) 为入口；下文题号与原文保留，不改写用户答案。

来源：本机课程 `s11_background_tasks/README.zh.md` 与 `code.py`。
工程任务单：[background-tasks.md](../../jobs/s11/background-tasks.md)。
本章按新流程完整实现后交接：工程检查范围与实际验证见任务单，用户理解和独立真机验收仍未代办。

## 1. 先看需求，不先钻线程细节

假设 Agent 要执行构建，同时核对发布说明。同步 bash 的结果只有等构建退出才返回，
所以整条工具分发停在这里；即使读文档完全不依赖构建结果，模型也没有机会决定下一步。

后台机制把“一次工具调用的回复”与“命令最终完成”拆成两个事件。
第一次只回答“已登记 bg_id”，这使工具交换闭合，循环可以继续；第二次通过通知告诉模型实际成败。
后台只改变何时拿结果，不改变业务依赖：必须依赖构建结果的发布动作，仍不能提前做。

完整主流程：

```text
模型：bash(command, run_in_background=true)
  → 主循环 PreToolUse：查表、权限、日志
  → BashTool：校验真实布尔值
  → BackgroundManager.start：登记 ID，启动工作者
  → tool_result：启动回执（原调用至此已闭合）
  → 主循环继续处理同批其他工具 / 下一次模型请求

后台工作者：执行 shell → 判断退出结果 → 完成事件入队
  → 后续正式请求前 collect
  → USER TextBlock：task_notification
  → compactor.prepare → 正式请求 → 模型据新结果决定下一步
```

模型负责选择独立慢命令，以及看到通知后如何推进；Harness 负责权限、执行、超时、事件投递和资源生命周期。
工作者不调用模型、不执行 hooks、不并发修改历史。此处的线程是 Java 执行线程，不是一个具有独立推理历史的子 Agent。

## 2. 课程原样与 JadeCode 的取舍

- 与课程一致：只给 bash 增参数，不通过 test/build 关键词猜测；启动回执加后续通知，不新增轮询工具。
- 与课程一致：通知不主动唤醒空闲循环。若模型已经结束本次 run，就等下一次用户输入；不是自动持续工作。
- 协议要求：原 tool_use 已有唯一 tool_result，完成通知必须是普通事件文本，不能再伪造第二个 tool_result。
- JadeCode 接线：先捕获 activeRequest 再附通知；将通知文本合并进最后的 USER，保留最新 tool_result，
  使“只检查末条的批次预算”和“只保护末尾两条交换的摘要路径”仍看得到新鲜结果。
  MicroCompactor 另按最后 assistant 位置判断已消费，不能把所有层笼统说成只检查末条。之后仍走现有上下文保护。
- JadeCode 范围：仅父代理可后台执行，子代理保持同步，避免子任务结束后通知无处交付。
- JadeCode 边界：限制 32 个未收集任务，单命令默认 120 秒；每条命令清理一次，CLI/JVM 正常关闭兜底。
  Linux 进程组不是沙箱，不管理自行另开 session 的程序，也不能处理 SIGKILL 或断电。
- 输出取舍：课程式有界摘要不是完整日志。shell 最多保留前 50,000 Java 字符，通知截取结果前 500 字符，
  截断会提示没有完整输出文件；可靠报告由命令显式写文件，再由读工具检查。同步工具沿用旧输出上限。

s10 Task System 保存业务进度与依赖，不自动执行；s11 bg_id 标识本 CLI 会话中的一次命令执行。
二者不自动对应。命令 exit 0 也只表示进程成功退出，不证明测试覆盖充分、产物正确或用户任务完成。

## 3. 推荐阅读顺序：先主干，再按需下钻

1. [Main.buildLoop / systemPrompt](../../../src/main/java/com/jadecode/Main.java)：同一管理器分别交给父 bash 与父循环，
   子 bash 不接通知队列。先看“谁拥有结果、谁负责结束会话”，不必重读全部历史 hooks。
2. [AgentLoop.run](../../../src/main/java/com/jadecode/agent/AgentLoop.java)：入口 activeRequest → while 顶部收通知 →
   自动整理 → 请求 → PreToolUse → 工具回执 → 批次闭合。理解为何通知只能由循环线程注入。
3. [BashTool.execute](../../../src/main/java/com/jadecode/tools/BashTool.java)：可选 boolean → 同步或启动后台。
   注意缺省同步、字符串 true 被拒绝、子代理不支持显式后台，以及回执并不等于进程完成。
4. [BackgroundManager.start / run / collect / close](../../../src/main/java/com/jadecode/tools/BackgroundManager.java)：
   登记 → 执行 → 入队 → 一次消费 → 退出取消。先忽略 XML 转义，抓住结果从工作者到模型的路径。
5. [ShellCommand.run / Running.stop / close](../../../src/main/java/com/jadecode/tools/ShellCommand.java)：
   这是支撑代码，按需看并发排空、超时、一次性进程组清理；无需背 Java 并发 API。
6. [ReplCommand.call](../../../src/main/java/com/jadecode/cli/ReplCommand.java) 与
   [RunCommand.call](../../../src/main/java/com/jadecode/cli/RunCommand.java)：一次用户轮次结束不等于 REPL 会话结束。
7. 最后按需看现有 BashToolTest、AgentLoopTest、MainTest、MainCompactionAssemblyTest 与 PermissionGateTest。
   没有新增 JUnit；后台闭环证据来自任务单记录的临时离线演示，不能冒称永久测试覆盖。

## 4. 架构场景问题（可讨论，不要求复述代码）

### B01：哪些等待可以移走，哪些依赖不能移走？

Agent 要启动构建、读发布说明、根据构建结果决定是否发布。哪些工作可以并行推进？
如果工具已经返回 bg_id，能否马上报告“构建通过”并部署？结合“启动回执”和“完成证据”解释。

回答：

### B02：后台命令结束了，模型为什么还可能不知道？

模型启动一条后台命令，立即给用户阶段性答复。五秒后命令退出，但 REPL 正等用户输入。
此时谁持有结果？什么动作才能让模型收到？这种设计与“Agent 自己持续运行到任务完成”相差哪层机制？
不要求实现下一章，也不用解释线程调度算法。

回答：

### B03：为什么不能给旧调用补一份 tool_result？

启动时已有一个 tool_result，完成时又拿到了真正输出。如果把后者作为同 ID 的 tool_result 追加，
会把“工具请求的回复”和“随后出现的事件”混成什么关系？为什么通知可以影响下一次决策，
却不能被当成人类新指令或取代 activeRequest？

回答：

### B04：可后补——什么叫后台工作仍属于当前会话？

用户启动后台检查后按 q 退出，或者命令只用 shell 的 & 脱离等待。为什么我们没有承诺退出后继续执行？
如果产品未来真要支持跨重启追踪和恢复，应补哪些职责，而不是只把虚拟线程换成普通线程？
顺便区分 bg_id、s10 的 task_id 和子代理 task 委派的用途。

回答：

## 5. 独立真机验收：发布检查报告

材料已经准备在 `tmp/s11-background-report/`（不进入 Git），先读其中 `RUNBOOK.md`。
在该目录运行 `../../jade repl`；配置沿用你现有安全方式，不需要给 ChatGPT 展示或复制凭据。
若当前目录缺少模型配置，先自行按既有方式提供配置，再启动。

先后台运行一个延迟生成报告的命令，同时读发布说明；等待后新发请求接收通知并读取报告；
再验证非零退出通知，最后启动长等待并退出 REPL 检查清理。
详细提示词、预期结果与不能夸大的结论都在 RUNBOOK 中。把真实输出给我后再记录独立验收状态。

本章没有实现通知主动唤醒、任务持久化、完整输出存储、子代理后台任务或跨平台进程管理。
