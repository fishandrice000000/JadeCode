# s01-4 作业:LLM 契约(llm 包)

## 背景

这一层是整个架构的接缝:主循环在上,模型实现在下,中间只隔一个 `LlmClient` 接口。

```text
AgentLoop ──CompleteRequest(system + 历史 + maxTokens)──▶ LlmClient.complete()
          ◀────ModelResponse(助手 Message + 用量)────────
          失败则抛 LlmException(statusCode)
```

三个设计意图:

1. **依赖倒置**:主循环只依赖 `LlmClient` 接口,不认识任何 SDK 类。后面会有两个实现(官方 SDK、手写 HTTP)和一个测试用假客户端——换实现不动主循环。
2. **消息模型零翻译**:进出都用 messages 包的 `Message`。SDK 适配层(s01-7)是唯一做"内部 ↔ SDK 类型"换算的地方。
3. **错误携带状态码**:`LlmException` 带 HTTP statusCode,为 s15 重试策略预留——429 重试、402 余额不足不重试、529 切备用模型。策略层判断靠状态码,不靠解析错误字符串。

## 文件清单(4 个,纯契约,无逻辑)

```text
src/main/java/com/jadecode/llm/LlmException.java
src/main/java/com/jadecode/llm/CompleteRequest.java
src/main/java/com/jadecode/llm/ModelResponse.java
src/main/java/com/jadecode/llm/LlmClient.java
```

### LlmException.java — 受检异常

```java
package com.jadecode.llm;

public class LlmException extends Exception {
    private final int statusCode;   // HTTP 状态码;0 表示无状态码(网络错误、解析失败)

    public LlmException(String message)                    // 无状态码 → statusCode = 0
    public LlmException(String message, int statusCode)
    public LlmException(String message, int statusCode, Throwable cause)  // 包装 SDK 底层异常用
    public int statusCode()
}
```

### CompleteRequest.java — 一次调用的输入

```java
public record CompleteRequest(String system, List<Message> messages, int maxTokens) {
    // system 可为 null:SDK 层见到 null 就不注入 system prompt
}
```

### ModelResponse.java — 一次调用的结果

```java
public record ModelResponse(Message message, Usage usage) {
    public record Usage(int inputTokens, int outputTokens) {}
}
```

`message` 约定为 role=ASSISTANT 的消息。`Usage` 嵌套在 `ModelResponse` 里——它只在这里有意义,不单独成文件。

### LlmClient.java — 契约本身

```java
public interface LlmClient {
    /** 同步调用一次模型。失败抛 LlmException(带 statusCode)。 */
    ModelResponse complete(CompleteRequest request) throws LlmException;
}
```

## 两个坑

1. **为什么 LlmException 是受检异常(extends Exception)而不是 RuntimeException?** "模型调用会失败"是常态而非意外(网络、限额、余额)。受检异常在编译期强制主循环的作者处理失败路径——这个契约就是要让"忘掉错误处理"编译不过。
2. **CompleteRequest 里为什么没有 tools?** `Tool` 是 s01-5 的功课,到时候给 record 加一个字段即可——现在还没有调用方,改起来零成本。自底向上构建的甜头:接口无人使用时,改动无痛。

## 测试策略

纯契约声明,没有逻辑可测。**本步不写测试**,从 s01-5(JSON Schema 构建逻辑)开始。

## 完成标准

`mvn -q test` 全绿(原有测试不回归)+ review 通过。

## 提交预告

按依赖顺序 4 批:LlmException → CompleteRequest → ModelResponse → LlmClient,每批独立可编译。
