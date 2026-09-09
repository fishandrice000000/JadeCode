# s04 作业:Hooks——挂在循环上,不写进循环里

## 背景

s03 的闸门现在硬编码在 AgentLoop 第 5.2 步。课程 s04 的命题:每加一个扩展(记录每次 bash 调用、操作后自动 git add、大输出提醒……)都要改循环,循环很快认不出来。循环应该是一个稳定的核心,扩展挂在外面。

课程解法:四个事件覆盖一个完整 agent cycle(UserPromptSubmit / PreToolUse / PostToolUse / Stop);循环只调用 trigger,注册表决定跑什么;**首个非 null 短路**——PreToolUse 非 null = 拦截本次工具执行,Stop 非 null = 强制续跑一轮;UserPromptSubmit / PostToolUse 的返回值不参与控制流。

我们的变化面:AgentLoop 丢掉 PermissionGate 参数,改持 Hooks;s03 的三道闸门整体搬进 Main 里注册的一个 PreToolUse hook(**闸门类本身一行不改**)。循环里不再出现"权限"二字。

设计决定(讨论后拍板):

- **统一注册表 `Map<String, List<Hook>>`**(课程原样):新事件 = register 一个新名字,Hooks 类永远不改,不做按事件分四张类型安全小表(那会把回调参数数量定死,每加事件都要改类)。
- **参数用 `Map<String, ?>` 按名传**(课程 *args 的按名替代):Python 回调能写 `def permission_hook(block)` 具名参数,Java lambda 没有具名参数,`args[0]` 可读性差;改按键取值 `params.get(PARAM_BLOCK)`。键名给常量防手滑。
- **查表注册成第一个 PreToolUse hook**(用户拍板,偏离课程原样——课程在 hook 之后才查 handler):工具不在分发表中,在进权限闸门之前就被拦下,回灌 `"Error: Unknown tool: {名字}"` 短路,后续 hook 不再执行。动机:查表是绝对的 PreToolUse 事件;工具多起来后注册本身会被拆出去(子代理、MCP 工具池),循环不该把"哪些工具合法"这个随上下文变化的配置焊死在类里。**已知代价**:① 未知工具的尝试对日志 hook 不可见(被第一个 hook 短路);② 查表必须注册在权限 hook 之前,注册顺序即正确性;③ 未来每个装配点都要记得注册这个 hook——**循环无兜底、完全信任装配点**(用户定案:到处兜底不优雅,与 v2 裸工具路线的单点信任同构)。漏注册的后果以崩溃暴露(未知工具 NPE),装配错误在源头现形而不是被静默兜住。
- 代价如实记账:取值后仍需强转(类型检查在运行期);键打错/漏放键 → null → NPE(坑 9);`Map.of` 不接受 null 值(坑 10)。

## 文件清单

```text
src/main/java/com/jadecode/safety/Hooks.java        ← 你写(新:事件表,本章核心机制)
src/main/java/com/jadecode/agent/AgentLoop.java     ← 你改:gate 参数换 hooks,三处触发点
src/main/java/com/jadecode/Main.java                ← 你改:建 Hooks、注册 5 个 hook、装配
src/main/java/com/jadecode/cli/ReplCommand.java     ← 你改:输入提交后触发 UserPromptSubmit
src/main/java/com/jadecode/cli/RunCommand.java      ← 你改:同上(一次性任务的 prompt)
src/test/java/com/jadecode/safety/HooksTest.java    ← Claude 写
src/test/java/com/jadecode/agent/AgentLoopTest.java ← Claude 改(构造点 + ⑦ 重写 + 3 新用例)
```

## Hooks 规格(你写)

```java
package com.jadecode.safety;

/** 事件表:s04 起,循环不再直接调用任何检查函数,只触发事件 */
public class Hooks {
    // 事件名常量(防手滑);事件集合本身是开放的——新事件直接 register 新名字即可
    public static final String USER_PROMPT_SUBMIT = "UserPromptSubmit";
    public static final String PRE_TOOL_USE = "PreToolUse";
    public static final String POST_TOOL_USE = "PostToolUse";
    public static final String STOP = "Stop";

    // 内置事件参数键常量(触发点与回调共用,打错字编译期抓不到,靠常量收敛)
    public static final String PARAM_QUERY = "query";       // UserPromptSubmit
    public static final String PARAM_BLOCK = "block";       // PreToolUse / PostToolUse
    public static final String PARAM_OUTPUT = "output";     // PostToolUse
    public static final String PARAM_MESSAGES = "messages"; // Stop

    // 课程同款注册表:事件名 → 回调列表。同名事件按注册顺序追加
    private final Map<String, List<Hook>> registry = new HashMap<>();

    @FunctionalInterface
    public interface Hook {
        // 参数按名取(各事件的键见上方常量):
        //   UserPromptSubmit: query:String
        //   PreToolUse:       block:ToolUseBlock
        //   PostToolUse:      block:ToolUseBlock, output:String
        //   Stop:             messages:List<Message>
        // 返回非 null = 拦截(PreToolUse)/ 强制续跑(Stop);UserPromptSubmit / PostToolUse 的返回值调用方忽略
        String run(Map<String, ?> params);
    }

    /** 注册:往事件对应的表尾追加(注册顺序 = 触发顺序) */
    public void register(String event, Hook hook) { ... }

    /** 触发:按注册顺序依次调用,首个非 null 立即短路返回;全部 null(或事件未注册)→ null */
    public String trigger(String event, Map<String, ?> params) { ... }
}
```

## AgentLoop 规格(你改)

1. 字段与构造:`PermissionGate gate` → `Hooks hooks`(第 5 参类型换掉,Main 与测试同步改)。
1. 第 4 步(无 tool_use,退出)变成:

```java
if (toolCalls.isEmpty()) {
    String force = hooks.trigger(Hooks.STOP, Map.of(Hooks.PARAM_MESSAGES, messages)); // 退出前最后一问:hook 可以强制续跑
    if (force != null) {
        messages.add(Message.userText(force));
        continue;
    }
    return;
}
```

1. 第 5 步循环体重写为课程形状(第一个 PreToolUse hook 负责查表 → 执行 → PostToolUse):

```java
for (ToolUseBlock u : toolCalls) {
    String blocked = hooks.trigger(Hooks.PRE_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u)); // s04:硬编码闸门 → 事件触发
    if (blocked != null) {
        results.add(new ToolResultBlock(u.id(), blocked)); // hook 返回什么就回灌什么
        continue;
    }
    Tool tool = tools.get(u.name()); // 无兜底:查表是第一个 PreToolUse hook 的职责,循环信任装配点
    ObjectNode node = u.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
    String output = tool.execute(node);
    hooks.trigger(Hooks.POST_TOOL_USE, Map.of(Hooks.PARAM_BLOCK, u, Hooks.PARAM_OUTPUT, output));
    results.add(new ToolResultBlock(u.id(), output));
}
```

1. 加访问器:`public Hooks hooks() { return hooks; }`——Repl/Run 触发 UserPromptSubmit 用。

## Main 装配(你改)

设计定案(讨论后拍板):**拆薄——buildLoop 只留注册清单,hook 实现抽成私有静态方法**(每个方法:参数 = 它捕获的依赖,返回一个 Hooks.Hook)。Main 仍是唯一装配点;注册清单一行一个注册,**顺序即触发顺序**,一眼可见。**tools 表先抽成局部变量**——查表 hook 要捕获它,而原来内联在构造函数里的写法拿不到:

```java
public static AgentLoop buildLoop() {
    AppConfig config = AppConfig.fromEnv(Env.loadDotEnv());
    Path cwd = Path.of("").toAbsolutePath();
    PathResolver resolver = new PathResolver(cwd);
    PathGuard guard = new PathGuard(resolver);
    CommandPolicy policy = new CommandPolicy();
    PermissionGate gate = new PermissionGate(policy, guard);
    Map<String, Tool> tools = Map.of("bash", new BashTool(),
            "read_file", new ReadFileTool(resolver),
            "write_file", new WriteFileTool(resolver),
            "edit_file", new EditFileTool(resolver),
            "glob", new GlobTool(resolver));
    Hooks hooks = new Hooks();
    // 注册清单:顺序即触发顺序(查表必须最前,短路是特性)
    hooks.register(Hooks.USER_PROMPT_SUBMIT, promptContextHook(cwd));
    hooks.register(Hooks.PRE_TOOL_USE, membershipHook(tools)); // ① 查表
    hooks.register(Hooks.PRE_TOOL_USE, permissionHook(gate));  // ② 权限
    hooks.register(Hooks.PRE_TOOL_USE, logCallHook());         // ③ 日志
    hooks.register(Hooks.POST_TOOL_USE, largeOutputHook());
    hooks.register(Hooks.STOP, summaryHook());
    return new AgentLoop(new AnthropicSdkLlmClient(config), tools, systemPrompt(cwd), config.maxTokens(), hooks);
}

// ---- 六个内置 hook 的实现(每个:依赖作参数,返回 Hook) ----

/** UserPromptSubmit:记录当前工作目录。返回值调用方忽略 */
private static Hooks.Hook promptContextHook(Path cwd) {
    return params -> {
        System.out.println("[HOOK] UserPromptSubmit: working in " + cwd);
        return null;
    };
}

/** PreToolUse ① 查表:不在分发表中直接报错,不进权限闸门(注册必须在权限 hook 之前,否则顺序失效) */
private static Hooks.Hook membershipHook(Map<String, Tool> tools) {
    return params -> {
        ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
        return tools.containsKey(block.name()) ? null : "Error: Unknown tool: " + block.name();
    };
}

/** PreToolUse ② 权限:s03 三道闸门整体搬进来,闸门类不动;带原因回灌(前缀随 hook 搬家) */
private static Hooks.Hook permissionHook(PermissionGate gate) {
    return params -> {
        ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
        ObjectNode node = block.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
        String denied = gate.check(block.name(), node);
        return denied == null ? null : "Permission denied: " + denied;
    };
}

/** PreToolUse ③ 日志:注册在权限之后 → 被拦的工具不出日志行(课程同款短路) */
private static Hooks.Hook logCallHook() {
    return params -> {
        ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
        System.out.println("[HOOK] " + block.name());
        return null;
    };
}

/** PostToolUse:大输出提醒 */
private static Hooks.Hook largeOutputHook() {
    return params -> {
        ToolUseBlock block = (ToolUseBlock) params.get(Hooks.PARAM_BLOCK);
        String output = (String) params.get(Hooks.PARAM_OUTPUT);
        if (output.length() > 100000) {
            System.out.println("[HOOK] ⚠ Large output from " + block.name());
        }
        return null;
    };
}

/** Stop:收尾统计(tool_result 计数),恒返回 null = 允许退出 */
private static Hooks.Hook summaryHook() {
    return params -> {
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) params.get(Hooks.PARAM_MESSAGES); // 泛型强转有 unchecked 警告
        long n = messages.stream().flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ToolResultBlock).count();
        System.out.println("[HOOK] Stop: session used " + n + " tool calls");
        return null;
    };
}
```

## Repl / Run(你改)

ReplCommand 在 `history.add(Message.userText(query))` 之前、RunCommand 在 `history.add(Message.userText(prompt))` 之前:

```java
loop.hooks().trigger(Hooks.USER_PROMPT_SUBMIT, Map.of(Hooks.PARAM_QUERY, query)); // 返回值不参与控制流
```

## 坑

1. **前缀搬家**:"Permission denied: " 现在在 Main 的权限 lambda 里,循环原样回灌 hook 返回值(课程 `str(blocked)`)。漏搬家 → 回灌变成裸理由,AgentLoopTest ⑦ 红。
1. **构造签名联动**:AgentLoop 第 5 参换型,Main + AgentLoopTest 三处构造点必须同 commit 改齐,否则编译断。
1. **查表 hook 必须注册在最前,顺序即正确性**(用户拍板,偏离课程——课程 handler 查找在 hook 之后):注册在权限 hook 之后 → 未知工具先过权限,设计失效。**循环无兜底**(用户定案):漏注册 → 未知工具 NPE 崩循环,错误以崩溃暴露。
1. **未知工具尝试对日志 hook 不可见**:被第一个查表 hook 短路,`[HOOK]` 日志行不打印——模型幻觉工具名时的调试信号丢失,已知代价。
1. **Stop hook 恒返回非 null → 死循环**(课程同款,无保护)。summary hook 恒返回 null,别手滑 return 字符串。
1. **注册顺序 = 触发顺序,短路是特性**:权限 hook 注册在日志 hook 之前 → 被拦截的工具不打印 [HOOK] 日志(课程同款)。
1. **事件名用常量,不用裸字符串**:register 和 trigger 都写 `Hooks.PRE_TOOL_USE`。裸字符串打错字不会报错——注册进一个没人触发的名字,静默失效,比崩溃更糟。
1. **强转是运行期检查**(统一注册表的代价):`params.get(...)` 后强转,类型写错要到触发时才炸(ClassCastException),编译期不拦。四个内置事件的参数契约已写在 Hook 接口注释里,新事件自己写清楚。
1. **参数键打错字 / 触发点漏放键 → `params.get(...)` 返回 null → NPE**:与裸字符串事件名同类风险,但更难察觉(回调体里炸,离现场远)。触发点与回调一律用 `PARAM_*` 常量,不在回调里裸写 `params.get("block")`。
1. **`Map.of` 不接受 null 值**(值或键为 null 直接 NPE):当前四个事件的参数都非 null,没事;将来某个事件要传 null,改用 `new HashMap<>()` 构造。
1. **回调抛异常会一路传播杀死循环**(课程同款,不吞):hook 是开发者代码,错了就大声失败;不用在 trigger 里 try/catch。
1. 老坑:REPL 验收前先 `mvn -q package`——./jade 跑的是 target/jadecode.jar,只跑 mvn test 会验收旧构建。

## 测试预告(Claude 写)

**HooksTest**(新):① 注册顺序即触发顺序;② 首个非 null 短路,第二个 hook 不被调用;③ 全部 null → null;未注册的事件 → null;④ 不同事件互不相干(注册 PreToolUse 不影响 Stop);⑤ 参数 Map 原样透传(键值一致);⑥ **新事件零成本**:register 一个常量表之外的新事件名 + 任意键值即可触发,不需改 Hooks 类(统一注册表的设计点,直接入测)。

**AgentLoopTest**(改):构造 helper `permissiveGate()` → 空 Hooks;三处构造点换第 5 参;④ 重写为**装配查表 hook(复刻 Main 接线——无兜底,测试必须带接线)**:未知工具 → 回灌 "Error: Unknown tool: no_such_tool"、循环继续、不 NPE;⑦ 重写为注册一个返回 "Permission denied: Potentially destructive command" 的 PreToolUse hook(模拟 Main 权限 lambda),断言原样回灌、工具未执行、循环继续;⑧ 新:Stop 强制续跑(hook 返回 "keep going" → 注入 user 消息继续,第二轮文本收尾);⑨ 新:PostToolUse 记录 hook 收到 (block, output) 且 output 等于执行结果;⑩ 新:查表 hook 短路在后续 hook 之前(注册查表 + 记录型两个 PreToolUse hook,未知工具名 → 只有查表被调用,记录型没被触发)。

## 完成标准

`mvn -q test` 全绿 + review 通过。真实验收(REPL,先 `mvn -q package`):

1. `read_file README.md`(工作区内)→ 直接通过;观察 `[HOOK]` 日志与 UserPromptSubmit 行
1. `write_file` 工作区内新建文件 → 通过;每轮退出时观察 Stop summary 计数
1. "删除 /tmp 下所有临时文件" → bash rm 触发 `[permission]` y/N(s03 闸门经 hook 生效)
1. `read_file /etc/hostname` → `[blocked]` 硬拒、不询问(闸门 1 依旧,权限 hook 不背锅)
1. 观察重点:权限被拒时是 hook 拦截的——循环里没有权限代码

验收后清掉工作区与 /tmp 的测试产物。

## 提交预告

① Hooks + HooksTest(新类独立编译)② AgentLoop + Main + ReplCommand + RunCommand + AgentLoopTest(构造签名联动,必须同 commit)③ 作业单。提交时按实际文件再核对。

---

顺手(非本作业):ReplCommand 的提示符还写着 "s01 >> ",可以顺手改成 "jade >> ",也可不动。

---

## 附:函数式接口写法速查(本作业用)

**心智模型**:接口里的 `run()` 只是"形状声明"(收什么、还什么),实现写在调用 `register()` 的地方;`trigger()` 里 `hook.run(params)` 才是真正"调用"它。语法上 lambda = 匿名类的糖:参数列表 `->` 方法体。你其实写过同款——`PermissionGate.Approval` + 测试里的 `(n, i, r) -> true`。

**三种等价写法**(选 lambda,其余看懂即可):

```java
// 匿名类(旧写法):new Hooks.Hook() { @Override public String run(Map<String, ?> params) { ... } }
// lambda(推荐):类型全省——register 参数类型是 Hook,参数类型编译器按目标类型推断
hooks.register(Hooks.USER_PROMPT_SUBMIT, params -> {
    System.out.println("[HOOK] UserPromptSubmit: working in " + cwd);
    return null; // 语句体必须显式 return;null 也是返回
});

// 方法引用(回调写成一个具名方法,register 处一行引用——最接近课程 Python 的命名函数)
private static String summaryHook(Map<String, ?> params) { ... }
hooks.register(Hooks.STOP, Main::summaryHook); // 方法签名与 run 一致即可
```

**两条语法规则**:

- 身体是**一条表达式** → 免 `{}` 免 `return`:`params -> "Permission denied: ..."`
- 身体是**多条语句** → 必须 `{}` + 显式 `return`(上面的 UserPromptSubmit 例子)

**两个注意点**:

- lambda 捕获外层局部变量必须 effectively final(只赋值一次)。`buildLoop()` 里的 `cwd`、`gate`、`hooks` 都符合。
- 触发点传 `Map.of(...)` 不可变 Map:回调想改共享参数会抛异常,而不是悄悄污染后续 hook 看到的数据。
