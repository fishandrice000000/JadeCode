import { clipLine } from "../presentation/text-safety.ts";
import { Input, truncateToWidth } from "@earendil-works/pi-tui";
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
		// 提交/跳转只由页面键表（tui.select.confirm）决定；Input 的默认 Enter/LF 回退不能
		// 绕过覆盖去 jump 或关闭页面。Input 只负责查询文本编辑。
	}

	private readonly search: (query: string) => Array<{ id: string; offset: number; snippet: string }>;

	theme(): ResolvedTheme { return this.context.theme(); }

	hint(): string { return "Type to search · Enter jump · Up/Down matches · Esc close"; }

	invalidate(): void { this.input.invalidate(); }

	render(width: number): string[] {
		const theme = this.context.theme();
		// resize/流式追加会改变文档行号；渲染前采用当前 Pi 索引，不沿用旧宽度的偏移。
		this.matches = this.query.trim() ? this.search(this.query) : [];
		this.selected = Math.max(0, Math.min(this.selected, this.matches.length - 1));
		this.context.setSearchHighlight?.(this.query, this.selected);
		const lines: string[] = [];
		for (const line of this.input.render(Math.max(1, width))) lines.push(line);
		lines.push(theme.style("dim", `${this.matches.length} match(es)${this.query ? ` for "${this.query}"` : ""}`));
		// 选中项跨过第十条时移动窗口，跳转使用该项真实文档行号。
		const start = Math.max(0, this.selected - 9);
		const visible = this.matches.slice(start, start + 10);
		for (const [index, match] of visible.entries()) {
			const marker = index + start === this.selected ? "▸ " : "  ";
			const line = `${marker}${match.snippet}`;
			lines.push(index + start === this.selected ? theme.bold(theme.style("accent", line)) : theme.style("text", line));
		}
		if (this.matches.length > visible.length) lines.push(theme.style("dim", `… ${this.matches.length - visible.length} more`));
		return lines.map((line) => clipLine(line, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		const keymap = this.context.keymap;
		if (keymap.matches("tui.altScreen.searchClose", data)) { this.context.closePage(); return true; }
		if (keymap.matches("tui.select.up", data)) {
			this.selected = Math.max(0, this.selected - 1);
			return true;
		}
		if (keymap.matches("tui.select.down", data)) {
			this.selected = Math.min(this.matches.length - 1, this.selected + 1);
			return true;
		}
		if (keymap.matches("tui.select.confirm", data)) {
			this.jump();
			return true;
		}
		if (keymap.matches("tui.altScreen.searchNext", data)) {
			if (this.matches.length > 0) this.selected = (this.selected + 1) % this.matches.length;
			return true;
		}
		if (keymap.matches("tui.altScreen.searchPrevious", data)) {
			if (this.matches.length > 0) this.selected = (this.selected - 1 + this.matches.length) % this.matches.length;
			return true;
		}
		// 未匹配当前页面确认动作的 Enter/LF 不得落入 Input 的 submit 回退。
		if (data === "\r" || data === "\n") return true;
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
		this.context.jumpToMatch(this.query, match.offset);
		this.context.closePage();
	}
}
