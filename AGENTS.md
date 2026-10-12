# JadeCode 项目公共指令

本文件只保存 `JadeCode` 中所有 Agent 都必须遵守的项目事实与工程规则。角色身份、职责边界和各自的工作步骤分别位于：

- `docs/SOPs/ChatGPT/AGENT.md`
- `docs/SOPs/Deepseek/AGENT.md`

不要把任一角色的专属身份或完整职责复制回本文件。

## 1. 项目定位

- `JadeCode` 是个人学习项目，目标是用 Java 21 重写 `../learn-claude-code` 的 s01–s17 课程能力。
- 项目形态是 CLI coding agent，不使用 Spring Boot。
- 核心理念：`Agency 来自模型，harness 让 agency 落地。`
- 用户是学习者和最终决策者。代码由执行方在规划下实现；用户通过阅读代码、回答问题和复核反馈学习，不再默认亲手实现课程核心。
- 当前章节、测试数量、最新提交和正在进行的任务都是动态事实，不在本文件中固化。

## 2. 技术基线

- Java 21；Maven 单模块。
- Anthropic Messages 格式；LLM 后端由项目配置决定，可使用兼容 Anthropic endpoint 的服务。
- 需要持久化的运行期能力使用工作区目录。
- Maven Toolchains 隔离 JDK。
- 依赖版本、构建产物、命令和当前能力必须从实际 `pom.xml`、脚本、源码与 `README.md` 核实。

常用命令：

```sh
mvn -q test
mvn -q package
./jade repl
./jade run "任务"
```

运行哪些验证、由谁运行，以当前任务邮件和用户最新指示为准。

## 3. 事实来源与启动核对

每次新会话都从仓库重新建立上下文，不依赖旧对话记忆。至少按任务需要检查：

1. 用户当前明确指示；
2. `git status`、相关 diff、实际源码与测试；
3. `README.md`；
4. `docs/jobs/` 中的相关作业；
5. `docs/SOPs/` 中的当前规则；
6. 两个当前通信文件。

学习交接与批阅还需读取 `docs/notes/s*/` 的相关题单和用户指定的回答；不得根据旧对话猜测作答内容。

仓库实际状态优先于过时文档或 Agent 报告。报告不是事实源的替代品；重要节点必须读取实际文件、diff 或测试结果复核。

如果代码、README、作业单和邮件不一致，以实际代码与 Git 状态判断当前能力，并明确报告文档漂移。章节完成标准以 `docs/SOPs/Learn-SOP.md` 为准。

## 4. 学习项目约束

- 所有项目代码（含课程核心、工程支撑、测试与修复）默认由执行方按 planner 的明确任务实现；持续授权不允许自行扩大范围或一次补完整章。
- 按一项完整、可理解的小能力交付：执行、工程 Review 与验证通过后，才交给用户做人工 Review 学习。代码存在、测试通过与用户已理解是不同状态。
- 代码必须同时有准确、充分的中文 JavaDoc 与行间注释，解释数据流、设计原因、Java 语义、边界与失败路径；注释质量是验收项。
- 每章仍必须编写并保留 `docs/jobs/s*/` 工程任务单，记录目标、契约、范围、验证与阶段状态，不因用户不再据此写代码而取消。
- 工程 Review 放行时在 `docs/notes/s*/` 提供代码阅读顺序、详细的编号问题与空白回答区；用户可以分批回答。
- 批阅反馈只在对话中按题号说明正确点、错误原因与追问，不代填、覆盖或批注用户笔记。关键机制误解纠正前不推进依赖它的学习阶段。
- 工程问题交给执行方修复并重新审查；“Review 通过”只表示已检查范围内没有已知阻塞问题，不保证代码绝对无误。
- 不因目录、骨架、TODO 或测试文件已经存在就宣称章节完成。

完整学习节奏、Review、测试、真机验收与章节完成条件见 `docs/SOPs/Learn-SOP.md`。

## 5. 工程与文档规则

- 修改前读取实际文件，保护用户未提交工作，只改当前任务范围。
- 不顺手重构、补功能、升级依赖或清理范围外技术债。
- 发现范围外问题只报告，不删除、不移动、不修复、不暂存。
- 保持 Java 21 与现有项目风格；公共 API、wire format、安全边界和失败语义必须有明确契约。
- 长期规则写入 `AGENTS.md`、角色 `AGENT.md` 或正式 SOP；动态任务与结果只写入 speak 文件。
- `docs/jobs/` 可以保留历史 Agent 名称、课程来源和当时的执行记录；不要机械替换历史事实。
- `Claude`、`Claude Code` 或 `CLAUDE.md` 可能是课程研究、功能对比或历史资料。只有当前双 Agent 工作流引用才迁移。

## 6. 双 Agent 通信

正式通信路径：

- GPT → DeepSeek：`docs/SOPs/ChatGPT/ChatGPT-speak.md`
- DeepSeek → GPT：`docs/SOPs/Deepseek/Deepseek-speak.md`

共同规则：

- 每次新任务或新结果都覆盖对应文件全文，禁止 append。
- speak 文件只保存当前轮完整邮件，不是聊天历史、长期知识库或实时进度日志。
- 长期有效的决定必须同步到正式项目文档，不能只留在 speak 文件。
- 发信前先读取对方最新邮件，避免忽略未处理的完成报告、阻塞或裁决请求。
- 每项新任务使用新的 `Message-ID`；回复用 `In-Reply-To` 对应实际处理的任务。
- 不根据文件仍然存在就重复执行已经完成的 `Message-ID`。

简单任务可以使用简短邮件；复杂任务应明确 Goal、Scope、Requirements、Constraints、Verification、Git Authorization 与 Expected Reply。

## 7. Git 公共规则

详细流程见 `docs/SOPs/Git-SOP.md`。所有参与方共同遵守：

- 一次提交只做一件事，按依赖顺序组织最小不可分批次。
- 一项小能力通过工程 Review 与相应验证后，及时提出提交方案并获批执行，不等用户答完笔记或整章真机验收；提交不等于学习通过或章节完成。
- 中单元完成后可单独规划 squash；改写前用获批的归档分支或 tag 保留细粒度历史。整章 squash 可选，不为层级整齐强行合并有意义的提交。
- 不提交空骨架、半成品、已知错误、生成物、秘密或无关文件。
- 没有用户明确批准不得 commit；commit 授权不包含 push、amend、reset、rebase 或 force push。
- 暂存必须使用精确路径；禁止 `git add .`、`git add -A`、`git add --all` 和通配符批量暂存。
- 每批提交前核对 staged 文件、stat、diff 与空白错误。
- 不擅自删除、恢复、暂存或提交 stray 文件。
- 提交标题使用中文祈使语气，优先“动词 + 对象”，不超过 72 字符，不加句号，不写 AI 署名。

## 8. 秘密与机器级配置

- 不读取、展示或提交 API Key、token、credential、`.env` 内容或其他秘密。
- `~/.codex/` 与 `~/.codex-deepseek/` 属于机器级配置，绝不能加入 JadeCode Git。
- 项目专属知识保存在本仓库；机器级配置只保留通用设置和极短的角色文件发现路由。
- 修改 provider、模型、认证、API Key、`models.json`、MCP 或 plugins 必须有对应任务授权。
