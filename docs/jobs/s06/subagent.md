# s06 作业单:Subagent — 给子任务一段独立上下文

课程 s06 直译:第七把工具 `task` + SubagentRuntime(嵌套循环,全新 messages,30 轮上限,只回传最终文本)。

**关键洞察**:隔离的是**消息**,不是进程或文件系统。父子共享工作区、LlmClient 与 Hooks;子代理只比父代理少两样——`task`(一层委派)和 `todo_write`(聚焦子任务不需要计划表)。

## 一、课程行为(已读,提炼为规格)

**SubagentRuntime**(`subagent/SubagentRuntime.java`):

- 入口 `run(String prompt)`:新建 `List<Message>`,只放一条 `Message.userText(prompt)`——**不复制父对话**
- 循环至多 **30 轮**:
  - 调 LLM(system = 子代理提示词,tools = 子代理工具表,maxTokens 与父同值)
  - assistant 消息追加进**子代理自己的** messages
  - 收集该回复的 tool_use:
    - **无** → 触发 Stop hook(共享事件表);返回非 null → 追加 user 消息继续(**也占一轮**);null → 返回最终文本
    - **有** → 逐条执行:**先查子代理工具表**(查不到 → 回灌 `Error: Unknown tool: <name>`,该条到此为止)→ 已知工具过 PreToolUse(拦截 → 拦截文案进 tool_result)→ 执行 → PostToolUse 通知;全部结果合成一条 user 消息回灌
- 最终文本 = `response.message().extractText()`;**空白 → `"(no summary)"`**
- 30 轮耗尽 → `"Subagent stopped after 30 turns without a final answer."`

**SubagentTool**(`tools/SubagentTool.java`,工具名 `task`):

| 项 | 值 |
| --- | --- |
| name | `task` |
| description | `Run a subagent with fresh conversation context and return its final text.` |
| properties | `{prompt: string}` |
| required | `[prompt]` |
| execute | 缺参检查 → `runtime.run(prompt)` 透传 |

**system prompt**:

- 子代理(课程原文):`You are a coding agent at {cwd}. Complete the given task, then return a concise final answer.`
- 父代理(s06 新增句):`Use task for focused exploration or a self-contained subtask.`

**终端输出**(课程):`[Subagent started]` / `[Subagent done]` / `[Subagent stopped]` 洋红;每条子工具调用后 `  [sub] {name}: {output 前 100 字符}` 灰。

## 二、设计决策(如无异议按推荐执行)

**D1 子代理工具表 — 推荐:5 个基础工具(bash / read_file / write_file / edit_file / glob),不含 task、不含 todo_write。**
课程原文"子 Agent 拥有五个基础工具,但没有 `task`";todo_write 是主循环的规划工具(还挂着 reminder 机制),子代理是聚焦子任务,30 轮内不需要计划表。代价:子代理不能规划,接受。

**D2 子代理三行输出的位置 — 采纳:新增三个通知型事件,由 Main 注册展示,运行时自己不 print。**
课程在 run_subagent 里直接 print,s04 已立的架构是"循环只触发事件、终端展示归 Main"(AgentLoop 一行不 print)——SubagentRuntime 同样是循环,不破例。"在子代理内"这个维度由**事件名**表达,不需要塞进参数。

| 事件 | 参数 | Main 打印 |
| --- | --- | --- |
| `SUBAGENT_START` | 无 | 洋红 `[Subagent started]` |
| `SUBAGENT_TOOL` | block + output | 灰 `  [sub] {name}: {output 前 100 字符}` |
| `SUBAGENT_END` | output(最终文本)+ `PARAM_OUTCOME`(`SubagentOutcome`) | 洋红 `[Subagent done]` / `[Subagent stopped]` / `[Subagent failed]` |

新增常量:`SUBAGENT_START` / `SUBAGENT_TOOL` / `SUBAGENT_END` / `PARAM_OUTCOME`,外加终态枚举 `SubagentOutcome`(`DONE` / `STOPPED` / `FAILED`)。
收益:展示可测(测试注册 hook 断言,不必抓 stdout);形态与父循环一致。代价:4 个常量 + 1 个枚举 + Main 三个小 lambda;SubagentRuntime 四处 trigger 调用(START、END(DONE)、END(STOPPED)、END(FAILED))——但**出口的 END 恰好一次**,起点与终点成对。

**D3 提示词合成** — 子代理:`You are Jade Code, a coding agent at {cwd}. Complete the given task, then return a concise final answer.`(保留 s01 起的自称);父代理在 `Act, don't explain.` 之后插一句 `Use task for focused exploration or a self-contained subtask.`

**D4 包位置** — `SubagentRuntime` 放 `subagent/`,`SubagentTool` 放 `tools/`(与其余 6 把工具一致,Main 装配表一眼看全)。

## 三、实现顺序(每步独立可编译;①②③④ 顺序不可换)

### ① SubagentRuntime — `src/main/java/com/jadecode/subagent/SubagentRuntime.java`(骨架已建)

- `private static final int MAX_TURNS = 30;`
- `run()` 用 `for (int turn = 0; turn < MAX_TURNS; turn++)`——循环正常走完即超限
- 子代理 messages 是 `run()` 的局部变量,绝不复用父列表
- 未知工具兜底见坑 1:**查表在 PreToolUse 之前**,查不到就回灌并跳过该条,不进任何工具 hook
- `hooks.trigger(...)`:入口一次 `SUBAGENT_START`;已知工具执行后 `POST_TOOL_USE` 与 `SUBAGENT_TOOL`(block + output);出口**恰好一次** `SUBAGENT_END`(最终文本 + 终态)——正常收尾 `DONE`、轮数耗尽 `STOPPED`、捕获 `LlmException` 时 `FAILED`(output 为 `Error: ` 加异常消息,随后原样抛给调用方,保证异常路径也有配对的 END)——D2,本类不 print

### ② Hooks 新增事件常量 + Ansi 新增 magenta

- `Hooks` 加 4 个常量:`SUBAGENT_START` / `SUBAGENT_TOOL` / `SUBAGENT_END` / `PARAM_OUTCOME`(并在 `Hook.run` 的 JavaDoc 里补齐三个 Subagent 事件的参数与返回值语义)
- `Ansi.magenta(String s)` → `color("35", s)`(横幅用;现有只有 red/yellow/cyan/gray)

### ③ SubagentTool — `src/main/java/com/jadecode/tools/SubagentTool.java`(骨架已建)

- 构造器注入 SubagentRuntime
- `execute`:仿 GlobTool 的 `for (String key : required())` 缺参检查 → `runtime.run(input.get("prompt").asText())`
- schema 用默认 `ToolSchema.objectSchema`(平铺够用);课程多一个 `minLength: 1`,想严格照抄就 override `inputSchema()`,不加不影响 execute 的缺参检查

### ④ Main 装配 — `src/main/java/com/jadecode/Main.java`

- 把 `new AnthropicSdkLlmClient(config)` 提为局部变量(父子共用同一实例)
- 子工具表:`Map<String, Tool> subTools = Map.of(...)`(D1 的 5 把)
- `SubagentRuntime subagentRuntime = new SubagentRuntime(llm, subTools, subSystemPrompt(cwd), config.maxTokens(), hooks);`
- 父工具表加第 7 条:`TASK_TOOL, new SubagentTool(subagentRuntime)`
- 新增静态纯函数 `subSystemPrompt(Path cwd)`(仿 `systemPrompt(cwd)`,便于测试)
- `systemPrompt(cwd)` 按 D3 追加 task 句 → **MainTest 会红,我随后同步**
- hooks 注册顺序不变:membershipHook 校验的是父表(超集),子代理的未知工具靠 ① 的兜底
- 注册三个子代理展示 hook(D2,在 todoStatusHook 之后):`subagentStartHook()` / `subagentToolHook()` / `subagentEndHook()`——洋红横幅 + 灰色 `  [sub] {name}: {output 前 100 字符}`

## 四、坑清单

1. **共享 membershipHook 校验的是父表(超集)**:子代理工具表是子集,模型若幻觉调用 `todo_write`,父表查得到、子表查不到 → **NPE**。子代理必须自己对"查不到工具"兜底,回灌 `Error: Unknown tool: <name>` 的 tool_result,不要解引用 null(课程同款 `Unknown: {name}`,我们统一 `Error: ` 前缀)。**短路必须发生在 PreToolUse 之前**:未知工具不进 PRE / POST / SUBAGENT_TOOL——否则 `logCallHook` 会为一把根本没执行过的工具打出一行 `[HOOK] xxx` 假日志
2. **30 轮上限**:`for` 正常走完 = 超限 → 返回 stopped 文案;Stop hook 的续跑也消耗一轮(课程同款)
3. **消息隔离**:子代理的 messages 绝不可混入父列表;父只收 `task` 的返回值(最终文本)
4. **Stop hook 在子代理内同样触发**(共享事件表)——Main 的 summaryHook 会打印两次,课程同款,不特殊处理
5. **空白最终回复**:`extractText()` 返回空串 → 回 `"(no summary)"`(课程 `or` 的语义)
6. **Ansi 没有 magenta**:需要新增(35),否则横幅没颜色
7. **子代理的已知工具调用会照常触发 PreToolUse / PostToolUse**(共享事件表)——Main 的 `logCallHook` 会给子工具也打一行 `[HOOK] bash`。与课程一致,不是 bug;因此每个子工具调用有两行输出,信息不重复(后者带 output 摘要)。未知工具例外:按坑 1 在 PRE 之前短路,不出任何 hook 行
8. **systemPrompt 改动**会让 `MainTest.systemPromptIncludesCwd` 变红——我同步,你不用管
9. **stale jar**:验收前 `mvn -q package`(老规矩)

## 五、测试(最终设计,已落盘)

**SubagentRuntimeTest**(`src/test/java/com/jadecode/subagent/`;Scripted/Recording 客户端 + 内存假 Tool + 自定义事件表,不碰网络与真实文件系统):

① 纯文本与请求透传 → 返回该文本;首个请求只有子 prompt,system / maxTokens / 工具名正确 ② 空白总结 → `(no summary)`,END 的 output 同为降级文本且终态 `DONE` ③ 已知工具执行一次,第二次请求带匹配 id / output 的 tool_result ④ 同轮多工具串行各执行一次,结果同处一条 user 消息且顺序与 tool_use 一致 ⑤ Stop hook 强制续跑 → 返回值作为 user 消息进入下一次请求 ⑥ PreToolUse 拦截 → 工具不执行、拦截文案回灌、POST 与 SUBAGENT_TOOL 均不触发 ⑦ 未知工具早短路 → `Error: Unknown tool: task`,PRE / POST / SUBAGENT_TOOL 计数全为 0 ⑧ 30 轮耗尽 → LLM 恰调用 30 次,返回 stopped 文案,END 恰一次且终态 `STOPPED` ⑨ 完整事件顺序 START → PRE → POST → SUBAGENT_TOOL → STOP → END(DONE) ⑩ LLM 异常 → run 原样抛出,事件仅 START → END(FAILED),END 的 output 为 `Error: ` 加异常消息

**SubagentToolTest**(`src/test/java/com/jadecode/tools/`):

① 契约:name / description / properties / required 与默认 inputSchema ② 缺 prompt → 缺参错误且 LLM 一次都不被调用 ③ 正常透传:prompt 成为子代理首条 user 消息,execute 返回最终总结 ④ LLM 抛异常 → execute 不抛出,回灌 `Error: boom`

**AgentLoopTest 追加**:

⑭ 父循环委派 `task`:父子共享同一 RecordingLlmClient,脚本顺序为 父委派 → 子收尾 → 父收尾;父 messages 恰 4 条,task result 的 ID 与内容(恰为子最终文本)正确,第二次请求只含子 prompt

**MainTest**:父 systemPrompt 期望同步加 task 句;新增 `subSystemPromptIncludesCwd`

## 六、验收(全绿后真机)

1. `mvn -q test` 全绿
2. `mvn -q package` 后 `./jade repl`:
   - `用 task 子代理去调研这个项目用了什么测试框架,只告诉我结论` → 观察洋红 `[Subagent started]` / `[Subagent done]`、灰色 `[sub]` 行、父循环只收到结论
   - 子代理里试图再开子代理 → 应回 `Error: Unknown tool: task`
3. `q` 退出无异常

## 七、提交预览(Git-SOP:每批独立可编译,具体批次提交前再核)

```
Ansi 新增 magenta 色码
添加 SubagentRuntime:嵌套循环与 30 轮上限
添加 task 工具:委派子代理并只回传最终文本
添加 SubagentRuntime 单元测试
AgentLoop 测试追加 task 委派用例
Main 装配 task 工具与子代理提示词,同步测试期望
```
