import { matchesKey } from "@earendil-works/pi-tui";
import type { Page, PageContext } from "./page-stack.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import type { KeymapAdapter } from "../editor/keymap-adapter.ts";
import { helpLines } from "../editor/commands.ts";
import { formatPercent, formatTokens } from "../presentation/format.ts";

/**
 * 四类只读信息页：Hotkeys / Model / Session / MCP。
 *
 * 全部放在输入区（Pi 上下横线页面），关闭不写转录；未知资料显示 "?"/“not configured”，
 * 不补假 0 或假 online。Hotkeys 默认 Common ≤8，Tab/左右切换分类并各自记住位置。
 */
abstract class InformationPage implements Page {
	protected readonly context: PageContext;
	readonly title: string;
	protected constructor(context: PageContext, title: string) {
		this.context = context;
		this.title = title;
	}
	theme(): ResolvedTheme { return this.context.theme(); }
	invalidate(): void {}
	abstract hint(): string;
	abstract render(width: number): string[];
	abstract handleInput(data: string): boolean;
}

/** 键位页：Common 与分类页，Tab 切换。 */
export class HotkeysPage extends InformationPage {
	private readonly keymap: KeymapAdapter;
	private category = 0;

	constructor(context: PageContext, keymap: KeymapAdapter) {
		super(context, "Keys and commands");
		this.keymap = keymap;
	}

	hint(): string { return "Tab/Left/Right switch category · Esc close"; }

	render(width: number): string[] {
		const theme = this.context.theme();
		const groups = this.keymap.groups();
		const lines: string[] = [];
		const common = this.category === 0;
		lines.push(theme.style("dim", common ? "Common" : `${groups[this.category - 1]?.context ?? ""}`));
		if (common) {
			for (const action of this.keymap.common()) {
				const keys = this.keymap.displayKeys(action.id);
				const label = action.label.padEnd(Math.min(30, Math.max(12, width - keys.length - 6)), " ");
				lines.push(`${theme.style("accent", keys || "—").padEnd(0)}  ${theme.style("text", label)}`);
			}
			lines.push("");
			lines.push(theme.style("muted", "Slash commands:"));
			for (const line of helpLines()) lines.push(theme.style("text", line));
		} else {
			for (const action of groups[this.category - 1]?.actions ?? []) {
				const keys = this.keymap.displayKeys(action.id) || "—";
				const label = action.label.padEnd(Math.min(30, Math.max(12, width - keys.length - 6)), " ");
				lines.push(`${theme.style("accent", keys)}  ${theme.style("text", label)}`);
			}
		}
		return lines.map((line) => line.slice(0, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		if (this.context.keymap.matches("tui.select.cancel", data)) { this.context.closePage(); return true; }
		const groups = this.keymap.groups();
		if (this.context.keymap.matches("tui.select.down", data) || matchesKey(data, "tab") || matchesKey(data, "right")) {
			this.category = (this.category + 1) % (groups.length + 1);
			return true;
		}
		if (this.context.keymap.matches("tui.select.up", data) || matchesKey(data, "shift+tab") || matchesKey(data, "left")) {
			this.category = (this.category - 1 + groups.length + 1) % (groups.length + 1);
			return true;
		}
		return false;
	}
}

/** 模型页：只读元数据；不提供模型热切换。 */
export class ModelPage extends InformationPage {
	constructor(context: PageContext) { super(context, "Model"); }

	hint(): string { return "Read-only · Esc close"; }

	render(width: number): string[] {
		const theme = this.context.theme();
		const snapshot = this.context.state.snapshot();
		if (!snapshot) return [theme.style("dim", "Waiting for backend state…")];
		const lines: string[] = [];
		lines.push(theme.style("text", `Model: ${snapshot.model || "?"}`));
		lines.push(theme.style("text", `Thinking: ${snapshot.thinkingLabel || snapshot.thinking || "default"}`));
		lines.push(theme.style("text", `Context window: ${snapshot.contextWindowTokens ?? "unknown"}`));
		lines.push(theme.style("text", `Max input: ${snapshot.maxInputTokens ?? "unknown"}`));
		lines.push(theme.style("text", `Max output: ${snapshot.maxOutputTokens ?? "unknown"}`));
		if (snapshot.modelSource) lines.push(theme.style("dim", snapshot.modelSource));
		return lines.map((line) => line.slice(0, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		if (this.context.keymap.matches("tui.select.cancel", data)) { this.context.closePage(); return true; }
		return false;
	}
}

/** 会话页：身份/标题/工作区/用量。 */
export class SessionPage extends InformationPage {
	constructor(context: PageContext) { super(context, "Session"); }

	hint(): string { return "Read-only · Esc close"; }

	render(width: number): string[] {
		const theme = this.context.theme();
		const snapshot = this.context.state.snapshot();
		if (!snapshot) return [theme.style("dim", "Waiting for backend state…")];
		const lines: string[] = [];
		lines.push(theme.style("text", `Title: ${snapshot.title || "New session"}`));
		lines.push(theme.style("text", `ID: ${snapshot.sessionId}`));
		lines.push(theme.style("text", `Generation: ${snapshot.generation}`));
		lines.push(theme.style("text", `Workspace: ${snapshot.workspace}${snapshot.gitBranch ? ` (${snapshot.gitBranch})` : ""}`));
		lines.push(theme.style("text", `Permission: ${snapshot.permission?.profile ?? "?"} (rev ${snapshot.permission?.revision ?? "?"})`));
		lines.push(theme.style("text", `Usage: in ${formatTokens(snapshot.usage.inputTokens)} · out ${formatTokens(snapshot.usage.outputTokens)} · cache ${formatTokens(snapshot.usage.cacheReadTokens)}`));
		lines.push(theme.style("text", `Context remaining: ${formatPercent(snapshot.context?.remainingPercent ?? null)}`));
		return lines.map((line) => line.slice(0, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		if (this.context.keymap.matches("tui.select.cancel", data)) { this.context.closePage(); return true; }
		return false;
	}
}

/** MCP 页：真实配置与连接状态；不连接、不重配服务。 */
export class McpPage extends InformationPage {
	constructor(context: PageContext) { super(context, "MCP servers"); }

	hint(): string { return "Read-only · Esc close"; }

	render(width: number): string[] {
		const theme = this.context.theme();
		const snapshot = this.context.state.snapshot();
		const lines: string[] = [];
		lines.push(theme.style("muted", "Configured MCP servers (read-only)"));
		if (!snapshot || snapshot.mcp.length === 0) {
			lines.push(theme.style("dim", "No MCP servers configured"));
			return lines;
		}
		for (const server of snapshot.mcp) {
			const state = !server.enabled ? "disabled" : server.connected ? "connected" : "configured";
			lines.push(theme.style("text", `${server.name} · ${state}`));
			for (const tool of server.tools) lines.push(theme.style("dim", `  ${tool}`));
		}
		return lines.map((line) => line.slice(0, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		if (this.context.keymap.matches("tui.select.cancel", data)) { this.context.closePage(); return true; }
		return false;
	}
}
