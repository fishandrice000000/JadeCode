# s03 作业:权限三道闸门

## 背景

课程对齐:`s03_permission/code.py`。s02 的 agent 有了"手",但 bash 不受限制——让它"清理一下项目",可能执行 `rm -rf /`。本章在工具执行前插一道权限管线,**主循环只加一处判断**:

```
+----------+      +-------+      +--------------+      +---------------+
|   User   | ---> |  LLM  | ---> | Permission   | ---> | Tool Dispatch |
|  prompt  |      |       |      | 1. deny list |      | execute       |
+----------+      +---+---+      | 2. rules     |      +-------+-------+
                      |          | 3. approval  |              |
                      |          +------+-------+              |
                      |            deny → tool_result "Permission denied."
                      +-----------------------------------------------+
```

三道闸门:| 闸门 | 作用 | 命中后 |
|------|------|--------|
| 1. 拒绝列表 | 永远禁止(bash 的 `rm -rf /`、`sudo`…) | 直接拒绝 |
| 2. 规则匹配 | 上下文相关(bash 破坏性命令) | 交给闸门 3 |
| 3. 用户审批 | 暂停等 y/N | 用户决定 |

都没命中 → 直接执行(大部分日常操作走这条路)。

## 与课程的两处有意偏离

1. **file tools 的 "Access outside workspace" 规则不移植**。课程 s03 用它询问"写工作区外?"——在我们项目里,PathGuard(s02)已经在工具内部**硬拦截**越界路径。审批不可能放行越界(那样等于给 PathGuard 开安全后门),所以我们的闸门 2 只有 bash 一条规则,file tools 由 PathGuard 全权负责。闸门管不到,也不该管。
2. **非主线程拒绝交互审批**(课程没有,总计划里有):后台任务(s11 起)跑到闸门 3 时不能挂起等输入,默认审批自动拒绝。现在先实现,以后才用得上。

## 文件清单

```text
src/main/java/com/jadecode/safety/CommandPolicy.java  ← 你写(闸门 1+2 的 bash 检查)
src/main/java/com/jadecode/safety/PermissionGate.java ← 你写(三闸串联 + 默认审批)
src/main/java/com/jadecode/agent/AgentLoop.java        ← 你改:构造器 +1 参,执行前过闸
src/main/java/com/jadecode/tools/BashTool.java         ← 你改:删除内嵌黑名单(职责上移)
src/main/java/com/jadecode/Main.java                   ← 你改:buildLoop 装配 + systemPrompt 追加
src/test/java/com/jadecode/safety/CommandPolicyTest.java  ← Claude 写
src/test/java/com/jadecode/safety/PermissionGateTest.java ← Claude 写
src/test/java/com/jadecode/agent/AgentLoopTest.java       ← Claude 适配(构造器 5 参)
src/test/java/com/jadecode/tools/BashToolTest.java        ← Claude 适配(黑名单用例移除)
```

## CommandPolicy 规格

bash 命令检查的两张表 + 一条正则,全部**静态常量**(无状态,构造器不需要注入):

```java
public class CommandPolicy {
    private static final List<String> DENY_LIST =
            List.of("rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=", "> /dev/sda");
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "(?i)(?:^|[;&|()\\n])\\s*(?:rm|del)(?=\\s|$|[;&|()])");   // 课程原文正则
    private static final List<String> DESTRUCTIVE_KEYWORDS =
            List.of("rm ", "> /etc/", "chmod 777");

    // 闸门 1:子串匹配 DENY_LIST(课程做法,不是正则)
    //   命中 → 返回 "Blocked: '{pattern}' is on the deny list"(课程消息,含单引号)
    //   全过 → 返回 null(null = 放行,不是错误)
    public String checkDenyList(String command) { ... }

    // 闸门 2 的 bash 部分:DESTRUCTIVE 正则命中 **或** 任一关键词子串命中
    //   → 返回 "Potentially destructive command";否则 null
    public String checkRules(String command) { ... }
}
```

两个方法**分开**——闸门 1 是硬拒,闸门 2 是询问,决策不同,别合并。

## PermissionGate 规格

```java
public class PermissionGate {
    private final CommandPolicy policy;
    private final Approval approval;
    private final Thread mainThread; // 构造时记录 Thread.currentThread():默认审批据此拒绝非主线程

    // 装配用:默认审批 = 打印提示 → 主线程检查 → 读一行 y/N
    public PermissionGate(CommandPolicy policy) { ... }

    // 测试用:注入假审批,闸门 3 完全不碰控制台
    public PermissionGate(CommandPolicy policy, Approval approval) { ... }

    // 三道闸门串联;false = 拒绝(loop 回灌 "Permission denied."),true = 放行
    public boolean check(String toolName, ObjectNode input) {
        // 闸门 1:仅 toolName 等于 "bash" 时查硬拒绝列表
        //   命中 → System.out.println("[blocked] " + reason) → return false
        // 闸门 2:规则匹配(目前只有 bash 一条 → policy.checkRules(command))
        //   无命中 → return true
        // 闸门 3:命中 → approval.ask(toolName, input, reason)
        //   false → return false;true → return true
    }

    @FunctionalInterface
    public interface Approval {
        boolean ask(String toolName, ObjectNode input, String reason);
    }
}
```

默认审批的行为(放在构造器私有逻辑或内部类,你定):
1. 打印 `"\n[permission] " + reason` 与 `"   Tool: " + toolName + "(" + input + ")"`
2. `Thread.currentThread() != mainThread` → 打印拒绝原因 → return false(**不等输入**)
3. 打印 `"   Allow? [y/N] "` → 读一行(trim + 小写)→ `"y"`/`"yes"` 放行,其余拒绝

## AgentLoop 改动

构造器加第 5 参:`PermissionGate gate`,存 final 字段。主循环只改 5.2 一处:

```java
// 5.2 工具在分发表中:先取 input,过闸门,再执行
ObjectNode node = u.input() instanceof ObjectNode o ? o : Json.mapper().createObjectNode();
if (!gate.check(u.name(), node)) {
    results.add(new ToolResultBlock(u.id(), "Permission denied.")); // 课程原文消息
    continue;
}
results.add(new ToolResultBlock(u.id(), tool.execute(node)));
```

拒绝时**照常回灌 tool_result**,循环不中断——模型看到 "Permission denied." 自己会换方案,这正是课程的设计。

## BashTool 改动

删除 `DANGEROUS` 常量与 run() 开头的检查循环。之后 BashTool 是**裸执行器**,防护全部由闸门接棒(与课程 run_bash 一致:课程代码里 bash 没有内嵌检查)。

## Main 改动

buildLoop 装配:

```java
CommandPolicy policy = new CommandPolicy();
PermissionGate gate = new PermissionGate(policy);   // 装配路径:默认审批
return new AgentLoop(..., gate, config.maxTokens()); // 注意参数顺序
```

systemPrompt 现有句子后追加课程原文:`" All destructive operations require user approval."`(让模型知道"破坏性操作要请示",它才配合走审批)。

## 坑

1. **Java 正则转义**:课程的 `\s`、`\n` 写进 Java 字符串字面量是 `"\\s"`、`"\\n"`;`(?:...)` 非捕获组、`(?=...)` 前瞻、`(?i)` 大小写不敏感,Java 全部支持。
2. **闸门 1 先于闸门 2,且是子串匹配**:`"rm -rf /tmp/x"` 包含 `"rm -rf /"` 子串 → 被闸门 1 **硬拒**,永远走不到询问。这是课程原语义,别"优化"成先问。
3. **正则的词边界是本章核心测试点**:`rm` 前必须是行首或 `;&|()\n`,后必须是空白/行尾/`;&|()`——所以 `rmdir` 不触发、`echo del test.txt` 不触发、`echo a; rm b` 触发、`DEL x.txt` 触发(大小写不敏感)。
4. **deny list 与 s01 我们 BashTool 的旧表不同**:课程是 `"> /dev/sda"`(我们旧表是更宽的 `"> /dev/"`)、课程多了 `"mkfs"`、`"dd if="`。**照课程原文**,旧表随黑名单一起删。
5. **非主线程拒绝要打印理由**:静默 false 会让模型只看到 "Permission denied." 然后盲目重试。
6. **System.in 的 BufferedReader 只建一个**(构造时),不要每次 ask 新建——会与 ReplCommand 抢缓冲。与 ReplCommand 同款即可。
7. **闸门管不到 file tools 的越界路径**:PathGuard 在工具内部硬拦(异常 → 工具返回错误),审批发生在 loop 里、在工具之前——两者各管一段,不重叠。
8. **AgentLoop 构造器签名变化是预期的**:旧测试会编译失败,由 Claude 适配,你不用动测试。
9. **input 的 ObjectNode 取一次**:现在 loop 里 `u.input() instanceof ObjectNode` 是内联在 results.add 里的,过闸后要复用同一个 node——先取变量,再 `gate.check`,再 `tool.execute`,别写两遍 instanceof。

## 测试预告(Claude 写)

**CommandPolicyTest**:
① 闸门 1:`"sudo ls"` → `"Blocked: 'sudo' is on the deny list"`;`"echo hi"` → null;② 闸门 2 正则:`"rm foo.txt"` → 命中;`"rm -rf /tmp/x"` → 命中;`"rmdir"` → null;`"echo del test.txt"` → null;`"echo a; rm b"` → 命中;`"DEL x.txt"` → 命中(大小写);③ 关键词:`"chmod 777 x"`、`"> /etc/x"` → 命中;普通命令 → null。

**PermissionGateTest**(注入假审批,记录是否被调用):
① bash `"sudo ls"` → false 且审批**未被调用**(闸门 1 短路);② bash `"rm foo.txt"` + 假审批 true → true;假审批 false → false;③ 非 bash 工具(如 `"glob"`)→ true 且审批未调用;④ **默认审批 + 非主线程**:新开一个 Thread 里调 check(bash 破坏性命令)→ false 且不阻塞不等输入;⑤ 假审批收到的 toolName/input/reason 透传正确。

**AgentLoopTest 适配**:
runWith 注入真实闸门 + 恒放行假审批(老用例行为不变);新增:拒绝闸门(bash `rm foo.txt` + 恒拒绝审批)→ tool_result 内容恰为 `"Permission denied."`、工具未执行(看不到 `hello`)、循环继续直到文本收尾。

**BashToolTest 适配**:
⑤ 黑名单用例删除(拦截职责已上移,由 PermissionGateTest 覆盖);其余 7 个用例不动。

## 完成标准

`mvn -q test` 全绿 + review 通过。真实验收(REPL,课程四个 prompt):
1. `Create a file called test.txt in the current directory` → 直接通过
2. `Delete the file test.txt` → bash rm 触发闸门 2 → y/N 询问;输 n → 模型收到 Permission denied.;输 y → 执行
3. `What files are in the current directory?` → 只读,全部通过
4. `Try to write a file to /etc/something` → **我们的偏离**:PathGuard 硬拦,不询问

观察重点:哪些直接过、哪些询问、哪些被拒。

## 提交预告

① CommandPolicy(新文件,独立编译)② PermissionGate(依赖①)③ AgentLoop + Main 接线(gate 生效;BashTool 内嵌黑名单仍在,行为不变——安全不回退)④ BashTool 移除黑名单(保护已由闸门接棒)⑤ 测试适配 + 新测试 ⑥ 作业单。提交时按实际文件再核对。
