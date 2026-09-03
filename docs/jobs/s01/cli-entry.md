# s01-9 作业:CLI 入口(Main + run/repl)

## 背景

课程对齐:`s01_agent_loop/code.py` 第 48–56 行(配置与装配)+ 第 120–143 行(REPL 入口)。

前八章的零件在本章完成**总装配**:Env → AppConfig → AnthropicSdkLlmClient → AgentLoop(分发表里放 BashTool)。从此你的 agent 有了嘴巴和耳朵——`run` 一次性任务,`repl` 多轮对话。

picocli(4.7.6,已在 pom)是本章的新面孔:注解式 CLI 库,课程里 argparse 的 Java 对应物。用它只需知道三个注解:`@Command`(声明命令及其子命令/版本/帮助)、`@Parameters`(位置参数)、`Callable<Integer>`(命令执行体,返回进程退出码)。pom 的 shade 插件已把 mainClass 指向 `com.jadecode.Main`,所以**包路径必须精确**。

## 文件清单

```text
src/main/java/com/jadecode/Main.java           ← 你写(根包,命令树 + 装配 + system prompt)
src/main/java/com/jadecode/cli/RunCommand.java ← 你写(一次性任务)
src/main/java/com/jadecode/cli/ReplCommand.java ← 你写(交互循环)
src/test/java/com/jadecode/MainTest.java       ← Claude 写(主类完成后)
```

## Main.java 规格(根包 com.jadecode)

```java
@Command(name = "jadecode", version = "jadecode 0.1.0-SNAPSHOT",
        mixinStandardHelpOptions = true,           // 自动获得 -h/--help 与 -V/--version
        subcommands = {RunCommand.class, ReplCommand.class},
        description = "JadeCode:学习用 CLI coding agent")
public class Main implements Callable<Integer> {
    public static void main(String[] args) {
        // System.exit(CommandLine 执行结果)——picocli 的退出码原样传给 shell
    }

    @Override
    public Integer call() {
        // 无子命令:打印用法(CommandLine.usage),返回 0
    }

    /** 装配:唯一一处把全部零件接起来的地方(public:子命令在 cli 包,需要跨包访问) */
    public static AgentLoop buildLoop() {
        // 1. Env.loadDotEnv() → AppConfig.fromEnv(env)
        // 2. new AnthropicSdkLlmClient(config)
        // 3. 返回 new AgentLoop(client, Map.of("bash", new BashTool()),
        //                      systemPrompt(cwd), config.maxTokens())
        // cwd 取启动时当前目录:Path.of("").toAbsolutePath()
    }

    /** 纯函数:拼出课程原文的 system prompt(参数化 cwd 是为了可测试) */
    static String systemPrompt(Path cwd) {
        // 课程原文:"You are a coding agent at {cwd}. Use bash to solve tasks. Act, don't explain."
        // 把 {cwd} 换成参数,返回拼接结果
    }
}
```

要点:

- **装配集中在 `buildLoop()`**:s02 起每章新增工具时只改这一处,两个子命令不动。
- `main` 里 `System.exit(picocli 的返回值)`:退出码(参数错误=2、命令 call 的返回值)原样传给 shell——脚本能检测成功与否。

## RunCommand.java 规格(com.jadecode.cli)

```java
@Command(name = "run", description = "执行一次性任务:给出一条指令,跑完打印最终回复")
public class RunCommand implements Callable<Integer> {
    @Parameters(paramLabel = "PROMPT", description = "任务描述(含空格请加引号)")
    private String prompt;

    @Override
    public Integer call() {
        // 1. AgentLoop loop = Main.buildLoop()
        // 2. history = new ArrayList<>();history.add(Message.userText(prompt))
        // 3. loop.run(history)
        // 4. 打印 history 最后一条消息的 extractText()
        // 5. catch LlmException → System.err 打印错误,返回 1;正常返回 0
    }
}
```

### run 行为表

| 情形 | 行为 |
| --- | --- |
| 正常完成 | 打印最后一条 assistant 消息的 extractText(),退出码 0 |
| LlmException | System.err 打印错误信息,退出码 **1**(一次性任务:失败必须是非零退出,脚本才能感知) |
| 参数错误(缺 prompt 等) | picocli 自动报错,退出码 2(不用你管) |

## ReplCommand.java 规格(com.jadecode.cli)

```java
@Command(name = "repl", description = "进入交互循环:多轮对话,输入 q 或 Ctrl+D 退出")
public class ReplCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        // 1. AgentLoop loop = Main.buildLoop()
        // 2. BufferedReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
        // 3. List<Message> history = new ArrayList<>();   ← 必须在循环外!
        // 4. 打印欢迎语,然后 while (true):
        //    System.out.print("s01 >> ");flush
        //    line = reader.readLine()
        //    line == null → break(EOF/Ctrl+D)
        //    IOException → 打印后 return 1
        //    query = line.strip();为空 / "q" / "exit" → break
        //    history.add(Message.userText(query))
        //    try { loop.run(history);打印最后一条的 extractText() }
        //    catch LlmException → System.err 打印,不退出循环
        // 5. 循环结束后 return 0
    }
}
```

## 四个坑

1. **REPL 的 history 必须在循环外声明**——多轮对话的连续性全靠这一个列表跨迭代存活;放循环里,每轮都是失忆的新人。
2. **`readLine()` 的两种结束**:返回 `null` 是 EOF(用户按 Ctrl+D),必须 break;抛 IOException 是输入流坏了,打印后 `return 1`。别用 Scanner 做交互循环——EOF 与编码处理更别扭,`BufferedReader` + UTF-8 是标准答案。
3. **两种命令对 LlmException 的态度不同**:run 失败 → 退出码 1(一次性任务,失败要能被脚本检测);repl 失败 → 打印错误继续循环(交互会话要韧)。这是课程直接崩溃行为之上的刻意改进。
4. **最终文本用 `extractText()`**——s01-2 写的方法就是为这一刻准备的,别手写遍历 content。

## 测试预告(MainTest,Claude 写,5 个用例)

CLI 的可测部分在于**纯解析**与**纯函数**(真实装配由 s01-10 冒烟验收):

1. `execute("--version")` 返回 0,不触发任何任务执行
2. `execute("--help")` 返回 0
3. `parseArgs("run", "hello world")` → 解析出的子命令是 RunCommand、位置参数值是 "hello world"(parseArgs 只解析不执行,安全)
4. `systemPrompt(Path.of("/home/fishandrice"))` → 字符串含该路径与 "Use bash to solve tasks. Act, don't explain."
5. 空参数解析 → 只有 Main 自己,无子命令

## 完成标准

1. `mvn -q test` 全绿 + review 通过
2. `mvn -q package` 后 `java -jar target/jadecode.jar --version` 正常输出(你跑)

真实对话(真的调用 DeepSeek)留给 s01-10 冒烟,本章不要提前用真实 API 跑。

## 提交预告

5 批:① Main ② RunCommand ③ ReplCommand ④ MainTest ⑤ 作业单。另有未提交的 `docs/notes/s01/tool_json.md` 修订(上次补充的分层作用)作为 ⑥。提交时按实际文件再核对。
