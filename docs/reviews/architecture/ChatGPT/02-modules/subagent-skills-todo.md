# 模块审查：子代理、技能与当前计划

## 子代理机制和包职责

SubagentTool 只转换工具参数，SubagentRuntime 每次创建新历史，最多 30 次正式回复。父子共用客户端、压缩器和权限 hooks，但不共用消息；子代理没有 task，因此没有递归委派；没有 load_skill 或持久任务工具，需要的知识由父委派文本带入。子代理继承可信任务来源和关闭信号，不能把定时任务转换成人工任务申请批准。

tools 与 subagent 的这个边界是合理的：协议适配留 tools，执行机制留 subagent。问题主要在机制重复，而非应该将 Runtime 移回 tools。

证据前缀 `src/main/java/com/jadecode/`：`Main.java:168–182`；`tools/SubagentTool.java:89–100`；`subagent/SubagentRuntime.java:166–246,269–355,372–395`。

## ARCH-010：父子执行流程分别维护，回复校验与失败契约已经分叉

**P2；有依据的潜在风险（逻辑不同已证实）；分类：执行契约分叉风险。**

父执行器检查正式回复角色为 assistant；子执行器直接扫描并加入回复，没有同样的角色检查。父执行器在工具返回后先保存结果、再调用日志，意外异常会配齐结果并结束任务；子执行器先跑 PostToolUse 和展示 hook，再追加结果，只对 LlmException 及少数显式捕获路径发送 FAILED。普通工具或 hook 抛出其它 RuntimeException 时可能没有 SubagentEnd，开始/终态观察记录可能不完整。

反向检查：生产 SDK 适配器总是构造 assistant，因此当前角色问题主要暴露于替代客户端和以后后端扩展；子历史局部丢弃，不会把半批子工具对直接混入父历史。父工具批次也能把委派的运行异常转换为任务失败，所以不能说整个父会话必然留下悬空工具调用。两个循环都正确处理重复 ID、普通权限拒绝和有限超限补救，已有测试保护这些路径。进一步核对 `docs/jobs/s12/session-event-refactor.md:163`，文档已经明确不对任意自定义运行异常承诺统一 FAILED；因此本项是面向维护的契约收敛建议，不按违反已承诺的三终态保证定性。

**具体设计**：提取很小的共享执行步骤：正式回复验证、单批工具的授权/执行/结果闭合、压缩采用判断。父仍由 SessionRuntime 管调度、后台等待和记忆，子仍保留独立历史和 30 轮，不复用整个父会话运行器。子终态用一个出口或受保护的 finally/结果转换保证“开始后恰好一个终态”；错误必须保留是否已发生工具副作用，不自动重试。将状态作为结构化委派结果返回给 SubagentTool，最后再渲染成工具文本，避免以后程序靠字符串判断 STOPPED。

收益是协议和安全修复一次覆盖父子，消除已出现的行为分叉；范围两个执行器、SubagentOutcome 和 Tool 结果适配；成本中；风险是 hooks 顺序、轮次计数和父子消息隔离，不能在抽取时一并重写调度。P2 因生产适配器与父批次已有缓解，主要影响异常和扩展路径。

证据：`agent/AgentLoop.java:282–288,332–346`；`subagent/SubagentRuntime.java:251–266,291–329,356–360`；`tools/SubagentTool.java:97–100`；`src/test/java/com/jadecode/subagent/SubagentRuntimeTest.java:319–371` 的 FAILED 测试集中于模型异常/重复 ID，未证明所有运行异常都带终态。

## 技能加载：职责清楚，当前是会话启动快照

技能扫描器只扫描一层目录，检查 manifest 真实路径，读取原文并形成内存目录；system 只放名称和描述，load_skill 才把正文作为工具结果交给模型。加载时按 registry 查名称，不拼用户路径，避免名称直接变成任意文件读取。重复名称后扫描覆盖、非法 frontmatter 回退、启动缺目录为空，都有明确实现和既有测试。

它的“按需”是按需进入模型上下文，不是按需磁盘读取：构造时已经读取全部正文。public scan 可重扫，但当前 CLI 无热更新入口；把新增技能放进目录不会自动让运行中的 system 目录更新。此为现行快照设计，不冒称故障。未来技能很多时，复用 ARCH-006 的单文件/总量预算，并让元数据扫描与按需正文读取分开；暂不建立插件管理平台。

证据：`skills/SkillLoader.java:46–104,113–139,154–201,224–250`；`tools/LoadSkillTool.java:66–73`；`skills/SkillDefinition.java:14`。已读测试分支包括软链、重复名称、重扫与不构造路径：`src/test/java/com/jadecode/skills/SkillLoaderTest.java:242–337`。

## Todo：保留轻量会话计划，不与持久任务混为一谈

TodoManager 先验证候选，再一次替换旧计划；最多 20 项、最多一项进行中。失败不改变旧状态，这种局部采用方式很好。它是模型维护的会话内可见计划，不负责后台结束条件或持久依赖调度。新任务未清空旧 todo 是跨轮计划选择，不在没有产品决定时宣称错误；模型可以主动替换或清空。

宽松 asText 转换和 status 缺省与较新工具的严格类型不一致，纳入 ARCH-011 的输入契约统一范围。三轮提醒是建议文字，不能证明模型真的更新计划，也不应作为正确性或完成条件。

证据：`todo/TodoManager.java:41–78,91–109`；`tools/TodoWriteTool.java:68–111`；`agent/AgentLoop.java:349–354`。Java 细节如默认 Locale 的大小写处理可局部改善，但当前英文状态集合无证实业务损害，不单列架构问题。

Review：**Non-blocking**；保留子代理/技能/todo 分包，优先统一执行步骤，而不是引入统一的万能 Agent 基类。
