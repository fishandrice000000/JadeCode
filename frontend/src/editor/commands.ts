/**
 * 会话内 slash 命令的唯一目录（名称/参数/说明/是否接受参数）。
 *
 * 与 CLI 的根参数不同：这些命令只在编辑器里解析。未知 slash 留在本地草稿并报错，绝不发给
 * 模型；CLI 首轮 prompt 直接作为任务，不经过这里。旧别名（/help、/theme、/models 等）不回加。
 */
export interface CommandDefinition {
	name: string;
	arguments: string;
	description: string;
	takesArguments: boolean;
}

export const COMMANDS: CommandDefinition[] = [
	{ name: "/settings", arguments: "", description: "Open TUI settings and theme/mode menus", takesArguments: false },
	{ name: "/name", arguments: "[title]", description: "Rename the current session", takesArguments: true },
	{ name: "/team", arguments: "", description: "Show teammates, assignments and reports", takesArguments: false },
	{ name: "/session", arguments: "", description: "Show session identity and usage", takesArguments: false },
	{ name: "/hotkeys", arguments: "", description: "Show keys and available commands", takesArguments: false },
	{ name: "/copy", arguments: "", description: "Copy the latest assistant reply", takesArguments: false },
	{ name: "/new", arguments: "", description: "Start a new session", takesArguments: false },
	{ name: "/resume", arguments: "[id|name]", description: "Resume a session in this workspace", takesArguments: true },
	{ name: "/quit", arguments: "", description: "Close the session and exit", takesArguments: false },
	{ name: "/model", arguments: "", description: "Show model metadata (read-only)", takesArguments: false },
	{ name: "/thinking", arguments: "[level]", description: "Change the thinking level for this session", takesArguments: true },
	{ name: "/permission", arguments: "[profile]", description: "Change permission profile", takesArguments: true },
	{ name: "/mcp", arguments: "", description: "Show MCP servers and tools (read-only)", takesArguments: false },
	{ name: "/goal", arguments: "[condition|clear]", description: "Set, inspect or clear a completion goal", takesArguments: true },
];

/** 解析一行命令；未知命令返回 null（调用方保留草稿并报错）。 */
export function parseCommand(line: string): { command: CommandDefinition; argument: string } | null {
	const trimmed = line.trim();
	if (!trimmed.startsWith("/")) return null;
	const space = trimmed.indexOf(" ");
	const name = space < 0 ? trimmed : trimmed.slice(0, space);
	const argument = space < 0 ? "" : trimmed.slice(space + 1);
	const command = COMMANDS.find((item) => item.name === name);
	return command ? { command, argument } : null;
}

/** @returns 帮助行（用法 · 英文说明）。 */
export function helpLines(): string[] {
	return COMMANDS.map((command) => `${command.name}${command.arguments ? ` ${command.arguments}` : ""} · ${command.description}`);
}

/** @returns 匹配前缀的命令名（补全用，不做模糊评分）。 */
export function matching(prefix: string): CommandDefinition[] {
	if (!prefix.startsWith("/")) return [];
	return COMMANDS.filter((command) => command.name.startsWith(prefix));
}
