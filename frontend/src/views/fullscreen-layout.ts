import { ScrollView, type Component } from "@earendil-works/pi-tui";
import { LAYOUT_NODE, type StackLayoutNode } from "@earendil-works/pi-tui/dist/layout-node.js";

/**
 * 全屏布局：只有转录进入 ScrollView，输入/页面/审批及状态栏留在底部。
 *
 * 普通 Component 只会被 Pi 当作一张叶子裁剪，不能滚动。这里公开 Pi 的布局节点，
 * 把剩余高度分给转录，先为停靠区预算空间，再把真实屏幕原点传给审批。
 * renderDock 可能先后测量/绘制两次；不能在其中发送协议或改变业务状态。
 */
export class FullscreenLayout implements Component {
	readonly scroll: ScrollView;
	private dockLines: string[] = [];
	private footerLines: string[] = [];
	private readonly dock: Component = { render: () => this.dockLines, invalidate: () => {} };
	private readonly footer: Component = { render: () => this.footerLines, invalidate: () => {} };

	constructor(private readonly callbacks: {
		size: () => { columns: number; rows: number };
		conversation: Component;
		scrollbarTrackStyle?: (text: string) => string;
		scrollbarThumbStyle?: (text: string) => string;
		renderDock: (width: number, availableRows: number, originY: number) => string[];
		renderFooter: (width: number) => string[];
	}) {
		this.scroll = new ScrollView(callbacks.conversation, { follow: "end", primary: true, scrollbar: "auto",
			scrollbarTrackStyle: callbacks.scrollbarTrackStyle, scrollbarThumbStyle: callbacks.scrollbarThumbStyle });
	}

	/** Pi 布局时调用；先分配底部空间，审批测量与最终坐标采用同一行预算。 */
	[LAYOUT_NODE](): StackLayoutNode {
		const { columns, rows } = this.callbacks.size();
		this.footerLines = this.callbacks.renderFooter(columns).slice(0, Math.max(0, rows - 1));
		const available = Math.max(1, rows - this.footerLines.length);
		const measured = this.callbacks.renderDock(columns, available, 0).slice(0, available);
		const origin = Math.max(0, available - measured.length);
		this.dockLines = this.callbacks.renderDock(columns, available, origin).slice(0, available);
		return {
			type: "vstack", gap: 0, align: "stretch",
			entries: [
				{ component: this.scroll, basis: 0, grow: 1, shrink: 0 },
				{ component: this.dock, basis: this.dockLines.length, shrink: 0 },
				{ component: this.footer, basis: this.footerLines.length, shrink: 0 },
			],
		};
	}

	/** 普通测量保留自然高度；全屏实际绘制使用上面的布局节点。 */
	render(width: number): string[] { return this.callbacks.conversation.render(width); }
	invalidate(): void { this.callbacks.conversation.invalidate(); }
}
