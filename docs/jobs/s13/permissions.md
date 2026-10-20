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
- 实现、工程自审与定向验证：通过，详见 §9–§10。
- 用户阅读与独立真机验收：待开始。

## 8. 配置格式与默认保护

权限配置仍在现有用户 `config.json`，不会读项目文件给项目自身授权，也不写 CODEX_HOME。
示例仅说明格式，不代表程序已替用户保存这条规则：

```json
{
  "permissions": {
    "defaultProfile": "workspace",
    "rules": [
      {
        "id": "user.example-build",
        "effect": "ALLOW",
        "kind": "COMMAND",
        "tool": "bash",
        "workspace": "/absolute/workspace",
        "argvPrefix": ["mvn", "package"]
      }
    ],
    "disabledDefaults": []
  }
}
```

每条规则必须显式提供 id/effect/kind/tool/workspace。workspace 空字符串才表示用户明确允许跨工作区匹配；
规则字段拼写错误、非法类型、重复 ID 或未知默认编号均报错。PATH 规则另填 operation=READ/WRITE/DELETE、
绝对 path 与可选 recursive；TOOL 规则不混填路径/命令字段。

默认编号及含义：

- `builtin.root-delete`：已识别文件系统根目录删除，硬拒。
- `builtin.raw-device-write`：已识别设备写入/格式化，硬拒（输出重定向 `/dev/null` 不误报）。
- `builtin.system-control`：实际 sudo/su/关机等可执行名，强制人工。
- `builtin.sensitive-access` / `builtin.sensitive-write`：已识别凭据、环境文件、启动脚本、Git 元数据、权限配置及系统位置，强制人工。
- `builtin.delete`：普通删除询问；Full Access 的普通删除免询问。
- `builtin.outside`：已识别工作区外访问询问；Full Access 普通越界免询问。
- `builtin.complex-shell`：动态展开、复杂语法询问；Full Access 普通复杂命令免询问。

默认硬保护先于普通 allow，只有用户在 disabledDefaults 明确列出 ID 才关闭该条默认保护，
不会因此取消显式 deny/ask。无法分析的任意程序仍可能有未识别副作用，不宣称硬保护可穿透任意脚本。
外部编辑配置下次启动读取；本轮没有文件监听热加载，也不提供多 CLI 并发写保证。
Read Only 检查模型调用的操作，日志、压缩、记忆等 Harness 自身状态仍照常保存。

## 9. 实现与自审记录

- Approval 从 execution 迁至 safety/approval；移除旧 CommandPolicy、PathGuard、DelegatedApproval 及 Session 的审批工厂插槽。
  执行来源与退出检查仍由 ExecutionContext 提供，队友与子代理沿用来源，辅助审批拿到当前具体任务说明。
- 工厂将同一 PermissionState 交给所有 Agent；TUI /resume 复用它。辅助审批采用单独用途记账，不进入主历史或流式展示。
- 人工申请带独立编号、操作/输入副本、规则版本、程序生成的建议；TUI 和 run 只答三项。
  规则变更唤醒旧等待，重判真实目标；规则先保存后采用，失败不执行。显式拒绝、无人工通道、辅助拒绝分别给出原因。
- 自审修复：数字文件名不能漏掉删除判断；重定向不可混入普通前缀授权；敏感原名与链接真实目标都检查；
  权限规则必须严格解析，避免字段拼错变成跨工作区允许；复合调用的 allow 覆盖所有段；补齐 reopen_task / retry_team_message 声明。
- PTY 初次检查发现新页脚刷新在关闭后调用可操作 current() 导致退出报错，已改 SessionHost 的只读权限快照并复测。
  同时使用不可变内存快照供重绘读取，配置写入和可能等待保存的档位切换不占 UI 线程。
- 旧四项 CommandPolicy 与六项 PathGuard 测试随契约维护并改名为 ShellPermissionCheckTest / FilePermissionCheckTest，
  保留原用例数量，没有新增永久测试。临时验证仅使用假模型、临时配置与临时工作区，不读凭据、不访问真实 API。
- 已知限制：有限 shell 解释可能保守多问，也可能看不到一般程序内部副作用；无沙箱，不消除文件系统 TOCTOU；
  辅助请求 20 秒超时取消本地等待，不保证远端请求已停止；跨进程配置协调和运行中手改热加载未实现。

工程自审结论：**Pass**。本轮已检查范围没有已知阻塞问题；上面的能力限制属于本阶段明确取舍，
不声称“任意 shell 都能静态判安全”，也不以自审替代真实模型行为或用户独立真机验收。

## 10. 验证与交付

- `mvn -q clean package -Dtest=MainTest,MainCompactionAssemblyTest,PermissionGateTest,ShellPermissionCheckTest,FilePermissionCheckTest,AgentExecutorTest,SubagentRuntimeTest`：
  退出 0，已有 **62** 条测试，0 failure / 0 error / 0 skipped，产物生成。
- 最后权限检查调整再用 `mvn -q -Dtest=PermissionGateTest,ShellPermissionCheckTest,FilePermissionCheckTest package`：
  退出 0，已有 **18** 条，0 failure / 0 error / 0 skipped。未增加永久测试。
- 临时离线检查 **42** 项通过：档位、规则优先级、复合命令、重定向、cwd、数字文件名、链接、规则保存/恢复/失败、
  配置保留与严格校验、陈旧答复、参数变化/null 答复、独立辅助请求的严格输出与来源、三按钮宽窄布局。
- 假模型 PTY 检查 **24** 项通过：四档菜单、Full Access 二次确认、切换、三项审批与空 Enter、长期授权保存及下次复用、
  default 保存、正常退出码与终端恢复；文本审批适配器另有 **4** 项通过（1/2/3、EOF，空行不自动作答）。
- 非法 `--permission` 在加载模型/终端前退出 2；检查 jar 不再包含旧 execution.Approval 或三份旧权限实现。
- 最后打包与文本输出检查只针对对应展示改动；临时验证文件交付前清理，不进入 Git。
- 没有真实 API 调用，没有读取凭据、修改机器 provider/认证或真实用户 config.json。真实辅助模型质量、真实终端手感与最终用户验收待完成。
- 阅读顺序与四道架构题见 [permissions-review.md](../../notes/s13/permissions-review.md)。

Git：设计留档为 `a724382`。实现作为下一笔完整能力提交，不 push、不改历史。
既有三项文档删除、三份用户笔记编辑及 docs/reviews/ 均排除；它们的空白问题也不代为清理。
