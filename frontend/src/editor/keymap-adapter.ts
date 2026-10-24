import { matchesKey } from "@earendil-works/pi-tui";

/**
 * 97 个动作的目录与用户覆盖（与旧 Java KeyBindings 同 ID/上下文/默认键）。
 *
 * 只登记 JadeCode 当前真正可执行的动作；`hidden` 对应旧 HIDDEN：帮助页不显示、也不会因为
 * 配置里出现就假装有后端。用户 `tui.keybindings` 覆盖按键但不新增动作；未知动作只记录问题，
 * 不破坏其它默认键。
 *
 * 这里只做“按键 → 动作 ID”的匹配，不执行动作；执行由 Application/页面/审批各自负责。
 */
export type ActionContext = "GLOBAL" | "EDITOR" | "SELECT" | "APPROVAL" | "ALT_SCREEN" | "SEARCH";

export interface ActionDefinition {
	id: string;
	label: string;
	context: ActionContext;
	hidden: boolean;
	defaults: string[];
}

const ACTIONS: ActionDefinition[] = [
	...editor("tui.editor.cursorUp", "Move cursor up", ["up"]),
	...editor("tui.editor.cursorDown", "Move cursor down", ["down"]),
	...hiddenAction("tui.editor.historyPrevious", "Previous input history", "EDITOR"),
	...hiddenAction("tui.editor.historyNext", "Next input history", "EDITOR"),
	...editor("tui.editor.cursorLeft", "Move cursor left", ["left", "ctrl+b"]),
	...editor("tui.editor.cursorRight", "Move cursor right", ["right", "ctrl+f"]),
	...editor("tui.editor.cursorWordLeft", "Move word left", ["alt+left", "ctrl+left", "alt+b"]),
	...editor("tui.editor.cursorWordRight", "Move word right", ["alt+right", "ctrl+right", "alt+f"]),
	...editor("tui.editor.cursorLineStart", "Move to line start", ["home", "ctrl+a"]),
	...editor("tui.editor.cursorLineEnd", "Move to line end", ["end", "ctrl+e"]),
	...hiddenAction("tui.editor.jumpForward", "Jump to character", "EDITOR"),
	...hiddenAction("tui.editor.jumpBackward", "Jump backward to character", "EDITOR"),
	...editor("tui.editor.pageUp", "Editor page up", ["pageup", "ctrl+pageup"]),
	...editor("tui.editor.pageDown", "Editor page down", ["pagedown", "ctrl+pagedown"]),
	...editor("tui.editor.deleteCharBackward", "Delete character backward", ["backspace"]),
	...editor("tui.editor.deleteCharForward", "Delete character forward", ["delete", "ctrl+d"]),
	...editor("tui.editor.deleteWordBackward", "Delete word backward", ["ctrl+w", "alt+backspace"]),
	...editor("tui.editor.deleteWordForward", "Delete word forward", ["alt+d", "alt+delete"]),
	...editor("tui.editor.deleteToLineStart", "Delete to line start", ["ctrl+u"]),
	...editor("tui.editor.deleteToLineEnd", "Delete to line end", ["ctrl+k"]),
	...editor("tui.editor.yank", "Yank killed text", ["ctrl+y"]),
	...editor("tui.editor.yankPop", "Cycle yanked text", ["alt+y"]),
	...editor("tui.editor.undo", "Undo", ["ctrl+-", "ctrl+_"]),
	...editor("tui.input.newLine", "Insert newline", ["shift+enter", "ctrl+j"]),
	...editor("tui.input.submit", "Submit input", ["enter"]),
	...editor("tui.input.tab", "Tab completion", ["tab"]),
	...hiddenAction("tui.input.copy", "Copy selection", "EDITOR"),
	...approval("approval.previous", "Previous approval option", ["up", "left", "shift+tab"]),
	...approval("approval.next", "Next approval option", ["down", "right", "tab"]),
	...approval("approval.confirm", "Confirm selected option", ["enter"]),
	...approval("approval.deny", "Deny current request", ["escape"]),
	...approval("approval.pageUp", "Read previous details", ["pageup"]),
	...approval("approval.pageDown", "Read next details", ["pagedown"]),
	...select("tui.select.up", "Previous item", ["up"]),
	...select("tui.select.down", "Next item", ["down"]),
	...select("tui.select.pageUp", "Previous page", ["pageup"]),
	...select("tui.select.pageDown", "Next page", ["pagedown"]),
	...select("tui.select.confirm", "Confirm selection", ["enter"]),
	...select("tui.select.cancel", "Cancel selection", ["escape", "ctrl+c"]),
	...alt("tui.altScreen.pageUp", "Scroll page up", ["pageup"]),
	...alt("tui.altScreen.pageDown", "Scroll page down", ["pagedown"]),
	...alt("tui.altScreen.halfPageUp", "Scroll half page up", []),
	...alt("tui.altScreen.halfPageDown", "Scroll half page down", []),
	...alt("tui.altScreen.lineUp", "Scroll line up", []),
	...alt("tui.altScreen.lineDown", "Scroll line down", []),
	...hiddenAction("tui.altScreen.previousPrompt", "Previous prompt", "ALT_SCREEN"),
	...hiddenAction("tui.altScreen.nextPrompt", "Next prompt", "ALT_SCREEN"),
	...alt("tui.altScreen.search", "Search transcript", ["ctrl+shift+f"]),
	...search("tui.altScreen.searchNext", "Next search match", ["enter", "ctrl+g"]),
	...search("tui.altScreen.searchPrevious", "Previous search match", ["shift+enter", "ctrl+shift+g"]),
	...search("tui.altScreen.searchClose", "Close search", ["escape"]),
	...alt("tui.altScreen.top", "Scroll to top", ["ctrl+home"]),
	...alt("tui.altScreen.bottom", "Scroll to bottom", ["ctrl+end"]),
	...global("app.interrupt", "Close local overlay", ["escape"]),
	...global("app.clear", "Clear draft", ["ctrl+c"]),
	...global("app.exit", "Exit when editor is empty", ["ctrl+d"]),
	...global("app.suspend", "Suspend to shell", ["ctrl+z"]),
	...global("app.thinking.cycle", "Cycle thinking level", ["shift+tab"]),
	...hiddenAction("app.thinking.save", "Save thinking default", "GLOBAL"),
	...hiddenAction("app.model.cycleForward", "Next model", "GLOBAL"),
	...hiddenAction("app.model.cycleBackward", "Previous model", "GLOBAL"),
	...hiddenAction("app.model.select", "Select model", "GLOBAL"),
	...global("app.tools.expand", "Expand or collapse tools", ["ctrl+o"]),
	...global("app.thinking.toggle", "Show or hide thinking", ["ctrl+t"]),
	...select("app.session.toggleNamedFilter", "Toggle named sessions", ["ctrl+n"]),
	...global("app.editor.external", "Open external editor", ["ctrl+g"]),
	...global("app.message.copy", "Copy last reply or selection", ["ctrl+x"]),
	...hiddenAction("app.message.followUp", "Queue follow-up", "GLOBAL"),
	...hiddenAction("app.message.dequeue", "Retrieve queued message", "GLOBAL"),
	...global("app.clipboard.pasteImage", "Paste clipboard image or text", ["ctrl+v"]),
	...global("app.session.new", "New session", []),
	...hiddenAction("app.session.tree", "Session tree", "GLOBAL"),
	...hiddenAction("app.session.fork", "Fork session", "GLOBAL"),
	...global("app.session.resume", "Resume session", []),
	...hiddenAction("app.tree.foldOrUp", "Fold tree or move up", "GLOBAL"),
	...hiddenAction("app.tree.unfoldOrDown", "Unfold tree or move down", "GLOBAL"),
	...hiddenAction("app.tree.editLabel", "Edit tree label", "GLOBAL"),
	...hiddenAction("app.tree.toggleLabelTimestamp", "Toggle label timestamp", "GLOBAL"),
	...select("app.session.togglePath", "Toggle session path", ["ctrl+p"]),
	...select("app.session.toggleSort", "Toggle session sort", ["ctrl+s"]),
	...hiddenAction("app.session.rename", "Rename session", "GLOBAL"),
	...hiddenAction("app.session.delete", "Delete session", "GLOBAL"),
	...hiddenAction("app.session.deleteNoninvasive", "Delete session non-invasively", "GLOBAL"),
	...hiddenAction("app.models.save", "Save model selection", "GLOBAL"),
	...hiddenAction("app.models.enableAll", "Enable all models", "GLOBAL"),
	...hiddenAction("app.models.clearAll", "Clear model selection", "GLOBAL"),
	...hiddenAction("app.models.toggleProvider", "Toggle provider models", "GLOBAL"),
	...hiddenAction("app.models.reorderUp", "Reorder model up", "GLOBAL"),
	...hiddenAction("app.models.reorderDown", "Reorder model down", "GLOBAL"),
	...hiddenAction("app.tree.filter.default", "Tree default filter", "GLOBAL"),
	...hiddenAction("app.tree.filter.noTools", "Hide tool results in tree", "GLOBAL"),
	...hiddenAction("app.tree.filter.userOnly", "Show user messages only", "GLOBAL"),
	...hiddenAction("app.tree.filter.labeledOnly", "Show labeled tree items", "GLOBAL"),
	...hiddenAction("app.tree.filter.all", "Show all tree items", "GLOBAL"),
	...hiddenAction("app.tree.filter.cycleForward", "Cycle tree filter forward", "GLOBAL"),
	...hiddenAction("app.tree.filter.cycleBackward", "Cycle tree filter backward", "GLOBAL"),
	...global("app.permission.menu", "Permission menu", []),
];

// 局部构造器只用于把上面的目录写得可读；它们只在模块初始化时运行一次。
function editor(id: string, label: string, defaults: string[]): ActionDefinition[] {
	return [{ id, label, context: "EDITOR", hidden: false, defaults }];
}
function approval(id: string, label: string, defaults: string[]): ActionDefinition[] {
	return [{ id, label, context: "APPROVAL", hidden: false, defaults }];
}
function select(id: string, label: string, defaults: string[]): ActionDefinition[] {
	return [{ id, label, context: "SELECT", hidden: false, defaults }];
}
function alt(id: string, label: string, defaults: string[]): ActionDefinition[] {
	return [{ id, label, context: "ALT_SCREEN", hidden: false, defaults }];
}
function search(id: string, label: string, defaults: string[]): ActionDefinition[] {
	return [{ id, label, context: "SEARCH", hidden: false, defaults }];
}
function global(id: string, label: string, defaults: string[]): ActionDefinition[] {
	return [{ id, label, context: "GLOBAL", hidden: false, defaults }];
}
function hiddenAction(id: string, label: string, context: ActionContext): ActionDefinition[] {
	return [{ id, label, context, hidden: true, defaults: [] }];
}

export const ACTION_COUNT = ACTIONS.length;
export const SUPPORTED_COUNT = ACTIONS.filter((action) => !action.hidden).length;
export const HIDDEN_COUNT = ACTIONS.filter((action) => action.hidden).length;

export class KeymapAdapter {
	private readonly definitions: ActionDefinition[];
	private readonly bindings = new Map<string, string[]>();
	private readonly overridden = new Set<string>();
	private readonly problems: string[] = [];

	constructor(overrides: Record<string, string[]> = {}) {
		this.definitions = ACTIONS.map((action) => ({ ...action, defaults: [...action.defaults] }));
		for (const action of this.definitions) this.bindings.set(action.id, [...action.defaults]);
		for (const [id, keys] of Object.entries(overrides)) {
			if (!this.bindings.has(id)) {
				this.problems.push(`Unknown keybinding action: ${id}`);
				continue;
			}
			this.bindings.set(id, keys.filter((key) => key.trim().length > 0));
			this.overridden.add(id);
		}
	}

	problemsFound(): string[] {
		return [...this.problems];
	}

	definition(id: string): ActionDefinition | undefined {
		return this.definitions.find((action) => action.id === id);
	}

	/**
	 * @returns 只包含用户覆盖、且由 Pi Editor/选择器消费的动作键配置。
	 * 未覆盖的编辑器动作保留 Pi 自己的默认键；应用级动作不传给 Pi。
	 */
	piOverrides(): Record<string, string | string[]> {
		const result: Record<string, string | string[]> = {};
		for (const action of this.definitions) {
			// HIDDEN 动作即使被配置绑定也不能交给 Pi；显式置空会清除 Pi 自带默认键
			// （例如 jumpForward 的 ctrl+]），避免“目录隐藏但实际可执行”。
			if (action.hidden) {
				result[action.id] = [];
				continue;
			}
			if (!this.overridden.has(action.id)) continue;
			if (!["EDITOR", "SELECT", "ALT_SCREEN", "SEARCH"].includes(action.context)) continue;
			const keys = this.bindings.get(action.id) ?? [];
			result[action.id] = keys.length === 1 ? keys[0]! : [...keys];
		}
		return result;
	}

	/** @returns 该动作是否被用户显式覆盖（用于兼容 Pi 的硬编码 LF 分支）。 */
	isOverridden(id: string): boolean { return this.overridden.has(id); }

	/** @returns 动作当前键（含用户覆盖）；未知动作返回空数组。 */
	keys(id: string): string[] {
		return [...(this.bindings.get(id) ?? [])];
	}

	/** 是否匹配某个动作（忽略 key release；release 不构成确认）。 */
	matches(id: string, data: string): boolean {
		for (const chord of this.bindings.get(id) ?? []) {
			if (matchesKey(data, chord as never)) return true;
		}
		return false;
	}

	/** 在给定上下文中匹配第一个动作。 */
	matchAny(contexts: readonly ActionContext[], data: string): string | null {
		for (const action of this.definitions) {
			if (action.hidden || !contexts.includes(action.context)) continue;
			if (this.matches(action.id, data)) return action.id;
		}
		return null;
	}

	/** Hotkeys 页：Common 分类最多 8 个真实常用动作。 */
	common(): ActionDefinition[] {
		const ids = [
			"tui.input.submit", "tui.input.newLine", "tui.input.tab", "app.tools.expand",
			"app.thinking.toggle", "app.editor.external", "app.message.copy", "app.suspend",
		];
		return ids.map((id) => this.definition(id)).filter((action): action is ActionDefinition => action !== undefined);
	}

	/** Hotkeys 页：按上下文分组（不含 HIDDEN）。 */
	groups(): Array<{ context: ActionContext; actions: ActionDefinition[] }> {
		const order: ActionContext[] = ["GLOBAL", "EDITOR", "SELECT", "APPROVAL", "ALT_SCREEN", "SEARCH"];
		return order.map((context) => ({
			context,
			actions: this.definitions.filter((action) => action.context === context && !action.hidden),
		})).filter((group) => group.actions.length > 0);
	}

	/** 显示用的键描述。 */
	displayKeys(id: string): string {
		return this.keys(id).join(", ");
	}
}
