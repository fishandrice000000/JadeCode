package com.jadecode.skills;

/**
 * 一个技能的完整定义：目录信息（名字、简介）加上完整原文。
 *
 * <p>两层知识注入：system prompt 里只放名字和简介（常驻成本小），
 * 完整的 SKILL.md 原文（含 frontmatter）只在模型调用 {@code load_skill(name)} 后作为工具结果进入消息，
 * 原样返回，不截断。
 *
 * @param name        技能名：优先用 frontmatter 里的 name，没有就用目录名
 * @param description 简介：优先用 frontmatter 里的 description，没有就用正文首行
 * @param content     SKILL.md 完整原文（含 frontmatter），由 {@link SkillLoader#load} 原样返回
 */
public record SkillDefinition(String name, String description, String content) {
}
