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
	/**
	 * 每个条目缓存自己的卡对象与已渲染行（含卡后空行）。
	 * 键包含正文 revision、宽度、主题和影响排版的设置，所以稳定旧卡不会因新 token 全部重建。
	 */
	private readonly cards = new Map<string, { entryKey: string; card: MessageCard | ToolCard; lines: string[] }>();
	private readonly branding = new BrandingView();
	private cachedLines: string[] | undefined;
	private cachedRevision = -1;
	private cachedKey = "";
	private viewportRows = 24;
	private readonly offsets = new Map<string, number>();

	constructor(
		private readonly transcript: TranscriptStore,
		private readonly session: SessionStore,
		private readonly factory: MarkdownFactory,
	) {}

	/** 终端真实行数；欢迎区档位与缓存都依赖它，不能写死 24。 */
	setViewportRows(rows: number): void {
		this.viewportRows = Math.max(1, Math.floor(rows));
	}

	render(width: number): string[] {
		const theme = this.session.snapshot() ? this.currentTheme() : undefined;
		if (!theme) return [];
		const settings = this.session.settings();
		// transcript.revision() 在任何条目新增/更新/替换时递增；必须参与顶层缓存键。
		const key = `${width}:${this.viewportRows}:${this.transcript.revision()}:${theme.name}:${theme.mode}:${theme.noColor ? 1 : 0}:` +
			`${settings?.thinkingVisible}:${settings?.outputPad}:${settings?.codeBlockIndent}:${this.session.teamActive()}:` +
			`${settings?.quietStartup}:${this.session.bootstrap()?.projectVersion ?? ""}`;
		if (this.cachedLines && this.cachedRevision === this.transcript.revision() && this.cachedKey === key) {
			return this.cachedLines;
		}
		this.cachedRevision = this.transcript.revision();
		this.cachedKey = key;
		this.offsets.clear();
		const entries = this.transcript.entries();
		const visible = entries.filter((entry) => entry.kind !== "THINKING" || settings?.thinkingVisible !== false);
		if (visible.length === 0) {
			// 空会话也走完整路径并缓存，避免第二次渲染错误地返回空数组。
			const lines: string[] = [];
			lines.push(...this.branding.render(theme, width, this.viewportRows,
				this.session.bootstrap()?.projectVersion ?? ""));
			lines.push("");
			if (!settings?.quietStartup) {
				lines.push(theme.style("dim", `Workspace: ${this.session.snapshot()?.workspace ?? ""}`));
			}
			this.offsets.set("__welcome__", 0);
			this.cachedLines = lines;
			return lines;
		}
		const lines: string[] = [];
		const entryKeySuffix = `${width}:${theme.name}:${theme.mode}:${theme.noColor ? 1 : 0}:` +
			`${settings?.outputPad}:${settings?.codeBlockIndent}:${settings?.thinkingVisible}:${this.session.teamActive()}`;
		for (const entry of visible) {
			this.offsets.set(entry.id, lines.length);
			const entryKey = `${entry.revision}:${entry.status}:${entry.elapsedMs}:${entry.expanded}:${entryKeySuffix}`;
			let cached = this.cards.get(entry.id);
			if (!cached || cached.entryKey !== entryKey) {
				const card = this.cardFor(entry, theme, settings?.thinkingVisible !== false, settings?.outputPad ?? 1,
					settings?.codeBlockIndent ?? "  ");
				cached = { entryKey, card, lines: card.render(width) };
				this.cards.set(entry.id, cached);
			}
			lines.push(...cached.lines);
			// 卡后空行属于该条目缓存，快速路径与重排路径的间距保持一致。
			lines.push("");
		}
		// 已删除/被回滚的条目不能继续占用缓存；用集合避免条目多时平方扫描。
		const visibleIds = new Set(visible.map((entry) => entry.id));
		for (const id of [...this.cards.keys()]) {
			if (!visibleIds.has(id)) this.cards.delete(id);
		}
		this.cachedLines = lines;
		return lines;
	}

	invalidate(): void {
		this.cards.clear();
		this.cachedLines = undefined;
		this.cachedRevision = -1;
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
