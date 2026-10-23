import { wrapTextWithAnsi } from "@earendil-works/pi-tui";
import type { Entry } from "../state/transcript-store.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import { display, singleLine, visibleEscapes } from "../presentation/text-safety.ts";
import { formatDuration } from "../presentation/format.ts";
import { statusLabel } from "./message-views.ts";

/**
 * 工具卡渲染：可信身份选择 renderer，预览 ≠ 执行，完整结果始终保留。
 *
 * 与旧 Java tool renderer 的可见规则一致：
 * - bash：折叠时显示结果末尾 5 行；
 * - read_file：不折叠正文；
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
		const pad = this.outputPad > 0 ? 1 : 0;
		const innerWidth = Math.max(1, width - pad * 2);
		const lines: string[] = [];
		lines.push(...this.header(innerWidth));
		const args = this.entry.arguments ? singleLine(visibleEscapes(this.entry.arguments)) : "{}";
		lines.push(this.theme.style("muted", `  ${args.slice(0, Math.max(0, innerWidth - 2))}`));
		const result = display(this.entry.body);
		if (result) lines.push(...this.resultLines(result, innerWidth));
		if (pad > 0) {
			const padded: string[] = [];
			for (const line of lines) padded.push(` ${line}`);
			return padded;
		}
		return lines;
	}

	private header(width: number): string[] {
		const actor = this.teamActive && this.entry.actor ? `${this.entry.actor} · ` : "";
		const elapsed = this.entry.elapsedMs !== null && this.entry.elapsedMs > 0 ? ` · ${formatDuration(this.entry.elapsedMs)}` : "";
		const text = `◍ ${actor}${this.entry.title} · ${statusLabel(this.entry.status)}${elapsed}`;
		const role = this.entry.status === "ERROR" || this.entry.status === "INTERRUPTED"
			? "error" : this.entry.status === "BLOCKED" || this.entry.status === "NOT_RUN" ? "warning"
				: this.entry.status === "PREVIEW" || this.entry.status === "QUEUED" || this.entry.status === "RUNNING"
					? "dim" : "toolTitle";
		const hint = this.entry.body && !this.entry.expanded ? " · Ctrl+O expand" : "";
		return [this.theme.style(role, text.slice(0, Math.max(0, width - hint.length)) + this.theme.style("dim", hint))];
	}

	private resultLines(result: string, width: number): string[] {
		const wrapped = wrapTextWithAnsi(result, Math.max(1, width - 2));
		const name = this.entry.tool?.originalName || this.entry.title;
		const expandable = name !== "read_file" && !this.entry.expanded;
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
			return "  " + this.theme.style("toolOutput", line);
		});
	}
}
