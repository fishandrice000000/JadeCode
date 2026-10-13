# s08：子会话压缩集成——必要阅读入口

工程状态：**GPT 复审 Pass（2026-10-13，SUBAGENT-COMPACTION-003）**。
独立验证：19 条新测试、85 条相关回归、全量 287 条，失败 / 错误 / 跳过均 0。
B16 本地小提交已获批，待 worker 执行；比较基点为 `5e70fd2`。
工程契约与提交范围见 [任务单](../../jobs/s08/subagent-context-compaction.md)。

## 1. 这次增加什么，而不是再学一遍压缩

父代理委派一项工作后，子代理有自己的目标与 messages。
新的历史只降低起步负担，不会阻止它读文件、跑命令后逐渐增长。
本轮给这段独立运行也接上已有压缩能力，没有新增第五层算法。

共享 compactor 表示复用同一套整理规则和存储能力，不等于共享模型看见的会话。
子目标用父代理下发的 prompt；父用户的完整对话不会自动进入子会话。
恢复发生在同一次委派内，既不能冒充子任务已失败退出，也不应计作一次成功行动。
最后父代理仍只收到子代理的终态文本，不收到其全部中间历史。

这是 JadeCode 对 s06 子代理与 s08 压缩的组合，不是原课程 s08 新增的一套独立理论。

## 2. 推荐只看这些入口

1. [SubagentRuntime.java](../../../src/main/java/com/jadecode/subagent/SubagentRuntime.java)：
   类头“共享什么、隔离什么”与 run 的入口状态，确认子目标和历史从哪里来。
2. 同一 run 的三条接入：请求前 prepare、正式请求的恢复分流、批次闭合后的 compactAfterBatch。
   看它们的位置即可；压缩策略本体已在前面学过，不必再次逐个阅读。
3. run 的外层收尾与 DONE / STOPPED 分支：理解恢复过程还属于同一次委派。
   细碎边界已由工程 Review 和测试核对，不要求逐行复述。
4. [集成测试](../../../src/test/java/com/jadecode/subagent/SubagentRuntimeCompactionTest.java)按需查证，
   不要求全文阅读一千余行。优先选 mixedCompactBatchKeepsLatestExchangeAndRunsHooksInOrder
   和 budgetResetsForTheNextRequestWithinTheSameRun，观察模型下一次收到什么。

## 3. 一个可选的架构讨论，不作为推进门槛

### A01：子代理的结论，怎样成为父代理可信的工作依据

场景：子代理经过多次工具调用与压缩，最终只告诉父代理“修复完成，测试通过”。
父代理没有读到完整中间历史。
你认为这条返回边界有什么好处、丢失了哪些复核能力？
如果父代理需要核验结论，你希望报告包含哪些可回查证据？
这类信息应当如何进入父代理后续判断，而不是被当作无需检查的新指令？

不要求设计新协议或修改代码；可以只在对话讨论，也可以不作答。
本题不考重试计数、异常类或 Java 语法，不要求回答才继续 Main。

回答：


## 4. 交付边界

Main 尚未装配压缩器，因此测试中的显式注入有效，不代表当前 CLI 已启用。
下一项是实际装配共享 compactor、两张工具表的 compact 和摘要参考数据的提示边界。
真实运行、摘要质量、剩余专项测试与整章验收仍未完成。
辅助摘要失败后保留归档的用例尚未独立比对完整 JSON，列为非阻塞维护；
失败发生在归档之后，不能说该归档不存在。旧用户笔记与答案均不修改。

尚未提交时可查看生产文件差异：

```sh
git diff 5e70fd2 -- src/main/java/com/jadecode/subagent/SubagentRuntime.java src/main/java/com/jadecode/context/ContextCompactor.java
```

新测试 / 本单尚未跟踪，直接打开阅读；普通 git diff 不显示未跟踪文件。
工程 Pass 不等于用户理解已被验证，也不等于 s08 完成。
