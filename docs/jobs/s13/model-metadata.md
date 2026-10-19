# 模型参数发现与保守回退

## 目标与数据流

用户要求使用模型前取得官方参数并缓存，缺失或查询失败时保留已有保守设置。生产启动读取 AppConfig／config.json → 识别受支持的官方端点 → 读取有效缓存或有时限地查询 → 按字段合并手动覆盖 → 将输出上限交给共享客户端 → 装配原会话。离线注入客户端的工厂入口不联网、不访问用户缓存。

首批适配：Anthropic `GET /v1/models/{id}` 的 max_input_tokens／max_tokens；DeepSeek `GET /models` 中精确匹配模型 ID 的 context_window／max_output_tokens。前者是最大输入，后者总窗口含输入与输出，不能混为一谈。官方资料：

- https://platform.claude.com/docs/en/api/models/retrieve
- https://api-docs.deepseek.com/api/list-models/

## 安全、配置与失败契约

- 仅识别精确官方 HTTPS 主机和已知路径；未知代理／兼容端点直接回退，不能猜测接口或将其密钥转发至另一家厂商。
- 查询只取元数据，不做生成请求；不自动重试、不跟随重定向；超时、无匹配模型、空字段、网络或缓存故障均不阻止原工作流。过期缓存不冒充当前官方数据。
- 缓存在用户 `~/.jadecode/cache/model-metadata/`，按端点与模型 ID 分开，保存时间及正整数限额；不保存密钥、对话或原始 HTTP 响应。缓存是优化，写失败仍可用本次查到的值。
- config.json 新增 `modelMetadata`：autoDiscover、cacheTtlHours、timeoutMillis，以及 overrides（按精确模型 ID 保存手动限额）。手动值明确是用户配置，不标成官方数据；不得以手动覆盖突破已知官方输出上限。
- 已知最大输出只压低超限的 max_tokens，不将每次请求放大到模型最大值；覆盖主请求、摘要、记忆和辅助审批的共享客户端。未知则保留各调用方原上限。
- 字符估算器不等于 tokenizer；本轮不把查到的 token 窗口换算成所谓精确字符上限，也不重构压缩器。现有 compaction.limits 保持为用户策略。
- TUI `/model` 展示参数及来源；未查到明确说明沿用原预算。非交互输出采用只读 Model 通知。

## 范围、验证与提交

范围：新增 llm 模型参数记录／发现器，JadeSettings／JadeConfigFile／模板、生产工厂、SDK 输出封顶、只读观察事件及 TUI／plain 显示、README、jobs／notes。提取失败提示同步提醒“模型输出上限”，避免用户只提高提取额度却仍被服务商封顶。不改密钥、provider、模型 ID、本机用户配置；不修改用户既有改动。

不新增永久单元测试。用临时传输替身／HTTP 本地服务验证官方字段映射、模型隔离、缓存时效、手动值、缺失与失败回退、请求输出封顶以及 /model 不发送生成请求；运行构建和已有相关测试。无真实生成请求；本轮亦不使用真实凭据查询官方端点。自审通过后本地提交，不 push。

## 工程自审与验证记录

- 工程自审 Pass：生产只发现一次；离线入口不联网；官方主机／路径白名单、不跟随重定向；缓存按端点／模型区分且有时效，写失败不丢本次数据；手动值不突破官方值；输出封顶不放大请求；/model 只读 UI 快照，不发送生成请求。未重构字符估算压缩器。
- 首次临时检查发现 `choose` 的 Integer／int 嵌套三目表达式对未知值自动拆箱，产生 NPE。这会破坏回退契约，已改为明确 null 分支；完整重跑后通过，未把首次失败算作通过。
- 临时模型参数检查最终 45 个断言通过，包括官方字段／认证头选择、不同模型不串缓存、过期／损坏／不可写缓存、未知端点不发查询、手动限额、缺字段回退、本地 HTTP 超时／拒绝重定向、真实 SDK max_tokens 线格式与 stop_reason、/model 不调用模型及主题保存不覆盖参数设置。
- Memory 临时检查加强到 34 个断言通过；已有 MainTest／MainCompactionAssemblyTest／AppConfigTest／AgentExecutorTest 共 38 条测试通过（0 失败、错误、跳过）。`mvn -q -DskipTests package` 退出 0。未新增永久测试，未使用真实凭据或调用真实模型。
- TUI 终端回归沿用本轮 PTY 与布局检查；真实终端滚轮／复制、线上提取稳定性、真实官方目录及用户独立验收仍待进行。临时验证材料交付前清理。
- 源码阅读与可选问题见 `docs/notes/s13/model-metadata-review.md`。本地提交仅包含本项实现与说明，不纳入用户原有笔记／ADR 变动，不 push。
