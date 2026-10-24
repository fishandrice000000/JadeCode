import {
	CombinedAutocompleteProvider,
	type AutocompleteItem,
	type AutocompleteProvider,
	type AutocompleteSuggestions,
	type SlashCommand,
} from "@earendil-works/pi-tui";
import { COMMANDS } from "./commands.ts";

/**
 * 补全：slash 命令 + 命令参数 + 普通文本/@ 文件路径。
 *
 * Q15=B：普通文本和 @ 路径接固定 Pi `CombinedAutocompleteProvider`（含文件候选），不再只给
 * slash 命令补全。`/resume`、`/thinking`、`/permission` 优先真实业务候选（来自后端能力），
 * 不给所有命令参数盲目塞文件名。commandSuggestions 关闭时只停 slash 候选，普通路径补全仍按
 * 自己的前缀规则工作。
 */
export interface CompletionContext {
	workspace: string;
	commandSuggestions: () => boolean;
	sessions: () => Promise<AutocompleteItem[]>;
	thinking: () => string[];
	permissions: () => string[];
}

export class JadeCompletionProvider implements AutocompleteProvider {
	private readonly paths: CombinedAutocompleteProvider;
	private readonly context: CompletionContext;
	private readonly commandList: SlashCommand[];

	constructor(context: CompletionContext) {
		this.context = context;
		this.commandList = COMMANDS.map((command) => ({
			// Pi 的 CombinedAutocompleteProvider 自行补 `/`；这里必须传裸名，
			// 否则 applyCompletion 会生成 `//settings`，Jade 的命令解析随即失败。
			name: command.name.replace(/^\//, ""),
			description: command.description,
			argumentHint: command.arguments,
			getArgumentCompletions: (prefix: string) => this.argumentCompletions(`/${command.name.replace(/^\//, "")}`, prefix),
		}));
		this.paths = new CombinedAutocompleteProvider(this.commandList, context.workspace);
	}

	/** 设置变更后重建（commandSuggestions / workspace 变化）。 */
	refresh(): void {
		// CombinedAutocompleteProvider 的字段不可变；重新构造一份即可。
		(this as unknown as { paths: CombinedAutocompleteProvider }).paths =
			new CombinedAutocompleteProvider(this.commandList, this.context.workspace);
	}

	async getSuggestions(lines: string[], cursorLine: number, cursorCol: number,
		options: { signal: AbortSignal; force?: boolean }): Promise<AutocompleteSuggestions | null> {
		const line = lines[cursorLine] ?? "";
		const beforeCursor = line.slice(0, cursorCol);
		// slash 候选受 commandSuggestions 控制；参数/路径候选不受影响。
		if (beforeCursor.startsWith("/") && !beforeCursor.includes(" ")) {
			if (!this.context.commandSuggestions()) return null;
		}
		return this.paths.getSuggestions(lines, cursorLine, cursorCol, options);
	}

	applyCompletion(lines: string[], cursorLine: number, cursorCol: number, item: AutocompleteItem, prefix: string) {
		return this.paths.applyCompletion(lines, cursorLine, cursorCol, item, prefix);
	}

	shouldTriggerFileCompletion(lines: string[], cursorLine: number, cursorCol: number): boolean {
		return this.paths.shouldTriggerFileCompletion?.(lines, cursorLine, cursorCol) ?? true;
	}

	/** 命令参数候选：只对真实有业务的命令给数据；路径参数交给 Pi 的文件候选。 */
	private async argumentCompletions(name: string, prefix: string): Promise<AutocompleteItem[] | null> {
		const needle = prefix.trim().toLowerCase();
		let values: string[] = [];
		switch (name) {
			case "/permission":
				values = this.context.permissions();
				break;
			case "/thinking":
				values = this.context.thinking();
				break;
			case "/resume":
				return (await this.context.sessions()).filter((item) => item.label.toLowerCase().includes(needle));
			case "/name":
				return null;
			default:
				return null;
		}
		return values.filter((value) => value.toLowerCase().includes(needle))
			.map((value) => ({ value, label: value }));
	}
}
