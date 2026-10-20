# 全仓库审查覆盖记录

审查开始日期：2026-10-16（Asia/Shanghai）。唯一写入范围为本目录。只读静态分析，不构建、不测试、不启动 CLI、不请求模型、不提交。

## 基线

- HEAD：`f15388d24defe210bb7a82d3b53b437bba7dfe7c`；分支 `main`，起始状态与 `origin/main` 无领先/落后标记。
- 既有修改：`docs/notes/s08/offline-acceptance-guide.md`、`docs/notes/s09/memory-recall-review.md`、`docs/notes/s10/task-system-review.md`。既有未跟踪目录：`docs/notes/other-agents-analysis/`。staged 为空。
- 结论以实际工作区为准。下列 SHA-256 仅作为审查前后内容一致性核对，不是执行验证。

## 阶段与模块

| 阶段 | 状态 | 产物 |
| --- | --- | --- |
| 一：清单与全局调查 | 已完成 | 本文件、01-global-architecture.md |
| 二：逐模块 | 已完成 | 02-modules/ |
| 三：跨模块与工程 | 已完成 | 03、04 报告 |
| 四：反证与综合 | 已完成 | 05-final-report.md |

实际模块：装配/CLI/配置；会话与执行器；LLM/消息；工具/权限/hooks；上下文；记忆；子代理/技能/todo；持久任务图；后台进程；cron。阶段二另按用户指示增加包架构专题。审查沿入口、状态所有权、故障边界与包依赖展开。

## 排除规则

- `.git/`：版本数据库；只通过只读 Git 命令获取基线，不审计内部对象。
- `target/`、`dependency-reduced-pom.xml`：既有构建产物，不以旧产物证明当前源码。
- `.env` 及秘密材料：不读取；模板单独登记排除，配置机制从代码核查。
- `.task_outputs/`、`.transcripts/`、`tmp/`：运行数据和人工验收产物，不属于产品实现，既不执行也不读取业务内容。
- `docs/notes/other-agents-analysis/`：其他 Agent 研究资料，按用户明确限制不开展对标分析。
- `.agents/`、`.codex/`、`.vscode/`：开发工具的本地辅助元数据，不是 JadeCode 产品运行配置；只登记存在。
- 不读取第三方源码、JAR、缓存和机器级秘密配置。

排除目录的文件数量（含子目录，按目录项统计，不读取内容）：

- `.git/`：2199
- `target/`：250
- `.task_outputs/`：14
- `.transcripts/`：6
- `tmp/`：69
- `.agents/`：0
- `.codex/`：0
- `.vscode/`：1
- `docs/notes/other-agents-analysis/`：11

## 可逐项核对的相关文件清单

状态四类：已深入审查 / 已完成基础检查 / 尚未审查 / 已排除。基础检查只表示已读结构、职责或测试声明，不等于逐分支证明。

| 路径 | 覆盖状态 | 说明 | 起始 SHA-256 |
| --- | --- | --- | --- |
| `.env.example` | 已排除 | 秘密配置模板：遵守不读取 .env 内容的边界 | `未读取` |
| `.gitignore` | 已完成基础检查 | 读取构建/脚本/忽略规则，核对架构关联；未执行 | `10136a21da9eb5fe432ade97462c53837ccb73c3825a8774f520dd4b2dfbc752` |
| `AGENTS.md` | 已深入审查 | 核对当前职责、契约、运行边界和版本提示 | `5d6655cca2f57d9ce14b345e0eaae4f4b0e3995eb5cc023859ae7172a6ab7deb` |
| `CONTEXT.md` | 已深入审查 | 核对当前职责、契约、运行边界和版本提示 | `4ab2f9bf723b255323f45475daedbca74e946101adf6b47a8e5f41d9beebf10a` |
| `README.md` | 已深入审查 | 核对当前职责、契约、运行边界和版本提示 | `e19d2af9003c8adf035a5b301265a52030a1535bc2d8b18b21c4b76934721058` |
| `docs/SOPs/Git-SOP.md` | 已完成基础检查 | 检查本轮授权与流程边界；以用户只读限制为准 | `9fa46f5fddf45dbe6b82ea1f236d38218e5086e0d05426a9f8fae9703a807b22` |
| `docs/SOPs/Learn-SOP.md` | 已完成基础检查 | 检查本轮授权与流程边界；以用户只读限制为准 | `c41ad7c1582b96167e06df3fea16a20ff0eb2778a7a98ffddfc500fc7a7ca002` |
| `docs/adr/0001-background-task-completion-dependency.md` | 已深入审查 | 核对当前职责、契约、运行边界和版本提示 | `b7355e9d8dcd3c0b5e4d11a402fc5eed8d5c93f1269385a489a3d634d9652c96` |
| `docs/adr/0002-session-event-processing-boundary.md` | 已深入审查 | 核对当前职责、契约、运行边界和版本提示 | `51cfa35ebf041f4553f6b4423269c13579360e91f2e66264896136206035b4ee` |
| `docs/jobs/s01/agent-loop.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `1ebbff33015e8f9d5638b4e09bc43931cc7e6748ccb8d40b913c5bb19d3228be` |
| `docs/jobs/s01/bash-tool.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `f375313eecfbd3423fdff6d30b425aaca60ae30e801d94b00e83b83790dee344` |
| `docs/jobs/s01/cli-entry.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `de99c1caef9a1915f21c6090e5d3017df43089080637f511510d6baf096f7394` |
| `docs/jobs/s01/llm-contract.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `1c54884a318209dc29be26a6335c7b5d7e5d93885a634b414b7ad434cc948a33` |
| `docs/jobs/s01/sdk-adapter.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `6a551f8982cb9da8db3efea9efa438227814c171c2d6990cf271ffbf140ba43f` |
| `docs/jobs/s01/smoke-test.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `c7bfd9cf55a88eb81333247b020cfc365924a92281bd6d4c153d15f429e55ad3` |
| `docs/jobs/s01/tool-contract.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `a8530bfe23850d6c4c13e6f5313af7a94a557436b500ee1db558b5569a8af3d7` |
| `docs/jobs/s02/file-tools.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `87d582e9f64f741ac5a5a7ab51330c44c24cc2496d575c265f9ff59b00a222f0` |
| `docs/jobs/s03/permission.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `7ea7671644b856166058ccb383cd603fe2dabad42e6f2c33d781ed5ab1d17916` |
| `docs/jobs/s04/hooks.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `483bec5654a550b29cabaaf51e38297bb8d92d571c0e7794edbcccea388afd2c` |
| `docs/jobs/s05/todo.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `8dcee8e459d5674177d2edfb3f09ccbcce1ee85e39bebf09e4acccfb7547f7f7` |
| `docs/jobs/s06/subagent.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `1ea5e9752e2aca9bee2e068a9173871ef9e48a17967b168ba944c9943be63ad7` |
| `docs/jobs/s07/skill-loading.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `cfe74d6b0b0aea5589dff546307d2423c779870f9dd07759914f5272da669330` |
| `docs/jobs/s08/context-compact.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `ea563645392fca62c731faeaf3af216098a5fa6388193f6008cf6514b4a54bde` |
| `docs/jobs/s08/main-context-compaction.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `0213f619e7357037301296a18d9f8133c56fcc9f567e5d7befba5bcc1cb88d17` |
| `docs/jobs/s08/offline-acceptance.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `90c8b5a1c2e2bb4c2f592da9c1df2eae26af765083320b8cfb790a40d72aabda` |
| `docs/jobs/s08/parent-auto-prepare.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `365e41d611fb8f8916667ece5f10f7325cab0fa180a6463cda3f9b115243217a` |
| `docs/jobs/s08/parent-reactive-recovery.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `cfc53ac028980c7b7175c22bd0caa6696fae4fe4ffc2ec7c75cd0d5c9eb3b80a` |
| `docs/jobs/s08/subagent-context-compaction.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `0622d36efd44d213bb7c2458ea5343c9611e1b696fbe84ebd8c918dc8e5f7e9b` |
| `docs/jobs/s09/memory.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `0c3990d2646fbebadf0fb4178e2889f0c99f14df0c67e7a71f49d37011851ec9` |
| `docs/jobs/s10/task-system.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `174c3fd526c5041ddf08ed8c78e83350837cb15bc4436be123db2b623d5ec647` |
| `docs/jobs/s11/background-tasks.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `3dad808b862e65ade87df5b5a07ca3932aff68418a720848fba1276eb4ebc2e0` |
| `docs/jobs/s12/cron-scheduler.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `d1108a4305117ca12afc6b2236e0a8b77394433344608a608754b26fd2a8a8f0` |
| `docs/jobs/s12/session-event-refactor.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `0438cab5e00a6164f87bde73d2ba9ca37970c94ecc1802e4a8453b077af8f995` |
| `docs/jobs/s12/terminal-and-acceptance.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `0a0e4869d3ee74325409176049ead640260734672712ee38d265f7879f9e8393` |
| `docs/jobs/v2/path-fence.md` | 已完成基础检查 | 检查主题、阶段状态、职责及相关失败契约；历史全文未逐段深审 | `1a143044e9454d43a70a9af0b7231eeb09017686d241bb1004ad3b113f4b79e4` |
| `docs/notes/optimization/optimization-backlog.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `d241aafbc3e07c994099a79185b9a6dcd8b360d50f8d4f7dd3630de09435456e` |
| `docs/notes/optimization/session-storage-path-design.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `510bfa7b455c7b69c316980c3e990b0ad7b6674b125256335d24f0e60374c887` |
| `docs/notes/s01/agent_loop.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `d6ae5b522562c85ff157ca1473b1fbadbdb77fbb1adb04afe1f5b878842583d7` |
| `docs/notes/s01/config.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `8d7a20877cb1e035fceae4c123d6f603cb1224d80f0aebbe3bf1d7354f0d0b35` |
| `docs/notes/s01/messages.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `0b5be20d997f2b162bccf4caeddf08389a14836c06a55e4236d334786638cfe5` |
| `docs/notes/s01/tool_json.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `41b17bda2b1c577d0341e237b0c1a82529125435d2297608fee6fa6105701c01` |
| `docs/notes/s08/active-request-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `5d721aee85ff57b4ca350d05bc2e92f14e5d91805b17d0e4c9d652e8adc6ad3d` |
| `docs/notes/s08/compact-tool-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `2d452250ba3a6eb19021d368074e9ccb37f8bfc43acdcb926a66ab6f4068b82e` |
| `docs/notes/s08/context-overflow-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `6b7b7bc30980043220162ee4d1ae63fe2acbdd4804d674843065ec7bb69de55c` |
| `docs/notes/s08/main-context-compaction-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `22e0578bdeac3f7f756aa4d0b182b3bbfbd360528d5926be266acf34272b24b4` |
| `docs/notes/s08/offline-acceptance-guide.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `9fe051a2420dc4d1e03148d33f765284e51d080339c57cf15b9db93f52b568f5` |
| `docs/notes/s08/parent-auto-prepare-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `4a4fb5e600df8db39ccc5a755d613addf044bbb6384b5c9458da4726f1e838d3` |
| `docs/notes/s08/parent-manual-compaction-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `7ee61e5bcdec4766273e836c739fe65f1539874c2fa5794f233a81ee6057b1a7` |
| `docs/notes/s08/parent-reactive-recovery-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `ae18d181b62503b1606d7eb8fcc6ce80210b852f442e8a5222bb1f43900e0c40` |
| `docs/notes/s08/reactive-compaction-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `17cbd2de351378214d41c6936a6f6678b99cd1597995d70a4ae8b11a68a5b40a` |
| `docs/notes/s08/subagent-context-compaction-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `eb36451ce3a3a7ce5b46ed06062306c7e26c6ac47b18680cde7e3028ea0214f2` |
| `docs/notes/s09/memory-extraction-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `69c8853fe1bf983c93b7c5bc4011e0aae8d3c708c6aff739c60ffd54dcee73b4` |
| `docs/notes/s09/memory-introduction.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `41b21a56fe68e8a095655bdb50239bc87acdb72b9e13a1ab84af718ab7181e42` |
| `docs/notes/s09/memory-lifecycle-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `3114f7e8b9dcd27fc4ee1a903beddf55f74504f60108fdbb1827a54342eff089` |
| `docs/notes/s09/memory-recall-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `1d8337ce11aada3305c9cc8be3914d86cc2bb8ac6b8ccd2cb129566832666cde` |
| `docs/notes/s10/task-system-introduction.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `8254ad400429ecf706c47f59cc2b12f817b84be7cf71cb5f4e9127c63bde6507` |
| `docs/notes/s10/task-system-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `4b94aefaa0ac2259dc896d298843dd929dfb9e96a3711ca9e35393400c05c1b0` |
| `docs/notes/s11/background-tasks-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `b082ed5622866ec78c4c39d91004d91e8ea081eb5cf89c3f25debe22ec70d45b` |
| `docs/notes/s12/cron-scheduler-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `b4b81719ce12702185c5ca6a3aa909222fc6477308e254c4f3a32436d6dfc1bd` |
| `docs/notes/s12/session-event-runtime-review.md` | 已完成基础检查 | 检查学习入口和架构边界片段；不批阅/改写答案，非全部历史正文深审 | `7be6577c99e5d56ed7f35c69adef4f7c2bfbc39ab0e3afd12050b94098b0f717` |
| `jade` | 已完成基础检查 | 读取构建/脚本/忽略规则，核对架构关联；未执行 | `89ca68b31891d4a202d4b2ff12dd8b64b3517c9719daeff095f1b5d13cd67357` |
| `pom.xml` | 已完成基础检查 | 读取构建/脚本/忽略规则，核对架构关联；未执行 | `a9e2206bc11369ec817958e66480e9d81e2605f5ffb0038cc7be33a8f55b2bd7` |
| `skills/jadecode-git/SKILL.md` | 已完成基础检查 | 作为产品技能数据读取，未调用其中工作流 | `a0546d849052ac537dbbffbbf2bd421cf440633301155a417620d12759854ac4` |
| `skills/java-code-review/SKILL.md` | 已完成基础检查 | 作为产品技能数据读取，未调用其中工作流 | `85167cf92f78b133dc91666a8ab59c0ffadb661d93cd50e55648a329a3168f4f` |
| `skills/maven-test-diagnosis/SKILL.md` | 已完成基础检查 | 作为产品技能数据读取，未调用其中工作流 | `acfd0f7067997654080cdaf9ecac4f749ee149702d0b6a8af1c414d9a043dfa6` |
| `skills/repository-orientation/SKILL.md` | 已完成基础检查 | 作为产品技能数据读取，未调用其中工作流 | `4340a5342746596b39f28711c8ef6935b40ab8721b7921d7521f6bbc083389c3` |
| `src/main/java/com/jadecode/Main.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `0fd1343904744ca4ae0afe786f64dea0f046683d4442a65261a3d7a75661db0d` |
| `src/main/java/com/jadecode/agent/AgentLoop.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `2144be2981fc29d1963bda096b823af80abce3fb40d7f00a4ed8e4d427983106` |
| `src/main/java/com/jadecode/agent/CronProducer.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `4c72c2425640c3c2193b255eb2c15c47379153500dd460b29cac44f671d1f224` |
| `src/main/java/com/jadecode/agent/ExecutionContext.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `405425c80748d4777504bceae804644156435aa617306b1d82459b4a1e4e5e26` |
| `src/main/java/com/jadecode/agent/SessionEvent.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `c6a43ff9da847914ae6e935fac984de5c99e06eb4ff954bd6879e09ea93d81b4` |
| `src/main/java/com/jadecode/agent/SessionInbox.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `fb14cf6cd6d3878cd94e7779254d6f6de7b0b072c3e7206fa6cd88c10e33d940` |
| `src/main/java/com/jadecode/agent/SessionRuntime.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `5c90e05e45dd10805a960b2c0da87136e3d0fcf8c57627776ab3e0054c2e7eae` |
| `src/main/java/com/jadecode/cli/ConsoleInput.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `a0839a3ac9af4cda5201be21207c40b5051a58c2e9ba823afac942b2a38accd6` |
| `src/main/java/com/jadecode/cli/ReplCommand.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `d349135d283e522c049324f028ea38c613fbaabc370e02c758838324f5b5f26e` |
| `src/main/java/com/jadecode/cli/RunCommand.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `280f7c7038868c9f061f77dddc09afc81d3c77b7868696cdb33fd9b631ee4864` |
| `src/main/java/com/jadecode/cli/TerminalDisplay.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `766402376e7d25bd2fe033c320db2f887ddae8325621275a8c036103477b8cbb` |
| `src/main/java/com/jadecode/config/AppConfig.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `3c905a7c180fe417df3a7b2c22eb9e81850eb9551e03c81723f022adabd01c6b` |
| `src/main/java/com/jadecode/config/Env.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `dcdeb483bb2a2446822812b42b279b142ab631229037a2b0eaee06d34de976ab` |
| `src/main/java/com/jadecode/context/CompactionLimits.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `0c9b0267cbf057b514bc05e0d4d78a52698ecfc8685189b6bf283e9646b75fd7` |
| `src/main/java/com/jadecode/context/ContextCompactor.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `b8ab70df820980ef7cd26d7a5962d43501a308c4889f85ce86dce5f01c5574b0` |
| `src/main/java/com/jadecode/context/ContextSerialization.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `85d8409132ff7a5a4f2cccbb0200c949271383e931b20d4dbd66e88651d014d3` |
| `src/main/java/com/jadecode/context/HistoryCompactor.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `8735a3c91bdc9c23f808f4e865f514aa53b7e92c0adf38384a8aa4fa5bd1fcbc` |
| `src/main/java/com/jadecode/context/MicroCompactor.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `73d3f6d6319be02efb33f712f20a1fcdc59f48632255f4475808359ed44ba4af` |
| `src/main/java/com/jadecode/context/SnipCompactor.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `f42e4cbd4258c2878fce06aa95d3e4a31c1c3ce1eb6158e831ed66635b346d09` |
| `src/main/java/com/jadecode/context/ToolExchangeBoundary.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `76f1f7c85246aaaf2e6951e8cc2379cb1157ccaebf2d94ee82203d0245c6fa52` |
| `src/main/java/com/jadecode/context/ToolOutputStore.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `c52be6f9485a18cdf0f8ed749b2d22ae64a6fb9f3ae61d5d635b2159e228de2d` |
| `src/main/java/com/jadecode/context/ToolResultBudgetCompactor.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `c19b0dedba4b29022c5ba4557bd07d97807982c04b0441e2713947f4c4220752` |
| `src/main/java/com/jadecode/context/ToolResultFitter.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `bdb4a5b6e5190080659b411175a6ec1403216e6676ba7874757cb9c0f1027c06` |
| `src/main/java/com/jadecode/context/TranscriptStore.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `6f278fd4655981d6031408ed1c9fad7ea697f6fa1aee9a5ef9b1bc722eeeac88` |
| `src/main/java/com/jadecode/cron/CronExpression.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `a15979ac96948057c60406a1c443d209e4d0621462df3ac8533f73d05d874127` |
| `src/main/java/com/jadecode/cron/CronJob.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `a77cd6340043bb15ff306fb5a573006d0b53891a779e84e1ef851a2a235e1153` |
| `src/main/java/com/jadecode/cron/CronScheduler.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `b5d906a7db690b55fa4f3a2b1563df4a9dc334c275011642d97084773ebb3cc1` |
| `src/main/java/com/jadecode/llm/AnthropicSdkLlmClient.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `a2a12d16d7e161dcdbf23dc60c7d79f7e630a1d7494e24382b4f316ebef92b84` |
| `src/main/java/com/jadecode/llm/CompleteRequest.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `8f5976956a9efc401d4a9ed1f3f473fbf292896ab6a09ff2e3be1c217ae2444f` |
| `src/main/java/com/jadecode/llm/LlmClient.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `cf07410b8877b2de132ed5e3c012ca32afc43edcf3e72dd17188d6713d9d2224` |
| `src/main/java/com/jadecode/llm/LlmException.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `d6ee5eed2857c9593c8578489c3b6289e0be8d572bb73838092c708b62d99753` |
| `src/main/java/com/jadecode/llm/ModelResponse.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `2e9b7be4b0c74a62d1abe495dfde76b77904b89451eb5d53b609b4ca4308081d` |
| `src/main/java/com/jadecode/memory/MemoryConsolidationService.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `268b63a53c72e65e401a9a8713b7516bcaa880f54a1e3046eedcba8fc160a282` |
| `src/main/java/com/jadecode/memory/MemoryExtractionService.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `00f21de64744221a57112bd7ba444712e6bd73c3c26646894a6c82aca3e2ae12` |
| `src/main/java/com/jadecode/memory/MemoryManager.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `07855cdfa281a44abbcedab56356fbede3c4285225e7058919bb9adafe4cc77c` |
| `src/main/java/com/jadecode/memory/MemoryRecallService.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `d0988820c24532807df5746dbe6fd911623e0db33efc44b70f0dd6630cb7ceba` |
| `src/main/java/com/jadecode/memory/MemoryStore.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `e7cee00bef791cf129892c78ac00500f907b8dcd6d68c6e6a2f152a4d2b18632` |
| `src/main/java/com/jadecode/messages/ContentBlock.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `f30a94422966afc841b48eabc969b807a440432f2605a75b38826975a1e86a79` |
| `src/main/java/com/jadecode/messages/Message.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `5df6beeaa0fbfbbf732e4f57d0b15e94abea2a5015c5aaaa1f9504333351befb` |
| `src/main/java/com/jadecode/messages/Role.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `1203622be9161db24be93503b39c77bef26aab6f5b4e7de904a1dd183a86f97d` |
| `src/main/java/com/jadecode/messages/TextBlock.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `fa2c3f0cc7bfdbde2d6456c9ac0bb224735ae7bfc2ace09ee4aabd430ea11e99` |
| `src/main/java/com/jadecode/messages/ToolExchangeProtocol.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `abd724fa31e0dde1860b3b0a0f114ca9620e5415e374db75a415fc53b32cc199` |
| `src/main/java/com/jadecode/messages/ToolResultBlock.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `3c57a8725ef250ca8f2dfe3f0a2b7328f20852dfd5e0eaacba3f305888720e0a` |
| `src/main/java/com/jadecode/messages/ToolUseBlock.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `c1f4347c1625a3473f08375e2ff5df4f5eb638a017309d19639c94bd8d50b837` |
| `src/main/java/com/jadecode/safety/CommandPolicy.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `d0d10efcf6e1a207771842e6401a331079ada71ff224961ee5e9270ca5d8a974` |
| `src/main/java/com/jadecode/safety/Hooks.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `8d2f957428d55a871ba63ba4b55e52538fbeaecedc1bb8128f78b1f73a28cde0` |
| `src/main/java/com/jadecode/safety/PathGuard.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `2130a0ba58cc2f12ace01d3d4272809c6824ab95ac0f51527ecd3a37924065b0` |
| `src/main/java/com/jadecode/safety/PathResolver.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `b2cf19ea098789d0c06d785f54dd6346aa6f1926babb9b4ca16552f6e6a35658` |
| `src/main/java/com/jadecode/safety/PermissionGate.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `731ed4c1f9ede4de867a90a9b4f156fc9c72aab1b380382abc5201c1feaa8295` |
| `src/main/java/com/jadecode/skills/SkillDefinition.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `d1cc14ce2dbf938b190c66bd60ba30730d904ab7b95076432fcb1736516ab37b` |
| `src/main/java/com/jadecode/skills/SkillLoader.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `42421857f51c61bcba232df1b09a759607ee8786731fcec74a062279a922990e` |
| `src/main/java/com/jadecode/subagent/SubagentOutcome.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `36566b157eabe6a8a0dfb7f43b1c5315cd382964a5b29a243dcd72fe62bd7c33` |
| `src/main/java/com/jadecode/subagent/SubagentRuntime.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `3d415c32b1c63975acb1892d019e187cdd2f430e664fc16cf181ba0f648682d2` |
| `src/main/java/com/jadecode/tasks/Task.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `0ed0a05746a4a045eff1194d77fff06e2b7b2b2544f9d86bf73f6b0814dca1d1` |
| `src/main/java/com/jadecode/tasks/TaskStore.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `1027caa780d3115bf41b9343112ee5ea65938d7a8f258964caadfa22e64dd46d` |
| `src/main/java/com/jadecode/todo/TodoManager.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `fc3d41959deff8683cdd05bd2094a8aee3fc2471eae9614a1289da248be47ffc` |
| `src/main/java/com/jadecode/tools/BackgroundManager.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `6dddd59a8344ee49e2cfe38ec0a3d2eb6b6eeea906f1a141df6f8a086a3eec02` |
| `src/main/java/com/jadecode/tools/BashTool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `8d8bd740ecd625aaf554dd3e2678635aec63cae90bfdb8f0c3b4a871dfc38d7e` |
| `src/main/java/com/jadecode/tools/CompactTool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `f1b1180a4090352c0881e3937a9da1f1bce08a865167984feb8257f4f62e8eaf` |
| `src/main/java/com/jadecode/tools/CronTools.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `e6e9ec72fb223b0a84573750e2abdeddf412b02ece5ca0471af05cda547001be` |
| `src/main/java/com/jadecode/tools/EditFileTool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `9a6380382550af65f1746cfe63444fe54c092f18c580ca0140a571c18fc1f5e6` |
| `src/main/java/com/jadecode/tools/GlobTool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `6a33f2e49053ec1b8c83a87d2d23096d854a7e2d8564f7946a115bd8fa193c8a` |
| `src/main/java/com/jadecode/tools/LoadSkillTool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `0731b0f6661923b6a8c2a4f49e6d35dab6772d6e28900e0a4e69c827837b2427` |
| `src/main/java/com/jadecode/tools/ReadFileTool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `e6f084a5e0708499c5b5986f4e080bc9c90f80c02a7a8949fab98a8495d9509d` |
| `src/main/java/com/jadecode/tools/ShellCommand.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `c86618fb5784b8de20a0c25069da1d18484ee4cec0999c0ebb5a6d5a47b448cb` |
| `src/main/java/com/jadecode/tools/SubagentTool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `9cb0661dfc0dc50e53b147df600391f8e03f5068166203fda01bb870af62810e` |
| `src/main/java/com/jadecode/tools/TaskTools.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `052ba42349a42319829fc7733d26304004801f597291f0252a6424761e71cd67` |
| `src/main/java/com/jadecode/tools/TodoWriteTool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `f3aadb72aff65dd42e4abf8d4648bb71876997a1fc379d02f2f1b6b13068066e` |
| `src/main/java/com/jadecode/tools/Tool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `1dc44fdf0f5c62d0d0476d8bbff2d4f690d5bf72877e66fe65ef82edb4b229ee` |
| `src/main/java/com/jadecode/tools/ToolSchema.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `fe1715a658da95b5cbeeb5a668d02028a5fe8c8deab5cc285b644cfeb9c4e38f` |
| `src/main/java/com/jadecode/tools/WriteFileTool.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `431bb1d6447c2154ccd8b0eb1b69cfa8dfd5d1cf43264208ac7339bcc014decc` |
| `src/main/java/com/jadecode/util/Ansi.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `fcb2f63fd515b9e1d2dfa01ccd6246c4c4426b6f1571204a2e789cc3f757d74a` |
| `src/main/java/com/jadecode/util/Json.java` | 已深入审查 | 已沿产品控制流、依赖和边界审查；见对应模块报告 | `75757ccbf1c762256ecbb75b3cdb4c270839c9749cca0281a5e72700f35adc24` |
| `src/test/java/com/jadecode/MainCompactionAssemblyTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `e488d4c5bad73200cf2bc7fea0fad056a89e3e4252b17e5534d2049c4c528139` |
| `src/test/java/com/jadecode/MainTest.java` | 已深入审查 | 全文读取关键测试/假客户端，核对反例和实际断言；未执行 | `fb8111fa45dd334432d5fa83050c3d390e13fc7ce0b632afe729e98b7af5f44f` |
| `src/test/java/com/jadecode/agent/AgentLoopAutomaticPreparationTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `3a0d7d48c4c91445719006deb35b8d21efdc36592f5b9b54b5a181444050d392` |
| `src/test/java/com/jadecode/agent/AgentLoopManualCompactionTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `f6de78d1a4792ef46957cd451b09b0cc7855a443c37755eef9b2a3f0cc5c2379` |
| `src/test/java/com/jadecode/agent/AgentLoopReactiveCompactionTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `6cfc563a1789cbcef40cbba1028c1e98797fd90a0a3644b635c6f811bf145340` |
| `src/test/java/com/jadecode/agent/AgentLoopTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `2ea58af243ded2446de9d9efec7e1319cbe46887c3ab97482c10654fcba9ac91` |
| `src/test/java/com/jadecode/config/AppConfigTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `371f8857ff85cf02408299351d03a6a69e859e49cdb7d02229e1fdc492939887` |
| `src/test/java/com/jadecode/config/EnvTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `9f2d2f0b694443bd3e68572d2bda11a99fa2500f588174c8d550b091c02d1966` |
| `src/test/java/com/jadecode/context/ActiveRequestExtractionTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `b0cc56e55ac7d9ab974b27aeba133b1f6290a74ba9b71e29d1b7487d8bff40d9` |
| `src/test/java/com/jadecode/context/ContextCompactorApiTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `8e6b9f5db29065eb644673b6e9eb7ea0e451ecd96f63e5269058485fe918e3d5` |
| `src/test/java/com/jadecode/context/ContextCompactorBatchCompactionTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `a7508aaeaaca8e00f05f9c2f5eb3b525beb16503c5969520bf6382ecfad17ca3` |
| `src/test/java/com/jadecode/context/ContextCompactorPreparationTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `286e7709c478a01b69f28de9ca21f307a47e001b7e2fd898b6f07e518f7c60db` |
| `src/test/java/com/jadecode/context/ContextOverflowDetectionTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `9f241defdbe4d478d204d4bc63f78fc4ffb03cdf3ceecac22538de5c0186e573` |
| `src/test/java/com/jadecode/context/HistoryCompactorTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `f7601730f8d3c3bba08e216f8d37a748e2f26b780f01f115f7f8a5df6ae40996` |
| `src/test/java/com/jadecode/context/MicroCompactorTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `fdb34535391103d156160e3376f66412f14f41737ced4d9b4ad28ccbe12e30dc` |
| `src/test/java/com/jadecode/context/ReactiveCompactionTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `1ffba65f4eaaa9a7b1bd515c1e869f0f7996d4dfe566fd6db0321b18bc61ea62` |
| `src/test/java/com/jadecode/context/SnipCompactorTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `1e42f3451e2709c2b92e0e24c4aef676d08d7d275b20f41e1de92c13620aabd6` |
| `src/test/java/com/jadecode/context/ToolOutputStoreTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `9a20f19baf3d043aac33f69df0bd333e19f661661de5f22e70a7623e939428ad` |
| `src/test/java/com/jadecode/context/ToolResultBudgetCompactorTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `e7438224fdac1c04547259d4879f34e3f5a74635dd940472058b40d76545419e` |
| `src/test/java/com/jadecode/context/ToolResultFitterTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `d4634c597518e0c0a7a8a322da5ab8c29c7b55a4c5cbdfef3ff532f2bbc8d575` |
| `src/test/java/com/jadecode/context/TranscriptStoreTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `910b377de2d684da041391fa02a3203a615a1b4c62fc2e3a44e1193584ff8569` |
| `src/test/java/com/jadecode/llm/RecordingLlmClient.java` | 已深入审查 | 全文读取关键测试/假客户端，核对反例和实际断言；未执行 | `2b69c7e7780fecd18468378c97aba84408c46392864c2a11d080f4bd6f3af943` |
| `src/test/java/com/jadecode/llm/ScriptedLlmClient.java` | 已深入审查 | 全文读取关键测试/假客户端，核对反例和实际断言；未执行 | `8c5e1cca4528a95bc346aee82ffc167c28c37b3d109c3291c46b3b0a99cc910c` |
| `src/test/java/com/jadecode/messages/MessagesJsonTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `da6aab80c3468f77c8030c2256813b88df50614afa28c0c96b8ca2a833aa762e` |
| `src/test/java/com/jadecode/messages/ToolExchangeProtocolTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `f5d77ede44a694b40a5e224882f1ce65d4b0fc73a8ff1fc1dcb8f61dff301250` |
| `src/test/java/com/jadecode/safety/CommandPolicyTest.java` | 已深入审查 | 全文读取关键测试/假客户端，核对反例和实际断言；未执行 | `b857250907af7f163f90a340d88ee6b7e8eb7a20636e165262977c226578c175` |
| `src/test/java/com/jadecode/safety/HooksTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `cd33a4f444d8ead90553ec79b3db698bce9dc7d30f026387936ac8fa42fe7a28` |
| `src/test/java/com/jadecode/safety/PathGuardTest.java` | 已深入审查 | 全文读取关键测试/假客户端，核对反例和实际断言；未执行 | `c1f29c6397e945178f08a0e26e5840324011e3398a37f4d4a2531aeadcfc2c16` |
| `src/test/java/com/jadecode/safety/PathResolverTest.java` | 已深入审查 | 全文读取关键测试/假客户端，核对反例和实际断言；未执行 | `8ff6d0e98b0ad05bdbc78cdf3daa9a6b81b956adc5893f655c8a6d12fcea19ce` |
| `src/test/java/com/jadecode/safety/PermissionGateTest.java` | 已深入审查 | 全文读取关键测试/假客户端，核对反例和实际断言；未执行 | `fce252d54cbd569cf0f5bf866ad10fc6a0fdf3ec7a9fce07fdc9ff09c110293f` |
| `src/test/java/com/jadecode/skills/SkillLoaderTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `2642717580d620721067fcf77c0a985fa88f3103212dab74e4d55525f76f8e04` |
| `src/test/java/com/jadecode/subagent/SubagentRuntimeCompactionTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `d516cdb27133caccf9a84c6d82a753c3a7487a19919114beb45e9fd7834281c4` |
| `src/test/java/com/jadecode/subagent/SubagentRuntimeTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `480de4e83b69d54e256dcf511d83b725d27aeeeea3f536fb3937bcf8e3e87f3e` |
| `src/test/java/com/jadecode/todo/TodoManagerTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `c97a02636df6eaaaf6ad9c24c98eab1f848962206f056c4c01503628998684ed` |
| `src/test/java/com/jadecode/tools/BashToolTest.java` | 已深入审查 | 全文读取关键测试/假客户端，核对反例和实际断言；未执行 | `4305725170ccf12676d03b40b4daf52f015dbcdb8b729521b11645a4a232b740` |
| `src/test/java/com/jadecode/tools/CompactToolTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `f90fd0f5008a72df237674fbf75073f4c3562cc10071e47bdd447ffd5a189569` |
| `src/test/java/com/jadecode/tools/FileToolsTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `7755e1a549e97717a7165c78aa080a3f9e38286104fc8e9842b714f7af07bf14` |
| `src/test/java/com/jadecode/tools/LoadSkillToolTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `e55a4fa48197726f03184bd753c4ba23929f71917b5089eebc32ece7dd74649c` |
| `src/test/java/com/jadecode/tools/SubagentToolTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `6e7a2e1b54300f6d24057183d3f0e372aa69be14e2fe6e80767f8beef85670e0` |
| `src/test/java/com/jadecode/tools/ToolSchemaTest.java` | 已完成基础检查 | 已检查测试结构与场景声明；重要反证另深入读取，未执行 | `4d212ca1efe3f88ba849adda8c0d2c519e3a92d12543b8897c17f944e0d0b59f` |

## 待验证

本轮没有执行动态验证。具体待验证项见下方闭环记录和最终报告第 7 节。

## 阶段二交付

主源码 73 项完成机制级深入阅读，测试 40 项完成结构与场景基础检查；重要安全、裁剪、输出与装配断言已定向深入核对。模块报告共 11 份，含用户新增重点的 package-architecture.md。基础检查不冒称测试分支全审或动态通过。

## 最终模块覆盖与报告入口

| 模块组 | 产品源码文件数 | 状态 | 报告 |
| --- | --- | --- | --- |
| 装配/CLI/配置/小工具 | 9 | 已深入审查 | [入口与配置](02-modules/cli-config-build.md) |
| 会话、执行器、定时生产 | 6 | 已深入审查 | [会话](02-modules/agent-session.md) |
| LLM 与消息 | 12 | 已深入审查 | [协议](02-modules/llm-messages.md) |
| 工具适配、权限和 hooks（除两进程类） | 18 | 已深入审查 | [工具与安全](02-modules/tools-safety.md) |
| 上下文 | 11 | 已深入审查 | [上下文](02-modules/context.md) |
| 记忆 | 5 | 已深入审查 | [记忆](02-modules/memory.md) |
| 子代理/技能/todo | 5 | 已深入审查 | [子代理等](02-modules/subagent-skills-todo.md) |
| 持久任务 | 2 | 已深入审查 | [任务](02-modules/tasks.md) |
| cron 规则与存储 | 3 | 已深入审查 | [cron](02-modules/cron.md) |
| 两进程基础设施类 | 2 | 已深入审查 | [后台与 shell](02-modules/background-shell.md) |
| 包职责、循环和迁移 | 跨以上 73 项，不重复计数 | 已深入审查 | [包架构专题](02-modules/package-architecture.md) |

## 待验证与未深入范围

- ARCH-001：多层链接、目标祖先链接与检查/使用时间差的实际 IO；没有建链或运行验证。
- ARCH-002：仅按静态权限能力检查，未做沙箱或渗透测试。
- ARCH-003/005：provider 停止原因、长任务裁剪后的模型行为；没有真实请求或模型质量评估。
- ARCH-004/017：记忆整库发布、文件原子替换的失败/进程中断/断电条件；不以源码证明断电持久。
- ARCH-006/008：受控大文件、海量目录和连续回复的资源/预算边界。
- ARCH-013/018：多进程写入、辅助请求忽略中断时的取消及晚到发布。
- Q-CRON-01：日/月步进定义、空匹配规则仍需明确需求。
- 测试完成全文件结构和场景基础检查，8 个关键测试/辅助文件全文深入阅读；其余测试没有逐个断言深审，更没有执行。
- 历史 jobs/notes 完成架构相关基础检查与定向对照，不做历史全文写作审计，不判断用户答案，不修改笔记。
- 排除范围仍全部排除：秘密、构建产物、运行业务材料、其它 Agent 研究和第三方内部实现等。未将发现路径当成深入审查。

## 四阶段产物与交叉复核

- [阶段一：全局架构](01-global-architecture.md) 在逐模块审查前写入。
- 阶段二：11 份模块/包架构报告，按推进逐批写入。
- [阶段三：跨模块](03-cross-module.md) 与 [工程质量](04-engineering-quality.md)。
- [阶段四：最终评价](05-final-report.md)，包含 20 个稳定编号、分类/优先级/成本/回归风险、优化依赖、反例修订和边界。
- 重要证据二次核对：链接链、snip 目标、SDK schema/停止信息、记忆发布、后台确认/唤醒、cron lease、父子失败差异、取消和同步截断。
- 修订记录：ARCH-010 根据任务单的明确例外改为“执行契约分叉风险”；ARCH-012 确认同 owner 仍可继续而定为 P3 优化；既有单层路径测试和归档存在不能推翻目标/权限缺口。

## 只读边界核验

178 项起始内容摘要用于比对（179 登记项中 .env.example 按限制不读取）。终审核对全部一致，未修改项目源码、测试、配置、脚本、旧文档或 SOP。起始 3 份笔记差异保持 12 行新增/2 行删除，原研究目录保留；新增只有本目录 Markdown。HEAD 未变，staged 为空，没有提交或分支操作。

未运行构建、测试、CLI、验收脚本、真实 API、依赖安装或自动修复。允许的 Python 标准库辅助仅用于文件清单、静态文本分析、报告写入和摘要/链接核对，使用 -B，未生成项目产物或 Python 缓存。

终审核对结果：16 份 Markdown；20 个唯一 ARCH 问题定义；175 处带文件与起始行号的证据定位均存在且未越界；报告内本地文件链接有效。覆盖清单合计 179 项：86 深入、92 基础、1 排除（其中产品源码 73 项全部深入）；所列生成物/运行数据等按目录规则另行排除。无“尚未审查”的相关清单项，但基础检查的深度限制仍如上。178 项内容摘要比对无差异，Git HEAD 与原有 numstat 保持不变。上述为静态文本/内容核对，不是构建或测试结果。


## 2026-10-17 架构优先复审

按用户最新要求重新核对执行器/会话/cron/进程的职责与实际调用。新增 [06-package-boundaries-2026-10-17.md](06-package-boundaries-2026-10-17.md)，更新包专题、跨模块与最终报告的阅读入口。原四阶段的 16 份产物和统计保留为当时记录，当前报告合计 17 份。报告现有目录为 architecture/ChatGPT，沿用该位置，未移动旧报告。

本轮只更新以上五份 Markdown（含新增），未改源码、测试、配置或用户笔记；未执行构建、测试或项目程序，未暂存/提交。重点证据为 AgentLoop 对 SessionEvent 和 runExisting 的反向依赖、CronProducer 的现成回调边界、进程类的包私有入口与 ExecutionContext 依赖。工程问题的风险等级不代替架构讨论顺序。


### 同日补充：其余包设计

新增 [07-other-package-boundaries.md](07-other-package-boundaries.md)，重新核对 Hooks、ExecutionContext、LLM 工具定义、父子执行、应用装配及 context/存储职责。当前报告合计 18 份；只新增该报告并更新本覆盖记录和 06 阅读链接，未改源码或运行构建/测试。


### 同日交叉核对：工具契约包环

核对用户转述的 Deepseek 包环意见。更新 06 目标依赖图及 07 第 8 节，补全 Tool 契约归属、ToolSchema 默认实现依赖与父子对 CompactTool.OUTPUT 的直接依赖；同时记录审批接口、路径服务、关闭及 cron 端口前提。报告仍为 18 份；未修改任何产品源码或测试，未执行构建/测试/CLI 或 Git 写操作。


### 同日范围收敛：仅包架构重设计

新增 [08-package-redesign-scope.md](08-package-redesign-scope.md)：11 项架构审阅项、6 项可选暂缓项及明确排除的工程修复；用户尚未逐项确认，不实施源码迁移。报告合计 19 份，本轮只新增范围清单并更新覆盖记录。源码、测试与原有笔记未变，未运行构建/测试或 Git 写操作。


### 最新审阅更新：工具归属保持一致

按用户要求修订 06/07/08：Tool、ToolSchema、SubagentTool 均保留 tools，撤回前述迁包候选。只新增 B07 待审接口选项（由 bootstrap 注入委派回调），不视为已批准。更新覆盖记录，报告数量不变；源码、测试与用户笔记未变，未运行构建/测试/程序或 Git 写操作。


### 审阅裁决：不引入子代理委派回调

用户要求保持现状，B07 本轮不做。已同步 06/07/08：SubagentTool 继续直接依赖 SubagentRuntime，Tool/ToolSchema/适配器均留 tools，不新增 Delegation 等接口，不将消除 tools/subagent 包环作为本轮要求。其余架构事项仍待逐项审阅。仅更新报告，未修改源码或执行构建/测试。


### 最终交接：新会话仅实施 A01–A11

用户确认后新增 [09-package-redesign-implementation-prompt.md](09-package-redesign-implementation-prompt.md)，完整汇总 A01–A11、工具归属保留、无委派回调、B 系列全部排除、行为边界及新会话的验证/提交要求。当前报告合计 20 份，08 已指向最终提示词。本轮仅准备文档，未启动源码迁移、构建/测试或 Git 写操作。
