# 会话用量与本地交互：源码阅读指南

本次是现有会话/TUI 的扩展，不是课程新增一章。重点是“哪些状态属于会话、哪些只是显示”，
不要把统计、模型历史和真实后台资源当作同一份数据。

## 推荐阅读顺序

1. `bootstrap/JadeCodeFactory.create / assemble / tracked`：同一个用户会话的所有模型请求如何归账，
   各个 Agent 怎样保持独立历史和思考设置。
2. `llm/UsageTrackingLlmClient.call` → `session/SessionUsage.record`：完整响应回来之后先记用量，
   再交业务检查；流式片段为何不逐个计费，缺失报告如何保存。
3. `session/history/SessionJournal.usage / write` → `SessionLoader.load`：
   用量/改名/设置不是模型消息，也不能覆盖主 Agent 的 Todo；版本 1 日志仍可读。
4. `SessionRuntime.apply(ModelReply) / display` → `ContextUsage`：
   为什么整个会话的累计花费和当前主 Agent 的窗口占用是两件事。
5. `cli/tui/TuiController.refreshSession / open / saveLocal / resume` → `ConversationView.render`：
   界面读取只读快照；名称重复时必须选择；异步设置不能写到后来切换的新会话。
6. `llm/AnthropicSdkLlmClient.toParams` → `ThinkingSettings`：
   思考强度真正写入请求，和 Ctrl+T 的显示开关不同；未知兼容端点不猜支持情况。
7. `TuiController.close` → `TuiApplication` 的清理与 `farewell`：
   `/quit`、`/exit` 关闭实际资源，终端恢复后才打印恢复资料。

## 问题与回答

### U01：一次请求很小，会话累计花费却很大

Leader 已运行很久，队友也做过几轮工作。此时累计 1.4M Token，但最新一次主请求只有
20K Token。为什么不能用 1.4M 去算主 Agent 的上下文剩余？队友的花费和窗口分别属于谁？

回答：

### U02：模型的业务回答被拒绝，为什么仍然记账？

记忆提取返回了不合法的 JSON，没有写入任何记忆文件。这次模型调用是否消耗 Token？
为什么统计应在业务解析之前，而不是等“任务成功”才计入？若服务端没报告，能否记成零？

回答：

### U03：恢复究竟恢复了什么？

恢复后可以继续累计用量，却不能恢复上次的后台进程和队友。为什么两者不能混为一谈？
旧版日志没有用量时，根据提示词长度补账有什么问题？

回答：

### U04：改变思考强度和折叠思考正文

Ctrl+T、`/thinking low` 分别改变哪一层？模型已开始生成时更改强度，为什么只对下一次请求生效？
为什么不直接修改全部队友和正在运行的子任务？

回答：

### U05：为什么名字不能代替会话 ID？

两次不同讨论都叫“压缩研究”。为什么应该让用户选择，而不是按最近更新时间打开第一项？
改名不移动目录、异步保存核对原 ID，分别避免什么问题？

回答：

## 验收范围

工程验证与实际提交见 [任务单](../../jobs/s13/session-usage-and-controls.md)。
没有新增永久单元测试，临时检查使用假模型和临时工作区；真实模型与终端体验仍由用户验收。
上下文百分比是最近正式响应后的估计，不是新输入还能追加多少 Token 的精确承诺。
