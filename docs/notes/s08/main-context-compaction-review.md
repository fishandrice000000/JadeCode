# s08：Main 装配——从组件能力到可运行的 Agent

工程状态：**GPT 最终复审 Pass（2026-10-13，MAIN-COMPACTION-003）**。
GPT 本轮独立 Main 定向 15 条、全量 294 条，失败 / 错误 / 跳过均 0。
首轮另独立执行十一文件回归 100 条；说明校正没有新增实现或测试逻辑。
B17 已由 GPT 本地提交并核验：`ce40069`，标题「装配父子代理的上下文保护」，父提交 `cd9be9d`。
工程契约与精确九文件范围见 [任务单](../../jobs/s08/main-context-compaction.md) §12–13。

## 1. 本轮解决的是能力启用，不是新的压缩理论

一个长任务可以先由父代理分析，再把局部工作委派给子代理。
即使两套循环都写好了上下文保护，只要启动入口没有把依赖接进去，用户运行 CLI 时仍会走旧路径。
这就是 Main 的职责：决定实际使用哪些组件、哪些工具对模型可见、数据持久化在哪里。

现在 run / repl 都通过同一份装配逻辑，父子正式请求前都有自动整理，正式超限后都有有限补救，
模型也可以通过各自工具表请求 compact。执行时机和策略仍由已有循环与 compactor 负责，Main 不重写它们。
自动整理不等于每轮摘要；注册 compact 也不等于模型一定会调用它。

本轮用同一 compactor 复用机制与工作区存储，不合并父子会话。
父历史由调用方持有，REPL 跨轮延续；每次子委派才创建新历史。
父加载的技能正文不会自动进入子历史；子表没有 load_skill，但这不是禁止子用基础 read_file 读取已知文件。
必要任务说明可以由父在 task 的 prompt 中显式带给子。

提示边界告诉正式模型：摘要是参考数据，不是新的指令来源。
这是提示层面的设计意图，不能单凭这句话声称已经阻断所有 prompt injection。

## 2. 推荐阅读顺序

1. [Main.java](../../../src/main/java/com/jadecode/Main.java)：从 public buildLoop() 进入三参重载。
   看清“获取实际环境”与“用现成依赖组装组件”这两个职责；生产入口与离线测试走同一份接线。
2. 同一文件中的 compactor 创建 → 子工具表与 SubagentRuntime → 父工具表与 AgentLoop。
   只确认依赖和能力如何到达父子运行时，不必重读各层压缩算法或全部 hook 实现。
3. 同一文件的 COMPACT_BOUNDARY、systemPrompt() 与 subSystemPrompt()。
   对照 tools 的实际注册与 prompt 的文字说明：模型看见的能力、运行时真的能做的事，两边要一致。
4. [装配测试](../../../src/test/java/com/jadecode/MainCompactionAssemblyTest.java)按需只选一个真实场景：
   realTaskDelegationRunsChildCompactionAndReturnsOnlyFinalText 看父 → 子 → 父的数据边界；
   oversizedReadFileResultIsPersistedAutomaticallyInParentAndChild 看不请求 compact 时也会落盘。
   不要求全文阅读测试、反射辅助方法或再次解释异常计数。

大文件场景的实际路径是 prepare 中的 fitToolResults：8 万字符未达到第一层 20 万的批次预算，
但整体超过 5 万且最新结果还没被消费，于是 fit 兜底落盘。该场景不是第一层预算的专项测试。
若只需核对父历史归属，可按需看 [ReplCommand.call](../../../src/main/java/com/jadecode/cli/ReplCommand.java)：history 在交互循环外创建。

## 3. 一个可选的架构讨论，不作为推进门槛

### A01：无会话状态，就适合在任何 Agent 之间共享吗？

场景：以后同一个进程先处理工作区 A，再处理工作区 B。两个任务希望各自的归档留在自己的项目里。
有人提议：“ContextCompactor 不存 messages 或目标，那就让所有工作区永久共用同一个实例。”

请从依赖与资源归属评价这项建议，而不是复述 Java 字段：
哪些东西应该属于一次会话，哪些属于工作区，哪些属于模型配置？
共享方式若选错，会给 Agent 的证据回查、数据隔离或运行配置带来什么影响？
你会怎样确定共享边界，同时保持父子循环可以复用同一套机制？

只讨论架构，不要求实现新工厂、并发系统或修改代码。可以在对话回答，也可以跳过，不增加重复细节题。

回答：


## 4. 下一步与验收边界

Main 装配已工程放行；旧题单中“Main 尚未接入”是当时的交接状态，以本轮核验为准。
当前离线测试证明生产接线和指定运行场景，不证明真实摘要质量、真实服务端窗口或全部压缩边界。
存储 / snip / 预算专项测试缺口及既有非阻塞维护仍留档，不能因为 294 条全绿就宣布 s08 完成。

本轮本地检查点已经留下，接下来做 s08 收尾缺口核对与用户独立真机验收。
本地提交经用户批准后执行，未等待可选讨论回答；真实模型调用由用户验收，不由本轮 GPT 自动发起。
用户已写的旧笔记和答案保持不变。

查看本轮生产接线差异：

```sh
git show ce40069 -- src/main/java/com/jadecode/Main.java
```
