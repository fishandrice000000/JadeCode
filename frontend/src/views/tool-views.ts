import { wrapTextWithAnsi } from "@earendil-works/pi-tui";
import type { Entry } from "../state/transcript-store.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import { clipLine, display, singleLine, visibleEscapes } from "../presentation/text-safety.ts";
import { formatDuration } from "../presentation/format.ts";
import { statusLabel } from "./message-views.ts";
import { headingDetail, headingPart, statusRole } from "./card-heading.ts";

/**
 * 工具卡渲染：可信身份选择 renderer，预览 ≠ 执行，完整结果始终保留。
 *
 * 与旧 Java tool renderer 的可见规则一致：
 * - bash：折叠时显示结果末尾 5 行；
 * - read_file：收起时隐藏正文，展开后显示完整结果；
 * - write_file：折叠时显示写入内容前 10 行；
 * - edit_file：折叠时显示片段（旧/新内容对比行）；
 * - fallback：前 10 行；
 * - 参数预览单独一行，永不显示成"已执行"。
 *
 * outputPad 只加外层留白，不修改工具原文；展开状态来自条目（Ctrl+O/全局 toolsExpanded）。
 */
export class ToolCard {
	constructor(
		private readonly entry: Entry,
		private readonly theme: ResolvedTheme,
		private readonly outputPad: number,
		private readonly teamActive: boolean,
	) {}

	render(width: number): string[] {
		const pad = this.outputPad > 0 && width > 2 ? 1 : 0;
		const innerWidth = Math.max(1, width - pad * 2);
		const lines: string[] = [];
		lines.push(...this.header(innerWidth));
		const args = this.entry.arguments ? singleLine(visibleEscapes(this.entry.arguments)) : "{}";
		for (const line of this.entry.expanded ? wrapTextWithAnsi(args, Math.max(1, innerWidth - 2)) : [clipLine(args, Math.max(0, innerWidth - 2))])
			lines.push(this.theme.style("muted", `  ${line}`));
		lines.push(...this.inputPreview(innerWidth));
		const result = display(this.entry.body);
		if (result) lines.push(...this.resultLines(result, innerWidth));
		if (pad > 0) {
			const padded: string[] = [];
			for (const line of lines) padded.push(` ${clipLine(line, innerWidth)}`);
			return padded;
		}
		return lines.map((line) => clipLine(line, width));
	}

	/** 参数和执行结果分开：完整 JSON 才能做专用预览，未闭合流式参数保持原文。 */
	private inputPreview(width: number): string[] {
		if (this.entry.tool?.kind !== "BUILTIN") return [];
		let args: Record<string, unknown>;
		try { args = JSON.parse(this.entry.arguments) as Record<string, unknown>; } catch { return []; }
		if (!args || typeof args !== "object") return [];
		const name = this.entry.tool.originalName;
		const field = (key: string): string => typeof args[key] === "string" ? display(args[key] as string) : "";
		let preview: string[] = [];
		if (name === "bash" && field("command")) preview = [`$ ${field("command")}`];
		if (name === "write_file") preview = field("content").split("\n");
		if (name === "edit_file") {
			// 这是待编辑片段，不冒称整文件 diff 或工具已成功执行。
			preview = ["Edit preview (requested fragments)", ...field("old_text").split("\n").map((line) => `- ${line}`),
				...field("new_text").split("\n").map((line) => `+ ${line}`)];
		}
		if (preview.length === 0 || (preview.length === 1 && !preview[0])) return [];
		const wrapped = preview.flatMap((line) => wrapTextWithAnsi(line, Math.max(1, width - 2)));
		const visible = this.entry.expanded ? wrapped : wrapped.slice(0, 10);
		if (visible.length < wrapped.length) visible.push(`… ${wrapped.length - visible.length} more input lines`);
		return visible.map((line) => {
			// 编辑预览仍是请求片段；只用红/绿标出旧/新行，不据此声称编辑成功。
			const role = name === "edit_file" && line.startsWith("+ ") ? "toolDiffAdded"
				: name === "edit_file" && line.startsWith("- ") ? "toolDiffRemoved" : "muted";
			return "  " + this.theme.style(role, line);
		});
	}

	private header(width: number): string[] {
		const theme = this.theme;
		const actor = this.teamActive && this.entry.actor ? headingPart(theme, "muted", `${this.entry.actor} · `) : "";
		const elapsed = this.entry.elapsedMs !== null && this.entry.elapsedMs > 0
			? headingDetail(theme, "dim", formatDuration(this.entry.elapsedMs)) : "";
		// 工具名与结果独立：保留原始状态词，不把 Returned 或历史记录改名为成功。
		const role = this.entry.status === "ERROR" || this.entry.status === "INTERRUPTED" ? "error" : "toolTitle";
		const text = headingPart(theme, role, "◍ ") + actor + headingPart(theme, role, this.entry.title, true)
			+ headingDetail(theme, statusRole(this.entry.status), statusLabel(this.entry.status)) + elapsed;
		const hint = this.entry.body && !this.entry.expanded ? " · Ctrl+O expand" : "";
		return [clipLine(text, Math.max(0, width - hint.length)) + theme.style("dim", hint)];
	}

	private resultLines(result: string, width: number): string[] {
		const wrapped = wrapTextWithAnsi(result, Math.max(1, width - 2));
		// 只有 Java 给出的内置身份可选专用规则；同名 MCP 工具仍用通用展示。
		const name = this.entry.tool?.kind === "BUILTIN" ? this.entry.tool.originalName : "";
		if (name === "read_file" && !this.entry.expanded) return [];
		const expandable = !this.entry.expanded;
		let lines = wrapped;
		if (expandable) {
			const limit = name === "bash" ? 5 : name === "write_file" ? 10 : 10;
			if (wrapped.length > limit) {
				lines = name === "bash" ? wrapped.slice(wrapped.length - limit) : wrapped.slice(0, limit);
				lines.push(this.theme.style("dim", `  … ${wrapped.length - limit} more lines`));
			}
		}
		const isDiff = name === "edit_file";
		return lines.map((line) => {
			if (isDiff && line.startsWith("+")) return "  " + this.theme.style("toolDiffAdded", line);
			if (isDiff && line.startsWith("-")) return "  " + this.theme.style("toolDiffRemoved", line);
			return "  " + this.theme.style(this.entry.expanded ? "text" : "toolOutput", line);
		});
	}
}
