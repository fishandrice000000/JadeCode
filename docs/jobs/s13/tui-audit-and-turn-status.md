# TUI 报告核实与本轮计时展示

日期：2026-10-20。起点：`91c3bcf`。用户要求核实 JadeCode 的 TUI 自查报告，
实现实时计数及加载动画；随后明确裁决：**只实现计时和动画，其余确认项留档**。

> 后续更新（2026-10-20）：用户现已授权并完成下表前七项的修复，当前实现与验证见
> [修复记录](tui-audit-fixes.md)。下表的“待修/Blocking”保留首次核查时的状态，不是当前待办清单。
> 滚动重放与鼠标策略仍保留既定行为，本次没有强改这两项产品取舍。

## 1. 报告核实结论

核实方法：阅读当前源码，以临时 Java 探针直接生成 ConversationView / Markdown 的帧，
不调用真实模型、不启动业务命令、不修改真实用户配置。不把 JadeCode 的自述当作事实。

| 报告项 | 当前结论 | 依据与后续建议 |
| --- | --- | --- |
| 审批详情静默截断 | **Blocking，确认，待修** | ConversationView.render 只取 max(2,height/4) 行，没有完整查看动作及省略提示。60×24 长命令尾部 END_OF_COMMAND 没有进入帧；原始参数虽然在内存里，不代表用户可以看到。后续需可查看全文并明确提示，不是只加省略号就解决 |
| 代码块/引用/列表续行丢前缀 | **Non-blocking，确认，待修** | Markdown.blocks/code 只把 prefix 放进第一份 spans，整段 wrap 不会给续行补回。60 列长文本复现；嵌套列表实际输出 `• • 内层`，不是合理缩进 |
| 代码块尾部空竖线 | **Non-blocking，确认，待修** | CommonMark 普通非空代码块 literal 带终止换行，按换行分割且保留末尾空元素时多画了一行。不能未来把用户刻意保留的所有空行也无区别删掉 |
| 模型名为空时页脚前导分隔符 | **Non-blocking，确认，待修** | fields 直接加入空 state.model；新 TuiState 的帧含 ` · Workspace · /help` |
| 窄屏裁剪没有省略提示 | **Non-blocking，确认显示行为，待改进** | 30 列长模型名占满页脚，后续字段不可见且无省略号。这不是宽度计算越界；后续应在各组件决定信息优先级/省略，不全局改变 DisplayWidth.take 的精确裁列契约 |
| Tab 补全后还显示单项候选 | **Non-blocking，确认交互现状，待改进** | commandInput 补回 editor 后再 refreshCommands，单项候选仍在；不妨碍命令执行，不是重复派发 |
| 选择器 caret 不跟选中行 | **Non-blocking，确认，待改进** | ConversationView 将光标设为 first；探针选第三项时 caret=3、选中行=5。高亮正确，不是选错项 |
| 已滚出视口条目变化清空 scrollback | **现象确认，不判新增 Bug** | InlineRenderer 的 first < viewportTop 明确触发重绘；这是既定 Pi regular 取舍。README 与 pi-tui.md Q2 已说明窗口、主题及旧条目重排，不是只在窗口提示里说过。改善需另行设计，不在本轮改变 |
| 鼠标上报关闭却保留 CLICK/Hit | **常规鼠标输入不可达，但报告结论过宽** | TerminalSession 为滚轮/拖选关闭上报是明确选择。CLICK 可作为冗余兼容分支留待整理；Hit 还用于 InlineRenderer.hasAction 防止批准未显示申请，不能当成完全无用直接删除 |

报告里“没发现问题”的部分不视为全模块无缺陷证明；本轮只复核上述具体断言。
用户已选择暂不修复，包括审批截断这一已知安全问题，不能因此宣称整个 TUI Review Pass。

## 2. 计时需求与设计

原 `SessionRuntime.turnEnded` 本来就用 `System.nanoTime() - Active.startedNanos`，**没有累加过去的轮次或工具用时**。
本轮让状态栏复用这个真实起点，不用 UI 收到通知时另起一只可能滞后的表：

1. 取出一轮工作时创建 Active，记录唯一 startedNanos；TURN_START 带同一值。
2. 状态栏 RUNNING 右侧显示“当前 nanoTime 减 startedNanos”，加载改为十帧单列点阵。
3. 审批、摘要、记忆、Stop 续跑及必等后台仍在这一轮内，计数不停；入队等待不算。
4. 真正 TURN_END 时由执行线程用同一起点测量并冻结总时长。IDLE 右侧显示 `此轮用时 xxx`。
5. 保留此前用户要求的 Reply 标题时长，采用同一终值；它与 IDLE 不是两套算法。
6. 新一轮重新起算，不加上一轮；队友的独立计时不能覆盖主 Agent。
7. 新建/恢复没有计时证据时不显示假零；切换会话清理显示计时。失败可显示耗时，不等于业务成功。

System.nanoTime 只能在同 JVM 内相减，绝对值可为负；不把起点当日期写入日志，不恢复旧进程的计时。
没有增加轮次取消、权限、收件箱、模型、历史持久化或调度能力。

## 3. 文件与提交范围

- execution/ExecutionNotice：附带执行起点，旧构造签名兼容。
- session/SessionRuntime：发布已有起点；最终耗时计算保持原语义。
- cli/tui/TuiState：关联运行 ID、维护活动/最近冻结用时。
- cli/tui/ConversationView：状态文字与点阵动画。
- cli/tui/TuiApplication：无流式片段及等待后台时也刷新计数。
- README、本任务单、新增 tui-turn-status-review.md。

验证与自审完成后作为一笔本地提交，不 push、不改历史。
不纳入既有三项文档删除、三份用户笔记改动与 docs/reviews/；不新增永久单元测试。

## 4. 验证与阶段状态

- 具体问题核实已完成，探针复现结果如 §1。
- `mvn -q package -Dtest=MainCompactionAssemblyTest,AgentExecutorTest,SubagentRuntimeTest`：退出 0，
  原有 **35** 项测试，0 failure / 0 error / 0 skipped，构建产物生成。没有新增永久测试。
- 可控时钟临时检查 **30** 项通过：初始无假用时、实时递增、后台等待、非工具累加、旧运行编号、
  Reply/IDLE 终值一致、新轮次归零、队友隔离、失败用时、恢复清理、负 nanoTime 起点、旧构造兼容、
  天级格式，以及 100/60/41/40/30/20/12 列不越界。
- 假模型 PTY **14** 项通过：无流式片段时仍刷新、多帧点阵、结束冻结、Reply/IDLE 同值、空闲重绘不增长、
  下一轮从头计时、正常退出及终端属性恢复。未调用真实 API；无真实业务工具副作用。
- 计时/动画的工程自审：**Pass**，只针对本轮修改。核对单一起点、事件/运行编号与 Agent 对应、结束冻结、
  JavaDoc 和行间注释、无持久化/模型协议变更。全 TUI 仍有 §1 的 Blocking 项，不能作整体通过结论。
- 真实终端观感及用户验收：待用户执行；真实 API 未调用。
- 其余问题：按用户指示仅留档，尚未修复。
