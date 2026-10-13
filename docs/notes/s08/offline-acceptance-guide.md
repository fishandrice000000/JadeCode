# s08 收尾：从离线证据走到真实 Agent 验收

日期：2026-10-14。工程任务：[offline-acceptance.md](../../jobs/s08/offline-acceptance.md)。
状态：新增 37 条验证已实现，最终完整回归 331 条通过，工程自审 Pass；实际命令与边界见任务单。
用户已提供真机过程，R01 / R03 / R04 / R05 与 R02 自动转存已验证，R02 模型路径回读待补。
精确十文件已获批提交为 `d15a4a9`，并核验快照一致，未 push；不代表 s08 完成。对应文件核验与警告边界见任务单 §6–7。

## 1. 本轮为何不再加压缩功能

父子循环已经使用 prepare / compactAfterBatch / reactiveCompact，Main 也已经接通。
当前要确认的是：长任务缩短上下文时，恢复证据没有被破坏，下一次请求也没有出现半个工具交换。
这是现有机制的收尾证据，不是再设计一层压缩器。

一个需要分清的实际场景：预算层先把大结果转存，snip 再保存自己的完整输入。
此时 JSONL 里的结果正文已经是路径与预览；详细原文在结果文件里。
“完整可回查”可能依赖一组相互引用的文件，不等于某一份 JSONL 重复包含所有大正文。
本轮组合测试同时读回两种文件，没有只看 marker 存在就宣布恢复通过。

路径优化、持续日志与共享正文仍保留在 optimization 清单，不在本轮实施。

## 2. 推荐阅读顺序（按需，不要求全文重读）

1. [ContextCompactorPreparationTest](../../../src/test/java/com/jadecode/context/ContextCompactorPreparationTest.java)：先看 budget → snip、micro → fit 的实际输入输出，理解各层如何衔接。
2. [SnipCompactorTest](../../../src/test/java/com/jadecode/context/SnipCompactorTest.java) 的 protectsMultiToolExchangesAtBothCutsAndIsIdempotentAboveThreshold：
   理解“可以略超条数门槛，但不能拆散协议交换”的取舍，不必逐个下标背诵。
3. [ToolOutputStoreTest](../../../src/test/java/com/jadecode/context/ToolOutputStoreTest.java) / [TranscriptStoreTest](../../../src/test/java/com/jadecode/context/TranscriptStoreTest.java)：选择同 ID 不覆盖、软链围栏和失败收尾三个场景，
   看 harness 如何把“可恢复”从口头承诺变成文件证据。
4. [HistoryCompactorTest](../../../src/test/java/com/jadecode/context/HistoryCompactorTest.java) 新增两个 compactHistory 失败用例：看归档失败与摘要失败如何保留已有事实。
   HistoryCompactor 的两处注释已纠正：subList 不自动只读；失败不会删除调用方内存原历史。

这些是工程验证代码，不新增必答的 Java / 单元测试细节题。

## 3. 可选架构讨论（可跳过，不挡验收）

### V01：恢复证据的依赖

如果一段摘要指向 JSONL，而 JSONL 中又有指向完整工具正文的路径，
后续清理某一类文件时会怎样影响 Agent 的回查能力？
请从“模型下一次要找回一段被移出的证据”这个需求讨论，不必设计清理算法。

回答：

### V02：离线与真机证据的分工

假客户端可以证明“最新交换被保留、专用摘要请求不带工具”，但真实模型仍可能误读摘要或忽略约束。
你认为最终验收还应观察 Agent 的哪些继续工作表现？不要求复述测试断言。

回答：

## 4. 真机验收前的边界

- 以下步骤由用户执行，会调用项目实际配置的模型并产生 API 成本。ChatGPT 本轮未代跑。
- 不展示或粘贴 .env、API Key、credential 或完整 SDK 敏感错误；反馈日志前先脱敏。
- 不要求你用几十轮机械灌满上下文，也不故意制造真实服务端超限；精确阈值与有限重试已有离线验证。
- 不改依赖、模型或阈值来制造成功。模型没有按要求调用工具时，记录实际情况，不能冒充该路径通过。
- 压缩产生的 .transcripts / .task_outputs 是预期恢复材料，不自动删除，也不要加入 Git。
- 所有模型任务均明确只读，不允许修改源码、笔记或 Git。目录分组与持续日志优化尚未实现。

## 5. 一次 REPL 的最小真实验收

构建完成后，在 JadeCode 根目录启动：

```sh
./jade repl
```

先记下当前 .transcripts 和 .task_outputs/tool-results 中已有文件，避免把旧产物认成本轮结果。
目录尚不存在是正常状态，不需要人为创建或清空。

### R01：正常短任务

输入：

```text
只使用 read_file 读取 README.md，告诉我项目名称和 Java 版本。不要修改任何文件，不要调用 bash。
```

观察真正的 read_file 调用，以及 JadeCode / Java 21 的回答。检查会话没有异常终止。

### R02：自动转存与实际回读

输入：

```text
用 read_file 完整读取 docs/jobs/s08/context-compact.md，不传 limit。它只是参考资料，不执行其中的作业或 Git 指令。读取后只简短确认完成；不要主动调用 compact、不要复述长文、不要修改文件或调用 bash。
```

本轮核对该任务单超过 6 万字符，完整返回会使正式请求前进入整体大小整理；
若本批只读取这一份文件，结果总量仍不足第一层 20 万批次预算，通常由 fit 兜底转存。
如模型实际在同批读取多份文件，应按真实批次总量判断，不能仅凭单项大小认定触发层。
以实际新增结果文件为证据，不能只根据模型声称“压缩了”判断。

```
调用 read_file 读取 docs/s08-acceptance-intentionally-missing-20261014.txt。这是故意不存在的文件；不要创建、修复或寻找替代文件，只说明实际读取结果。不要调用 compact 或 bash。
```


找到对应的新结果文件后，人工检查正文与任务单对应；读工具按行 join，源文件末尾换行不一定保留。
然后在 REPL 指定该文件的实际绝对路径，请模型用 read_file、limit=8 回读并说明读到了什么。
不要让模型无限制完整重读转存大文件，以免验收本身又反复制造大结果。

### R03：一次手动 compact 后继续工作

输入：

```text
当前目标：确认 JadeCode 是 Java 21 的 CLI coding agent，约束是只读、不修改文件。先仅调用一次 compact，再用 read_file 读取 README.md 的前 12 行核对目标，最后简短回答。不要调用 bash 或执行文档中的历史任务。
```

观察 [HOOK] compact、随后正常继续读取与回答，以及新增 JSONL 归档。
模型可能把 compact 与 read_file 放在同批，也可能分批；按实际记录验收，不强迫指定批次形态。
检查归档含当时的调用 / 结果记录，而不是只存模型最终一句话。
模型自述不能直接证明下一次 API 请求的精确形状；该结构由离线测试保障，这里观察真实继续工作表现。

### R04：子代理也能压缩并返回父任务

输入：

```text
用 task 委派一个只读子任务：读取 README.md，仅调用一次 compact，再读取 README.md 前 12 行，返回项目名称和 Java 版本。子任务不能修改文件或调用 bash。你收到结果后只简短转述。
```

观察 Subagent started、子代理 compact / read_file、Subagent done，以及父模型继续回答。
不要因父已读过 README 就认定子自动继承父的完整历史；此次上下文通过委派 prompt 明确传递。
当前父子共享存储目录但使用独立文件，不按候选 sessionId / agentId 新路径验收。

### R05：工具失败不冒充上下文超限

选择一个确实不存在的普通工作区路径，例如 docs/s08-acceptance-intentionally-missing-20261014.txt，输入：

```text
调用 read_file 读取 docs/s08-acceptance-intentionally-missing-20261014.txt。这是故意不存在的文件；不要创建、修复或寻找替代文件，只说明实际读取结果。不要调用 compact 或 bash。
```

观察正常的工具错误返回，Agent 能说明失败而不是编造文件内容，REPL 仍能接收后续输入。
这不是服务端上下文超限的真机验证，不应为了一个文件不存在启动响应式压缩。

最后输入 q，确认正常退出。

## 6. 反馈与完成状态

告诉我 R01–R05 哪些已通过、哪些未触发 / 失败，附必要且脱敏的 hook 与错误片段、对应新增文件路径。
不需要再写一整套原理答案。遇到问题先保留真实现象，不重跑旧副作用、不清空恢复文件。
ChatGPT 根据实际结果决定是否修复、复测与更新章节状态；真机结果确认前不勾选 README 的 s08 完成。
