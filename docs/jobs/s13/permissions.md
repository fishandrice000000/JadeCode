# 权限档位与审批链路重构

日期：2026-10-20。用户已批准留档后实施本方案；这是 s13 现有工程的权限改造，不推进新章节。

## 1. 需求与边界

减少重复审批，替换课程中的整段字符串关键词/目录黑名单；让用户能选择信任程度、理解询问原因并保存明确授权。
模型仍决定要调用什么工具，Harness 决定这次调用是否获准，不用另一次模型请求解释每条普通命令。

- 四档：Read Only、Workspace、Approve for Me、Full Access；默认 Workspace。
- Linux 沙箱不接入，已列 OPT-006；本轮不安装系统后端、不增加文件系统或网络隔离。
- 按用户意见，不在界面常态显示“无系统沙箱”。文档仍如实说明应用内判断不能限制任意脚本副作用。
- 不更改模型、provider、凭据和已有模型配置；辅助审批复用现有客户端并单独记账。
- 不新增永久单元测试；维护已有测试，采用构建、定向回归、临时假模型/配置/PTY 检查；真实 API 与最终真机验收由用户完成。

参考：仓库根的 `../explore-mature-agents/research-notes/permission-system/` 下 Claude Code 与 Codex 的两份 01 研究笔记。
借鉴前者的工具参数分析与规则，借鉴后者的预设/审批策略/审查者分离，不宣称完整复刻其沙箱。

## 2. 四档的实际契约

| 档位 | 应用内默认行为 | 审批 |
| --- | --- | --- |
| Read Only | 已识别读取/查询允许；写入、一般程序及未知行为需确认 | 用户；不能静默批准写入 |
| Workspace | 普通工作区读写、普通构建/命令允许；越界目标、风险或不能可靠分析的结构需确认 | 用户 |
| Approve for Me | 与 Workspace 相同的基础规则 | 普通询问交独立辅助模型，拒绝/不确定/失败有明确处理；强制人工询问不交模型 |
| Full Access | 普通调用免审批；显式禁止及少量默认硬保护仍有效 | 不询问；仍需确认的动作拒绝，可用范围明确的允许规则消除普通风险询问 |

档位属于当前 CLI 实例，Leader、队友、子代理共享；切换后对后续许可判定生效，不杀掉已经开始的命令。
恢复聊天不恢复历史临时批准；当前 CLI 中 /resume 沿用当前权限状态。
定时任务不获得人工审批通道；需要人工确认且无人可问时拒绝。切换档位不改变真实任务来源。

## 3. 判定顺序与工具解释

1. 工具成员、参数、退出检查仍先执行。
2. 工具专属检查器解释参数；文件检查操作与真实路径，shell 解析命令/参数/连接符/重定向，不运行命令。
3. 显式 deny 优先，其次显式 ask；默认硬保护及强制人工确认先于普通 allow。
4. 明确 allow 可以覆盖普通默认风险询问，但不能覆盖显式 deny/ask 或仍启用的硬保护。
5. 普通判断按当前预设；未知工具缺少检查声明时询问，不默认允许。
6. 需确认时按审批策略和审查者处理；结果陈旧或输入/规则改变时重新判断，不套用旧许可。

规则匹配命令 token、工作目录与规范化路径，不对任意正文做 contains。复合命令逐段汇总，allow 必须覆盖全部段。
shell 仅支持能可靠处理的 `/bin/sh` 语法子集；动态展开、复杂结构不生成宽泛“允许此类”建议。
不把命令解析当作任意程序安全证明；无沙箱时被批准的脚本仍以宿主机权限运行。

默认规则区分普通读写、删除风险、敏感配置/凭据、系统管理与根目录/原始设备等少量硬保护。
不再一刀切禁止整个 /usr、/etc，亦不因文件名包含 sudo/shutdown 就拒绝。
默认规则都有稳定 ID，可明确禁用单条默认保护；非法配置报错，不静默扩大权限。

## 4. 三种审批答复与规则保存

界面只提供：**仅本次 / 允许此类命令 / 拒绝**。权限选择器与审批分开，不增加第四个审批按钮。

- 仅本次只批准原请求，不存规则。
- 允许此类命令保存绑定当前工作区的长期 allow 规则；这是用户批准本方案后采用的范围，不跨工作区复用。
- shell 建议必须明确命令 token 与 cwd；单文件操作先生成精确目标规则。不能可靠概括或强制人工确认时中间项禁用。
- UI 只回答选择，不自行生成规则。权限层使用原请求保存的不可变建议；存储失败不伪称保存成功。
- 拒绝只形成该工具的错误结果，不默认终止整轮。关闭/中断/EOF 按拒绝处理。
- 运行期权限版本变化会唤醒人工审批、丢弃陈旧结果并重判；重复答复不能批准另一请求。

用户配置沿用 `~/.jadecode/config/config.json`：`permissions.defaultProfile`、`permissions.rules`、`permissions.disabledDefaults`。
项目文件不自动给自己授权。保存只改相关字段，保留未知字段；原子替换，不读写 CODEX_HOME。
`/permission` 打开四档选择；`/permission <profile>` 切换当前实例；`/permission default <profile>` 明确保存启动默认。
Full Access 在 TUI 中需一次明确确认；CLI 的显式 `--permission` 参数视为用户选择。

## 5. Java 组织

- `safety`：PermissionProfile / PermissionPolicy / PermissionState / PermissionDecision / PermissionGate。
- `safety/checks`：ToolPermissionCheck / FilePermissionCheck / ShellPermissionCheck / ShellCommandParser。
- `safety/rules`：PermissionRule / DefaultPermissionRules / PermissionRuleMatcher / PermissionRuleStore。
- `safety/approval`：Approval / ApprovalRequest / ApprovalAnswer / ApprovalCoordinator / ModelApprovalReviewer。
  Approval 从 execution 迁入；请求和答复不依赖 ExecutionContext/SessionRuntime/CLI。
- `execution` 保留任务来源与运行上下文，仅引用 Approval 协议。
- `config/JadeConfigFile` 实现规则存储，JadeSettings 增加权限快照；safety 不依赖配置 IO 实现。
- `cli` 的 Broker/PlainApproval 继续负责输入；新增 PermissionMenu，不把审批规则放入 TUI。
- `bootstrap` 装配共享权限状态和按 Agent 归账的辅助审查；SessionRuntime 不接管规则匹配。
- `process` 执行获准命令，不加假沙箱接口。旧 CommandPolicy/PathGuard/DelegatedApproval 在迁移后退出调用链，既有测试随新契约维护。

## 6. 实施与 Git 计划

1. 单独提交本设计留档，不带源码与未验证骨架。
2. 按依赖实现协议迁移、规则与参数检查、协调器、配置/工厂/CLI 接线，组成独立可构建的完整能力提交。
3. 实现后单独工程自审，补阅读指南和用户真机操作；不把工程通过当作用户理解或真机验收。

范围为上述相关 main/test 类、README、本任务单和新阅读指南；不碰用户既有笔记、三项文档删除及 docs/reviews/。
开始 staged 为空，HEAD 为 5ad4e6a，main ahead 8。只做本地 commit，不 push 或改历史。

## 7. 状态

- 完整设计：已留档。
- 实现、工程自审、构建/定向验证：待完成。
- 用户阅读与独立真机验收：待开始。
