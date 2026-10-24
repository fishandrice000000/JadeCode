import { render, sourceBox, type Cls } from "grok-mermaid";
import type { ResolvedTheme, Role } from "../theme/theme-resolver.ts";

/**
 * Mermaid 转 Unicode 图：固定 grok-mermaid 0.2.3，按当前主题给语义块着色。
 *
 * 只用它的布局结果（`styled` 的 span 类别），颜色一律映射到 Jade 主题角色，不安装它的
 * ANSI 主题。图太宽或语法暂不支持时退回带标题的源码框，绝不吞掉用户内容。
 *
 * 更新策略设置（streaming/final/off）按 M14 继续延期：`off` 仍沿用旧行为；当前实现不对
 * 流式中的半成品做特殊处理，grok-mermaid 自己会对 flowchart 做一次宽松重试。
 */

const CLASS_ROLES: Record<Exclude<Cls, "none">, Role> = {
	border: "mdCodeBlockBorder",
	text: "mdCodeBlock",
	edge: "mdLink",
	edgeLabel: "mdQuote",
	title: "mdHeading",
};

export class MermaidTransformer {
	private lastTheme: ResolvedTheme | undefined;
	private readonly cache = new Map<string, { width: number; lines: string[]; themeKey: string }>();

	/**
	 * @param source Mermaid 源码（围栏内）
	 * @param width 当前可用列宽；用于判断图是否放得下
	 * @returns 已着色行；空源码返回空数组
	 */
	render(source: string, width: number, theme: ResolvedTheme): string[] {
		// 同名文件 watch 后会生成新快照；旧缓存含颜色，必须一并作废。
		if (this.lastTheme !== theme) { this.cache.clear(); this.lastTheme = theme; }
		const trimmed = source.trim();
		if (!trimmed) return [];
		const themeKey = `${theme.name}:${theme.mode}:${theme.noColor ? "nocolor" : "color"}`;
		const key = `${trimmed}\u0000${width}\u0000${themeKey}`;
		const cached = this.cache.get(key);
		if (cached) return cached.lines;
		let lines: string[];
		try {
			const art = render(trimmed);
			if (!art) {
				lines = plainLines(sourceBox(trimmed, width), theme);
			} else if (art.width > width) {
				lines = plainLines(sourceBox(trimmed, width), theme);
			} else {
				lines = art.styled.map((spans) => spans.map((span) => {
					if (span.cls === "none" || !span.text) return span.text;
					return theme.style(CLASS_ROLES[span.cls], span.text);
				}).join(""));
			}
		} catch {
			lines = trimmed.split("\n");
		}
		// 有界缓存：长会话里旧图不再无限占用内存。
		if (this.cache.size > 64) this.cache.clear();
		this.cache.set(key, { width, lines, themeKey });
		return lines;
	}

	/** 主题/宽度变化后清空缓存（Markdown 实例也会重建）。 */
	clear(): void {
		this.cache.clear();
	}
}

/** 源码框是普通文本；用代码块正文色统一着色，保持与代码一致的可读性。 */
function plainLines(art: { plain: string[] }, theme: ResolvedTheme): string[] {
	return art.plain.map((line) => theme.style("mdCodeBlock", line));
}
