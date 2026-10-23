import type { Component } from "@earendil-works/pi-tui";
import type { Entry, TranscriptStore } from "../state/transcript-store.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import type { MarkdownFactory } from "../markdown/markdown-factory.ts";
import type { SessionStore } from "../state/session-store.ts";
import { MessageCard } from "./message-views.ts";
import { ToolCard } from "./tool-views.ts";
import { BrandingView } from "./branding-view.ts";

/**
 * 对话视图：把转录条目渲染成完整文档（fullscreen 由 Pi 滚动，regular 进 scrollback）。
 *
 * 缓存策略：每个条目一张卡，键为 entry.id；条目 revision / 宽度 / 主题变化时才重建。
 * 稳定旧卡不因新 token 全部失效。另外记录每条目的文档行偏移，供搜索跳转使用（只依赖本视图
 * 自己的渲染结果，不读 Pi 内部布局）。
 */
export class ConversationView implements Component {
	private readonly cards = new Map<string, { revision: number; key: string; card: MessageCard | ToolCard; lines: string[] }>();
	private readonly branding = new BrandingView();
	private lastWidth = -1;
	private lastKey = "";
	private readonly offsets = new Map<string, number>();

	constructor(
		private readonly transcript: TranscriptStore,
		private readonly session: SessionStore,
		private readonly factory: MarkdownFactory,
	) {}

	render(width: number): string[] {
		const theme = this.session.snapshot() ? this.currentTheme() : undefined;
		if (!theme) return [];
		const settings = this.session.settings();
		const key = `${width}:${theme.name}:${theme.mode}:${theme.noColor ? 1 : 0}:${settings?.thinkingVisible}:${settings?.outputPad}:${settings?.codeBlockIndent}:${this.session.teamActive()}`;
		const entries = this.transcript.entries();
		if (this.lastWidth === width && this.lastKey === key && this.cardsCover(entries)) {
			const cached: string[] = [];
			for (const entry of entries) {
				const card = this.cards.get(entry.id);
				if (card) cached.push(...card.lines);
			}
			return cached;
		}
		this.lastWidth = width;
		this.lastKey = key;
		this.offsets.clear();
		const lines: string[] = [];
		const visible = entries.filter((entry) => entry.kind !== "THINKING" || settings?.thinkingVisible !== false);
		if (visible.length === 0) {
			const rows = this.session.snapshot() ? 24 : 24;
			lines.push(...this.branding.render(theme, width, rows, this.session.bootstrap()?.projectVersion ?? ""));
			lines.push("");
			if (!settings?.quietStartup) {
				lines.push(theme.style("dim", `Workspace: ${this.session.snapshot()?.workspace ?? ""}`));
			}
			this.offsets.set("__welcome__", 0);
			return lines;
		}
		for (const entry of visible) {
			this.offsets.set(entry.id, lines.length);
			const card = this.cardFor(entry, theme, settings?.thinkingVisible !== false, settings?.outputPad ?? 1,
				settings?.codeBlockIndent ?? "  ");
			const cardKey = `${entry.revision}:${width}:${theme.name}:${theme.mode}:${theme.noColor ? 1 : 0}:${settings?.outputPad}:${settings?.codeBlockIndent}`;
			const cached = this.cards.get(entry.id);
			if (!cached || cached.revision !== entry.revision || cached.key !== cardKey || cached.card !== card) {
				const cardLines = card.render(width);
				this.cards.set(entry.id, { revision: entry.revision, key: cardKey, card, lines: cardLines });
				lines.push(...cardLines);
			} else {
				lines.push(...cached.lines);
			}
			lines.push("");
		}
		return lines;
	}

	invalidate(): void {
		this.cards.clear();
		this.lastWidth = -1;
	}

	/** @returns 条目在文档中的起始行（搜索跳转用），未知返回 null。 */
	entryOffset(id: string): number | null {
		return this.offsets.get(id) ?? null;
	}

	/** 供搜索跳转：第一个包含查询文本的条目偏移。 */
	findOffset(query: string): number | null {
		if (!query) return null;
		const needle = query.toLowerCase();
		for (const entry of this.transcript.entries()) {
			const haystack = `${entry.title}\n${entry.arguments}\n${entry.body}`.toLowerCase();
			if (haystack.includes(needle)) return this.offsets.get(entry.id) ?? null;
		}
		return null;
	}

	private cardsCover(entries: readonly Entry[]): boolean {
		if (this.cards.size !== entries.length) return false;
		for (const entry of entries) if (!this.cards.has(entry.id)) return false;
		return true;
	}

	private currentTheme(): ResolvedTheme {
		// 由 Application 在构造时注入最新主题访问器（见 setThemeAccessor）。
		return this.themeAccessor();
	}

	private themeAccessor: () => ResolvedTheme = () => {
		throw new Error("ConversationView theme accessor was not installed");
	};

	/** 安装主题访问器；Application 在创建后立即调用，避免复制第二份主题状态。 */
	setThemeAccessor(accessor: () => ResolvedTheme): void {
		this.themeAccessor = accessor;
	}

	private cardFor(entry: Entry, theme: ResolvedTheme, thinkingVisible: boolean, outputPad: number,
		codeBlockIndent: string): MessageCard | ToolCard {
		if (entry.kind === "TOOL") {
			return new ToolCard(entry, theme, outputPad, this.session.teamActive());
		}
		return new MessageCard(entry, theme, this.factory, codeBlockIndent, thinkingVisible,
			this.session.teamActive(), (actor) => this.session.displayActor(actor) || actor);
	}
}
