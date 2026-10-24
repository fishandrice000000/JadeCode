import { matchesKey } from "@earendil-works/pi-tui";
import type { Page, PageContext, SessionItem } from "./page-stack.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import { relativeTime } from "../presentation/format.ts";

/**
 * 恢复页：工作区会话清单（UUID 身份、同名各一项、过滤/排序、路径显示）。
 *
 * Node 不读会话目录，只调用 Java 的 session.list/find；恢复失败保留旧界面并显示真实错误。
 * 忙时切换由 Java 拒绝（BUSY），本页只负责展示结果。
 */
export class ResumePage implements Page {
	readonly title = "Resume session";
	private readonly context: PageContext;
	private sessions: SessionItem[] = [];
	private selected = 0;
	private filter = "";
	private namedOnly = false;
	private sortRecent = true;
	private showPath = false;
	private loading = true;
	private error = "";

	constructor(context: PageContext) {
		this.context = context;
		void this.reload();
	}

	theme(): ResolvedTheme { return this.context.theme(); }

	hint(): string {
		return "Type to filter · Up/Down select · Enter resume · Ctrl+P path · Ctrl+S sort · Ctrl+N named · Esc";
	}

	invalidate(): void {}

	render(width: number): string[] {
		const theme = this.context.theme();
		const lines: string[] = [];
		const filterLine = `Filter: ${this.filter}${this.filter ? "▏" : ""}`;
		lines.push(theme.style("text", filterLine));
		if (this.loading) {
			lines.push(theme.style("dim", "Loading sessions…"));
			return lines;
		}
		if (this.error) lines.push(theme.style("error", this.error));
		const filtered = this.visible();
		if (filtered.length === 0) {
			lines.push(theme.style("dim", "No other sessions in this workspace; Esc to return"));
			return lines;
		}
		if (this.selected >= filtered.length) this.selected = filtered.length - 1;
		for (let index = 0; index < filtered.length; index++) {
			const session = filtered[index]!;
			const marker = index === this.selected ? "▸ " : "  ";
			const age = relativeTime(session.updatedAt);
			let line = `${marker}${session.title || session.id} · ${age}`;
			if (this.showPath) line += ` · ${session.workspace}`;
			line += ` · ${session.id.slice(0, 8)}`;
			lines.push(index === this.selected ? theme.bold(theme.style("accent", line)) : theme.style("text", line));
			if (index === this.selected) {
				lines.push(theme.style("dim", `  ${session.model || "unknown model"}`));
			}
		}
		lines.push(theme.style("dim", `${filtered.length} session(s) · named only: ${this.namedOnly ? "on" : "off"} · sort: ${this.sortRecent ? "recent" : "title"}`));
		return lines.map((line) => line.slice(0, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		const keymap = this.context.keymap;
		if (keymap.matches("tui.select.cancel", data)) { this.context.closePage(); return true; }
		if (keymap.matches("app.session.togglePath", data)) { this.showPath = !this.showPath; return true; }
		if (keymap.matches("app.session.toggleSort", data)) { this.sortRecent = !this.sortRecent; return true; }
		if (keymap.matches("app.session.toggleNamedFilter", data)) { this.namedOnly = !this.namedOnly; return true; }
		if (keymap.matches("tui.select.up", data)) { this.selected = Math.max(0, this.selected - 1); return true; }
		if (keymap.matches("tui.select.down", data)) { this.selected = Math.min(this.visible().length - 1, this.selected + 1); return true; }
		if (keymap.matches("tui.select.confirm", data)) {
			const target = this.visible()[this.selected];
			if (!target) return true;
			const client = this.context.client();
			if (!client) return true;
			client.command("session.resume", { targetSessionId: target.id }, this.context.identity()).then((result) => {
				if (!result.ok) this.error = result.message ?? "Could not resume that session";
				this.context.requestRender();
			});
			this.context.closePage();
			return true;
		}
		if (matchesKey(data, "backspace")) { this.filter = this.filter.slice(0, -1); this.selected = 0; return true; }
		if (data.length === 1 && data >= " " && data !== "\x7f") {
			this.filter += data;
			this.selected = 0;
			return true;
		}
		return false;
	}

	private visible(): SessionItem[] {
		const needle = this.filter.toLowerCase();
		const items = this.sessions.filter((session) => {
			if (this.namedOnly && (!session.title || session.title === "New session")) return false;
			return !needle || session.title.toLowerCase().includes(needle) || session.id.startsWith(needle);
		});
		return items.sort((a, b) => this.sortRecent
			? b.updatedAt.localeCompare(a.updatedAt)
			: (a.title || a.id).localeCompare(b.title || b.id));
	}

	private async reload(): Promise<void> {
		const client = this.context.client();
		if (!client) return;
		try {
			const result = await client.command("session.list", {}, this.context.identity());
			if (result.ok && Array.isArray((result.payload as { sessions?: unknown }).sessions)) {
				this.sessions = ((result.payload as { sessions: unknown[] }).sessions as SessionItem[]);
				this.error = "";
			} else if (!result.ok) {
				this.error = result.message ?? "Could not list sessions";
			}
		} catch (failure) {
			this.error = (failure as Error).message;
		} finally {
			this.loading = false;
			this.context.requestRender();
		}
	}
}
