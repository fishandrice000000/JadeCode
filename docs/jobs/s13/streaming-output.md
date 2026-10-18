# Leader 流式正文与 thinking 展示

## 目标与契约

2026-10-19 用户批准：直接与用户交互的 Leader 展示流式正文和接口返回的 thinking。
队友、一次性子代理的 thinking 不展示；Memory、摘要、辅助审批保持静默非流式。
不改变后端、模型、认证或默认思考模式，不为获得更多思考而改提示词。

每次请求独立创建展示对象，SDK 内部收集完整消息；半段 JSON 不执行工具，断流不写入半份
assistant 历史。成功后复用完整响应推进原循环，已展示的正文不在大轮次结束时重复打印。
隐藏 thinking 不等于丢弃：内部消息保留 thinking/signature 与 redacted_thinking/data，
工具续跑时按原协议发回；不把思考拼成普通回复或记忆候选 JSON。

终端使用 JLine 的底部显示区预览未换行片段，完整行进入记录；队友日志与输入区继续可用。
无支持的终端时按完整行输出，不输出控制字符。未来 pi UI 可替换展示层，无需重写流式协议。

## 范围与失败处理

- llm：增加请求级片段回调、SDK 流式拼装、类型化 thinking 转换。
- messages：两种思考块的序列化、辅助回复文本检查。
- agent/session：安装 Leader 展示、完整响应采用和防止重复打印。
- bootstrap/cli：仅向 Leader 注入流式展示工厂，终端预览与退出清理。
- memory：允许协议思考块伴随 JSON，但仍只解析 TextBlock，拒绝工具回复。
- docs：README、OPT-004 状态、阅读说明与工程结果。

不新增永久单元测试，不改任务图、Inbox、权限策略或整个压缩方案。已有压缩保留的工具交换
须保留完整 assistant 思考块；整段有损摘要仍按原契约替换历史。
网络失败明确报告；收到部分内容不代表完整响应成功，也不授权执行其中工具。
未识别的其它块仍给出类型级警告，不打印完整 payload 或思考签名。

## 验证与 Git

先构建与已有相关定向测试，再用本地假 SSE 服务及临时 PTY 检查：片段提前可见、完整工具
参数、thinking 回传、截断/错误、无重复回复、Leader/队友隔离、辅助 JSON、输入和审批。
不读取真实凭据、不调用真实模型；最终真实后端与终端验收由用户执行。
实现与同一 Agent 工程自审：Pass。已核对 SDK 事件处理、完整工具参数、消息采用时机、
四处重复打印分支、共享客户端的展示隔离、思考块回传、辅助 JSON、终端锁与退出清理。

### 实际验证

```sh
mvn -q clean -Dtest=MessagesJsonTest,AgentExecutorTest,AgentExecutorReactiveCompactionTest,MainTest,MainCompactionAssemblyTest,SubagentRuntimeTest,HistoryCompactorTest package
```

已有 78 条定向测试通过：MessagesJson 6、AgentExecutor 18、Reactive 11、Main 9、装配 6、
SubagentRuntime 11、HistoryCompactor 17；Failures/Errors/Skipped 均为 0，package 成功。
未新增永久单元测试，未运行全量回归。

临时本地 HTTP/SSE 服务通过真实 SDK 完成 26 项检查：thinking/text 增量、签名与 redacted
数据保留/回传、缺省签名不补造、JSON 往返、Token 用量、thinking 伴随辅助 JSON、工具参数拼装、
工具执行后续跑、最终正文不重放、EOF/坏 JSON/流内 error 拒绝、Leader 独占流式展示与队友/辅助静默。
服务仅监听 127.0.0.1，使用占位凭据，没有连接真实模型或产生 API 费用。

另用临时压缩检查确认：最新工具交换的 assistant 保持原引用，thinking/signature/redacted
在完整归档中保留，工具调用配对不被新增块破坏。

PTY/管道 35 项检查通过：thinking 与正文在完成前可见、通知交错时输入文字仍完整、
空 Enter 不拒绝审批、y 批准、多行粘贴、Alt+Enter、NO_COLOR、退出清理、管道无色码、
完整正文/缩进只输出一次、失败提示一次性收尾。未把这些检查说成用户终端观感验收。

### 发现与修正

- 最初误把 JLine 的单参数 setOpt 当成可变参数，编译已指出并改为分别调用。
- 首次本地坏 JSON 检查发现 SDK 会为非法工具片段回退成空对象；现额外验证原始片段，
  不完整对象、尾随 JSON、未闭合内容块都不能推进工具执行。
- 一次增量打包混入带“Unresolved compilation problem”的占位字节码，源码 import 已存在。
  清理生成目录后完整重建，检查最终字节码已包含真实 JSON 校验，再重跑本地检查；
  没有把该构建产物异常掩盖成协议失败或用跳过检查通过验收。

### 尚未证明的行为

- 当前真实后端返回的具体 thinking 类型、模式与签名行为；没有为了打开思考修改配置。
- 用户实际终端的布局体验和真实网络故障。取消仍沿用现有中断/退出检查，
  不保证在无新片段到来时立即强行终止网络读取。
- 不支持底部预览的终端/管道只按完整行显示；没有 pi UI、完整 Markdown 渲染或新的历史恢复。
- 未知其它响应块仍只给出具体类型警告，没有宣称此前所有 ContentBlock 警告都已定位。

临时验证材料在交付前清理；用户理解和最终真机验收仍待完成。

仅精确暂存本轮文件，形成一笔可构建的完整能力提交；不 push、不改历史，不纳入旧笔记、
用户删除项、docs/reviews 或任何运行产物。

协议依据：[流式消息](https://platform.claude.com/docs/en/build-with-claude/streaming)、
[thinking 保留要求](https://platform.claude.com/docs/en/docs/build-with-claude/extended-thinking)。
