import { clipLine } from "../presentation/text-safety.ts";
import { Input, truncateToWidth, visibleWidth } from "@earendil-works/pi-tui";
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
		this.input = new Input({ prompt: " ", placeholder: "Find in transcript" });
		// 确认并关闭只由页面键表（tui.select.confirm）决定；上下导航会预览匹配位置，
		// Input 的默认 Enter/LF 回退不能绕过覆盖去确认。Input 只负责查询文本编辑。
	}

	private readonly search: (query: string) => Array<{ id: string; offset: number; snippet: string }>;

	theme(): ResolvedTheme { return this.context.theme(); }

	hint(): string { return "Type to search · Enter jump · Up/Down matches · Esc close"; }

	invalidate(): void { this.input.invalidate(); }

	get focused(): boolean { return this.input.focused; }
	set focused(value: boolean) { this.input.focused = value; }

	/**
	 * 右上角三行搜索栏：输入和当前位置同排，底边显示前后匹配键；不占用编辑器停靠区。
	 * 仍复用同一查询/跳转/键位逻辑，resize 后重新取得正文行号，不搜索浮层本身。
	 */
	renderOverlay(width: number): string[] {
		this.updateMatches();
		const theme = this.context.theme();
		const inner = Math.max(0, width - 2);
		const result = !this.query ? "" : this.matches.length ? ` ${this.selected + 1}/${this.matches.length} ` : " No matches ";
		const count = truncateToWidth(result, inner, "");
		const inputWidth = Math.max(0, inner - visibleWidth(count));
		const input = truncateToWidth(this.input.render(Math.max(1, inputWidth))[0] ?? "", inputWidth, "");
		const previous = this.context.keymap.keys("tui.altScreen.searchPrevious")[0] ?? "Unbound";
		const next = this.context.keymap.keys("tui.altScreen.searchNext")[0] ?? "Unbound";
		const labels = ` ↑ ${previous} · ↓ ${next} `;
		const controls = truncateToWidth(visibleWidth(labels) <= inner ? labels : " ↑ · ↓ ", inner, "");
		const border = (line: string) => theme.style("border", line);
		if (width < 2) return [border("┌"), border("│"), border("└")];
		return [border(`┌${"─".repeat(inner)}┐`),
			`${border("│")}${input}${" ".repeat(Math.max(0, inputWidth - visibleWidth(input)))}${theme.style("dim", count)}${border("│")}`,
			border(`└${"─".repeat(Math.max(0, inner - visibleWidth(controls)))}${controls}┘`)];
	}

	/** 流式追加或宽度变化可能改变匹配，渲染前同步当前正文索引及高亮。 */
	private updateMatches(): void {
		this.matches = this.query.trim() ? this.search(this.query) : [];
		this.selected = Math.max(0, Math.min(this.selected, this.matches.length - 1));
		this.context.setSearchHighlight?.(this.query, this.selected);
	}

	render(width: number): string[] {
		const theme = this.context.theme();
		// resize/流式追加会改变文档行号；渲染前采用当前 Pi 索引，不沿用旧宽度的偏移。
		this.updateMatches();
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
			this.revealSelected();
			return true;
		}
		if (keymap.matches("tui.select.down", data)) {
			this.selected = Math.min(this.matches.length - 1, this.selected + 1);
			this.revealSelected();
			return true;
		}
		if (keymap.matches("tui.select.confirm", data)) {
			this.jump();
			return true;
		}
		if (keymap.matches("tui.altScreen.searchNext", data)) {
			if (this.matches.length > 0) this.selected = (this.selected + 1) % this.matches.length;
			this.revealSelected();
			return true;
		}
		if (keymap.matches("tui.altScreen.searchPrevious", data)) {
			if (this.matches.length > 0) this.selected = (this.selected - 1 + this.matches.length) % this.matches.length;
			this.revealSelected();
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
		// 浮层在主布局之后合成；必须在下一帧布局之前更新正文高亮，不能等浮层 render 才设置。
		this.revealSelected();
		this.context.requestRender();
	}

	/**
	 * 搜索预览：高亮与正文滚动必须使用同一个匹配，不能只更新浮层计数。
	 * 使用索引给出的真实文档行号，由Application驱动Pi视口；不关闭搜索、不改编辑器草稿。
	 * 空结果只清理高亮，不跳到文档开头。
	 */
	private revealSelected(): void {
		this.context.setSearchHighlight?.(this.query, this.selected);
		const match = this.matches[this.selected];
		if (match) this.context.jumpToMatch(this.query, match.offset);
	}

	private jump(): void {
		const match = this.matches[this.selected];
		if (!match) return;
		this.context.jumpToMatch(this.query, match.offset);
		this.context.closePage();
	}
}
