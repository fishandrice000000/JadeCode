# s02 作业:文件工具四件套 + PathGuard 注册

## 背景

课程对齐:`s02_tool_use/code.py`。s01 的 agent 只能"看"(bash 输出),本章起获得"手":读、写、编辑、搜索文件。**主循环一行不改**,只做两件事:新增四个工具 + 注册进分发表——这正是 s01-9 把装配集中在 `buildLoop()` 的第一次兑现。

分工说明:路径围栏是安全基础设施而非 agent 开发重点,**PathGuard 由 Claude 已写好**(见 `src/main/java/com/jadecode/safety/PathGuard.java`,三层防线:词法归一化 → 逐段解析符号链接 → 与真实工作区比对;等价于课程 `safe_path` 的 `.resolve() + is_relative_to`)。你**通读它并提问**(代替 review),其余四个工具与注册由你写。

## 文件清单

```text
src/main/java/com/jadecode/safety/PathGuard.java    ← Claude 已写,你通读
src/main/java/com/jadecode/tools/ReadFileTool.java  ← 你写
src/main/java/com/jadecode/tools/WriteFileTool.java ← 你写
src/main/java/com/jadecode/tools/EditFileTool.java  ← 你写
src/main/java/com/jadecode/tools/GlobTool.java      ← 你写
src/main/java/com/jadecode/Main.java                ← 你改:buildLoop 注册新工具
src/test/java/com/jadecode/safety/PathGuardTest.java ← Claude 写
src/test/java/com/jadecode/tools/FileToolsTest.java  ← Claude 写
```

## 公共设计

- 四个工具都实现 `Tool` 接口,构造器注入同一个 `PathGuard` 实例。
- 每个 execute 的骨架:`Path p = guard.check(路径参数)`(越界抛 `IllegalArgumentException`)→ 干活 → 返回结果字符串;整体 catch `Exception` → `"Error: " + e.getMessage()`(课程同款,也满足"绝不抛异常"契约)。
- inputSchema 用现有 `ToolSchema.objectSchema`——integer 类型本来就在 Map 值里写字符串 `"integer"`,无需改 ToolSchema。
- 工具描述照抄课程(模型依赖它理解用法)。

## 各工具规格

### ReadFileTool

```java
public class ReadFileTool implements Tool {
    private final PathGuard guard;
    public ReadFileTool(PathGuard guard) { ... }

    // name() 返回 "read_file";description() 返回 "Read file contents."
    // inputSchema():properties {path: string, limit: integer},required [path]
    // execute(ObjectNode input):
    //   1. guard.check(input.get("path").asText())  ← path 缺失由契约测试兜住?不:先判空
    //   2. 读全部行(Files.readAllLines(path, UTF_8))
    //   3. limit 非 null 且小于行数 → 只留前 limit 行,末尾补 "... (N more lines)"(课程格式)
    //   4. 返回 String.join("\n", lines)
}
```

### WriteFileTool

```java
    // name() 返回 "write_file";description() 返回 "Write content to a file."
    // inputSchema():properties {path: string, content: string},required [path, content]
    // execute:
    //   1. guard.check(path)
    //   2. Files.createDirectories(p.getParent())(父目录不存在时创建)
    //   3. Files.writeString(p, content, UTF_8)
    //   4. 返回 "Wrote N bytes to {原始 path}"——N 是 UTF-8 字节数,不是字符数
```

### EditFileTool

```java
    // name() 返回 "edit_file";description() 返回 "Replace exact text in a file once."
    // inputSchema():properties {path: string, old_text: string, new_text: string},required 全部三个
    // execute:
    //   1. guard.check(path)
    //   2. 读全文;old_text 不在其中 → 返回 "Error: text not found in {原始 path}"
    //   3. 只替换第一次出现:indexOf 定位 → substring 拼接(见坑 2)
    //   4. 写回,返回 "Edited {原始 path}"
```

### GlobTool

```java
    // name() 返回 "glob";description() 返回 "Find files matching a glob pattern; ** matches recursively."
    // inputSchema():properties {pattern: string},required [pattern]
    // execute:
    //   1. PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + pattern)
    //   2. Files.walk(workspace)(try-with-resources)逐条 relativize 后让 m 匹配
    //   3. 每个匹配项再过一遍 guard.check(防符号链接目录被 ** 穿透)——越界就跳过
    //   4. 排序;超过 200 条截断并附 "... (more matches omitted; narrow the pattern)"
    //   5. 空结果返回 "(no matches)"
    // 注意:workspace 从 guard 拿?PathGuard 目前没暴露——给 GlobTool 加个 workspace 访问器
    // 或者 GlobTool 构造器同时注入 guard 与 workspace(你的选择,作业单不强求方案)
```

## 坑

1. **execute 里参数判空**:`input.get("path")` 可能是 null(模型不按 schema 出牌,老规矩),先判空返回 "Error: Missing required parameter: path",再 `asText()`。content/old_text/new_text 同理。
2. **edit 只替换一次 + 字面量匹配**:`String.replace(CharSequence, CharSequence)` 会替换**全部**;`replaceAll` 把 old_text 当**正则**——`"a.b"` 会匹配 `"axb"`。正确做法:`indexOf(old_text)`(找不到返回 -1)→ `text.substring(0, i) + new_text + text.substring(i + old_text.length())`。
3. **WriteFile 的字节数**:返回消息用 `content.getBytes(StandardCharsets.UTF_8).length`,不是 `content.length()`——中文字符 3 字节,两者不一致,课程消息也是字节数。
4. **read 的 limit 语义是"行数"**:课程 `lines[:limit]`,不是字符数。limit 为 0 或负数时按不截断处理(课程用 `if limit and limit < len(lines)` 的 Python 真值语义,Java 等价物是 `limit != null && limit > 0 && limit < lines.size()`)。
5. **glob 的 `**` 会跟随符号链接目录**——Python 课程如此(因此每个 match 要 resolve 过滤),Java 的 Files.walk 默认**不跟随**链接,反而安全;但为对齐课程语义 + 双保险,匹配项再过 guard.check 过滤(越界就跳过,不是报错)。
6. **buildLoop 只改一处**:把 `Map.of("bash", new BashTool())` 扩成五个条目(顺序无所谓);`PathGuard` 实例建一次、四个工具共享。BashTool 不动——课程 s02 给 bash 设的 `cwd=WORKDIR` 与 JVM 启动目录等价,无需修改。

## 测试预告(Claude 写)

**PathGuardTest**(用 JUnit `@TempDir` 建临时工作区):
① 工作区内合法路径 → 通过;② 绝对路径 `/etc/passwd` → 拒;③ `../` 穿透 → 拒;④ `a/../../b` 归一化后越界 → 拒;⑤ symlink 指向工作区外 → 拒;⑥ symlink 指向工作区内 → 通过;⑦ 悬空 symlink 指向外 → 拒。

**FileToolsTest**(`@TempDir` + 真工具):
① read:写入临时文件 → 读回内容一致;limit=1 → 首行 + "... (N more lines)";② write:新文件 + 父目录不存在 → 创建成功,返回含字节数;③ edit:替换第一次出现;old 不存在 → "Error: text not found";④ glob:建两个匹配文件 → 排序输出;无匹配 → "(no matches)"。

## 完成标准

`mvn -q test` 全绿 + review 通过。可选真实验收(你决定):REPL 里让 agent"创建一个 hello.txt 并读回内容"。

## 提交预告

① PathGuard(我已写好,先行提交)② ReadFileTool ③ WriteFileTool ④ EditFileTool ⑤ GlobTool ⑥ Main 注册 ⑦ PathGuardTest ⑧ FileToolsTest ⑨ 作业单。提交时按实际文件再核对。
