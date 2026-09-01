# s01-8 作业:AgentLoop(主循环)

## 背景

课程对齐:`s01_agent_loop/code.py` 第 86–117 行 `agent_loop`。整个 agent 的秘密就是这个模式:

```text
while True:
    response = LLM(messages, tools)
    if response 里没有 tool_use:
        break
    执行工具
    追加结果
```

从本章起,之前各章的全部成果首次串联:s01-2 的消息模型、s01-4 的 LlmClient 契约、s01-5 的 Tool 契约、s01-6 的 BashTool、s01-7 的 SDK 适配层,全部被这个几十行的循环**装配起来**。写完本章,你的 agent 就"活着"了。

与课程的两处差异(刻意):

1. **分发表取代写死的 bash**:课程里 `agent_loop` 直接调 `run_bash`;Java 版用 `Map<String, Tool>` 分发表——s02 起每章新增工具时**循环一行不用改**,只需往表里注册。
2. **循环内不打印**:课程在循环里 `print("$ command")` 给用户反馈;Java 版把打印留给 s01-9 的 CLI 层,循环保持纯逻辑(职责分离)。

## 文件清单

```text
src/main/java/com/jadecode/agent/AgentLoop.java         ← 你写
src/test/java/com/jadecode/llm/ScriptedLlmClient.java    ← Claude 写(测试基础设施)
src/test/java/com/jadecode/llm/RecordingLlmClient.java   ← Claude 写(测试基础设施)
src/test/java/com/jadecode/agent/AgentLoopTest.java      ← Claude 写(主类完成后)
```

## AgentLoop.java 规格

```java
package com.jadecode.agent;

public class AgentLoop {
    private final LlmClient client;
    private final Map<String, Tool> tools;   // 分发表:tool_use 的 name → 工具实例
    private final String system;
    private final int maxTokens;

    public AgentLoop(LlmClient client, Map<String, Tool> tools, String system, int maxTokens) { ... }

    /** 在传入的 messages 上原地续跑,直到模型不再调工具为止 */
    public void run(List<Message> messages) throws LlmException {
        while (true) {
            // 1. 组装 CompleteRequest 并调用
            //    tools 列表:List.copyOf(tools.values())
            // 2. 追加 assistant 消息:messages.add(response.message())
            // 3. 从 response.message().content() 收集 ToolUseBlock
            //    (instanceof 模式或 isText 判断皆可)
            // 4. 没有 tool_use → return(模型决定结束了)
            // 5. 逐个执行:tools.get(u.name())
            //    查不到 → "Error: Unknown tool: " + u.name()
            //    input 转 ObjectNode(见坑 5)
            // 6. 全部结果合成一条 user 消息:messages.add(Message.userBlocks(results))
            //    一个 ToolResultBlock(u.id(), output) 对应一个 ToolUseBlock
        }
    }
}
```

### 行为表

| 情形 | 行为 |
| --- | --- |
| 模型回复纯文本 | 追加 assistant 消息后 `return`,历史以 assistant 文本结尾 |
| 模型回复含 tool_use | 逐个执行,全部结果作为**一条** user 消息回灌,继续循环 |
| tool_use 的 name 不在分发表 | 不崩,回灌 `"Error: Unknown tool: " + name`,让模型自己纠错 |
| input 不是 JSON 对象 | 用空 ObjectNode 代替(防御,见坑 5) |
| client.complete 抛 LlmException | 向外传播(`run` 声明 `throws LlmException`),s01-9 CLI 层处理 |

## 六个坑

1. **回灌形状:一条 user 消息装全部 tool_result。** 多个结果合成一个 `List<ContentBlock>`,用 `Message.userBlocks(...)` 包成**一条** user 消息——不是一个结果一条消息。这是 Anthropic 协议要求,也是课程行为。
2. **`tool_use_id` 必须逐块对应**:`new ToolResultBlock(u.id(), output)`——第一个参数取**对应那个** tool_use 块的 id。忘填或填错,API 会拒绝请求(tool_result 必须应答某个 tool_use)。
3. **串行执行**:for 循环逐个 execute,不要 stream/并发。课程如此;并发执行留到 s15。
4. **未知工具名是新增边界**:课程写死 bash,没有这个问题;分发表泛化后模型可能喊出不存在的工具名,循环必须容错(行为表第 3 行)。
5. **input 不保证是对象**:`u.input()` 类型是 JsonNode,直接强转 `(ObjectNode)` 遇到非对象输入会 ClassCastException 炸掉整轮对话。用 `u.input() instanceof ObjectNode on ? on : Json.mapper().createObjectNode()`。空对象交给 BashTool 会得到 "Error: Missing required parameter: command"——降级链条自洽。
6. **循环没有上限**:终止完全依赖模型判断,课程如此设计(`while True`)。不用担心测试挂死——假客户端脚本耗尽时会抛 LlmException,每个测试天然有界。生命周期控制(停止钩子等)s15 再加。

## 测试预告(AgentLoopTest,Claude 写,6 个用例)

假客户端:`ScriptedLlmClient`(实现 LlmClient,构造时收一个响应脚本队列,`complete` 逐次弹出,耗尽则抛 LlmException"脚本耗尽"——测试失败得响亮而不是 NPE);`RecordingLlmClient`(包装另一个 LlmClient,记录每次请求供断言)。

1. 纯文本回复:脚本 1 条 assistant(text) → 历史恰 2 条(user + assistant),只调了 1 次 client
2. 一次 tool_use(bash `echo hello`,id=toolu_1) → 回灌 user 消息恰 1 条、content 恰 1 个 ToolResultBlock,content="hello"、toolUseId="toolu_1";历史顺序 user → assistant → user
3. 一条 assistant 消息含 2 个 tool_use → 串行执行,回灌 user 消息含 2 个 tool_result,顺序与 tool_use 一致
4. 未知工具名 → 循环不崩,对应 tool_result content 以 "Error: Unknown tool:" 开头
5. 工具链:脚本 2 条——assistant(tool_use) → assistant(text "done") → 历史恰 4 条(user → assistant → user → assistant)
6. 请求透传:Recording 断言传给 client 的请求——system、maxTokens 与构造注入值一致,tools 大小等于注册表大小

## 完成标准

`mvn -q test` 全绿 + review 通过。真实网络验证在 s01-10 冒烟统一进行。

## 提交预告

5 批:① AgentLoop ② ScriptedLlmClient ③ RecordingLlmClient ④ AgentLoopTest ⑤ 作业单。提交时按实际文件再核对。
