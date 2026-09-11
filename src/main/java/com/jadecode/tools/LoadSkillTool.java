package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.skills.SkillLoader;

/**
 * load_skill:按名字取回完整 SKILL.md 原文。
 *
 * <p>
 * 薄壳:参数校验在此,查表与全文取回全在 {@link SkillLoader}。
 * 这是"两层知识注入"的第二层——system prompt 里的 catalog 只是目录,
 * 模型认为某个技能适用时才调本工具把全文拉进 messages。
 * </p>
 */
public final class LoadSkillTool implements Tool {
    /** 技能加载器:构造器注入 */
    private final SkillLoader loader;

    /**
     * @param loader 技能加载器
     */
    public LoadSkillTool(SkillLoader loader) {
        this.loader = loader;
    }

    /**
     * @return 工具名 "load_skill"
     */
    @Override
    public String name() {
        return "load_skill";
    }

    /**
     * @return 给模型的说明:按技能名取回完整 SKILL.md 内容
     */
    @Override
    public String description() {
        return "Load the full SKILL.md content by skill name.";
    }

    /**
     * @return 单参数 name(string)
     */
    @Override
    public Map<String, String> properties() {
        return Map.of("name", "string");
    }

    /**
     * @return 必填 [name]
     */
    @Override
    public List<String> required() {
        return List.of("name");
    }

    /**
     * 缺参检查后透传 {@link SkillLoader#load(String)}。
     *
     * @param input 模型参数,须含 name
     * @return 完整 SKILL.md 原文;缺参回 "Error: Missing required parameter: name";
     *         未命中回 loader 的未知技能文案
     */
    @Override
    public String execute(ObjectNode input) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return "Error: Missing required parameter: " + key;
            }
        }

        return loader.load(input.get("name").asText());
    }
}
