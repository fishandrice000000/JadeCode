# s01 - LLM 与 Agent 间交互消息的格式

一个交互消息的例子:

```json
{
    "role": "assistant",
        "content": [
        {"type": "text", "text": "好的,我先看看目录里有什么。"},
        {"type": "tool_use", "id": "toolu_01", "name": "bash", "input": {"command":"ls"}}
    ]
}
```

设计的抽象:

- Message: 一个交互消息.
  - Role: 该消息发布者的身份.
  - ContentBlock 的列表: 一个内容块数组组成消息内容.
    - TextBlock: 文本类型内容块.
    - ToolUseBlock: 工具调用类型内容块.
    - ToolResultBlock: 工具调用结果类型内容块.
