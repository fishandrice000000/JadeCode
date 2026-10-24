import type { Color, TerminalColorMode } from "@earendil-works/pi-tui";
import { resolveTheme, type ResolvedTheme } from "./theme-resolver.ts";
import { ThemeRepository, type ThemeLoadResult } from "./theme-repository.ts";

/**
 * 主题选择/预览/提交/监听。
 *
 * 状态分三层：逻辑选择（配置里的 theme 名）、有效快照（当前真正用于渲染的颜色）、预览
 * （尚未保存的临时选择）。Esc 取消预览不写配置；Enter 提交才发 settings.patch。主题文件
 * watch 的迟到结果用票据丢弃，坏文件保留最后有效快照。
 */
export interface ThemeSettings {
	theme: string;
	lightTheme: string;
	darkTheme: string;
}

export interface ThemeControllerOptions {
	repository: ThemeRepository;
	settings: () => ThemeSettings;
	/** 提交主题选择；返回真实失败信息（undefined 表示已排队保存）。 */
	save: (patch: Record<string, unknown>) => void;
	onError: (message: string) => void;
	onChange: () => void;
}

export class ThemeController {
	private readonly options: ThemeControllerOptions;
	private snapshotValue: ResolvedTheme;
	private previewName: string | null = null;
	private dark = true;
	private terminalForeground: Color | undefined;
	private terminalBackground: Color | undefined;
	private noColor = Boolean(process.env.NO_COLOR);
	private watchCancel: () => void = () => {};
	/** 订阅代次：只在真正更换被监听文件时递增，普通快照重算不能作废当前订阅。 */
	private watchTicket = 0;
	/** 构建代次：丢弃迟到的解析结果，与订阅代次分开。 */
	private buildTicket = 0;
	private closed = false;
	/** 已发出保存但尚未收到回执的主题；失败时用它恢复可重试预览。 */
	private committedName: string | null = null;
	private lastError = "";

	constructor(options: ThemeControllerOptions) {
		this.options = options;
		this.snapshotValue = this.build(this.options.settings().theme, 0);
		this.installWatch(this.effectiveName());
	}

	/** @returns 当前用于渲染的快照（预览优先于已保存选择）。 */
	snapshot(): ResolvedTheme {
		return this.snapshotValue;
	}

	/** @returns 页面中显示的逻辑选择（含未提交预览）。 */
	selectedName(): string {
		return this.previewName ?? this.options.settings().theme;
	}

	/** @returns 最近一次加载错误；空串表示当前快照有效。 */
	lastFailure(): string {
		return this.lastError;
	}

	/** @returns 可选项：system/auto/dark/light + 内置与自定义主题。 */
	names(): string[] {
		const custom = this.options.repository.list().map((entry) => entry.name).filter((name) => name !== "jadecode");
		return ["system", "auto", "dark", "light", "jadecode", ...custom];
	}

	/** 终端上报了明暗变化；auto/system 需要立刻重算，其它主题保持不变。 */
	setScheme(dark: boolean): void {
		if (this.dark === dark) return;
		this.dark = dark;
		const name = this.effectiveName();
		// auto 的明暗配对来自不同文件；切换后必须重订阅到新的实际来源。
		if (name === "auto") this.installWatch(name);
		if (name === "auto" || name === "system" || this.isBuiltinVariant(name) || this.isCustom(name)) {
			this.rebuild(name);
		}
	}

	/** 终端普通前景/背景；system 主题绑定它们，但强调色仍来自 Jade 主题。 */
	setTerminalColors(foreground: Color | undefined, background: Color | undefined): void {
		this.terminalForeground = foreground;
		this.terminalBackground = background;
		if (this.effectiveName() === "system") this.rebuild("system");
	}

	/** 预览：只改渲染快照，不写配置；同时把 watch 切到新主题文件。 */
	preview(name: string): void {
		if (!this.names().includes(name)) return;
		this.previewName = name;
		this.installWatch(name);
		this.rebuild(name);
	}

	/** 提交：保存逻辑选择；失败保留预览并报告真实错误。 */
	commit(): void {
		const name = this.previewName;
		if (!name) return;
		this.previewName = null;
		this.committedName = name;
		this.options.save({ theme: name });
		this.installWatch(name);
		this.rebuild(name);
	}

	/** settings.saved 回执：只清除与本次提交匹配的待定状态。 */
	commitSucceeded(): void { this.committedName = null; }

	commitSucceededFor(name: string): void {
		if (this.committedName === name) this.committedName = null;
	}

	/**
	 * settings.failed/command.error：保存失败时恢复可重试预览，保持选择名、快照与订阅一致。
	 * 只报告真实错误，不假装已保存。
	 */
	commitFailed(message: string): void {
		const name = this.committedName;
		if (!name) return;
		this.commitFailedFor(name, message);
	}

	/**
	 * 只恢复“仍是当前选择”的失败提交；提交后用户已预览/取消到更新状态时，
	 * 旧回执只报告错误，不覆盖当前选择、快照或订阅。
	 */
	commitFailedFor(name: string, message: string): void {
		if (this.committedName !== name) {
			this.options.onError(message);
			return;
		}
		this.committedName = null;
		if (this.previewName !== null && this.previewName !== name) {
			this.options.onError(message);
			return;
		}
		this.previewName = name;
		this.lastError = message;
		this.installWatch(name);
		this.rebuild(name);
		this.options.onError(message);
	}

	/** 取消预览：回到已保存选择，不写配置。 */
	cancel(): void {
		if (this.previewName === null) return;
		this.previewName = null;
		const saved = this.options.settings().theme;
		this.installWatch(saved);
		this.rebuild(saved);
	}

	/** 由已保存设置变化（settings.saved）触发；预览中的选择不被覆盖。 */
	settingsChanged(): void {
		if (this.previewName !== null) return;
		const saved = this.options.settings().theme;
		this.installWatch(saved);
		this.rebuild(saved);
	}

	close(): void {
		if (this.closed) return;
		this.closed = true;
		// 作废已排队回调：取消底层订阅与拒绝迟到通知是不同边界。
		this.watchTicket += 1;
		this.watchCancel();
	}

	/** 重新加载自定义主题（watch/手动刷新）；迟到票据不影响更新的选择。 */
	reload(): void {
		this.rebuild(this.effectiveName());
	}

	private effectiveName(): string {
		return this.previewName ?? this.options.settings().theme;
	}

	/**
	 * 解析“这次订阅要监听哪个文件”。
	 *
	 * <p>必须使用调用方传入的逻辑选择，而不是再次读取 settings().theme：commit 发出异步保存后、
	 * settings.saved 回执到达前，store 仍是旧主题；若在此回读会把刚提交的新主题订阅成旧文件。</p>
	 */
	private watchSourceName(name: string): string {
		if (name !== "auto") return name === "system" ? "jadecode" : name;
		const pair = this.dark ? this.options.settings().darkTheme : this.options.settings().lightTheme;
		if (!pair || pair === "auto" || pair === "system") return "jadecode";
		return pair;
	}

	private installWatch(name: string): void {
		if (this.closed) return;
		this.watchCancel();
		const ticket = ++this.watchTicket;
		const source = this.watchSourceName(name);
		this.watchCancel = this.options.repository.watch(source, (result) => {
			if (ticket !== this.watchTicket || this.closed) return; // 旧选择/关闭后的迟到结果
			if (result instanceof Error) {
				this.lastError = result.message;
				this.options.onError(result.message);
				return;
			}
			this.lastError = "";
			// 用逻辑名重建：auto 仍按当前明暗/配对解析；rebuild 只递增 buildTicket。
			this.rebuild(name);
		});
	}

	private rebuild(name: string): void {
		if (this.closed) return;
		const ticket = ++this.buildTicket;
		try {
			const next = this.build(name, ticket);
			if (ticket !== this.buildTicket) return;
			this.snapshotValue = next;
			this.lastError = "";
			this.options.onChange();
		} catch (error) {
			this.lastError = (error as Error).message;
			this.options.onError(`Theme "${name}" could not be loaded: ${this.lastError}. Keeping the previous colors.`);
		}
	}

	/** 解析逻辑选择 → 具体文档/变体。system/auto 需要终端事实；其它主题按明暗取变体。 */
	private build(name: string, _ticket: number): ResolvedTheme {
		const settings = this.options.settings();
		const mode: TerminalColorMode = colorMode();
		if (name === "system") {
			const document = this.options.repository.load("jadecode").document;
			return resolveTheme(document, this.dark ? "dark" : "light", {
				mode,
				noColor: this.noColor,
				system: { foreground: this.terminalForeground, background: this.terminalBackground, dark: this.dark },
			});
		}
		if (name === "auto") {
			const chosen = this.dark ? settings.darkTheme : settings.lightTheme;
			return this.build(chosen === "auto" ? "dark" : chosen, _ticket);
		}
		if (name === "dark" || name === "light") {
			const document = this.options.repository.load("jadecode").document;
			return resolveTheme(document, name, { mode, noColor: this.noColor });
		}
		const loaded = this.options.repository.load(name);
		return this.resolveDocument(loaded, mode);
	}

	private resolveDocument(loaded: ThemeLoadResult, mode: TerminalColorMode): ResolvedTheme {
		if (loaded.document.schemaVersion === 1) {
			return resolveTheme(loaded.document, "single", { mode, noColor: this.noColor });
		}
		return resolveTheme(loaded.document, this.dark ? "dark" : "light", { mode, noColor: this.noColor });
	}

	private isBuiltinVariant(name: string): boolean {
		return name === "dark" || name === "light" || name === "jadecode";
	}

	private isCustom(name: string): boolean {
		return this.options.repository.list().some((entry) => entry.name === name && !entry.builtin);
	}

	/** 供设置页显示：当前生效的变体名与外观。 */
	describe(): string {
		return `${this.snapshotValue.name} (${this.snapshotValue.appearance})`;
	}
}

/** 终端色能力：COLORTERM=truecolor 用 24bit，否则 256 色索引。 */
function colorMode(): TerminalColorMode {
	const colorterm = process.env.COLORTERM ?? "";
	return /truecolor|24bit/i.test(colorterm) ? "truecolor" : "256color";
}
