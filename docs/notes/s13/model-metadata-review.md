# 模型参数发现阅读入口

工程自审与离线检查通过，实际服务商查询仍待用户启动 CLI 复核。此能力用于了解模型约束，不会自动选模型、升级 provider 或将每次输出提高到最大额度。

## 主流程阅读

1. `JadeCodeFactory.create` 的生产入口：配置 → 参数快照 → 共享客户端 → Session。对照可注入客户端入口为何不访问网络／真实缓存。
2. `ModelMetadataResolver.resolve`：先看有效缓存、官方读取及逐字段回退，再读 target／parse／readCache／writeCache。
3. `AnthropicSdkLlmClient.toParams` → `ModelMetadata.capOutput`：唯一转换点如何同时约束 Leader、队友、摘要与记忆请求，而不改变各调用方原额度。
4. `ExecutionNotice.MODEL` → `TuiState.modelInfo` → `TuiController.open("/model")`：参数只是展示数据，不混入用户任务或模型历史。

## 可选问题

MODEL01：为什么“支持 Anthropic 消息协议”不能推出“可以用 Anthropic 的目录接口查到模型参数”？这会影响认证安全和回退方式吗？

回答：

MODEL02：某模型目录只报告总窗口 128K，没有最大输出。Harness 应保留什么事实、哪些仍为未知？为什么不能直接把 128K 同时当成最大输入和当前压缩字符阈值？

回答：

MODEL03：原正式请求额度为 8000，查到模型最大输出 32000。为什么不自动改成 32000？如果改用最大输出 4000 的模型，统一封顶应该放在哪里才能覆盖摘要、记忆和队友？

回答：

MODEL04：查询超时且缓存过期时，继续使用已有预算与假称“已取得官方参数”有什么区别？用户通过 /model 应该能辨认哪些来源？

回答：

## 复核步骤

重新启动 `./jade repl`，先输入 `/model`；它不请求生成、不消耗一次 Agent 任务。核对参数来源及未知字段；有网络查询失败或未匹配模型时，应明确显示沿用现有预算。

再检查鼠标滚轮／拖选复制、solo 标题无 Leader，以及让模型输出一张含中文长单元格的表格。真实模型调用由用户决定；本轮工程检查未调用真实模型。Memory 若仍提示失败，请保留新显示的固定原因，不必粘贴包含私人对话的完整响应。

## 兼容别名补充

DeepSeek 官方说明旧 `deepseek-v4-flash` 仍可调用，但目录使用 `deepseek-flash`。补读 `ModelMetadataResolver.parse/findModel/deepseekAlias` 与缓存的 lookupModel：这是参数匹配，不是换模型，不改正式请求名称。无目录项与无 token 字段现在分别提示；/model 的字段换行也已保留。

可选 MODEL05：为什么“这个名字可以发出模型请求”不意味着“目录一定存在同名条目”？兼容匹配的依据、参数来源和真实请求名称为什么要分别记录？

回答：
