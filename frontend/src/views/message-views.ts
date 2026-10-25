import { wrapTextWithAnsi, visibleWidth } from "@earendil-works/pi-tui";
import type { Entry } from "../state/transcript-store.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import type { MarkdownFactory } from "../markdown/markdown-factory.ts";
import { clipLine, display } from "../presentation/text-safety.ts";
import { formatDuration } from "../presentation/format.ts";
import { headingDetail, headingPart, statusRole } from "./card-heading.ts";

/**
 * 消息卡：Request / Reply / Thinking / Notice。
 *
 * 保留既有产品契约：标题 + 真实状态 + 真实耗时；正文交给 Pi Markdown。没有耗时就不显示
 * 0.0s；HISTORICAL 不加成功标记。同一张卡按 (entry.revision, width, theme) 缓存 Markdown
 * 实例，稳定旧卡不会每 token 重建。
 */
export class MessageCard {
	private markdown: { markdown: { render(width: number): string[]; setText(text: string): void; invalidate(): void }; setText: (text: string) => void; invalidate: () => void } | undefined;
	private markdownRevision = -1;
	private markdownWidth = -1;
	private markdownTheme = "";

	constructor(
		private readonly entry: Entry,
		private readonly theme: ResolvedTheme,
		private readonly factory: MarkdownFactory,
		private readonly codeBlockIndent: string,
		private readonly thinkingVisible: boolean,
		private readonly teamActive: boolean,
		private readonly actorName: (actor: string) => string,
	) {}

	/** 该条目是否应该显示（思考可按设置隐藏）。 */
	visible(): boolean {
		if (this.entry.kind === "THINKING" && !this.thinkingVisible) return false;
		return true;
	}

	render(width: number): string[] {
		if (!this.visible()) return [];
		const lines: string[] = [];
		lines.push(this.title(width));
		const body = this.body(width);
		for (const line of body) lines.push(line);
		return lines.map((line) => clipLine(line, width));
	}

	private title(width: number): string {
		const theme = this.theme;
		// 标题说明“谁/什么”，状态说明“结果如何”，分别取色才能快速扫描故障。
		const actor = this.teamActive && this.entry.actor ? headingPart(theme, "muted", `${this.actorName(this.entry.actor)} · `) : "";
		const status = this.entry.status && this.entry.status !== "STREAMING"
			? headingDetail(theme, statusRole(this.entry.status), statusLabel(this.entry.status)) : "";
		const elapsed = this.entry.elapsedMs !== null && this.entry.elapsedMs > 0
			? headingDetail(theme, "dim", formatDuration(this.entry.elapsedMs)) : "";
		const icon = this.entry.kind === "USER" ? "◆" : this.entry.kind === "THINKING" ? "◇" : this.entry.kind === "TOOL" ? "◍" : "◆";
		const role = this.entry.status === "ERROR" || this.entry.status === "HISTORICAL ERROR" ? "error"
			: this.entry.kind === "USER" ? "requestTitle" : this.entry.kind === "THINKING" ? "thinkingTitle"
				: this.entry.kind === "NOTICE" ? "customMessageLabel" : "replyTitle";
		const text = headingPart(theme, role, `${icon} `) + actor + headingPart(theme, role, this.entry.title, true) + status + elapsed;
		return clipLine(text, Math.max(0, width));
	}

	private body(width: number): string[] {
		const theme = this.theme;
		if (this.entry.kind === "THINKING" && !this.entry.expanded) return [];
		if (this.entry.kind === "REPLY" || this.entry.kind === "THINKING") {
			const key = `${theme.name}:${theme.mode}:${theme.noColor}`;
			if (!this.markdown || this.markdownRevision !== this.entry.revision || this.markdownWidth !== width || this.markdownTheme !== key) {
				if (!this.markdown) this.markdown = this.factory.create(theme, this.codeBlockIndent, this.entry.kind !== "THINKING", this.entry.kind === "THINKING");
				this.markdown.setText(display(this.entry.body));
				this.markdown.invalidate();
				this.markdownRevision = this.entry.revision;
				this.markdownWidth = width;
				this.markdownTheme = key;
			}
			return this.markdown.markdown.render(width);
		}
		if (this.entry.kind === "USER") {
			return this.boxed(display(this.entry.body), width, "userMessageBg", "userMessageText");
		}
		const prefix = "  ";
		// 错误正文也需要醒目；普通通知使用当前主题正文色，不再整块压成灰色。
		const role = this.entry.status === "ERROR" || this.entry.status === "HISTORICAL ERROR" ? "error" : "customMessageText";
		return wrapTextWithAnsi(display(this.entry.body), Math.max(1, width - prefix.length))
			.map((line) => prefix + theme.style(role, line));
	}

	/** 用户消息使用主题 surface 背景；NO_COLOR 时只保留缩进。 */
	private boxed(text: string, width: number, bg: "userMessageBg", fg: "userMessageText"): string[] {
		const theme = this.theme;
		const inner = Math.max(1, width - 2);
		const lines = wrapTextWithAnsi(text, inner);
		const result: string[] = [];
		for (const line of lines) {
			const padded = line + " ".repeat(Math.max(0, inner - visibleWidth(line)));
			result.push(theme.withBackground(fg, bg, ` ${padded} `));
		}
		return result;
	}
}

/** 状态英文标签；未知状态保留原值（来自真实执行事实）。 */
export function statusLabel(status: string): string {
	switch (status) {
		case "STREAMING": return "Streaming";
		case "DONE": return "Done";
		case "INCOMPLETE": return "Incomplete";
		case "HISTORICAL": return "Historical";
		case "HISTORICAL ERROR": return "Historical error";
		case "PREVIEW": return "Preview";
		case "QUEUED": return "Queued";
		case "RUNNING": return "Running";
		case "RETURNED": return "Returned";
		case "ERROR": return "Error";
		case "BLOCKED": return "Blocked";
		case "NOT_RUN": return "Not run";
		case "INTERRUPTED": return "Interrupted";
		case "STOP_REQUESTED": return "Stop requested";
		default: return status;
	}
}
