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
    paths?: (prefix: string, signal: AbortSignal) => Promise<AutocompleteSuggestions | null>;
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
		// 裸 @ 路径沿 Pi 的目录枚举继续补全；默认 Combined provider 的 @ 分支要求额外 fd，
		// Jade 并未装配它。只移去本次路径标记，再把标记加回候选，不复制文件扫描算法。
        // 常驻/远程会话由后端枚举，不能误读客户端本机同名目录。
        const word = beforeCursor.match(/(?:^|[\s(])([^\s]*)$/u)?.[1] ?? "";
        const command = beforeCursor.startsWith("/") && COMMANDS.some((item) =>
            item.name.startsWith(beforeCursor) || beforeCursor.startsWith(`${item.name} `));
        if (this.context.paths && !command) return this.context.paths(word, options.signal);
        const at = beforeCursor.match(/(?:^|[\s(])(@(?:"[^"]*|[^\s]*))$/u)?.[1];
		if (at) {
			const start = beforeCursor.lastIndexOf(at);
			const query = [...lines];
			query[cursorLine] = line.slice(0, start) + line.slice(start + 1);
			const suggestions = await this.paths.getSuggestions(query, cursorLine, cursorCol - 1, { ...options, force: true });
			return suggestions ? { prefix: `@${suggestions.prefix}`, items: suggestions.items.map((item) => ({ ...item, value: `@${item.value}` })) } : null;
		}
		const token = beforeCursor.trimStart();
		const absolutePath = token.startsWith("/") && !token.includes(" ") &&
			(token.slice(1).includes("/") || !COMMANDS.some((command) => command.name.startsWith(token)));
		// slash 候选受 commandSuggestions 控制；参数/路径候选不受影响。
		if (!absolutePath && beforeCursor.startsWith("/") && !beforeCursor.includes(" ")) {
			if (!this.context.commandSuggestions()) return null;
		}
		return this.paths.getSuggestions(lines, cursorLine, cursorCol, absolutePath ? { ...options, force: true } : options);
	}

	applyCompletion(lines: string[], cursorLine: number, cursorCol: number, item: AutocompleteItem, prefix: string) {
		if (prefix.startsWith("/") && item.value.startsWith("/")) {
			// Pi 将行首 /prefix 一律当命令，原先会给绝对目录加第二个 / 和尾空格，断开后续层级。
			const changed = [...lines];
			const start = cursorCol - prefix.length;
			const line = lines[cursorLine] ?? "";
			changed[cursorLine] = line.slice(0, start) + item.value + line.slice(cursorCol);
			return { lines: changed, cursorLine, cursorCol: start + item.value.length };
		}
		return this.paths.applyCompletion(lines, cursorLine, cursorCol, item, prefix);
	}

	shouldTriggerFileCompletion(lines: string[], cursorLine: number, cursorCol: number): boolean {
		const prefix = (lines[cursorLine] ?? "").slice(0, cursorCol).trimStart();
		if (prefix.startsWith("/") && prefix.slice(1).includes("/")) return true;
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
