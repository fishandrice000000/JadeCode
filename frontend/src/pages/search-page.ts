import { Input, matchesKey } from "@earendil-works/pi-tui";
import type { Page, PageContext } from "./page-stack.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";

/**
 * 全文搜索页：输入区 Pi 布局，查询/匹配位置/跳转。
 *
 * 跳转只在 fullscreen 有效：用本视图记录的文档行偏移 + Pi 公开的相对滚动 API
 * （scrollToTop + scrollBy）。搜索文字只用于本地过滤，绝不提交模型。关闭恢复编辑器焦点。
 */
export class SearchPage implements Page {
	readonly title = "Search transcript";
	private readonly context: PageContext;
	private readonly input: Input;
	private matches: Array<{ id: string; offset: number; snippet: string }> = [];
	private selected = 0;
	private query = "";

	constructor(context: PageContext, search: (query: string) => Array<{ id: string; offset: number; snippet: string }>) {
		this.context = context;
		this.search = search;
		this.input = new Input();
		this.input.onSubmit = () => this.jump();
	}

	private readonly search: (query: string) => Array<{ id: string; offset: number; snippet: string }>;

	theme(): ResolvedTheme { return this.context.theme(); }

	hint(): string { return "Type to search · Enter jump · Up/Down matches · Esc close"; }

	invalidate(): void { this.input.invalidate(); }

	render(width: number): string[] {
		const theme = this.context.theme();
		const lines: string[] = [];
		for (const line of this.input.render(Math.max(1, width))) lines.push(line);
		lines.push(theme.style("dim", `${this.matches.length} match(es)${this.query ? ` for "${this.query}"` : ""}`));
		const visible = this.matches.slice(0, 10);
		for (const [index, match] of visible.entries()) {
			const marker = index === this.selected ? "▸ " : "  ";
			const line = `${marker}${match.snippet}`;
			lines.push(index === this.selected ? theme.bold(theme.style("accent", line)) : theme.style("text", line));
		}
		if (this.matches.length > visible.length) lines.push(theme.style("dim", `… ${this.matches.length - visible.length} more`));
		return lines.map((line) => line.slice(0, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		if (matchesKey(data, "escape")) { this.context.closePage(); return true; }
		if (matchesKey(data, "up")) {
			this.selected = Math.max(0, this.selected - 1);
			return true;
		}
		if (matchesKey(data, "down")) {
			this.selected = Math.min(this.matches.length - 1, this.selected + 1);
			return true;
		}
		if (matchesKey(data, "enter")) {
			this.jump();
			return true;
		}
		this.input.handleInput(data);
		const value = this.input.getValue();
		if (value !== this.query) {
			this.query = value;
			this.refresh();
		}
		return true;
	}

	private refresh(): void {
		this.selected = 0;
		this.matches = this.query.trim() ? this.search(this.query) : [];
		this.context.requestRender();
	}

	private jump(): void {
		const match = this.matches[this.selected];
		if (!match) return;
		this.context.jumpToMatch(this.query);
		this.context.closePage();
	}
}
