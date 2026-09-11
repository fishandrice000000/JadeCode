package com.jadecode.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * SkillLoader 单元测试:全部在 {@link TempDir} 里造技能树,不碰仓库文件、不访问网络。
 *
 * <p>
 * 覆盖作业单 §六 的 1–10 类行为,外加 I/O 三态(明确不存在 / 无法判定 / 真实故障)与
 * manifest 形态的边界。
 * </p>
 */
class SkillLoaderTest {

    @TempDir
    Path ws;

    /** skills 根目录:各用例自行决定建不建、建成什么 */
    private Path skills;

    @BeforeEach
    void setUp() {
        skills = ws.resolve("skills");
    }

    /** 造一个技能目录 + SKILL.md(UTF-8) */
    private void skill(String dirName, String content) throws IOException {
        Path dir = Files.createDirectories(skills.resolve(dirName));
        Files.writeString(dir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
    }

    /* ---------- ① 根目录不存在 / 空目录 ---------- */

    /** ①a 根目录确实不存在:合法空 registry(不是错误) */
    @Test
    void missingRootIsEmptyRegistry() {
        SkillLoader loader = new SkillLoader(skills); // 从未创建

        assertEquals("(no skills found)", loader.catalog());
        assertEquals("Error: Unknown skill 'x'. Available: none", loader.load("x"));
    }

    /** ①b 根目录存在但为空:同样合法空 registry */
    @Test
    void emptyRootIsEmptyRegistry() throws IOException {
        Files.createDirectories(skills);

        SkillLoader loader = new SkillLoader(skills);

        assertEquals("(no skills found)", loader.catalog());
        assertEquals("Error: Unknown skill 'x'. Available: none", loader.load("x"));
    }

    /* ---------- I/O 三态:真实故障必须 fail-fast ---------- */

    /** 根路径存在但是普通文件:Files.list 抛 NotDirectoryException → 包装成带路径的 IllegalStateException */
    @Test
    void rootIsRegularFileFailsFast() throws IOException {
        Files.writeString(ws.resolve("skills"), "我不是目录");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new SkillLoader(skills));

        assertTrue(e.getMessage().contains(skills.toString()), "异常应带路径上下文: " + e.getMessage());
        assertNotNull(e.getCause(), "应保留原始 IOException 作为 cause");
    }

    /**
     * 根目录不可读:同样 fail-fast,不静默成空 registry。
     * root 会绕过权限位,该环境下跳过——不写必然失败的脆弱断言。
     */
    @Test
    void unreadableRootFailsFast() throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")), "root 绕过权限位,构造不出不可访问目录");

        Files.createDirectories(skills);
        Files.setPosixFilePermissions(skills, PosixFilePermissions.fromString("---------"));
        try {
            assertThrows(IllegalStateException.class, () -> new SkillLoader(skills));
        } finally {
            Files.setPosixFilePermissions(skills, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /* ---------- ② 单技能:目录只含元数据,load 给全文 ---------- */

    /** ② catalog 只有 name / description,绝不泄露 body;load 返回原始全文(含 frontmatter) */
    @Test
    void singleSkillCatalogHidesBodyAndLoadReturnsRawText() throws IOException {
        String raw = "---\nname: pdf\ndescription: 处理 PDF\n---\n# 正文标题\n这里是完整指令";
        skill("pdf", raw);

        SkillLoader loader = new SkillLoader(skills);

        assertEquals("- pdf: 处理 PDF", loader.catalog());
        assertFalse(loader.catalog().contains("正文标题"), "catalog 不得出现正文");
        assertFalse(loader.catalog().contains("完整指令"), "catalog 不得出现正文");
        assertEquals(raw, loader.load("pdf"), "load 返回逐字原文");
    }

    /* ---------- ③ UTF-8 ---------- */

    /** ③ 名称、描述、正文全走 UTF-8(含中文与 emoji) */
    @Test
    void utf8NameDescriptionAndBody() throws IOException {
        skill("claude", "---\nname: 文档助手\ndescription: 处理中文 ✅\n---\n正文也用中文写 🌟");

        SkillLoader loader = new SkillLoader(skills);

        assertEquals("- 文档助手: 处理中文 ✅", loader.catalog());
        assertTrue(loader.load("文档助手").contains("正文也用中文写 🌟"));
        assertEquals("Error: Unknown skill '无'. Available: 文档助手", loader.load("无"));
    }

    /* ---------- ④ 多行 description 折叠 ---------- */

    /** ④ YAML 多行 description:折叠成一个空格 */
    @Test
    void multilineDescriptionFoldsToSingleLine() throws IOException {
        skill("multi", "---\ndescription: |\n  第一行\n  第二行\n---\n正文");

        assertEquals("- multi: 第一行 第二行", new SkillLoader(skills).catalog());
    }

    /* ---------- ⑤ 各种 fallback ---------- */

    /** ⑤a 无 frontmatter:name 回退目录名,description 回退 body 首行(去前导 # 与空格) */
    @Test
    void noFrontmatterFallsBackToDirNameAndBodyFirstLine() throws IOException {
        skill("fallback", "#  标题   带   多空格\n正文其余部分");

        assertEquals("- fallback: 标题 带 多空格", new SkillLoader(skills).catalog());
    }

    /** ⑤b 未闭合:metadata 空,body 为原文(故 description 回退到原文首行) */
    @Test
    void unclosedFrontmatterKeepsWholeTextAsBody() throws IOException {
        skill("unclosed", "---\nname: 不会生效\n没有结束分隔符");

        SkillLoader loader = new SkillLoader(skills);

        assertEquals("- unclosed: ---", loader.catalog(), "description 回退到原文首行");
        assertEquals("Error: Unknown skill '不会生效'. Available: unclosed", loader.load("不会生效"));
    }

    /** ⑤c 非法 YAML:metadata 空,但合法分隔出的 body 正常返回 */
    @Test
    void invalidYamlClearsMetadataButKeepsBody() throws IOException {
        skill("bad", "---\nname: [未闭合\n---\n## 正文首行\n正文");

        assertEquals("- bad: 正文首行", new SkillLoader(skills).catalog());
    }

    /** ⑤d 根节点不是 mapping:同样只清 metadata */
    @Test
    void nonMappingYamlClearsMetadataButKeepsBody() throws IOException {
        skill("scalar", "---\n就是一个字符串\n---\n# 回退到这里");

        assertEquals("- scalar: 回退到这里", new SkillLoader(skills).catalog());
    }

    /** ⑤e 非 String 的 name / description 按缺失处理(不 toString) */
    @Test
    void nonStringMetadataTreatedAsMissing() throws IOException {
        skill("typed", "---\nname: 123\ndescription: 456\n---\n# 回退首行\n正文");

        SkillLoader loader = new SkillLoader(skills);

        assertEquals("- typed: 回退首行", loader.catalog());
        assertEquals("Error: Unknown skill '123'. Available: typed", loader.load("123"));
    }

    /* ---------- ⑥ 分隔符边界 ---------- */

    /** ⑥a CRLF:独占分隔符照样识别,load 仍返回逐字原文 */
    @Test
    void crlfDelimitersAreRecognized() throws IOException {
        String raw = "---\r\nname: win\r\ndescription: 换行是 CRLF\r\n---\r\n正文";
        skill("crlf", raw);

        SkillLoader loader = new SkillLoader(skills);

        assertEquals("- win: 换行是 CRLF", loader.catalog());
        assertEquals(raw, loader.load("win"), "CRLF 原样保留");
    }

    /** ⑥b 首行带前导空白不算独占:整篇都是 body,不开 frontmatter */
    @Test
    void indentedFirstLineDoesNotOpenFrontmatter() throws IOException {
        skill("indent", "  ---\nname: 不生效\n---\n正文");

        assertEquals("- indent: ---", new SkillLoader(skills).catalog());
    }

    /** ⑥c block scalar 内缩进的 --- 不是结束分隔符 */
    @Test
    void indentedDelimiterInsideBlockScalarDoesNotCloseFrontmatter() throws IOException {
        skill("block", "---\ndescription: |\n  第一行\n  ---\n  第二行\n---\n# 正文\n正文");

        SkillLoader loader = new SkillLoader(skills);

        // 内层 --- 被当成 description 的一部分折叠掉,没有提前截断
        assertEquals("- block: 第一行 --- 第二行", loader.catalog());
    }

    /* ---------- ⑦ 只扫一层 ---------- */

    /** ⑦ 缺 SKILL.md 的目录、杂项文件、更深一层目录全部忽略 */
    @Test
    void onlyOneLevelIsScanned() throws IOException {
        Files.createDirectories(skills.resolve("no-manifest"));
        Files.writeString(skills.resolve("README.md"), "杂项");
        skill("deep/inner", "---\ndescription: 深\n---\n深");
        skill("real", "---\ndescription: 真的技能\n---\n正文");

        assertEquals("- real: 真的技能", new SkillLoader(skills).catalog());
    }

    /** ⑦b manifest 位置是目录:跳过,不当成技能 */
    @Test
    void manifestThatIsDirectoryIsSkipped() throws IOException {
        Files.createDirectories(skills.resolve("weird").resolve("SKILL.md"));
        skill("ok", "---\ndescription: 正常\n---\n正文");

        assertEquals("- ok: 正常", new SkillLoader(skills).catalog());
    }

    /* ---------- ⑧ 符号链接围栏 ---------- */

    /** ⑧a manifest 是指向根目录外的符号链接:跳过 */
    @Test
    void symlinkManifestOutsideRootIsSkipped() throws IOException {
        Path outside = Files.createDirectories(ws.resolve("outside"));
        Files.writeString(outside.resolve("stolen.md"), "---\ndescription: 越界\n---\n不该被读到");

        Files.createDirectories(skills.resolve("linked"));
        Files.createSymbolicLink(skills.resolve("linked").resolve("SKILL.md"),
                outside.resolve("stolen.md"));

        SkillLoader loader = new SkillLoader(skills);

        assertEquals("(no skills found)", loader.catalog());
        assertFalse(loader.catalog().contains("越界"));
    }

    /** ⑧b 技能目录本身是指向根目录外的符号链接:同样跳过 */
    @Test
    void symlinkDirectoryOutsideRootIsSkipped() throws IOException {
        Path outside = Files.createDirectories(ws.resolve("outside"));
        Files.writeString(outside.resolve("SKILL.md"), "---\ndescription: 越界目录\n---\n不该被读到");

        Files.createDirectories(skills); // 符号链接的父目录必须先存在
        Files.createSymbolicLink(skills.resolve("linked-dir"), outside);

        assertEquals("(no skills found)", new SkillLoader(skills).catalog());
    }

    /* ---------- ⑨ 顺序 / 覆盖 / 重扫 ---------- */

    /** ⑨a catalog 顺序稳定(按路径字典序,与建目录顺序无关) */
    @Test
    void catalogOrderIsStable() throws IOException {
        skill("zeta", "---\ndescription: Z\n---\nZ");
        skill("alpha", "---\ndescription: A\n---\nA");
        skill("mid", "---\ndescription: M\n---\nM");

        String expected = "- alpha: A\n- mid: M\n- zeta: Z";
        assertEquals(expected, new SkillLoader(skills).catalog());
        assertEquals(expected, new SkillLoader(skills).catalog(), "重跑同一目录顺序不变");
    }

    /** ⑨b 重复 name:按扫描顺序后者覆盖 */
    @Test
    void duplicateNameIsOverwrittenByLaterScanOrder() throws IOException {
        skill("a-first", "---\nname: same\ndescription: 先\n---\n先");
        skill("b-second", "---\nname: same\ndescription: 后\n---\n后");

        SkillLoader loader = new SkillLoader(skills);

        assertEquals("- same: 后", loader.catalog());
        assertTrue(loader.load("same").contains("后"));
    }

    /** ⑨c 重扫清掉已删除的技能 */
    @Test
    void rescanClearsRemovedSkills() throws IOException {
        skill("keep", "---\ndescription: 留着\n---\n留");
        skill("drop", "---\ndescription: 删掉\n---\n删");
        SkillLoader loader = new SkillLoader(skills);
        assertEquals("- drop: 删掉\n- keep: 留着", loader.catalog());

        Files.delete(skills.resolve("drop").resolve("SKILL.md"));
        loader.scan();

        assertEquals("- keep: 留着", loader.catalog());
        assertEquals("Error: Unknown skill 'drop'. Available: keep", loader.load("drop"));
    }

    /* ---------- ⑩ 未命中文案与 available 顺序 ---------- */

    /** ⑩ available 按 registry 顺序逗号分隔;空 registry 用 none */
    @Test
    void unknownSkillMessageListsAvailableInOrder() throws IOException {
        skill("beta", "---\ndescription: B\n---\nB");
        skill("alpha", "---\ndescription: A\n---\nA");
        SkillLoader loader = new SkillLoader(skills);

        assertEquals("Error: Unknown skill 'nope'. Available: alpha, beta", loader.load("nope"));
    }

    /** ⑩b load 只查 registry:名字带路径意图也当普通 key,绝不拼路径读文件 */
    @Test
    void loadNeverBuildsPaths() throws IOException {
        skill("alpha", "---\ndescription: A\n---\nA");
        // 根目录外造一个同名技能:若 load 拼了路径,就可能被读到
        Path outside = Files.createDirectories(ws.resolve("alpha"));
        Files.writeString(outside.resolve("SKILL.md"), "不该被读到");

        SkillLoader loader = new SkillLoader(skills);

        assertEquals("Error: Unknown skill '../alpha'. Available: alpha", loader.load("../alpha"));
        assertEquals("Error: Unknown skill '/etc/passwd'. Available: alpha", loader.load("/etc/passwd"));
    }

    /* ---------- 解析器的直测(package-private) ---------- */

    /** 解析结果本身:合法 frontmatter 的 metadata 与 body */
    @Test
    void parseFrontmatterReturnsMetadataAndTrimmedBody() {
        SkillLoader.ParsedFrontmatter parsed = SkillLoader.parseFrontmatter(
                "---\nname: pdf\ndescription: 处理 PDF\n---\n\n  正文  \n");

        assertEquals(Map.of("name", "pdf", "description", "处理 PDF"), parsed.metadata());
        assertEquals("正文", parsed.body());
    }

    /** 无合法开头:metadata 空,body 为原文(不做任何裁剪) */
    @Test
    void parseFrontmatterWithoutOpeningDelimiterReturnsRawText() {
        String raw = "just text\nline2";
        SkillLoader.ParsedFrontmatter parsed = SkillLoader.parseFrontmatter(raw);

        assertEquals(Map.of(), parsed.metadata());
        assertEquals(raw, parsed.body());
    }
}
