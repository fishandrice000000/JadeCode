# A01–A11 包架构调整

## 目标与边界

- 依据用户指定的 `docs/reviews/architecture/ChatGPT/09-package-redesign-implementation-prompt.md` 实施；启动 HEAD：`f15388d24defe210bb7a82d3b53b437bba7dfe7c`。
- 只改变包归属、调用边界和资源所有权，保留工具名/参数、提示词、权限、压缩、记忆、后台、定时与失败语义。
- 不执行 B 系列、不修审查中的工程缺陷、不新增测试、不调用真实模型、不读取秘密。
- 原有三份笔记改动、`docs/notes/other-agents-analysis/` 与 `docs/reviews/` 全部保护，不改写/暂存/提交；启动无 staged。

## 数据流与依赖方案

CLI 提供展示 hook → bootstrap.JadeCodeFactory 装配 → session.SessionRuntime 排队/采用历史/调用记忆 → agent.AgentExecutor 请求模型与执行工具 → tools 适配器调用能力服务。

- `execution`：ExecutionContext 与独立 Approval，仅共享值/回调契约，不引用 safety、agent、session、tools。
- `agent`：AgentExecutor 及嵌套 ModelReply/ToolBatch/Continue；不引用 session。压缩采用回调只传 `List<Message>`，回调内先复制列表再替换原 history，正式请求之前即完成采用。
- `session`：SessionRuntime/SessionInbox/SessionEvent；任务入口提取目标和召回记忆，真正结束后维护记忆。同步历史兼容入口也归 session。
- `cron`：CronProducer 与现有 CronScheduler/CronJob；保留 Predicate<CronJob> 投递，开放 start/canDeliver/pending/received/failed/release/close，不引用具体 session。
- `process`：BackgroundManager/ShellCommand；只接收命令、任务归属和等待属性，内部 Running/线程/进程集合保持封装。完成事实由 session 渲染为原通知文字。
- `hooks`：原字符串事件、参数表和短路机制；普通展示实现归 cli，权限检查与装配归 bootstrap。
- `filesystem`：PathResolver；安全分类仍由 safety.PathGuard 执行，路径算法不变。
- `llm`：ToolDefinition 保存名称/说明/完整 schema 快照，CompleteRequest 不再持有可执行 Tool；Tool/ToolSchema/SubagentTool/TaskTools/CronTools 仍在 tools，SubagentTool 继续直接引用 SubagentRuntime，接受 tools/subagent 包环。
- `bootstrap`：统一创建生产/离线会话，接收既有 Hooks 协议的展示适配，不依赖 cli；Main 只保留 picocli 顶层入口。

## 关闭契约

CLI 拥有 TerminalDisplay 与 ConsoleInput。退出时先拒绝待答审批，再由 session 标记 CLOSED，停止 cron、禁止执行器晚到操作、关闭同步/后台命令、拒绝队列/当前 future、中断并限时等待消费者；最后由 CLI 恢复终端。bootstrap 创建后将执行器、cron/process 的关闭责任交给 session，不建立并行的第二条关闭链。共享 LLM 客户端不新增取消/关闭能力。同步 runExisting 结束只解除临时监听，不关闭借用执行器与服务。

## 实施与本地提交计划

1. **迁移共享执行契约与基础能力包**：execution/hooks/filesystem/process/cron 类、受影响 import/可见性和已有测试包；保持当前循环可构建。标题：`迁移共享执行契约与基础能力包`。
2. **分离模型工具定义与执行接口**：llm.ToolDefinition、CompleteRequest、SDK 适配、父子请求及已有断言定位。标题：`分离模型工具定义与执行接口`。
3. **划分执行器会话与装配职责**：AgentExecutor、session 类型、bootstrap 工厂、CLI 展示与资源绑定、所有调用方和已有测试、README/新版阅读入口。标题：`划分执行器会话与装配职责`。

每批精确文件集合以实际 diff 留档；只暂存本轮明确修改路径，检查完整 staged diff、空白错误及构建，提交后核对 hash 与剩余状态。不 push、不改写历史。

## 验证计划与已知限制

先构建/最窄已有测试，再按实际影响扩大到父子执行/压缩/装配等已有测试；记录命令、退出码和 Surefire 实际计数。检查依赖与公开面、工具/提示词/持久化契约及关闭路径。最后检查受保护文件哈希。

**保留已知限制：SDK 的 schema 转换仍只映射 type/properties/required，会丢弃根级 additionalProperties 等字段；保存完整 ToolDefinition 不修复此缺陷。** 其它报告中列举的行为缺陷也不在本轮修复。动态真实模型/CLI 真机行为未在本轮验证，用户理解与最终真机验收单独记录。

## 阶段状态

- 启动核对与方案：完成。
- A01–A11 实现：待开始。
- 工程自审与验证：待开始。
- 本地提交：待开始。
- 用户理解、最终真机验收：待用户完成。

## 实际验证与结果

后续按批次续写。

### 批次 1：共享契约与基础迁包

- 已完成 ExecutionContext/Approval、Hooks、PathResolver、BackgroundManager/ShellCommand、CronProducer 迁移及消费者接线；cron 跨包入口公开，process 启动只接收 owner ID，Running/进程集合仍封装。
- 工程自审：Pass。仅迁包、独立审批接口、必要可见性与参数投影；权限规则、线程、轮询、重试、进程执行体未改。
- `mvn -q -DskipTests package`：退出 0。初次定向测试退出 0，但发现 target 保留旧包类；以清理后的结果为准。
- `mvn -q clean -Dtest=PermissionGateTest,PathGuardTest,PathResolverTest,HooksTest,BashToolTest,FileToolsTest,SubagentToolTest test`：退出 0，42 tests / 0 failures / 0 errors / 0 skipped。
- 后续批次仍待实现：工具定义拆分、AgentExecutor/session/bootstrap 边界、通知渲染和关闭责任调整。
