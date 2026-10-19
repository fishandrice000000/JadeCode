# 记忆提取格式兼容与失败诊断

## 问题与契约

用户反复看到 INVALID_RESPONSE。现有代码把空回复、非法 JSON、顶层非数组等原因合并，无法从旧日志反推本机真实原因。本轮不能声称已证明线上根因，也不读取凭据或调用真实模型排查。

实施：保留模型 stop_reason；明确区分输出截断、空正文、非法块、JSON 错误、非数组、拒答；允许完整 JSON 外包一层 json 代码围栏，仍拒绝解释文字夹带 JSON、尾随数据和不完整输出。只解析 TextBlock，不解析 thinking，不以格式容错代替写盘校验。不增加关键词语义过滤或自动重试。

提取输出额度从固定 1000 改为 config.json 的 `memory.extractionMaxOutputTokens`，默认 8000；提示要求最多三项、简短正文。此上限不是要求模型用完，也不用于主请求或摘要。保留合法 [] 静默零新增和主任务结果不受记忆失败撤销的行为。

范围：MemoryExtractionService、MemoryManager、ModelResponse、AnthropicSdkLlmClient、JadeSettings、JadeConfigFile、JadeCodeFactory、配置模板、README 和本任务说明。兼容已有构造器，不修改用户笔记、记忆文件或模型后端配置。

## 验证与提交

不新增永久单元测试；临时假模型检查围栏、[]、截断、尾随 JSON、错误块、非法候选与实际写入；构建及已有 Main 装配／Agent 回归按风险运行。通过自审后精确本地提交，不 push。真实后端能否稳定输出合法候选由后续真机复核；警告不得静默隐藏。

## 结果

- 实现完成；工程自审 Pass：stop_reason 仅是协议元信息，不进入模型正文；截断即拒绝；只允许覆盖全文的一层围栏；无原文日志、无自动重试、不绕过 scope／保存校验。旧构造器仍可用。
- `mvn -q -DskipTests package` 退出 0；临时假模型／文件系统 26 个断言通过；`mvn -q -Dtest=MainTest,MainCompactionAssemblyTest,AppConfigTest test` 退出 0，已有 20 条测试通过（0 失败／错误／跳过）。
- 没有新增永久测试，没有真实模型调用。旧日志不足以确认线上失败究竟是围栏、截断还是其它格式问题；后续真机若仍失败，可按新固定原因定位，不能声称已证明原根因。
- 源码入口与可选思考见 `docs/notes/s09/memory-extraction-format-review.md`。本轮不判定 s09 整章独立验收通过。
