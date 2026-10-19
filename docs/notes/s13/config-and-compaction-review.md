# 配置决定默认行为，不等于删除能力

## 需求与数据流

之前一次长会话可能连续经历 snip、micro、fit 和摘要。它便于学习每层，但“已经实现某层”
不等于它应在日常配置里默认运行。现在保留已有策略，默认只转存单条超大结果、必要时摘要。

配置读取发生在启动时，不在每次模型请求前反复读文件：

`config.json → JadeConfigFile.load → JadeSettings → JadeCodeFactory → CompactionLimits + CompactionPolicy → prepare`

同一个快照也给界面；Theme 的显式保存只更新 tui.theme。凭据仍由原 AppConfig 管理。
这不是 Claude Code 的全部上下文实现，字符估算、摘要形态与归档契约仍是 JadeCode 的机制。

## 推荐阅读顺序

1. `config.example.json` → `config/JadeSettings`：先看产品默认开关及字符／token 单位。
2. `context/ContextCompactor.prepare` → `toolResultBudget`：默认走什么，哪些步骤需要开启。
3. `context/ToolResultBudgetCompactor.persistLargeResults`：单项限制为什么不能依赖批次已超限。
4. `bootstrap/JadeCodeFactory` 与父／子执行器的 reactiveEnabled 判断：谁读取配置、谁执行策略。
5. 按需看 `config/JadeConfigFile` 的覆盖、校验、旧主题迁移和保存，再看 `TuiPreferences` 的异步保存。
6. 最后对照已有 Preparation／Reactive／SubagentRuntimeCompaction 等测试：课程场景显式开启课程管线，
   断言没有删掉，离线装配不会读取用户 HOME。临时终端及默认行为检查结果见任务单。

## 可选理解问题

### CFG-01：默认行为与已有能力

一批包含六个 40K 字符结果，另一批只有一个 60K 结果。它们都未触发总摘要线时，默认各会怎么处理？
聚合预算沿用课程中“只挑超过单项门槛的结果”规则，因此打开它也不一定缩短前一种情况。
为什么这些阈值不能都当成硬上限？为什么单条结果转存不应以整批已经超限为前提？

回答：

### CFG-02：谁有权重新运行请求？

reactiveEnabled 关闭时，服务端第一次拒绝上下文超限。为何仅在 reactiveCompact 里返回原历史，
却仍让执行器重试一次，不等于真正关闭了响应式补救？配置应在哪次决定之前起作用？

回答：

### CFG-03：偏好保存不应改运行策略

会话运行中用户修改了 compaction 配置，随后切换主题。保存主题应保留什么？
为什么不直接把启动时那份完整默认配置重新写回磁盘？这次运行和下一次启动分别采用哪份设置？

回答：
