# 权限档位与审批：源码阅读指南

本轮把课程中简化的关键词/目录规则改为可选择的应用内权限系统。工程设计与验证记录见
[任务单](../../jobs/s13/permissions.md)。这不是 Linux 沙箱；用户最终独立真机验收仍待完成。

## 1. 先理解要解决的问题

Agent 要能正常构建、读写文件，不能每条命令都打断用户；但“相信模型”也不等于让模型
自己决定有没有执行权限。模型提出工具调用，Harness 根据用户选择的档位、明确规则与
本次真实参数作许可判断。界面只收答复，执行器只运行已获准的操作。

课程的字符串匹配易把 `shutdown-notes.md` 当作关机命令；改进后识别实际 argv、连接符、
重定向和路径。不过这里仍是有限解释，不能静态知道任意脚本到底会做什么。
因此 Read Only/Workspace 是审批策略，不是“整个进程一定不能写文件”的系统保证。

## 2. 推荐阅读顺序

1. **完整调用链**：`bootstrap/JadeCodeFactory.permissionHook` →
   `safety/PermissionGate.check` → `agent/AgentExecutor.executeTools`。
   先看模型建议怎样变成获准或拒绝的工具结果，而不是先钻 shell 扫描器。
2. **用户选择怎样影响行为**：`PermissionProfile.policy`、`PermissionPolicy`、
   `PermissionState`，再看 `cli/tui/TuiController` 的 permissionCommand / choosePermission /
   applyPermission。档位属于本次 CLI，Leader、队友、一次性子代理共享，不随聊天恢复旧许可。
3. **为什么需要问与怎么问分开**：`PermissionGate.evaluate` →
   `rules/DefaultPermissionRules` / `PermissionRuleMatcher` →
   `approval/ApprovalCoordinator.request`。再读 `ModelApprovalReviewer.review`，区分独立辅助请求
   与主循环：它没有工具、不能保存规则、不会成为主任务回复，异常或不确定不能当作批准。
4. **仅本次与长期授权**：`PermissionGate.suggestion` → `ApprovalRequest` / `ApprovalAnswer` →
   `PermissionState.remember` → `config/JadeConfigFile.save`。
   “允许此类”是用户明确同意界面显示的范围，不是模型自己产生一条允许规则就能获权。
5. **必要依赖**：`checks/FilePermissionCheck`、`ShellPermissionCheck`、最后才读
   `ShellCommandParser.parse`；将它们当作提取权限资料的方法，不是执行 shell 的另一套实现。
   随后读 `cli/tui/bridge/ApprovalBroker` 与 `ConversationView`，了解为何申请必须带独立 ID、
   为什么界面不直接跑工具、为什么设置变化后必须重判。
6. **已有测试**：`safety/PermissionGateTest`、`ShellPermissionCheckTest`、
   `FilePermissionCheckTest`。后两份是原 CommandPolicy/PathGuard 测试的迁移，并未新增用例数。
   装配链另由已有 `MainTest` / `MainCompactionAssemblyTest` 检查。

上述路径除 cli/tui 之外都在 `src/main/java/com/jadecode/` 下；测试在对应的 `src/test/java/`。

## 3. 用真实 Agent 场景理解设计

### P01：谁来决定一次命令有没有权执行？

用户让 Agent 构建项目。模型提出 `mvn package`，随后又提出读取工作区外的资料。
比较 Workspace、Approve for Me 与 Full Access 会怎样处理；哪些判断由模型作，哪些判断
必须留给 Harness？为什么 Approve for Me 不能让主 Agent 在自己的历史中“审一下自己”？
不需要背枚举名，说明各方职责与信任范围即可。

回答：

### P02：允许“此类”到底允许了什么？

用户对 `rm temp.txt` 选择“允许此类命令”。解释为什么规则绑定当前工作区、按 argv token
而不是字符串前缀匹配；为什么同一开头后面再加 `; 另一条命令` 或重定向，不应自动复用这份批准？
这个授权在新 CLI 或另一工作区中分别有什么意义？

回答：

### P03：等待批准期间，世界可能已经变了

队友在等批准时，当前权限档位发生变化，或者文件链接指向了另一个目标。
如果 Harness 不重新判断，用户批准的到底是哪一次操作？结合版本和路径重解析说明本实现
如何缩小这个问题，并说明为什么这仍不能替代系统沙箱，也不能消除检查后到执行前的一切竞态。

回答：

### P04：主任务成功、审批成功与记忆维护成功是同一件事吗？

定时任务想执行需确认的操作，但当前没有人工审批通道；辅助模型输出又不合法。
Harness 应该暴露什么，而不是怎样伪装继续成功？被拒绝后工具调用/结果配对、主循环的
继续决策与最终任务成败分别由谁负责？

回答：

## 4. 用户真机最小检查

在没有重要材料的临时工作区启动 `jade repl --permission workspace`：

1. `/permission` 看四档、描述及页脚；直接切换普通档位不发送模型请求。
2. 切 Full Access，先返回一次，再明确确认一次；确认普通编辑不会反复询问，默认硬保护仍在。
3. 回到 Workspace，让模型对临时文件执行一次 `rm`；检查三项选择、无默认选中、草稿保留。
4. 对明确范围选择“允许此类命令”，检查范围显示及下次相同操作不再询问；到另一工作区不复用。
5. `/permission default workspace`，重启核对默认。最后退出，确认没有异常退出或终端属性遗留。

这里只给操作顺序，不需要执行危险命令证明保护有效；实际模型调用与用户批准的业务副作用由用户掌握。
