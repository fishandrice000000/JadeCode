# s01-5 作业:工具契约(tools 包)

## 背景

模型只会发 `name` + `input` JSON;harness 要做三件事:**声明**工具(模型读 `input_schema` 学会怎么调)、**执行**、把结果**回传**。课程对齐:`s02_tool_use/code.py` 第 128–168 行(`TOOLS` 列表 + `TOOL_HANDLERS` 分发表)。

每个工具有两个面:

- **声明面**(name / description / inputSchema):给**模型**读——描述何时用、怎么传参,JSON Schema 就是参数的"类型注解"
- **执行面**(execute):给 **harness** 调

两条契约(来自课程):

1. **错误返回字符串,绝不抛异常**——错误要作为 tool_result 内容回传给模型,让它自己修正;抛异常会打断主循环(课程 122 行:`except Exception as e: return f"Error: {e}"`)
2. **未知工具返回 `"Unknown: {name}"`**——分发表查不到时的降级(课程 166 行)

## 文件清单(3 个新文件 + 1 个修改)

```text
src/main/java/com/jadecode/tools/Tool.java          ← 新
src/main/java/com/jadecode/tools/ToolSchema.java    ← 新
src/test/java/com/jadecode/tools/ToolSchemaTest.java ← 新
src/main/java/com/jadecode/llm/CompleteRequest.java ← 修改:增加 tools 字段
```

### Tool.java — 契约接口

```java
package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {
    String name();                     // 与模型 tool_use 块的 name 对应
    String description();              // 给模型看:何时用、怎么用
    ObjectNode inputSchema();          // JSON Schema:模型读它学会怎么传 input
    String execute(ObjectNode input);  // 出错返回错误字符串,绝不抛异常
}
```

### ToolSchema.java — JSON Schema 构建工厂(本步真正有逻辑的部分)

```java
package com.jadecode.tools;

public final class ToolSchema {
    private ToolSchema() {}

    /** 构造 {"type":"object","properties":{...},"required":[...]}
     *  properties:属性名 → JSON Schema 类型名("string" / "integer" / ...)
     *  required 中的每个 key 必须出现在 properties 里,否则抛 IllegalArgumentException */
    public static ObjectNode objectSchema(Map<String, String> properties, List<String> required)
}
```

实现提示:用 `Json.mapper().createObjectNode()` / `put` / `set` 构建 JSON 树,**不要拼 JSON 字符串**。

### CompleteRequest.java — 修改

增加字段 `List<Tool> tools`(空列表表示不提供工具,全项目还没有调用方,零成本)。

### ToolSchemaTest.java — 断言清单

① 基本形状:`objectSchema(Map.of("command", "string"), List.of("command"))` → `type=="object"`、`properties.command.type=="string"`、`required` 恰含 `command`
② 多属性多类型:`{path: string, limit: integer}` + `required=[path]` → `properties.limit.type=="integer"`、`required` 只含 `path`
③ required 含不在 properties 里的 key → `IllegalArgumentException`,消息含该 key 名
④ 空 required → `required` 字段存在且为空数组
⑤ 空 properties → `properties` 空对象、`required` 空数组

## 两个坑

1. **拼 JSON 字符串 vs 构建树**:引号转义、嵌套、中文——手拼必然漏。用 `ObjectNode` API 构建,序列化交给 Jackson。
2. **required ⊆ properties 校验**:工具声明写错要**趁早炸**(注册时),而不是等模型按错误 schema 调用时才暴露——fail-fast 原则在工具层的应用。

## 完成标准

`mvn -q test` 全绿 + review 通过。

## 提交预告

按依赖顺序 4 批:Tool → ToolSchema → CompleteRequest 修改 → ToolSchemaTest,每批独立可编译。
