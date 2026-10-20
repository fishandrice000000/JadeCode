# 模块报告：CLI、配置与构建（`Main`、`cli/`、`config/`、`util/`、`pom.xml`、`jade`）

覆盖：`Main`、`ReplCommand`、`RunCommand`、`ConsoleInput`、`TerminalDisplay`、`Env`、`AppConfig`、`Ansi`、`Json`、`pom.xml`、`jade`、`README.md`。

## 1. 设计上合理的地方

- **资源关闭顺序显式且有理由**：`ReplCommand`/`RunCommand` 用 try-with-resources，并且**故意把 `TerminalDisplay` 放在第一位**，使它在最后关闭，让任务清理日志仍能正常显示；注释说明了这个顺序（`ReplCommand.java:30-34`、`RunCommand.java:41-45`）。
- **终端只有一个读取者**：`ConsoleInput` 记录创建线程 `inputOwner`，非该线程读 stdin 直接抛 `IllegalStateException`（`ConsoleInput.java:34,207-209`）。执行线程通过 `pending` future 等待，绝不自己 `readLine`。
- **审批与任务输入不混淆**：`readQuery`/`await` 只把“整条输入等于 y/n 或空行”当作审批答复，粘贴的多行任务中的单独 `y` 不会被截出来批准危险命令（`ConsoleInput.java:126-200`）。这是很实际的安全细节。
- **构造失败也恢复终端**：`TerminalDisplay` 构造函数里如果 JLine 初始化到一半失败，会先恢复 `System.out/err` 再关闭终端（`TerminalDisplay.java:94-100`），避免返回 shell 后键盘仍在错误模式。
- **非交互自动降级**：`System.console() == null` 时不劫持输出、不启用行编辑、不输出 ANSI（`TerminalDisplay.java:65-72`、`Ansi.java:11`）。管道运行保持纯文本。
- **构建隔离干净**：Maven toolchain 只对本项目用 JDK 21（`pom.xml`），`jade` 脚本只在单次进程内指向 JDK 21；shade 合并 JLine 的 `META-INF/services` 并设置 Main-Class。这解决了“本机默认 Java 8”的现实问题。

## 2. 已证实的问题

### ARCH-001（P2，实际缺陷）：配置边界校验缺失，`.env.example` 的空 key 会绕过 fail-fast

**现状**：`Env.get` 的语义是“`.env` 值非 null 就返回它，否则回退系统环境变量”（`Env.java:88-91`）；`required` 只判断“结果是否为 null”（`100-106`）。而 `.env.example` 里写的是 `ANTHROPIC_API_KEY=`（空值）。用户复制成 `.env` 后如果忘填，`Env.load` 会把 key 映射到空字符串（`Env.java:39-51`），`vars.get` 返回 `""`（非 null），于是 `required` **不抛异常**，`AppConfig.apiKey` 变成空字符串。

`AppConfig.getApiKeyFrom` 的 `catch (IllegalStateException)` 本意是“缺 key 时打印‘复制 .env.example 并填入’再抛出”（`AppConfig.java:31-38`）；由于空字符串不触发异常，这条友好指引也不会出现。真正的失败被推迟到第一次 SDK 调用（"复制 .env.example" 的提示成为死代码路径）。

同类问题：`MAX_TOKENS` 只做 `Integer.parseInt`，`0` 或负数可以通过（`AppConfig.java:53-63`），随后作为 `maxTokens` 发给模型。

**影响条件**：任何“复制模板但没填值”的首次运行；这是最常见的初次使用路径。

**影响范围**：启动诊断质量；`maxTokens ≤ 0` 可能让服务端直接报 400，任务在第一次请求就失败。

**证据强度**：已证实（静态推理：空串非 null）。

**建议**：`Env.required` 把“空白字符串”也当缺失（throw），`Env.get` 对空白返回 null；`AppConfig` 增加 `maxTokens > 0` 校验（并给上限如 `≤ 200_000`）。收益是把最常见的初次失败变成清晰提示；代价是行为变化（原来是“静默接受空值”），需要确认没有依赖空值合法的场景。附带地，`.env.example` 的注释可以提示“必须填写”。

### ARCH-003（P2，实际缺陷）：工具注册表使用 `Map.of`/`Map.copyOf`，发送给模型的工具顺序不确定

**现状**：`Main.buildLoop` 里子工具表用 `Map.of(...)`（`Main.java:172`），父工具表先建 `LinkedHashMap` 再 `Map.copyOf(registered)`（`189-206`）。`AgentLoop.request` 与 `SubagentRuntime.run` 都用 `List.copyOf(tools.values())` 作为请求里的 `tools` 数组（`AgentLoop.java:268-269`、`SubagentRuntime.java:210-211`）。

**为什么是问题**：`Map.of`/`Map.copyOf` 返回的是 JDK 内部不可变映射，其**迭代顺序未指定**，实现上还会用每 JVM 一次的随机 salt；元素超过 2 个时，不同 JVM 运行之间顺序可能不同。于是每次启动，发给模型的工具数组顺序可能不一样。

**影响**：

- 调试/复盘时“同一提示词、同一历史”却出现不同的请求字节；
- 对 DeepSeek 的自动前缀缓存（以及任何按前缀缓存的服务端）而言，工具数组属于请求前缀的一部分，顺序抖动会降低缓存命中、抬高成本。

**证据强度**：已证实（迭代顺序未指定）；成本影响待验证（V-2，取决于服务端缓存实现）。

**建议**：工具注册表在装配时就固定顺序并贯穿到请求——例如 `Main` 保留一个 `List<Tool>`，`AgentLoop`/`SubagentRuntime` 接收 `List<Tool>` 或按 `LinkedHashMap` 的插入顺序发送（`Collections.unmodifiableMap(new LinkedHashMap<>(registered))`）。收益是请求可复现、缓存友好；代价极小。注意：不要用 `Map.copyOf`。

### ARCH-014（P3，可选优化）：`Main` 同时承担装配、提示词与 hook 实现

**现状**：`Main`（406 行）做三件事：picocli 入口与 `buildLoop` 装配（`113-228`）、10 个 hook 的 lambda 实现（`232-355`）、父/子 system prompt 拼接（`366-405`）。

**为什么值得改**：提示词是产品行为、hook 是策略、装配是对象图，三者变更频率与责任人不同。混在一起会让 `Main` 成为每次新工具的必经编辑点，也让“模型看到什么”散落在代码里，无法单独审阅或本地化。

**建议**：至少拆出 `Prompts`（把 system prompt 文本集中，最好放到资源文件）与 `DefaultHooks`（把 hook 实现搬出）。装配仍留在 `Main`/`Bootstrap`。收益是可读与非开发者可审阅提示词；代价是纯搬运，低风险。

### ARCH-019（P3，文档一致性）：README 的章节映射列出了尚未实现的包与类名

**现状**：`README.md` 的“章节 ↔ 包映射”写了 s13 `team/`、s14 `mcp/`、s15 `agent/AsyncEventLoop`、`context/SystemPromptBuilder`、`llm/RetryPolicy`、s16 `workflow/`、s17 `goal/`；持久化一栏还写了 `.mailboxes/`。这些包/类在当前源码中**都不存在**（`git ls-files` 与 `find` 可核实）。

**影响**：读者无法区分“已定的设计”与“未来可能的实现”；对初学者尤其容易误以为这些能力已经具备。进度列表本身是诚实的（s13–s17 标未完成），所以这不是“假完成”，而是“计划与现状混排、缺少标记”。

**建议**：把这些条目标为“计划 / 待实现 / 仅为课程对照”，或从映射表移到一个单独的“路线图”小节；`.mailboxes/` 同理标注为未来。收益是文档与源码一致；代价极低。相关：`02-modules/` 其它报告也引用了这份文档。

## 3. 其它观察（不单列编号）

- **`TerminalDisplay` 进程级替换 `System.out/err`**：这是为了让现有 hook/SDK/记忆的 `println` 都能被 JLine 重画（`TerminalDisplay.java:29-32,92-93`）。它工作，但属于全局可变状态：任何在 `open()` 之前缓存了 `System.out` 的代码会绕过重画；测试若在真实终端下运行也会被影响。当前 `Main`/`ReplCommand` 的装配顺序避免了这些问题。若将来引入日志抽象（ARCH-008），可以取消这个进程级替换，改成显式注入的输出对象。
- **`ConsoleInput` 的 `reader`/`display` 二选一**：注入 `BufferedReader`（测试）与 JLine（生产）两条读路径共存（`ConsoleInput.java:45-71,217-222`）。这属于必要的兼容，但让类稍显复杂；若终端抽象统一（ARCH-008），可简化。
- **`AppConfig` 不校验 baseUrl/模型名**：空 baseUrl 会交给 SDK 处理，错误信息不如显式校验清晰。属诊断改进，可与 ARCH-001 同批。
- **`util/Json` 与 `util/Ansi`**：都合理；`Ansi.ENABLED` 在类加载时求值一次，之后无法切换（`Ansi.java:11`），对 CLI 进程足够。

## 4. 修改顺序建议

1. ARCH-001（配置边界）：影响首次使用体验，改动小。
2. ARCH-003（工具顺序）：改动小、收益明确，建议与 ARCH-001 同批做一次“装配健壮性”提交。
3. ARCH-014（拆分 Main）、ARCH-019（文档修正）：纯整理，可在任意低风险窗口做。
