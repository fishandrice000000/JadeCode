import { Markdown, type MarkdownOptions, type MarkdownTheme } from "@earendil-works/pi-tui";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import { markdownTheme } from "../theme/theme-adapters.ts";
import { MermaidTransformer } from "./mermaid-transformer.ts";
import { highlightCode } from "./syntax-highlight.ts";

/**
 * Markdown 实例工厂：正文/表格/LaTeX/代码块全部交给固定 Pi Markdown。
 *
 * 不实现另一套 Markdown 解析器，也不把 Java `MarkdownLayout` 搬过来。每张消息卡按内容修订
 * 持有实例；只有正文变化才 setText，只有宽度/主题变化才重建，避免每帧重新解析稳定旧卡。
 *
 * Mermaid 通过 `transform` 把 ```mermaid 围栏换成 `jade-mermaid`，再由高亮函数渲染成
 * Unicode 图；可用宽度在 transform 时记录，供渲染阶段判断是否需要退回源码框。
 */
export class MarkdownFactory {
	private readonly mermaid = new MermaidTransformer();

	/**
	 * @param theme 当前主题快照（来自同一 ThemeController）
	 * @param codeBlockIndent 配置的代码块缩进（原样保留，不另写排版器）
	 * @param mermaidEnabled false 时 Mermaid 围栏保持为普通代码块
	 */
	create(theme: ResolvedTheme, codeBlockIndent: string, mermaidEnabled = true): {
		markdown: Markdown;
		setText: (text: string) => void;
		invalidate: () => void;
	} {
		const markdownThemeValue = markdownTheme(theme, codeBlockIndent);
		let availableWidth = 80;
		const options: MarkdownOptions = {
			renderLatex: true,
			transform: (source, width) => {
				availableWidth = width;
				return mermaidEnabled ? rewriteMermaidFences(source) : source;
			},
		};
		// 让 highlightCode 拿到同一实例最近一次的可用宽度。
		const themed: MarkdownTheme = {
			...markdownThemeValue,
			highlightCode: (code, lang) => {
				const language = (lang ?? "").toLowerCase();
				if (language === "jade-mermaid") return this.mermaid.render(code, availableWidth, theme);
				return highlightCode(code, lang, theme);
			},
		};
		const markdown = new Markdown("", 0, 0, themed, undefined, options);
		return {
			markdown,
			setText: (text: string) => markdown.setText(text),
			invalidate: () => markdown.invalidate(),
		};
	}
}

/** 把 ```mermaid 围栏改写为 jade-mermaid；其它围栏/缩进代码保持不变。 */
export function rewriteMermaidFences(source: string): string {
	if (!source.includes("mermaid")) return source;
	const lines = source.split("\n");
	let inFence = false;
	let fence = "";
	const out: string[] = [];
	for (const line of lines) {
		const match = /^(\s*)(`{3,}|~{3,})(.*)$/.exec(line);
		if (!inFence && match) {
			fence = match[2]!;
			inFence = true;
			const info = match[3]!.trim();
			out.push(`${match[1]}${fence}${info === "mermaid" ? "jade-mermaid" : match[3]!}`);
			continue;
		}
		if (inFence && match && match[2]!.startsWith(fence[0]!) && match[2]!.length >= fence.length && match[3]!.trim() === "") {
			inFence = false;
			out.push(line);
			continue;
		}
		out.push(line);
	}
	return out.join("\n");
}
