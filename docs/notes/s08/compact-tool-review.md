# s08 阅读与问答：模型主动请求压缩

## 目标与状态

理解 `CompactTool` 为什么是一个正式工具，却只返回请求信号；把“模型提出请求”与“harness 安全执行压缩”分开。

- 工程留档：[s08 任务单](../../jobs/s08/context-compact.md)，实现任务 `GPT-20261012-S08-COMPACT-001`。
- GPT 工程 Review：`GPT-20261012-S08-COMPACT-002`，Pass；已核对实际实现、注释、3 条薄壳测试、共享 schema 与当前循环的集成缺口。
- GPT 独立运行 `mvn package`：BUILD SUCCESS，235 条测试，失败/错误/跳过均为 0，其中本工具 3 条。`git diff --check` 与本轮文件空白检查通过。
- 当前工具没有注册进 Main，父子循环也没有处理压缩请求。下面涉及批次处理的场景是后续集成契约，不是已运行的功能。
- 学习状态：阅读指南与问题已提供，等待用户阅读作答；工程通过不代表用户已理解或整章完成。

## 推荐阅读顺序

1. [CompactTool](../../../src/main/java/com/jadecode/tools/CompactTool.java) 的类说明 → `execute`（当前 98 行）→ `OUTPUT`。先弄清这个工具实际做了什么，以及哪些工作刻意不放在这里。
2. 同类的 `name` / `description` / `properties` / `required` → [Tool.inputSchema](../../../src/main/java/com/jadecode/tools/Tool.java)。追踪模型如何看到并选择这个工具；`ToolSchema.objectSchema` 只需按需确认空参数的形状，不展开通用 JSON/集合语法。
3. [AgentLoop.run](../../../src/main/java/com/jadecode/agent/AgentLoop.java) 的工具执行段 → `results.add(...)` → 批次末尾的 `messages.add(Message.userBlocks(results))`。这里目前只有普通工具分发与结果回灌；用它理解后续压缩该接在哪个生命周期位置，别寻找尚未实现的压缩分支。
4. [CompactToolTest](../../../src/test/java/com/jadecode/tools/CompactToolTest.java) 可选查看：三个场景分别验证元数据、空参数 schema、固定输出与输入不变。工程测试已由 GPT 核对，无需逐条阅读断言。

只设 3 道 Agent 场景题，可分批回答。核心题为 Q01–Q02；Q03 用于串联已学的触发机制，允许后补。不要求写代码或画图，按数据流用自己的话说明即可。

## Q01：模型调用 compact，harness 实际收到什么？

假设后续已完成注册，模型在某阶段结束时返回 `tool_use(id=c1, name=compact, input={})`。

从模型看到的工具定义出发，说明 `description` 与空参数 schema 各自传递什么信息；再追踪 `execute` 的输出应如何成为对应 `c1` 的 `tool_result`。为什么这条成功结果不能被解释成“历史已经压缩”？工具没有接收历史或压缩范围参数，这体现了模型与 harness 怎样的职责划分？

回答：

1. `discription`表示调用此工具的意图是压缩上下文; 空参数 schema 表示调用此工具无需传入任何信息.
2. `execute`的输出在循环中在这里灌入结果``results.add(new ToolResultBlock(u.id(), output));``.
3. 调用此工具仅仅是向 LLM 传达"这批工具调用后压缩一下上下文"的信息, 尚未进行压缩. 等到当前轮次结束后, Harness 再调用压缩工具. 随后在下一轮次中, 将压缩后的历史发送给大模型作为新请求.
4. 职责划分是这样的: 模型负责给出方向, 确定压缩意图; 而 Harness 负责执行意图.

## Q02：同批还有文件工具时，什么时候才能压缩？

假设一条 assistant 消息按顺序包含三个调用：`write_file(id=w1)`、`compact(id=c1)`、`read_file(id=r1)`；文件写入成功，各调用均正常完成。

沿当前循环的执行与回灌顺序，描述 compact 执行到返回信号时，哪些动作已经发生、哪些结果还没有成为历史中的完整结果消息；再说明安全执行压缩之前，历史里应先具备什么完整交换。如果直接在 compact 的 `execute` 中裁剪历史，会给工具协议与已经发生的文件副作用记录带来什么风险？

补充一个权限分支：若 `PreToolUse` 拦截 compact，返回拒绝文本，未来循环应不应该把“出现过名为 compact 的调用”当作成功压缩请求？如何区分调用意图与真正成功的信号，同时保证被拦截调用仍有对应结果？不需要实现判断代码。

回答：

1. 写已经发生, 读尚未执行.
2. 在安全执行压缩之前,  所有的工具的结果都应该全部回灌到了历史中. 历史中不会存在孤立的 tool use.
3. 如果直接在 compact 的 `execute` 中裁剪历史, 那么可能会导致如刚才例子中的读这样在 compact tool 后的 tool result 不能正确与对应的 tool use 配对, 因为 tool use 已经被压缩了.
4. 不应该. 这里是一个初步的想法: 应该检查 tool result 中 compact 对应的结果, 检查结果是为期望的`Compaction requested after this tool batch.` 若是, 才判断调用成功. 而如果被 `PreToolUse` 拦截, 那么结果中的文字就不会是期望的文本, 也就不会被认为是调用成功,

## Q03：为什么不能在工具实例里记住“请求过压缩”？（可后补）

假设同一个 CompactTool 实例在会话 A 和会话 B 中复用：A 的一个批次成功执行了 compact，B 的下一批次完全没有调用 compact。

若在工具实例中保存一个不会自动清除的 `requested=true`，会怎样把 A 的状态带进 B？“本批请求过压缩”应由哪个层负责、作用范围应有多大？最后把这条模型主动请求链路，与 `prepare` 的请求前自动整理、`isContextOverflow → reactiveCompact` 的失败后补救区分开：它们的触发来源与执行时机分别是什么？只说明已学的机制，不展开尚未实现的重试代码。

回答：

1. 应由每个实例的Agent Loop 内负责, 而非由 CompactTool 负责. 且作用范围也应该只停留在那一轮中.
2. 三者辨析:
   1. 主动请求:
      1. 触发来源: tool calls
      2. 执行时机: 当前轮次结束后, 在循环中由 Harness 调用压缩工具.
   2. `prepare()`:
      1. 触发来源: 每轮发送请求前都进行
      2. 执行时机: 每轮发送请求前都进行
   3. `reactiveCompact()`:
      1. 触发来源: 当请求因上下文超限失败时触发
      2. 执行时机: `prepare()`并发送请求失败后.

## 暂停点

先阅读 CompactTool，再回答 Q01–Q02；Q03 可后补。回答完成后告诉 GPT 题号，批阅只在对话中进行，不改写笔记。
工具注册、批次请求标志、闭合后压缩、父子循环接入与真机验收仍待后续明确任务；当前不会自动推进或宣称整章完成。
