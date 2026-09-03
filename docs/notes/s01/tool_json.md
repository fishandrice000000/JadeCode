# s01 - 工具 JSON Schema 与 objectSchema

## 一问一答

**Q: ToolSchema 是干什么的? 它的实例就是一个可序列化的工具声明对象吗?**

A: ToolSchema 是**静态工厂类, 没有实例**. `objectSchema(...)` 返回一个 `ObjectNode`——它本身就是 JSON 树, 天然可序列化. 名字读作"给工具用的 schema 工厂".

它的作用分两层看:**接口统一在 `Tool`, 构造统一在 `ToolSchema`**. `Tool` 接口(name/description/inputSchema/execute)是工具们共同遵守的契约, 分发表 `Map<String, Tool>` 也基于它; 其中 `inputSchema()` 返回 `ObjectNode` 是"统一入口", 让 s01-7 适配层可以无差别地拆解任何工具的三段声明(type/properties/required).
ToolSchema 是**为了替每个不同的工具构造 tool_schema 而引入的抽象**. 没有它, 每个工具都要手抄 JSON 组装代码, 且校验逻辑散落到各工具里, `comand` 这种拼写错误就没人拦了——校验和构造必须住在同一个地方, 才能每个工具免费共享.

**Q: 一个 tool_schema 长什么样?**

A: bash 的例子:

```json
{
  "type": "object",
  "properties": {"command": {"type": "string"}},
  "required": ["command"]
}
```

完整工具声明 = `name` + `description` + `input_schema`, 而"schema"部分只有 `input_schema` 一处——前两者只是普通字符串, 无构造难度. 完整声明的组装在 s01-7 SDK 适配层(唯一接触 SDK 类型的类).

**Q: objectSchema 的作用是什么? properties 和 required 又是什么?**

A: 把 Java 的 `Map` + `List` 变成 JSON 树. `properties` = 参数名→类型(给模型看的参数说明书), `required` = 必填参数名列表. 构造三步: ① 校验 `required ⊆ properties`(失败抛异常, 一次报告所有缺失 key) ② 建根 ③ 填 `type`/`properties`/`required`. 关键 API: `putObject(字段名)`/`putArray(字段名)` 一步完成"创建 + 挂载". 完整实现见 `tools/ToolSchema.java`.

**Q: LLM 调用工具时, 传入的是 properties 还是 required?**

A: **两个都不是.** 两者是声明, 模型读它学会"怎么调"; 真正调用时模型传的是 `input`——真实参数值(`{"command": "ls"}`). 类比: `properties`+`required` ≈ 函数签名, `input` ≈ 调用时的实参.

**Q: properties 和 required 都是 harness 自己写死的, 为什么还需要校验?**

A: 因为两者都是**字符串**, 编译器帮不上忙——`comand` 这种拼写错误照常编译. 没有校验: 错误 schema 正常注册, 模型照着调, 最终表现为"模型怎么老调不对"——**拼写错误伪装成了模型行为问题**, 诊断方向全错. 校验把失败前移到注册时, 带着 key 名当场炸. 是 fail-fast 原则在工具层的应用, 也是对"字符串类型接口"的补偿.

**Q: 为什么 `"type": "object"` 对 input_schema 是必要的?**

A: JSON Schema 的类型声明. 不写 = 任意类型都行; 且 `properties`/`required` 只在实例是对象时才有意义, 不声明则 schema 语义模棱两可. 不是服务端的硬校验, 而是让声明无歧义的标准写法. 同时与 harness 侧的 `ObjectNode` 是同一约定: 一边告诉模型 input 是对象, 一边约束自己按对象处理.
