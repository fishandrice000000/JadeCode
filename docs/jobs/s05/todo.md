# s05 作业单:TodoWrite — 先计划再执行

课程 s05 直译:第六把工具 `todo_write` + TodoManager + reminder 注入 + system prompt 引导。
**关键洞察**:todo_write 不给模型任何执行能力,只给规划能力。工作仍由原 5 工具完成。

## 一、课程行为(已读,提炼为规格)

**TodoManager**:内存持有任务列表,每次更新**整体替换**;校验失败则整体拒绝、旧列表不动。

**校验矩阵**(错误文案统一 `Error:` 前缀,与现有 Java 工具惯例一致):

| 场景 | 错误文案 |
| --- | --- |
| todos 不是数组 | `Error: todos must be a list` |
| 超过 20 项 | `Error: Max 20 todos allowed` |
| 第 i 项不是对象 | `Error: todos[i] must be an object` |
| content 缺失 / strip 后为空 | `Error: todos[i] requires content` |
| status 不在枚举内 | `Error: todos[i] has invalid status '<原值>'` |
| in_progress 多于 1 个 | `Error: Only one todo can be in_progress at a time` |

- status 缺省 `"pending"`;比较前转小写(大小写不敏感),报错时带**原值**
- content 存 strip 后的值;**多余键丢弃**,每项只留 {content, status}

**渲染**:

```
[ ] 步骤一        ← pending
[>] 步骤二        ← in_progress
[x] 步骤三        ← completed

(1/3 completed)
```

列表为空 → `No todos.`;渲染文本每轮更新后**原样返回给模型**(工具输出)。

**Reminder**(harness 层):每次 `run()` 计数从 0 开始(每条用户消息重置,Python 同款——`rounds_since_todo` 是 `agent_loop` 局部变量);某一轮若**成功执行**过 `todo_write` → 计数清零,否则 +1;计数 ≥ 3 时,该轮回灌的 user 消息里**追加一条** `TextBlock("<reminder>Update your todos.</reminder>")`,计数清零。被 PreToolUse 拦截的 todo_write **不算**使用(课程 used_todo 只置在成功路径)。

**system prompt**:在现有人设上追加规划引导(课程句: *Before starting any multi-step task, use todo_write to plan your steps. Update status as you go.*)。

## 二、设计决策(如无异议按推荐执行)

**D1 reminder 装在哪 — 推荐:AgentLoop.run() 内局部变量,课程直译。**
s04 把工具生命周期事件全迁进 hook 后,reminder 不属于任何现有事件的形态:PostToolUse 返回值被忽略、PreToolUse 返回值是拦截语义,没有事件能把文本塞进回灌结果。计数+节流注入是 harness 骨架机制(与 maxTokens 同级),不是可扩展事件点。代价:AgentLoop 需要认识 `"todo_write"` 这个名字——一个 `private static final String TODO_TOOL` 常量,课程同款,接受。

**D2 状态打印 — 推荐:Main 注册 PostToolUse hook,工具保持纯净。**
课程在工具函数里打印黄色 `## Current Tasks`;Java 侧沿用 s04 拆薄结论——execute 只返回字符串、无副作用(便于单测),终端展示归 Main 的 hook:新增 `todoStatusHook`(与 largeOutputHook 并列),`name().equals("todo_write")` 时 `System.out.println("\n" + Ansi.yellow("## Current Tasks\n" + output))`。

## 三、实现顺序(每步独立可编译;①②③④ 顺序不可换)

### ① TodoManager — `src/main/java/com/jadecode/todo/TodoManager.java`(包已建,纯逻辑:无 IO、无 print)

```java
package com.jadecode.todo;

/** 内存 todo 列表:校验通过才整体替换,渲染结果返回给模型 */
public class TodoManager {
    private List<Todo> items = List.of();

    /** 元素级校验 + 整体替换;返回渲染文本;任何错误抛 IllegalArgumentException(文案见上表) */
    public String update(ArrayNode todos) { ... }

    /** 渲染当前列表;空 → "No todos." */
    public String render() { ... }

    private record Todo(String content, String status) { }

    private static String marker(String status) { ... } // "[ ]" / "[>]" / "[x]"
}
```

规格要点:

- 下标错误用 `[i]`(0 起),与课程文案一致
- 校验全过后 `items = validated`,再返回 `render()`
- 项元素先 `isObject()` 判型;content 用 `path("content")` 判缺失 + `asText("")` + strip;status 缺省 pending 再 lower-case
- 错误消息**由 update 抛出**、由调用方加 `Error:` 前缀?不——**update 直接抛含完整文案的 IllegalArgumentException**(含 `todos[i]` 这些词的完整句),`TodoWriteTool.execute` catch 后拼 `"Error: " + e.getMessage()`(与课程 `f"Error: {e}"` 同构,也符合 Java 工具"错误也返回字符串"契约)

### ② TodoWriteTool — `src/main/java/com/jadecode/tools/TodoWriteTool.java`

```java
/** todo_write:模型更新计划状态;构造器注入 TodoManager(仿 GlobTool 注入 PathResolver) */
public class TodoWriteTool implements Tool {
    private final TodoManager manager;

    public String name() { return "todo_write"; }
    public String description() { return "Create and manage a task list for your current coding session."; }

    /** ToolSchema.objectSchema 只支持平铺 String 类型,数组/嵌套/枚举表达不了 —— 必须 override */
    @Override
    public ObjectNode inputSchema() { ... }

    public String execute(ObjectNode input) {
        // 1. todos 缺失 → "Error: Missing required parameter: todos"(仿 GlobTool)
        // 2. todos 非数组 → "Error: todos must be a list"(类型检查在 execute,update 的签名已限定数组)
        // 3. try { manager.update((ArrayNode) ...) } catch (IllegalArgumentException e) → "Error: " + e.getMessage()
    }
}
```

inputSchema 形状照抄课程 `TOOLS` 末条(用 `Json.mapper()` 手拼):

```json
{"type":"object","properties":{"todos":{"type":"array","maxItems":20,"items":{
  "type":"object","properties":{"content":{"type":"string","minLength":1},
  "status":{"type":"string","enum":["pending","in_progress","completed"]}},
  "required":["content","status"]}}},"required":["todos"]}
```

### ③ AgentLoop reminder — `src/main/java/com/jadecode/agent/AgentLoop.java`

```java
// 类级常量
private static final String TODO_TOOL = "todo_write";

// run():while 前
int roundsSinceTodo = 0;

// step 5 循环内:5.2 成功执行后、5.3 前
boolean usedTodo = u.name().equals(TODO_TOOL);   // 放 execute 成功后 → 拦截的调用不计

// step 5 for 结束、step 6 之前
roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;
if (roundsSinceTodo >= 3) {
    results.add(new TextBlock("<reminder>Update your todos.</reminder>"));
    roundsSinceTodo = 0;
}
```

注意 `usedTodo` 变量要在 for 外声明、循环内置位(每轮一块,不必逐条置位)。

### ④ Main 装配 — `src/main/java/com/jadecode/Main.java`

- tools map 加第 6 条:`"todo_write", new TodoWriteTool(new TodoManager())`
- hooks 注册一条 POST_TOOL_USE(在 largeOutputHook 之后):`todoStatusHook()`(D2)
- systemPrompt 追加规划引导。建议合成文案(先按这个写,不满意随时调):

```
You are Jade Code, a coding agent at {cwd}. Use bash to solve tasks. Act, don't explain.
Before starting any multi-step task, use todo_write to plan your steps. Update status as you go.
All destructive operations require user approval.
```

改完 ④ 后 `MainTest.systemPromptIncludesCwd` 会红(测试期望由我随后同步,你不用管)。

## 四、坑清单

1. **schema 平铺陷阱**:TodoWriteTool 必须 override `inputSchema()`——`ToolSchema.objectSchema` 的 properties 是 `Map<String,String>`,只有 "type" 一层,表达不了 array/items/enum
2. **整体替换语义**:校验失败绝不能改 `items`(模型会先喂半个坏列表再喂好列表,状态必须不被污染)
3. **status 大小写**:`IN_PROGRESS` 要接受;非法值报错带原值不是 lower 后的值
4. **reminder 是 text 不是 tool_result**:加进 results 的是 `TextBlock`,构造参数就一个 text——混在 tool_result 同一条 user 消息里发给 API(课程同款);真机若遇 400 再回来处理,测试不受影响
5. **计数位置**:`roundsSinceTodo` 在 `while (true)` **外**声明(run 内跨轮存活)、在每次 `run()` 入口自然重置;别放进 for 内
6. **拦截不计使用**:被权限 hook 拦下的 todo_write 不置 usedTodo(放成功路径之后)
7. **别给权限闸门加规则**:todo_write 不碰文件不碰 bash,PermissionGate 两张表都不需要动——放行是查不到规则的默认行为
8. **stale jar**:验收前 `mvn -q package`(老规矩)

## 五、我随后补的测试(预览,写代码时心里有数)

**TodoManagerTest**(todo 包,校验矩阵):
① 合法列表整批替换、渲染行序与尾部 `(done/total completed)` 计数 ② 空数组 → `No todos.` ③ content 缺失/纯空白 → `[0]` 文案 ④ 21 项 → Max 20 ⑤ 双 in_progress → Only one ⑥ 非法 status → 带原值 ⑦ status 大小写混写接受 ⑧ **失败不污染**:先合法更新再喂坏列表 → render 仍是旧列表 ⑨ 多余键丢弃(带 id 字段 → 渲染无 id)

**AgentLoopTest 追加**:
⑩ 连续三轮纯 bash → 第三条回灌消息尾部带 reminder TextBlock ⑪ 第三轮用了 todo_write → 该轮无 reminder、下一轮计数归零(再造三轮才再见 reminder) ⑫ reminder 是独立 TextBlock,不顶掉 tool_result(id 逐块对应不变)

## 六、验收(全绿后真机)

1. `mvn -q test` 全绿
2. REPL 派一个多步任务(如 "列出本目录文件,统计 Java 文件行数,再挑最大的三个打印"):观察第一轮调用是不是 `todo_write`、终端是否出现黄色 `## Current Tasks`
3. 故意给 3+ 步纯 bash 任务不用 todo:第 3 轮后模型应收到 reminder 并开始更新 todo(模型回话里能看到它"被提醒")
4. 让模型把同一项标两次 in_progress:应看到 `Error: Only one todo...` 回灌,模型自纠
5. `q` 退出无异常

## 七、提交预览(Git-SOP:每批独立可编译,具体批次提交前再核)

```
添加 TodoManager:todo 列表校验与渲染
添加 TodoManager 单元测试:校验矩阵与渲染
添加 todo_write 工具:整体替换任务列表
AgentLoop 注入 reminder:连续三轮未用 todo 时提醒
Main 装配 todo_write 与规划引导,同步测试期望
```
