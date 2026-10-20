# 模块审查：CLI、装配、配置与构建

## 入口和终端

run 和 repl 都装配同一执行器，创建 SessionRuntime。run 等待单项 future，不开定时生产；repl 继续读输入，提交新任务后不等待完成。ConsoleInput 绑定唯一输入线程，审批通过 future 交给该线程，避免工具执行线程直接竞争 stdin；定时任务没有交互通道。y/n 只在 pending 存在时作为审批，否则是任务文本，多行输入整体判断。用户最近选择的简短审批交互不应被此次审查擅自改回带 ID 命令。

TerminalDisplay 在真实终端中用 JLine 处理多行编辑、粘贴和上方日志，管道模式用 BufferedReader；关闭时恢复全局输出流。这个适配用了进程级 System.setOut/setErr，符合当前单 CLI 宿主，却不适合同时嵌入多个会话。当前没有这种产品要求，不将全局流本身列为独立缺陷；若以后做 IDE/服务入口，应注入展示端口，而非复制 TerminalDisplay。

输入中 Ctrl+C、EOF、q/exit 关闭整会话，future.cancel 不取消任务，代码和注释已有说明。run 在终端审批阶段被中断的具体 JLine/SDK 时序仍待运行检查，不能静态保证每一种终端都一致。

证据前缀 `src/main/java/com/jadecode/`：`cli/RunCommand.java:35–60`；`cli/ReplCommand.java:29–49`；`cli/ConsoleInput.java:85–110,126–144,164–199,252–257`；`cli/TerminalDisplay.java:64–100,120–157`。

## 装配职责与包结构

Main 是唯一装配根，这是优点；它还包含提示词和内置观察 hook。当前约四百行有不少教学注释，不能按行数称为巨型业务类。随着宿主增多，适合把 Agent 运行对象的创建与 CLI 展示 hook 分开，让 Main 只选择入口和绑定依赖；提示词也可用专门构造器。现阶段不需要 DI 框架或每个 hook 一份类。

进程基础设施分包见 [ARCH-019](package-architecture.md)；共享任务身份和权限端口见 ARCH-011。按这两项做自然拆分，比按课程 s01/s02 建包更适合长期维护。课程编号适合保留在 jobs/notes，不应该成为生产依赖方向。

## ARCH-016：配置只检查是否存在和能否解析，缺少有效值边界

**P2；已证实（校验缺口）；分类：实际配置边界缺陷。具体 provider 拒绝方式待验证。**

MAX_TOKENS 解析成任意 int 即通过，0 和负数也能进入配置；key 只检查非 null，空白 key 通过；base URL 和模型 ID 只在 null 时取缺省，空串没有明确失败。AppConfig 的公开 record 构造器也不建立这些不变量。错误因此延迟到 SDK 请求、装配或实际业务开始，使用者可能得到远离配置入口的异常。

反向检查：缺失 key、非数字上限已有清楚错误；缺省上限和模型均有实现。不能把 `.env` 优先于进程环境当作 Bug，现有测试明确约定了它。Env 的轻量解析不支持复杂 shell 语法，当前也没有承诺支持。

**具体设计**：在 AppConfig 的构造边界统一校验非空白 key/model、正数输出上限和合法的 HTTP(S) URL；provider 特有上限保留在提供方能力约束中，不写死未知模型窗口。错误只报配置名和安全原因，不显示密钥值。Env 继续只做来源读取，CLI 将配置失败映射成清楚退出码。若保留 .env 优先策略就在 README 明说，避免部署时误判环境变量覆盖结果。

收益是失败更早、更可定位；范围 AppConfig、两入口的错误映射和配置说明；成本低；兼容风险是过去容忍的空白设置开始失败，属于合理显式化。P2 因主要影响可诊断性和无效启动，不等同于已发生安全事故。

证据：`config/AppConfig.java:17,26–61`；`config/Env.java:88–105`；`Main.java:113–116`；`src/test/java/com/jadecode/config/AppConfigTest.java:12–64` 与 `src/test/java/com/jadecode/config/EnvTest.java:66–91`。本轮没有读取 .env 或真实配置值。

## 构建、依赖和小工具

pom 为 Java 21、单模块，toolchains 同时约束编译和 fork 测试；shade 生成入口 JAR，合并 service 发现资源，并移除签名文件。jade 在单次进程选择 JDK 21，不修改机器默认 JDK。脚本按自身位置找 JAR，但保留调用者 cwd 作为工作区，这与源码按当前目录创建状态一致。默认 JDK 路径面向 Linux，同时允许 JAVA21_HOME 覆盖。

依赖版本写在 pom，JLine 版本集中；Jackson 两个模块显式同版本。单模块没有必要拆 Maven 子工程，当前包边界调整足够。没有运行依赖解析，也没有核对第三方漏洞公告，不能在本报告断言任何版本安全、最新或真实 JAR 可启动。

Json 是共享 mapper；若未来运行时动态改它的配置，会影响多个存储，所以应保持初始化后配置不变，目前没有发现生产代码这样做。Ansi 是极小展示 helper，颜色仅在 console 存在时启用。它更接近 CLI，但现在移动收益小于进程分包，不需要把本次架构演进拆成大量目录美化提交。

证据：`pom.xml:17–163`、`jade:1–16`、`.gitignore:1–38`；`util/Json.java:13–25`；`util/Ansi.java:10–44`。构建文件行号在终审核对，不以静态阅读冒称 package 或 test 已通过。

Review：**Non-blocking**。构建和 JLine 终端集成的实际运行结果不在此次只读审查证据内。
