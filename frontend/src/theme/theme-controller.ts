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
	private ticket = 0;
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

	/** 预览：只改渲染快照，不写配置。 */
	preview(name: string): void {
		if (!this.names().includes(name)) return;
		this.previewName = name;
		this.rebuild(name);
	}

	/** 提交：保存逻辑选择；失败保留预览并报告真实错误。 */
	commit(): void {
		const name = this.previewName;
		if (!name) return;
		this.previewName = null;
		this.options.save({ theme: name });
		this.rebuild(name);
	}

	/** 取消预览：回到已保存选择，不写配置。 */
	cancel(): void {
		if (this.previewName === null) return;
		this.previewName = null;
		this.rebuild(this.options.settings().theme);
	}

	/** 由已保存设置变化（settings.saved）触发；预览中的选择不被覆盖。 */
	settingsChanged(): void {
		if (this.previewName !== null) return;
		this.rebuild(this.options.settings().theme);
	}

	close(): void {
		this.watchCancel();
	}

	/** 重新加载自定义主题（watch/手动刷新）；迟到票据不影响更新的选择。 */
	reload(): void {
		this.rebuild(this.effectiveName());
	}

	private effectiveName(): string {
		return this.previewName ?? this.options.settings().theme;
	}

	private installWatch(name: string): void {
		this.watchCancel();
		const ticket = ++this.ticket;
		this.watchCancel = this.options.repository.watch(name, (result) => {
			if (ticket !== this.ticket) return; // 迟到结果属于旧主题选择
			if (result instanceof Error) {
				this.lastError = result.message;
				this.options.onError(result.message);
				return;
			}
			this.lastError = "";
			this.rebuild(name);
		});
	}

	private rebuild(name: string): void {
		const ticket = ++this.ticket;
		try {
			const next = this.build(name, ticket);
			if (ticket !== this.ticket) return;
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
