import { clipLine } from "../presentation/text-safety.ts";
import { visibleWidth, type Component } from "@earendil-works/pi-tui";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import type { ApprovalView } from "../approval/approval-view.ts";
import type { Page } from "../pages/page-stack.ts";
import type { Editor } from "@earendil-works/pi-tui";

/**
 * 底部停靠区：审批 > 完整页 > 编辑器（与 PageStack 优先级一致）。
 *
 * 完整页使用 Pi 风格上下横线、右上标题，替换编辑器但保留草稿；审批是独立闭合卡片，左右各
 * 2 列留白（窄屏 1/0），由 ApprovalView 自己画框。这里只决定“现在轮到谁”，不实现页面算法。
 */
export interface DockContent {
	approval?: ApprovalView;
	page?: Page;
	editor?: Editor;
	/** 停靠区可用行数；regular 的完整对话仍交给终端 scrollback。 */
	maxRows?: number;
}

export class DockView implements Component {
	private content: DockContent = {};
	private bodyStart = 0;
	private bodyLength = 0;

	/** 屏幕行先减页框，再映射回实际正文窗口；边框/提示不能命中候选。 */
	handlePagePress(row: number, column: number): boolean {
		if (row < 1 || row > this.bodyLength) return true;
		return this.content.page?.handleBodyPress?.(this.bodyStart + row - 1, column) ?? true;
	}

	/** 切换停靠内容；由 Application 根据页面/审批状态调用。 */
	setContent(content: DockContent): void {
		this.content = content;
	}

	render(width: number): string[] {
		const { approval, page, editor } = this.content;
		// 审批卡需要视口原点/行数，由 AppLayout 直接渲染；这里不应收到 approval。
		if (approval) return [];
		if (page) return framePage(page, width, this.content.maxRows, (start, length) => { this.bodyStart = start; this.bodyLength = length; });
		if (editor) return editor.render(width);
		return [];
	}

	invalidate(): void {
		this.content.approval?.invalidate();
		this.content.page?.invalidate();
		this.content.editor?.invalidate();
	}
}

/** 页面外框：上下横线 + 右上标题（与 Pi editor 的横线风格一致）。 */
export function framePage(page: Page, width: number, maxRows?: number, onWindow?: (start: number, length: number) => void): string[] {
	const theme = page.theme();
	const inner = Math.max(1, width);
	// 标题不能把页面顶边撑出可用宽度；窄屏按完整字素截断，避免拆开 VS16/ZWJ/组合字符。
	let title = ` ${page.title} `;
	if (visibleWidth(title) > inner) {
		title = "";
		const graphemes = new Intl.Segmenter(undefined, { granularity: "grapheme" });
		for (const { segment } of graphemes.segment(` ${page.title} `)) {
			if (visibleWidth(title + segment) > inner) break;
			title += segment;
		}
	}
	const titleWidth = visibleWidth(title);
	const left = Math.max(0, inner - titleWidth);
	const top = theme.style("border", "─".repeat(left)) + theme.style("borderAccent", title);
	const lines = [top];
	let body = page.render(inner);
	// 小屏保留页头/提示/页底，正文窗口跟随当前选择，不能把确认项或 Esc 提示裁掉。
	const hint = page.hint();
	let windowStart = 0;
	if (maxRows !== undefined) {
		const capacity = Math.max(0, maxRows - 2 - (hint ? 1 : 0));
		const selected = body.findIndex((line) => line.includes("▸"));
		const start = Math.max(0, Math.min(page.bodyScrollOffset?.() ?? Math.max(0, selected - capacity + 1), body.length - capacity));
		windowStart = start;
		page.setBodyWindow?.(start, capacity);
		body = body.slice(start, start + capacity);
	}
	onWindow?.(windowStart, body.length);
	for (const line of body) lines.push(clipLine(line, inner));
	if (hint) lines.push(theme.style("dim", clipLine(hint, inner)));
	lines.push(theme.style("border", "─".repeat(inner)));
	return lines;
}
