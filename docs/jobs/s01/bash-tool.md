# s01-6 作业:BashTool(第一个真实工具)

## 背景

课程对齐:`s01_agent_loop/code.py` 第 71–83 行的 `run_bash`。这是 `Tool` 接口的第一个实现,也是 agent 拥有的第一个能力。

Python 版逐个机制翻译成 Java:

| Python | Java |
| --- | --- |
| `shell=True` | `new ProcessBuilder("/bin/sh", "-c", command)`(按参数数组传,免拼接错误;别用 `Runtime.exec`) |
| `timeout=120` | `process.waitFor(120, TimeUnit.SECONDS)` 返回 false 即超时,必须 `destroyForcibly()` 杀进程,否则留孤儿进程 |
| `stdout + stderr` 手拼 | `redirectErrorStream(true)`,stderr 流入 stdout,读一次 |
| `errors="replace"` | `new String(bytes, StandardCharsets.UTF_8)`,解码器遇坏字节默认替换 U+FFFD,行为一致 |
| 黑名单子串匹配 | 原样保留(见坑 4) |

## 文件清单

```text
src/main/java/com/jadecode/tools/BashTool.java        ← 你写
src/test/java/com/jadecode/tools/BashToolTest.java    ← Claude 写(主类完成后)
```

## BashTool.java 规格

```java
package com.jadecode.tools;

public class BashTool implements Tool {
    private static final List<String> DANGEROUS = List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/");
    private static final int MAX_OUTPUT = 50000;

    private final long timeoutSeconds;

    public BashTool() { this(120); }
    public BashTool(long timeoutSeconds) { ... }   // 构造器注入:测试传 2 秒测超时,不用真等 120 秒

    @Override public String name() { return "bash"; }
    @Override public String description() { return "Run a shell command."; }  // 课程原文
    @Override public ObjectNode inputSchema() {
        return ToolSchema.objectSchema(Map.of("command", "string"), List.of("command"));
    }
    @Override public String execute(ObjectNode input) { ... }   // 取 command → run
    private String run(String command) { ... }
}
```

### execute 行为表

| 情形 | 返回 |
| --- | --- |
| input 缺 command(null) | `"Error: Missing required parameter: command"` |
| 命中黑名单(子串匹配) | `"Error: Dangerous command blocked"` |
| 超时(waitFor 返回 false,destroyForcibly 后) | `"Error: Timeout (" + timeoutSeconds + "s)"` |
| 输出 strip 后为空 | `"(no output)"` |
| 输出超过 50000 字符 | 截断到 50000 |
| 进程启动失败(IOException) | `"Error: " + 异常消息` |

## 四个坑

1. **契约测试:input 里可能没有 command**(模型不按 schema 出牌时)。`input.get("command")` 是 null,直接 `asText()` 会 NPE——违反"绝不抛异常"契约。先判空返回错误字符串。
2. **超时消息跟着配置走**:`BashTool(2)` 超时应报 `"Error: Timeout (2s)"`,不要写死 120。
3. **读输出放 waitFor 之后**——顺序是"等完再读"。反过来的坑:边读边等,`readAllBytes` 会阻塞到进程结束,timeout 保护失效。**已知局限**(写进注释):子进程输出超过管道缓冲(约 64KB)会写满阻塞、拖到超时——课程 Python 版内部处理了,s01 接受此局限(课程场景输出远小于 64KB),后续章节用虚拟线程升级。
4. **黑名单子串匹配的误伤**:"sudo" 会拦掉 `grep sudo 日志` 这类无辜命令。跟随课程(注释里说明),s03 权限章升级成正则 + 交互审批。

## 测试预告(BashToolTest,Claude 写,8 个用例)

① `echo hello` → `"hello"`;② `true` → `"(no output)"`;③ `echo err 1>&2` → `"err"`(stderr 合并);④ 未知命令 → 输出含 `"not found"`(shell 存在、进程正常启动,stderr 报错文本,不是 `"Error:"`);⑤ 黑名单 2 例(`sudo ls`、`rm -rf /tmp/x`)→ blocked;⑥ `new BashTool(2)` + `sleep 5` → `"Error: Timeout (2s)"`;⑦ 输出 60000 字符 → 恰为 50000 个 x;⑧ 空 input → `"Error: Missing required parameter: command"`。

## 完成标准

`mvn -q test` 全绿 + review 通过。
