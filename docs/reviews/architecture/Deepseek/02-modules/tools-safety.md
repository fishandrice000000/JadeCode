# 模块报告：工具与安全边界（`tools/` 的工具部分、`safety/`）

覆盖：`Tool`、`ToolSchema`、`BashTool`（同步/后台分派，进程细节见背景报告）、文件工具（`ReadFileTool`/`WriteFileTool`/`EditFileTool`/`GlobTool`）、`TaskTools`、`CronTools`、`SubagentTool`、`TodoWriteTool`、`LoadSkillTool`、`CompactTool`；`safety/` 的 `CommandPolicy`、`PathGuard`、`PathResolver`、`PermissionGate`、`Hooks`。

## 1. 模块职责与安全模型

- `Tool` 是统一的工具协议：名字、描述、参数、`execute`。
- `safety` 分三层：`PathResolver`（把字符串路径解析成可操作路径）、`PathGuard`（高危/越界分类）、`CommandPolicy`（命令黑名单/破坏性/高危目录），最终由 `PermissionGate` 决定“硬拒 / 询问用户 / 放行”。
- `Hooks` 提供工具执行前后的可插拔检查与展示。

**权限模型的正确之处**：是否允许询问用户，只看 Harness 放进 `ExecutionContext` 的 `source`，不看线程身份、不看模型自称（`PermissionGate.java:96-112`）。`PreToolUse` 的返回值就是拒绝原因，会变成对应 `tool_use_id` 的 `tool_result`，不会静默跳过。

## 2. 设计上合理的地方

- **工具不各自做路径安全判断**：文件工具只调用 `resolver.resolve` 拿路径，是否越界交给闸门；`GlobTool` 只遍历工作区、不跟随符号链接目录（`GlobTool.java:94-95`）。
- **Hooks 是显式注册的**：事件名与参数名用常量（`Hooks.java:21-41`），减少拼写错误；顺序有注释说明。
- **`CompactTool` 没有副作用**：它只返回固定文案，真正压缩在整批结果回灌后由循环触发（`CompactTool.java:59-87`），避免“压缩拆开工具交换”。
- **`TaskTools`/`CronTools` 不暴露任意状态写入**：参数形状先校验，`owner/status/path/pending_delivery` 等由 Harness 控制，模型不能填（`TaskTools.java:108-128`、`CronTools.java:38-64`）。

## 3. 已证实的问题

### ARCH-027（P1，实际缺陷）：多层符号链接没有被完全解析，路径围栏存在绕过路径

**现状**：`PathResolver.walkSegments`（`PathResolver.java:67-87`）按段消费路径：如果**当前正在消费的那一段**是符号链接，就读出目标并从目标继续；否则把它当作普通目录拼上去。

问题出在两类情况：

1. 解析发生后，`next` 本身可能仍是符号链接，但代码不会重新检查它，而是直接用它作为新的 `base` 去处理剩余段。
2. 段已经消费完时，直接返回当前 `base`（可能是符号链接）。

**具体反例（静态推演）**：设工作区内有 `a -> b`、`b -> /etc`（两跳链接），调用 `read_file("a/passwd")`：

- `base/a` 是符号链接 → 解析为 `base/b`；
- 剩余段 `passwd` 拼到 `base/b` 上得到 `base/b/passwd`，操作系统会透过 `b` 指向 `/etc`，于是实际是 `/etc/passwd`，但 `Files.isSymbolicLink(base/b/passwd)` 为 false；
- 返回的路径在**词法上**仍在工作区内，`PathGuard.outsideReason` 的 `p.startsWith(workspaceReal)` 判定为“工作区内”，因此不询问、直接放行。

对比：单跳链接（`a -> /etc`，读 `a/passwd`）是能正确解析到 `/etc/passwd` 并被 `highRiskReason` 硬拒的——所以现有测试很容易“看起来是对的”。这也解释了为什么既有测试没有暴露它。

**影响条件**：工作区内存在两跳及以上的符号链接，且其最终目标落在工作区外/高危目录。这类链接可能来自用户自己的仓库、其它工具或历史遗留，不一定要模型当场创建。

**影响范围**：`read_file`/`write_file`/`edit_file` 的路径围栏（读、写、覆盖都被影响），以及任何依赖 `resolver.resolve` 判断归属的地方。

**证据强度**：已证实（可由代码逐段推出）。

**建议**：把“解析一段”改成“解析到不动点”——对 `base` 与拼出的 `candidate` 都循环检查 `isSymbolicLink` 并展开（设一个较小的最大跳数，例如 40，防环），直到不再是链接。更稳妥的做法是在 `resolve` 末尾对最终路径再调用一次“解析链直到不动点”。同时在 `PathGuard` 里对解析结果再次 `toRealPath`（在文件存在时）作为纵深防御。收益是堵住一个真实的边界绕过；代价是解析逻辑与测试需要重写，且要处理悬空链接与链接环。

### ARCH-010（P2，实际缺陷/一致性）：工具 schema 与运行时校验分散，契约不一致

**现状**：

- 默认 schema 由 `ToolSchema.objectSchema` 生成，**不设置** `additionalProperties:false`（`ToolSchema.java:33-64`）；只有 `TaskTools` 与 `CronTools` 手动补上（`TaskTools.java:115`、`CronTools.java:62`）。
- 运行时“必填参数”检查在每个工具里各写一遍：`BashTool`、`ReadFileTool`、`WriteFileTool`、`EditFileTool`、`GlobTool`、`LoadSkillTool` 都循环 `required()` 并返回 `Error: Missing required parameter`；`TaskTools` 用自己的 `validate()`；`CronTools` 用自己的字段循环。
- `Tool.properties()` 返回 `Map<String,String>`（类型名字符串），表达不了数组/嵌套，需要数组的工具 override `inputSchema()` 并手写 JSON。

**为什么是问题**：同一份“参数契约”存在 schema、`required()`、运行时校验三份表述，容易漂移；新增工具时忘记某一份不会编译报错，只会在特定输入下表现不一致。`additionalProperties` 的默认不一致还会让模型以为可以传多余字段（实际被部分工具拒绝、部分忽略）。

**建议**：在 `Tool` 上提供一个小的“参数校验”默认实现（例如按 `properties()/required()` 自动判型并生成错误文案），让简单工具不再手写循环；把 `additionalProperties:false` 的决定统一（要么都设、要么都不设并在文档说明）。注意保持既有错误文案（`Error: ...`）不变，避免影响模型行为与已有测试。

### ARCH-011（P2，有依据的潜在风险）：单个工具的意外异常会让整批乃至整个任务失败

**现状**：`AgentLoop.executeTools` 的 `for` 循环外只有一个 `catch (RuntimeException)`（`AgentLoop.java:340-347`）。任何一个工具（或 `PostToolUse` hook）抛出运行异常，循环立刻中止，给剩余调用补 `Error: Tool batch interrupted...`，然后把 `failure` 带回 `SessionRuntime`，由 `runTask` 抛出、任务失败（`SessionRuntime.java:275-280`）。

**为什么是问题**：工具是外部世界接口，最容易出现非预期异常（磁盘错误、进程启动失败、第三方库 bug）。当前设计把“一个工具坏了”升级成“整个任务报废”，而且失败时可能已经产生了副作用。对比：工具**返回**的 `Error:` 文本是交给模型处理的（合理），但**抛出**的异常不是。

**影响条件**：任一工具实现抛 RuntimeException；hook 抛异常（例如日志 hook 出错）也会命中。

**建议**：区分“可预期失败（返回文本）”与“意外异常”。对意外异常，至少做到按工具隔离：把异常转成该调用的 `Error: ...` 文本、继续执行同批其它工具，并让模型决定下一步；只在“工具链已经不可信”时才让任务失败。若产品坚持“意外异常即任务失败”，也应把 `PostToolUse` hook 的异常与工具实现的异常分开处理，避免展示层把任务带崩。收益是显著提升长任务韧性；代价是要重新定义失败语义，并更新相关测试与注释。

### ARCH-015（P3，有依据的潜在风险）：`Hooks` 的顺序、短路与线程安全是隐式契约

**现状**：`Hooks.trigger` 按注册顺序执行，遇到第一个非 null 返回值立即停止（`Hooks.java:92-104`）；`register` 的表不是线程安全，注释明确“不要边执行边从其它线程增加函数”（`70-83`）。`Main` 的注册顺序因此有语义：`membershipHook` 必须在 `permissionHook` 之前，否则未知工具会先进权限闸门（`Main.java:209-211` 与 `membershipHook` 注释 `240`）。

**为什么是风险**：这套语义没有类型或断言承载，只写在注释里。三个可观察后果：①同一个事件的不同用途互相耦合（“先判成员再判权限再打日志”），新增 hook 时必须选对位置；②`PostToolUse`/`Stop` 的“返回非 null 就短路”意味着后面注册的 hook 不会跑，与 `PreToolUse` 的“拒绝”语义混在同一套规则里；③`SubagentRuntime` 触发 `POST_TOOL_USE` 时不带 `PARAM_CONTEXT`（`323`），如果某个 hook 默认该参数总存在，切换父子时就会出问题。

**影响条件**：新增/重排 hook；或在父子之间复用同一个 hook 而参数集不同。

**建议**：①为事件约定一个最小参数集并用断言/测试守护（例如 `PreToolUse` 必须带 `block` 与 `context`）；②把“短路语义”文档化到 `Hooks` 的每个事件常量上，或改成“PreToolUse 收集全部拒绝原因、取第一条”的显式规则；③若未来 hook 需要并发注册，换成不可变注册（一次性构建）。收益是消除隐式顺序依赖；代价是可能需要调整现有 hook 的写法。

### ARCH-008（P2，实际缺陷）：安全层与记忆层直接写 `System.out/err`，没有日志抽象

**现状**：`PermissionGate.checkWith` 用 `System.out.println(Ansi.red("[blocked] ..."))` 打印硬拒（`PermissionGate.java:125`）；`MemoryManager`、`MemoryRecallService`、`MemoryStore` 用 `System.err.println("[Memory] ...")` 打印召回/提取/整理/坏文件（`MemoryManager.java:58,93,96,103,113`、`MemoryRecallService.java:93`、`MemoryStore.java:96,106,328`）；`AnthropicSdkLlmClient` 用 `System.err` 打印未知块（`AnthropicSdkLlmClient.java:173`）。

**为什么是问题**：

1. 分层被打破：安全层的职责是“判定”，不是“展示”；
2. 无法控制详细程度、无法重定向、难以在测试里断言；
3. 与 TUI 的输出重画机制耦合（现在靠 `TerminalDisplay` 在进程级替换 `System.out/err` 兜底，见 `02-modules/cli-config-build.md`）；
4. `pom.xml` 已经引入了 `slf4j-simple`，但只服务于 SDK，项目自己的诊断没有走它。

**建议**：引入一个极薄的 `Reporter`/`Diagnostics` 接口（`info/warn/error`），由 `Main` 注入；安全层只报告“拒绝事件”，由展示层决定怎么打印。不要一上来引入完整日志框架；一个接口加一个默认实现即可。收益是可测试、可控制输出；代价是需要改动若干调用点。

## 4. 需要明确声明的边界（不是缺陷，但要说清楚）

### ARCH-028（P2，有依据的潜在风险）：bash 的拦截是子串/关键词规则，与“任意 shell”的能力有落差

**现状**：`CommandPolicy` 用子串黑名单（`rm -rf /`、`sudo`、`shutdown`、`mkfs`、`dd if=`、`> /dev/sda`，`CommandPolicy.java:13-14`）、一个破坏性正则（识别 `rm`/`del` 作为命令词，`15-16`）和关键词（`rm `、`> /etc/`、`chmod 777`，`17`）。`checkProtectedDirs` 用 `command.contains(dir + "/")`（`65-72`）。

这些规则拦不住等价写法（`rm -r -f /`、`find / -delete`、`python -c "shutil.rmtree('/')"`），也无法理解 shell 语法。代码注释已明确声明这是“学习项目清单，不是完整安全边界”。

**建议**：不要把当前规则当作安全围栏来宣传或依赖。长期看更合理的做法是显式区分“可信/自动模式”与“逐条审批模式”，让用户对“bash 到底被信任到什么程度”有明确选择；对真正危险的操作，宁可整体拒绝 bash（或加白名单），也不要维持“看起来在拦”的假象。这属于产品级安全取舍，需要用户裁决，不能由实现者单方面扩大限制。

## 5. 反例与缓和因素

- ARCH-027 需要工作区内存在多跳链接；不是“任何 read_file 都能越界”。但它是真实边界缺口，且单跳测试通过会给人错误安全感。
- ARCH-028 的影响被“bash 是主工具、用户本来就在授权”部分抵消；但自动化（cron）场景下没有人工复核，风险更高。
- 文件工具“先整读、替换、再整写”对 `edit_file` 是原子的（`EditFileTool.java:80-113`），但 `write_file` 直接覆盖目标文件，写入中断可能截断原文件（ChatGPT 报告亦提到同类点）。这一点我未单独编号，因为它与“有备份/临时文件再替换”的取舍有关，建议在实现 ATOMIC 写入时一并处理。

## 6. 修改顺序建议

1. ARCH-027（路径解析）优先：安全边界缺口，且修复局部。
2. ARCH-011（按工具隔离异常）：提升长任务韧性，改动集中在一个方法。
3. ARCH-010（schema/校验统一）、ARCH-008（日志抽象）：保行为收敛。
4. ARCH-028：需要用户先裁决“bash 信任模型”，只做设计讨论，不擅自收紧。
