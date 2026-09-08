# v2 作业:范围围栏——高危目录硬拒 + 工作区外审批放行

## 背景

s03 的闸门只保护 bash。file tools 的路径安全目前压在 PathGuard 硬拦上(s02):访问工作区外**一律拒绝**,连审批的机会都没有。本作业把它升级为课程 s03 文件规则 + 我们自己的加固版:

| 路径落在 | 行为 |
| --- | --- |
| 工作区内 | 直接放行 |
| 高危系统目录(`/etc` 等) | **闸门 1 硬拒**,不询问 |
| 工作区外(非高危) | **闸门 2 询问**,批准后执行 |

**已定路线(用户拍板):裸工具(课程原样)**——四个工具不再调用 `guard.check`,直接在 run() 里 `PathResolver.resolve(path)` 拿路径;安全执法集中在闸门。已知代价并接受:单点执法,绕过闸门的调用路径没有工具级自卫(课程同款);s02 的逐段符号链接解析随 PathResolver 抽取完整保留(含悬空链接),只是从"工具内硬拦"变成"闸门分类的依据"。

## 文件清单

```text
src/main/java/com/jadecode/safety/PathResolver.java  ← Claude 写(机制:路径解析,通读+提问)
src/main/java/com/jadecode/safety/PathGuard.java     ← Claude 写(转型:从硬拦器变为分类器,通读+提问)
src/main/java/com/jadecode/safety/PermissionGate.java ← 你改:两张表各加一条文件规则
src/main/java/com/jadecode/tools/ReadFileTool.java    ← 你改:构造器换 PathResolver,run 用 resolve
src/main/java/com/jadecode/tools/WriteFileTool.java   ← 你改:同上
src/main/java/com/jadecode/tools/EditFileTool.java    ← 你改:同上
src/main/java/com/jadecode/tools/GlobTool.java        ← 你改:walk 基准换 resolver.workspace()
src/main/java/com/jadecode/Main.java                  ← 你改:装配 resolver/guard/gate 三者
src/test/java/com/jadecode/safety/PathResolverTest.java ← Claude 写
src/test/java/com/jadecode/safety/PathGuardTest.java    ← Claude 重写(分类矩阵)
src/test/java/com/jadecode/safety/PermissionGateTest.java ← Claude 加两条用例
src/test/java/com/jadecode/tools/FileToolsTest.java      ← Claude 适配(构造器)
```

## PathResolver 规格(Claude 写)

```java
public class PathResolver {
    private final Path workspace; // 词法绝对归一后的工作区

    public PathResolver(Path workspace) { ... }

    // 类 Python Path.resolve(),但更强:悬空链接也能追(逐段 isSymbolicLink + readSymbolicLink,
    // 不依赖目标存在——toRealPath 在悬空处会抛,这正是 s02 手写走法的来源)
    // 步骤:workspace.resolve(raw) → normalize → 逐段:段是符号链接则读目标(相对目标相对链接
    // 所在目录)继续走,否则保持词法
    public Path resolve(String raw) { ... }

    // Path 重载:同一条管线,只是跳过 workspace.resolve(raw) 一步
    public Path resolve(Path p) { ... }

    // GlobTool 走文件树的基准
    public Path workspace() { ... }
}
```

## PathGuard 规格(转型为分类器,Claude 写)

```java
public class PathGuard {
    private static final List<String> HIGH_RISK = List.of(
            "/etc", "/boot", "/dev", "/sys", "/proc", "/bin", "/sbin",
            "/lib", "/lib64", "/usr", "/var"); // 学习项目清单,不完整,不是完整安全边界(课程对 deny list 同定位)
    // + 用户家目录敏感子目录:~/.ssh、~/.gnupg、~/.aws、~/.config? ← 你来定,作业单不强求

    private final PathResolver resolver;
    private final Path workspaceReal;

    public PathGuard(PathResolver resolver) { ... } // 共享同一个 resolver 实例

    // 闸门 1 用:解析后路径落在高危区内 → "Access to protected directory: {路径}",否则 null
    public String highRiskReason(String raw) { ... }

    // 闸门 2 用:解析后路径在工作区外且非高危 → "Access outside workspace",否则 null
    public String outsideReason(String raw) { ... }

    // 内部共用:resolver.resolve(raw) → 与 workspaceReal 前缀比对 → 分类
    // 悬空链接已由 resolver 追到词法终点,分类基于该终点
}
```

## PermissionGate 规格(你改)

两张表各加一条,其余不动:

```java
// hardRules 追加:
new Rule(List.of("read_file", "write_file", "edit_file"),
        input -> guard.highRiskReason(input.path("path").asText("")))
// askRules 追加:
new Rule(List.of("read_file", "write_file", "edit_file"),
        input -> guard.outsideReason(input.path("path").asText("")))
```

注意:PermissionGate 构造器要**新增 `PathGuard guard` 参数**(现在只有 CommandPolicy + Approval),Main 与测试构造点同步改。glob 不设规则(walk 本来就以 workspace 为根)。

## 四个工具(你改)

每个工具:构造器参数 `PathGuard guard` → `PathResolver resolver`;run() 里:

```java
Path p = guard.check(path);              // 旧
Path p = resolver.resolve(path);         // 新
```

其余逻辑不动。GlobTool:`guard.workspace()` → `resolver.workspace()`(构造器同换)。

## Main 装配(你改)

```java
PathResolver resolver = new PathResolver(cwd);
PathGuard guard = new PathGuard(resolver);   // 分类器,给闸门
PermissionGate gate = new PermissionGate(policy, guard);
// 四个文件工具注入 resolver;BashTool 不变
```

## 坑

1. **`resolve(String)` 的基准必须是 workspace,不是 cwd**——工具路径是工作区相对的,@TempDir 测试里两者不同,相对 cwd 会全崩。这就是 PathResolver 做成实例类的原因。
2. **resolve 要追悬空链接**:用逐段 `isSymbolicLink` + `readSymbolicLink`(读链接本身,不要求目标存在),不能用 `toRealPath`(悬空即抛)。相对链接目标要相对**链接所在目录**解析,不是相对 workspace。
3. **分类基于解析终点**:symlink 指向 `/etc` → 解析终点在 `/etc` → 高危硬拒;symlink 指向工作区外普通目录 → 询问。s02 的防线没有被拆掉,只是从"工具内拒绝"变成"闸门分类的依据"。
4. **闸门构造器签名变化**波及 Main 与 PermissionGateTest,一次性改齐。
5. **工具里的 `guard.check` 必须删干净**——留下任何一个,批准了还是被拦(空交互,本作业的起因)。四个工具 + GlobTool 全过一遍。
6. **单点执法的自觉**:工具不再自卫,任何新代码路径绕过闸门直调工具都能写 `/etc`——这是裸工具路线的已知代价,后续章节(s06 子代理、s11 后台)新增执行路径时必须确认它们都经过 AgentLoop 的闸门。

## 测试预告(Claude 写)

**PathResolverTest**(新,`@TempDir`):① 普通相对路径 → 工作区内路径;② 绝对路径 → 保持绝对;③ `..` 穿透 → 解析到工作区外;④ symlink 指向工作区内 → 解析到目标;⑤ symlink 指向工作区外 → 解析到工作区外;**⑥ 悬空 symlink 指向工作区外 → 仍解析到工作区外**(toRealPath 做不到的那条);⑦ 相对链接目标相对链接所在目录。

**PathGuardTest**(重写为分类矩阵):① 工作区内 → 两个方法都 null;② 绝对 `/etc/passwd` → highRiskReason 命中;③ `../` 越界 → outsideReason 命中;④ symlink 指向 `/etc` → highRiskReason 命中;⑤ symlink 指向工作区外普通目录 → outsideReason 命中;⑥ 悬空 symlink 指向 `/etc` → highRiskReason 命中。

**PermissionGateTest**(追加):① `read_file` 高危路径 → false 且审批未调用;② `read_file` 工作区外路径 + 假审批 true/false → 裁决透传;③ glob 依然直接放行。

**FileToolsTest**(适配):构造器换 `PathResolver`;行为断言不变(工作区内路径一切照旧)。

## 完成标准

`mvn -q test` 全绿 + review 通过。真实验收(REPL):

1. 让 agent `read_file` 一个 `/etc/` 下文件 → 打印 `[blocked]`,模型收到 Permission denied.
2. 让 agent 写一个工作区外的普通文件(如 `/tmp/jadecode-v2-test.txt`)→ `[permission] Access outside workspace` → 输 y → 文件真的出现在 /tmp;输 n → 拒绝
3. 工作区内读写照旧直接通过

验收后清掉 /tmp 的测试产物。

## 验收后增强(2026-09-08 真实验收暴露,已获批准)

1. **B:bash 旁路高危目录**——`cat /etc/hostname` 能绕过文件工具的高危硬拒。修复:问询表加第三条 bash 规则,命令中出现高危目录片段(由 PathGuard 清单派生,单一真相源)即询问。
2. **C:拒绝消息带原因**——统一短句 `"Permission denied."` 让模型盲目试探换路。修复:`PermissionGate.check()` 从 boolean 改为返回拒绝理由(null = 放行),loop 回灌 `"Permission denied: {理由}"`。偏离课程原文,属 harness 层增强。

## 提交预告

① PathResolver(新,独立编译)② PathGuard 转型 + PermissionGate 接 guard + Main 装配 + 四工具切换(环环相扣,可能按实际拆)③ 测试适配 + 新测试 ④ 作业单。提交时按实际文件再核对。
