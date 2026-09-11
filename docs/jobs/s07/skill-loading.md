# s07 作业单:Skill Loading — 目录常驻,全文按需

课程 s07 直译:第八把工具 `load_skill` + SkillLoader(扫描 `skills/*/SKILL.md`、解析 frontmatter、生成 catalog、按名取全文)。

**关键洞察:两层知识注入。**

```
skills/                      system prompt 里只有目录        LLM -- load_skill(name) --> 完整 SKILL.md
├── pdf/SKILL.md     ──►     - pdf: 处理 PDF 时用……    ──►    作为 tool_result 进入 messages
└── review/SKILL.md          - review: 代码评审时用……
```

目录(name + description)是**常驻成本**——每轮请求都带;全文是**按需成本**——模型认为某技能适用时才拉进 messages。这两层严格不混:**完整 SKILL.md 绝不进 system prompt**,这是本章的架构红线。

## 一、课程行为(已读 `../learn-claude-code/s07_skill_loading/code.py`,提炼为规格)

**SKILL.md 的形态**(格式示意):

```markdown
---
name: pdf
description: 处理 PDF 时使用
---
# 正文标题
这里是完整指令……
```

**课程 `SkillLoader`**:

- `__init__(skills_dir)`:存目录、空 dict、**立即 `self.scan()`**
- `parse_frontmatter(text)` 静态方法:`splitlines(keepends=True)`,首行 `rstrip("\r\n") == "---"` 才算开头;向下找同样独占的 `---` 作结尾;中间送 `yaml.safe_load`,结果 `or {}`、非 dict 则 `{}`;body 取结尾之后并 `.strip()`
- `scan()`:`clear()` → 目录不存在直接 return → `sorted(glob("*/SKILL.md"))` → 非普通文件或 `resolve()` 不在根目录下则跳过 → UTF-8 读取
- `catalog()`:空 → `"(no skills found)"`;否则每行 `- {name}: {description}`
- `load(name)`:查 dict;未命中 → `Error: Unknown skill '{name}'. Available: {", ".join(...) or "none"}`
- system prompt 里插入 `Skills available:\n{catalog}\n\nUse load_skill to read the full instructions when a skill applies.`

**本项目在课程之上加固的**(见下节设计契约):目录符号链接同样要跳过、I/O 故障 fail-fast 而非静默、frontmatter 结束分隔符要能识别 block scalar 内缩进的 `---`。

## 二、设计契约(本章规格,以此为准)

### 2.1 技能发现(Skill discovery)

- skills 根目录**由 Main 注入** `cwd.resolve("skills")`,Loader 不读全局 cwd(可测性)。
- **构造完成时扫描一次**;`scan()` 可显式重扫,**每次先清空旧 registry**(重扫要能清掉已删除的技能)。
- 根目录不存在 = **合法空 registry**,不是错误。
- 只扫描**恰好一层**的 `skills/<name>/SKILL.md`,不递归发现更深 manifest。
- manifest 按**路径字典序**处理,保证 catalog 顺序稳定。
- manifest 必须是普通文件,且**真实路径仍位于 skills 根目录真实路径之下**。
- 指向根目录外的**文件或目录符号链接**必须跳过。
- 文件按 **UTF-8** 读取。
- **I/O 失败不得伪装成空目录**:转为带路径上下文的 `IllegalStateException`,启动时 fail-fast。

### 2.2 frontmatter 解析

- 只有**第一行独占的** `---` 才开启 frontmatter;**LF 与 CRLF 都要支持**。
- 结束分隔符也必须是**后续独占一行的** `---`。
- 缺少合法开头**或**结尾:metadata 为空,**body 为原文**。
- 分隔符之间用**项目已有的 Jackson YAML 依赖**解析(`jackson-dataformat-yaml`,pom 已就位)。
- YAML 非法 / 为空 / 根节点不是 mapping:metadata 为空,但**合法分隔出的 body 正常返回**。
- YAML **block scalar 内缩进的 `---` 不是结束分隔符**。
- body 去除首尾空白。

### 2.3 元数据回退与归一化

- **name**:metadata 中非空 `String` 去首尾空白;否则回退 **manifest 父目录名**。
- **description**:metadata 中非空 `String` 去首尾空白;否则回退 **body 第一行**。
- description **最终**移除开头连续的 `#` 与空格,并把**所有连续空白折叠成一个空格**。
- **非 String** 的 name / description **按缺失处理**(不 `toString()`)。
- registry key 用**最终 name**;重复 name 按扫描顺序**后者覆盖**,保持课程语义。

### 2.4 catalog 与 load

- 空 registry:`catalog()` **精确返回** `(no skills found)`。
- 非空 catalog:每行 `- <name>: <description>`,**只含目录信息,绝不泄露完整 body**。
- `load(name)` **只按 registry key 精确查找;name 绝不能拼接成路径**。
- 命中返回**完整 SKILL.md 原文**。
- 未命中**精确返回** `Error: Unknown skill '<name>'. Available: <comma-separated names>`。
- registry 为空时 available 用 `none`。

### 2.5 工具与集成契约

| 项 | 值 |
| --- | --- |
| name | `load_skill` |
| description | `Load the full SKILL.md content by skill name.` |
| properties | `{name: string}` |
| required | `[name]` |
| execute | 缺参沿用现有 `Tool` 文案;其余**透传** `loader.load(name)` |

**后续(不在本次实现,由 Codex 放行)**:

- Main 父工具表增加 `load_skill`;
- 父 system prompt 增加:`Skills available:\n<catalog>\n\nUse load_skill to read the full instructions when a skill applies.`
- **完整 SKILL.md 不得进入 system prompt**。
- **子代理工具表保持 s06 的五把基础工具,不增加 `load_skill`**(这是隔壁 s15 集成版的选择)。
- Loader 在 `buildLoop` 启动时扫描一次;运行中新增技能需重建 loop 或显式 `scan()`。

## 三、实现顺序与现状(①②③④ **均已完成**)

> **现状说明(2026-09-11 更新)**:本章的 `SkillLoader` 经**用户明确授权由 Claude Code 代写**
> (用户判断其属 dirty-work),`LoadSkillTool` 由**用户本人实现**。原"骨架填空"的规划已作废,
> 本节保留各步骤的实现要素,供 Review 与复习对照。

### ① `skills/SkillDefinition.java`(已完整,无需改动)

public record,三字段 `name` / `description` / `content`。`content` 存**完整 SKILL.md 原文(含 frontmatter)**,`load` 原样返回、不裁剪。

### ② `skills/SkillLoader.java`(**已实现**:4 个方法 + 6 个私有辅助)

| 方法 | 可见性 | 实现内容 |
| --- | --- | --- |
| `SkillLoader(Path)` | public | 依赖赋值 + **按契约调用 `scan()`** |
| `scan()` | public | §2.1 全部 |
| `catalog()` | public | §2.4 前半 |
| `load(String)` | public | §2.4 后半 |
| `parseFrontmatter(String)` | **package-private static** | §2.2 全部 |
| `ParsedFrontmatter(Map, String)` | **package-private** record | 已声明,无需改动 |

> 构造器已按契约实现为「依赖赋值 + 调用一次 `scan()`」(课程同款),由 Claude Code 按用户授权实现。
>
> **I/O 三态是本章的硬要求**(Codex Review 的 Blocking Finding):`Files.exists` /
> `Files.isRegularFile` 在"无法判定"时返回 false,会把真实 I/O 故障伪装成"不存在"。
> 正确写法是 `Files.notExists` 只用于**明确确认不存在**的分支,其余一律继续走会抛异常的
> `toRealPath` / `Files.list` / `readAttributes`,由 `IOException` 兜住并包装成带路径的
> `IllegalStateException`。见 §2.1。

### ③ `tools/LoadSkillTool.java`(薄壳,**用户已实现**)

构造器注入 `SkillLoader`;`name` / `description` / `properties` / `required` 按 §2.5 表格;`execute` 缺参检查仿 `GlobTool` 的 `for (String key : required())`,命中后透传 `loader.load(name)`。

### ④ Main 装配(**已完成**)

`buildLoop()` 里新建 `SkillLoader(cwd.resolve("skills"))`(构造即扫描一次);父工具表加入第 8 把
`load_skill`;**子代理工具表维持五把,不加 `load_skill`**;`systemPrompt(cwd, catalog)` 追加
`Skills available:` 块——**只放目录,不放正文**。

## 四、坑清单

1. **不要用 skill name 拼文件路径**。`load` 只能查 registry——name 来自模型,拼接即路径穿越(`../../etc/passwd`)。
2. **不要用跨全文贪婪正则解析 frontmatter**。要识别"独占一行"的分隔符,并正确处理 CRLF;正则很容易把正文里的 `---` 当分隔符。
3. **不要把完整 SKILL.md 放进 system prompt**。catalog 只放 name / description,全文只走 tool_result。
4. **不要用 `HashMap`**,否则 catalog 顺序不稳定(课程用 dict 保插入序,Java 对应 `LinkedHashMap`)。
5. **坏 YAML 只清空 metadata,不得丢掉 body fallback**。description 要能回退到 body 第一行。
6. **不要静默吞掉真实文件 I/O 故障**。根目录不存在是合法的;但读到坏文件要 fail-fast,别 catch 成空结果。
7. **不要把 `load_skill` 顺手加入子代理**。子代理保持五把基础工具。
8. **`*/` 是 JavaDoc 杀手**:注释里写 `{@code */SKILL.md}` 会把块注释提前闭合,导致语法错误(本作业单骨架已踩过一次)。写成 `{@code skills/<name>/SKILL.md}`。

## 五、学习阶段(实际走向)

1. ✅ Codex 发布作业单,Claude 落三个骨架。
2. ✅ **`SkillLoader` 由 Claude 代写**(用户明确授权,判断其属 dirty-work;Codex 复核后接受该委托)。
3. ✅ **`LoadSkillTool` 由用户亲手实现**,Codex Review 通过。
4. ✅ Codex Review 提出 Blocking Finding(I/O 三态),Claude 修复。
5. ✅ Claude 落地永久测试 + Main 装配,并运行 `mvn test`。
6. ✅ **用户真机验证**(`mvn -q package` 后 `./jade repl`,用真实的 `skills/<name>/SKILL.md`)。
7. ✅ Git 阶段:Codex 已规划且用户已批准,提交结果以 Git 历史为准。

> **与原始规划的偏差**:原计划 Step 2 是"用户实现 `SkillLoader`,Claude 只落骨架"。
> 实际由用户授权改派给 Claude。本作业单保留全部设计契约与坑清单,便于复习与后续 Review 对照。

## 六、测试(已落地,由 Claude 编写)

落点:`SkillLoaderTest`(`skills` 包)、`LoadSkillToolTest`(`tools` 包)、`MainTest` 技能块三条、
`AgentLoopTest` 的 tool_result 边界一条。全部用 `@TempDir` 造技能树,不碰仓库文件、不访问网络。

覆盖清单(与下方 1–13 一一对应):

1. 根目录不存在与空目录。
2. 单技能 catalog 只含元数据,load 返回原始全文。
3. UTF-8 名称、描述和正文。
4. YAML 多行 description 折叠空白。
5. 无 frontmatter、未闭合、非法 YAML、非 mapping、非 String metadata 的 fallback。
6. LF / CRLF 独占分隔符及 block scalar 内 `---`。
7. 只扫描一层,忽略缺 SKILL.md 的目录与杂项。
8. 根目录外 symlink manifest / 目录被忽略。
9. 顺序稳定、重复 name 后者覆盖、重扫清除已删除技能。
10. unknown 文案及 available 顺序;空 registry 为 `none`。
11. `LoadSkillTool` schema、缺参、命中与未命中透传。
12. system prompt 含 catalog 但不含正文;父工具表含 `load_skill`,子工具表不含。
13. AgentLoop 调 `load_skill` 后,完整内容**只出现在 tool_result**。

## 七、验收

1. `mvn -q test` 全绿。
2. `mvn -q package` 后 `./jade repl`(需先在 `skills/` 下放一个真实技能):
   - 父 system prompt 里应能看到 `Skills available:` + 目录行,且**不含正文**;
   - 提问触发该技能 → 观察 `[HOOK] load_skill` 与完整正文进入 messages;
   - 问一个不存在的技能 → 回 `Error: Unknown skill '...'. Available: ...`。
3. `q` 退出无异常。

## 八、提交预览(Git-SOP:每批独立可编译,具体批次提交前再核)

```
添加 s07 作业单:Skill Loading 与 load_skill 工具
添加 SkillDefinition:技能名、简述与完整原文
添加 SkillLoader:扫描、frontmatter、catalog 与按名取全文
添加 load_skill 工具:按名取回完整技能原文
添加 SkillLoader 单元测试
添加 LoadSkillTool 单元测试
Main 装配 load_skill 与技能目录提示词,同步测试期望
更新 README:进度到 s07,目录结构对齐现状
```
