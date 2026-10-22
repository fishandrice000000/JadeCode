package com.jadecode.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * 扫描 {@code skills/<name>/SKILL.md}，把技能目录和完整原文分开提供。
 *
 * <p>两个出口对应两层知识注入：{@link #catalog()} 给 system prompt（只有名字和简介），
 * {@link #load(String)} 给 {@code load_skill} 工具（完整原文）。两者不会混：
 * 完整 SKILL.md 不会进 system prompt。
 *
 * <p>技能根目录由 Main 注入，本类不读全局工作目录。根目录不存在算合法的空技能表；
 * 真实的读写错误则直接抛异常，不假装成空目录。
 */
public final class SkillLoader {
    /** 技能 manifest 的文件名(约定每个技能目录下恰好一个) */
    private static final String MANIFEST = "SKILL.md";

    /** frontmatter 的 YAML 解析器;Jackson YAML 依赖自 s07 起由 pom 引入 */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** skills 根目录;构造时注入,本类不读全局 cwd */
    private final Path skillsDir;

    /** 保序注册表:name → 技能定义。用 LinkedHashMap 让 catalog 顺序稳定(扫描顺序 = 输出顺序) */
    private final Map<String, SkillDefinition> registry = new LinkedHashMap<>();

    /**
     * @param skillsDir skills 根目录(约定 {@code skills/<name>/SKILL.md} 恰好一层)
     */
    public SkillLoader(Path skillsDir) {
        this.skillsDir = skillsDir;
        scan();
    }

    /**
     * 扫描根目录下恰好一层的 {@code skills/<name>/SKILL.md},重建 registry。
     *
     * <p>
     * 每次先清空旧 registry(重扫要能清掉已删除的技能);manifest 按路径字典序处理;
     * 只接受普通文件且真实路径仍在根目录真实路径之下,越界符号链接一律跳过。
     * </p>
     */
    public void scan() {
        registry.clear();

        // 三态处理:只有"明确确认不存在"才是合法空 registry(课程同款)。
        // notExists 返回 false 时可能是"存在"、也可能是"无法判定",一律继续往下走,
        // 让 toRealPath / Files.list 把真实 I/O 故障抛出来——绝不静默折叠成空目录
        if (Files.notExists(skillsDir)) {
            return;
        }

        try {
            // 根目录的"真实路径":围栏比对以它为准,符号链接绕不出去
            Path root = skillsDir.toRealPath();

            for (Path manifest : manifests()) {
                // 明确不存在 → 该技能目录没放 manifest,跳过;
                // 其余情况(含无法判定)继续,交给下面这些可抛异常的 API 定夺
                if (Files.notExists(manifest)) {
                    continue;
                }

                // 先解析真实路径再围栏:指向根目录外的(文件或目录)符号链接一律跳过
                Path real = manifest.toRealPath();
                if (!real.startsWith(root)) {
                    continue;
                }

                // 用可抛异常的 attributes 确认普通文件:目录 / 特殊文件在此排除,
                // 且不会像 isRegularFile 那样把 I/O 故障静默当成"不是文件"
                if (!Files.readAttributes(real, BasicFileAttributes.class).isRegularFile()) {
                    continue;
                }

                // content 存原文(含 frontmatter),load 时原样回灌
                String content = Files.readString(real, StandardCharsets.UTF_8);
                ParsedFrontmatter parsed = parseFrontmatter(content);

                String name = resolveName(parsed, manifest);
                String description = resolveDescription(parsed);

                // 重复 name 由后者覆盖,保持课程语义(LinkedHashMap.put 覆盖但保留首次插入位)
                registry.put(name, new SkillDefinition(name, description, content));
            }
        } catch (IOException e) {
            // 真实 I/O 故障不伪装成空目录:带路径上下文抛出去,启动时 fail-fast
            throw new IllegalStateException("Failed to scan the skills directory: " + skillsDir, e);
        }
    }

    /**
     * 目录信息:每行 {@code - <name>: <description>}。
     *
     * @return 空 registry 精确返回 {@code (no skills found)};否则逐行列出,顺序同 registry
     */
    public String catalog() {
        if (registry.isEmpty()) {
            return "(no skills found)";
        }
        return registry.values().stream()
                .map(skill -> "- " + skill.name() + ": " + skill.description())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 按名取回完整 SKILL.md 原文。
     *
     * <p>
     * <b>只查 registry,绝不用 name 拼路径</b>——名字来自模型,拼接即路径穿越。
     * </p>
     *
     * @param name 技能名(registry key,精确匹配)
     * @return 命中:完整原文(含 frontmatter);未命中:{@code Error: Unknown skill '<name>'.
     *         Available: <逗号分隔的名字>}(registry 为空时 available 用 {@code none})
     */
    public String load(String name) {
        SkillDefinition skill = registry.get(name);
        if (skill != null) {
            return skill.content();
        }
        String available = registry.isEmpty() ? "none" : String.join(", ", registry.keySet());
        return "Error: Unknown skill '" + name + "'. Available: " + available;
    }

    /**
     * 解析 SKILL.md 的 frontmatter。
     *
     * <p>
     * 只有首行独占的 {@code ---} 才开启 frontmatter(LF / CRLF 皆可),结束分隔符同样必须独占一行。
     * 缺开头或结尾:metadata 为空、body 为原文。YAML 非法 / 为空 / 根节点非 mapping:metadata 为空,
     * 但合法分隔出的 body 正常返回。YAML block scalar 内缩进的 {@code ---} 不算结束分隔符。
     * </p>
     *
     * @param text SKILL.md 原文
     * @return 解析结果;body 已去除首尾空白
     */
    static ParsedFrontmatter parseFrontmatter(String text) {
        String[] lines = text.split("\n", -1);

        // 首行必须"独占" ---:只容行尾 \r(CRLF),前导空白即判否
        if (lines.length == 0 || !"---".equals(stripEol(lines[0]))) {
            return new ParsedFrontmatter(Map.of(), text);
        }

        // 结束分隔符同样要独占一行。block scalar 内缩进过的 --- 带前导空白,不会命中
        int closing = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(stripEol(lines[i]))) {
                closing = i;
                break;
            }
        }
        if (closing < 0) {
            return new ParsedFrontmatter(Map.of(), text); // 未闭合:metadata 空,body 为原文
        }

        // 逐行还原分隔符之间的原文(行内的 \r 保留),并补回最后一行的换行——
        // split 会吃掉行终止符,而 YAML block scalar 的值是含末尾换行的,少一个字符就失真
        String frontmatter = String.join("\n", Arrays.copyOfRange(lines, 1, closing)) + "\n";
        String body = String.join("\n", Arrays.copyOfRange(lines, closing + 1, lines.length)).strip();

        return new ParsedFrontmatter(parseYaml(frontmatter), body);
    }

    /* ----- 内部实现 ----- */

    /** 列出根目录下恰好一层的候选 manifest 路径,按字典序;不递归进更深层。
     *  只把"技能目录"纳入候选:杂项文件、悬空符号链接都不是技能,直接跳过;
     *  真实 I/O 故障不在这里吞掉,交给会抛异常的 readAttributes / toRealPath。 */
    private List<Path> manifests() throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(skillsDir)) {
            for (Path entry : entries.sorted().toList()) {
                try {
                    // 跟随符号链接:指向目录的链接仍是候选,越界与否交给下面的围栏判
                    if (Files.readAttributes(entry, BasicFileAttributes.class).isDirectory()) {
                        candidates.add(entry.resolve(MANIFEST));
                    }
                } catch (NoSuchFileException e) {
                    // 悬空符号链接,或列举后被删除:那不是技能,也不是 I/O 故障
                }
            }
        }
        return candidates;
    }

    /** 行尾去掉 CR(支持 CRLF);保留前导空白,故"是否独占一行"的判定不受影响 */
    private static String stripEol(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** 解析 frontmatter 的 YAML。非法 / 为空 / 根节点非 mapping 一律回空表(正文不受牵连) */
    private static Map<String, Object> parseYaml(String frontmatter) {
        try {
            if (YAML.readValue(frontmatter, Object.class) instanceof Map<?, ?> map) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                map.forEach((key, value) -> metadata.put(String.valueOf(key), value));
                return metadata;
            }
        } catch (IOException e) {
            // 坏 YAML 只清空 metadata:分隔符已经切好,body 照常返回
        }
        return Map.of();
    }

    /** name:metadata 中非空 String 去首尾空白;否则回退 manifest 父目录名 */
    private static String resolveName(ParsedFrontmatter parsed, Path manifest) {
        String raw = stringOrNull(parsed.metadata().get("name"));
        return (raw == null || raw.isBlank())
                ? manifest.getParent().getFileName().toString()
                : raw;
    }

    /** description:metadata 中非空 String;否则回退 body 第一行;最后统一归一化 */
    private static String resolveDescription(ParsedFrontmatter parsed) {
        String raw = stringOrNull(parsed.metadata().get("description"));
        if (raw == null || raw.isBlank()) {
            String body = parsed.body();
            int newline = body.indexOf('\n');
            raw = newline < 0 ? body : body.substring(0, newline);
        }
        return normalizeDescription(raw);
    }

    /** 取 String 值并去首尾空白;非 String(数字 / 列表 / null)按缺失处理,不 toString */
    private static String stringOrNull(Object value) {
        return value instanceof String s ? s.strip() : null;
    }

    /** 去开头连续的 # 与空格,再把所有连续空白折叠成一个空格 */
    private static String normalizeDescription(String text) {
        String cleaned = text.replaceAll("^[# ]+", "").strip();
        return String.join(" ", cleaned.split("\\s+"));
    }

    /**
     * frontmatter 解析结果。
     *
     * @param metadata 分隔符之间解析出的 YAML 键值;无合法 frontmatter 或 YAML 不可用时为空表
     * @param body     分隔符之后的正文(已去首尾空白);无合法 frontmatter 时为原文
     */
    record ParsedFrontmatter(Map<String, Object> metadata, String body) {
    }
}
