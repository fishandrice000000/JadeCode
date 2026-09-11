package com.jadecode.skills;

/**
 * 一个技能的完整定义:目录信息(name / description)+ 完整原文(content)。
 *
 * <p>
 * 两层知识注入的载体:system prompt 里的 catalog 只取 name / description(常驻成本小),
 * content 保存 SKILL.md 的完整原文——<b>含 frontmatter</b>——只在模型调用
 * {@code load_skill(name)} 后作为 tool_result 进入 messages,原样回灌、不做裁剪。
 * </p>
 *
 * @param name        技能名:frontmatter 的 name 优先,缺失时回退 manifest 父目录名
 * @param description 技能简述:frontmatter 的 description 优先,缺失时回退正文首行
 * @param content     SKILL.md 完整原文(含 frontmatter),由 {@link SkillLoader#load} 原样返回
 */
public record SkillDefinition(String name, String description, String content) {
}
