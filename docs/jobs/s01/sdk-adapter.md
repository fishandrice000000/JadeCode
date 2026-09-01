# s01-7 作业:SDK 适配层(AnthropicSdkLlmClient)

## 背景

唯一接触 SDK 类型的类(隔离设计兑现):所有"内部类型 ↔ SDK 类型"换算集中于此,项目其余部分不认识 `com.anthropic.*`。

以下 SDK API 已用 `javap` 核对(anthropic-java 2.57.0,Kotlin 重写版):

| 环节 | SDK 用法 |
| --- | --- |
| 建客户端 | `AnthropicOkHttpClient.builder().apiKey(k).baseUrl(url).build()`;SDK 自动在 baseUrl 后拼 `/v1/messages` → DeepSeek 端点成立 |
| 调模型 | `client.messages().create(params)`(接口 `com.anthropic.services.blocking.MessageService`) |
| 请求 | `MessageCreateParams.builder().model(String).maxTokens(long).system(String).messages(List<MessageParam>).tools(List<ToolUnion>).build()` |
| 消息 | `MessageParam.builder().role(MessageParam.Role.USER/ASSISTANT).contentOfBlockParams(List<ContentBlockParam>).build()` |
| 文本块 | `ContentBlockParam.ofText(String)`(也有 `ofText(TextBlockParam)`) |
| tool_use 块 | `ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(String).name(String).input(ToolUseBlockParam.Input).build())` |
| tool_result 块 | `ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(String).content(String).build())` |
| 工具声明 | `Tool.builder().name().description().inputSchema(Tool.InputSchema).build()`,再包 **`ToolUnion.ofTool(...)`** |
| InputSchema | `Tool.InputSchema.builder().type(JsonValue).properties(Tool.InputSchema.Properties).required(List<String>)`;Properties 用 `putAdditionalProperty(String, JsonValue)` 逐条填 |
| Jackson 桥 | 入:`JsonValue.fromJsonNode(JsonNode)`;出:`jsonValue.convert(JsonNode.class)` |
| 响应 | `Message.content()` → `List<ContentBlock>`;`isText()/asText()/isToolUse()/asToolUse()`;`ToolUseBlock.id()/name()/_input()`(`_input` 返回 `JsonValue`,注意下划线) |
| 用量 | `usage().inputTokens()/outputTokens()` 返回 **long** |
| 异常 | `AnthropicServiceException.statusCode()`(429=RateLimitException、402=UnexpectedStatusCodeException 都是它的子类);网络类异常基类 `AnthropicException`(无状态码) |

> **包路径提醒**(2026-09-01 实测更正):`AnthropicOkHttpClient` 在 `com.anthropic.client.okhttp` 包;表内其余模型类全在 `com.anthropic.models.messages` 子包(**不是** `com.anthropic.models`)。代码中全部用全限定名,如 `com.anthropic.models.messages.MessageCreateParams`。

## 文件清单

```text
src/main/java/com/jadecode/llm/ModelResponse.java            ← 修改:Usage 的 int → long
src/main/java/com/jadecode/llm/AnthropicSdkLlmClient.java   ← 新
```

### ModelResponse.java 修改

`Usage(int, int)` → `Usage(long, long)`:SDK 给 long,契约跟随真相;ModelResponse 尚无消费者,现在改零成本。

### AnthropicSdkLlmClient.java

```java
package com.jadecode.llm;

public class AnthropicSdkLlmClient implements LlmClient {
    private final com.anthropic.client.AnthropicClient client; // 接口在 core,构建器在 client.okhttp 包
    private final String model;

    public AnthropicSdkLlmClient(AppConfig config) {
        // AnthropicOkHttpClient.builder().apiKey(config.apiKey()).baseUrl(config.baseUrl()).build()
        // this.model = config.model()
    }

    @Override
    public ModelResponse complete(CompleteRequest request) throws LlmException {
        // 1. toParams(request)
        // 2. try { client.messages().create(params) } 
        //    catch AnthropicServiceException e → LlmException(e.getMessage(), e.statusCode(), e)
        //    catch AnthropicException e           → LlmException(e.getMessage(), e)
        // 3. toResponse(message)
    }

    // MessageCreateParams toParams(CompleteRequest):
    //   model / maxTokens(自动宽化 long) / messages(逐条 toMessageParam)
    //   system 非 null 才调 .system(...)
    //   tools 非空才调 .tools(流式 map toSdkTool → ToolUnion.ofTool)

    // MessageParam toMessageParam(Message):
    //   role 映射 + contentOfBlockParams(逐块 toContentBlockParam)
    //   用 switch (sealed 穷举,编译器保证三块全处理):
    //   case TextBlock t -> ContentBlockParam.ofText(t.text())
    //   case ToolUseBlock u -> ofToolUse(builder().id/name/input(toSdkInput(u.input())))
    //   case ToolResultBlock r -> ofToolResult(builder().toolUseId(r.toolUseId()).content(r.content()))

    // ToolUseBlockParam.Input toSdkInput(JsonNode input):
    //   逐字段迭代 input.fields() → putAdditionalProperty(key, JsonValue.fromJsonNode(value))

    // Tool toSdkTool(Tool tool):
    //   从 tool.inputSchema() 拆出 type/properties/required 三段,分别转换成
    //   InputSchema.builder().type(JsonValue).properties(Properties).required(List<String>)

    // ModelResponse toResponse(Message message):
    //   遍历 message.content():
    //     isText()    → new TextBlock(b.asText().text())
    //     isToolUse() → new ToolUseBlock(id, name, _input().convert(JsonNode.class))
    //     其他块(如 thinking) → System.err 打印"跳过未知响应块" + 跳过(降级,不整轮失败)
    //   usage → new ModelResponse.Usage(inputTokens, outputTokens)
    //   message 用 Message.assistant(blocks) 包
}
```

## 五个坑

1. **long/int**:SDK 的用量是 long,契约改 `Usage(long, long)`。
2. **`ToolUseBlockParam.Input` 没有 `of(JsonNode)` 工厂**:必须逐字段迭代 ObjectNode,`putAdditionalProperty(key, JsonValue.fromJsonNode(value))` 塞进 builder。
3. **未知响应块降级跳过 + 打日志**(DeepSeek 可能回 thinking 块):跳过单块,不整轮失败。
4. **catch 顺序**:`AnthropicServiceException` 在前(带状态码)、`AnthropicException` 在后(无状态码)——子类先接。
5. **tools 空列表时跳过 `tools()` 调用**,不发空数组。

## 测试策略

不写测试:行为只能网络验证。s01-8 假客户端测主循环,s01-10 真实冒烟验收(风险闸门)。本章完成标准:编译过 + review 通过。

## 提交预告

2 批:① ModelResponse 修改(Usage long)② AnthropicSdkLlmClient。外加作业单 1 批。
