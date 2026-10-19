# 记忆提取格式修复阅读

工程自审与离线检查通过；真实后端复核待进行。本轮解决“辅助模型结果为何不能保存、用户看不出原因”，不是重做记忆策略。

阅读顺序：`MemoryManager.finishTask` → `MemoryExtractionService.extract/candidateArray` → `ModelResponse.stopReason` 与 SDK `toResponse` → `JadeConfigFile` 的 memory 配置。

MEM-F01：模型说“没有长期记忆”并返回 []，与模型只返回思考或达到输出上限，为什么不能都解释成“没有记忆可存”？对用户理解 Agent 的能力有什么影响？

回答：

MEM-F02：允许完整 JSON 外包一层围栏，和从任意解释文字里抽一个数组，有什么区别？为什么容错之后仍然需要 scope、字段和文件名检查？

回答：

MEM-F03：辅助提取失败为什么既不应撤销主任务的回答，也不能被静默包装成成功？诊断需要哪些信息，又不应记录哪些内容？

回答：
