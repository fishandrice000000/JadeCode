package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.skills.SkillLoader;

/**
 * load_skill 工具：按名字取回完整的 SKILL.md 原文。
 *
 * <p>参数在这里校验，查表和读文件都在 {@link SkillLoader}。这是两层注入的第二层：
 * system prompt 里只有技能目录，模型觉得某个技能适用时才调用本工具把全文拉进消息。
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

    private Map<String, String> properties() {
        return Map.of("name", "string");
    }

    /**
     * @return 必填 [name]
     */

    private List<String> required() {
        return List.of("name");
    }

    /**
     * 检查完参数后直接交给 {@link SkillLoader#load(String)}。
     *
     * @param input 模型参数,须含 name
     * @return 执行结果（content 保留原输出，isError 由本方法失败分支给定）：完整 SKILL.md 原文;缺参回 "Error: Missing required parameter: name";
     *         未命中回 loader 的未知技能文案
     * @param context Harness 提供的任务上下文；不从模型参数读取，离线只读调用可按工具契约传 null
     */
    @Override
    public com.jadecode.tools.ToolOutcome executeOutcome(ObjectNode input,
            com.jadecode.execution.ExecutionContext context) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return new com.jadecode.tools.ToolOutcome("Error: Missing required parameter: " + key, true);
            }
        }

        String name = input.get("name").asText();
        // 技能正文可能以 Error 开头；只按注册表是否命中来报告失败。
        return new ToolOutcome(loader.load(name), !loader.names().contains(name));
    }

    /** 提供完整参数结构；标量构造器只负责组装 Schema，不授予执行权限。 */
    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
        return com.jadecode.tools.ToolSchema.objectSchema(properties(), required());
    }
}
