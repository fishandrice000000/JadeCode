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
}

export class DockView implements Component {
	private content: DockContent = {};

	/** 切换停靠内容；由 Application 根据页面/审批状态调用。 */
	setContent(content: DockContent): void {
		this.content = content;
	}

	render(width: number): string[] {
		const { approval, page, editor } = this.content;
		// 审批卡需要视口原点/行数，由 AppLayout 直接渲染；这里不应收到 approval。
		if (approval) return [];
		if (page) return framePage(page, width);
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
export function framePage(page: Page, width: number): string[] {
	const theme = page.theme();
	const inner = Math.max(1, width);
	const title = ` ${page.title} `;
	const titleWidth = visibleWidth(title);
	const left = Math.max(0, inner - titleWidth);
	const top = theme.style("border", "─".repeat(left)) + theme.style("borderAccent", title);
	const lines = [top];
	const body = page.render(inner);
	for (const line of body) lines.push(line);
	const hint = page.hint();
	if (hint) lines.push(theme.style("dim", hint.slice(0, inner)));
	lines.push(theme.style("border", "─".repeat(inner)));
	return lines;
}
