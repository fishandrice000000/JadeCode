import { Editor, type TUI } from "@earendil-works/pi-tui";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import type { TuiSettings } from "../state/session-store.ts";
import { editorTheme } from "../theme/theme-adapters.ts";
import type { JadeCompletionProvider } from "./completion-provider.ts";
import { parseCommand } from "./commands.ts";

/**
 * 编辑器控制器：Pi Editor 包装 + slash 命令路由 + 草稿/历史/热设置。
 *
 * 草稿归属：页面/审批替换编辑器时不销毁 Editor 实例，因此关闭后同一个草稿和光标位置仍在。
 * Pi 在 onSubmit 前会清空输入，所以未知命令/空输入必须由这里把原文恢复，不能悄悄丢失。
 *
 * 设置热应用：paddingX/autocompleteMaxVisible/边框颜色立即调用 Pi setter；commandSuggestions
 * 只影响 slash 候选（普通/@ 路径补全仍按自身前缀工作）。
 */
export interface EditorCallbacks {
	/** @returns true 表示已接受（编辑器保持清空）；false 时恢复草稿。 */
	submitPrompt: (text: string) => boolean;
	/** @returns true 表示命令已处理。 */
	runSlash: (name: string, argument: string, raw: string) => boolean;
	/** 空 Enter：升级已缓冲输入；没有缓冲时无变化。 */
	promoteBuffer: () => boolean;
	error: (message: string) => void;
}

export class EditorController {
	readonly editor: Editor;
	private readonly completion: JadeCompletionProvider;
	private readonly callbacks: EditorCallbacks;
	private theme: ResolvedTheme;
	private settings: TuiSettings | null;
	private draftBeforeSubmit = "";

	constructor(tui: TUI, theme: ResolvedTheme, settings: TuiSettings | null,
		completion: JadeCompletionProvider, callbacks: EditorCallbacks) {
		this.theme = theme;
		this.settings = settings;
		this.completion = completion;
		this.callbacks = callbacks;
		this.editor = new Editor(tui, editorTheme(theme), {
			paddingX: settings?.editorPaddingX ?? 0,
			autocompleteMaxVisible: settings?.autocompleteMaxVisible ?? 5,
		});
		this.editor.setAutocompleteProvider(completion);
		this.editor.onSubmit = (text) => this.handleSubmit(text);
	}

	/** 主题变化：只更新颜色，不重建编辑器（保留草稿/历史）。 */
	setTheme(theme: ResolvedTheme): void {
		this.theme = theme;
		this.editor.borderColor = (text) => theme.style("border", text);
		this.editor.invalidate();
	}

	/** 设置变化：热应用 Pi setter；commandSuggestions 由补全 provider 读取。 */
	applySettings(settings: TuiSettings): void {
		this.settings = settings;
		this.editor.setPaddingX(settings.editorPaddingX);
		this.editor.setAutocompleteMaxVisible(settings.autocompleteMaxVisible);
		this.completion.refresh();
	}

	/** @returns 当前草稿全文。 */
	draft(): string {
		return this.editor.getText();
	}

	/** 外部编辑器返回/页面恢复时设置草稿；只在同一次调用栈内生效。 */
	setDraft(text: string): void {
		this.editor.setText(text);
	}

	/** 用户输入历史（只来自真实 USER 文本）。 */
	rememberInputs(texts: readonly string[]): void {
		for (const text of texts) if (text.trim()) this.editor.addToHistory(text);
	}

	/** 把焦点交给编辑器；页面关闭后调用。 */
	focus(tui: TUI): void {
		tui.setFocus(this.editor);
	}

	/** 提交当前草稿（外部快捷键/测试用）。 */
	submit(): void {
		const text = this.editor.getText();
		this.handleSubmit(text);
	}

	private handleSubmit(text: string): void {
		this.draftBeforeSubmit = text;
		if (text.trim().length === 0) {
			const changed = this.callbacks.promoteBuffer();
			if (!changed) this.editor.setText(text);
			return;
		}
		if (text.startsWith("/")) {
			const parsed = parseCommand(text);
			if (!parsed) {
				this.callbacks.error(`Unknown command: ${text.trim().split(/\s+/)[0]}. The draft was kept.`);
				this.editor.setText(text);
				return;
			}
			const accepted = this.callbacks.runSlash(parsed.command.name, parsed.argument, text);
			if (!accepted) this.editor.setText(text);
			return;
		}
		const accepted = this.callbacks.submitPrompt(text);
		if (!accepted) this.editor.setText(text);
	}

	/** 供页面在关闭时恢复“被提交但未接受”的草稿（目前只保留最近一次）。 */
	lastSubmitted(): string {
		return this.draftBeforeSubmit;
	}
}
