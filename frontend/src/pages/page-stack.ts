import type { BackendClient } from "../bridge/client.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import type { SessionStore, TuiSettings } from "../state/session-store.ts";
import type { KeymapAdapter } from "../editor/keymap-adapter.ts";

/**
 * 完整页栈：同一时刻只有一个活动页；父子页 Esc 只退一级，父页保留筛选/选中。
 *
 * 页面只操作“显示与本地请求”，不写配置、不改权限事实：设置通过白名单 patch 交给 Java，
 * 恢复/改名/权限等通过命令交给会话服务。关闭页面恢复编辑器焦点与同一草稿（Application 负责）。
 */
export interface SessionItem {
	id: string;
	title: string;
	updatedAt: string;
	workspace: string;
	model: string;
}

export interface PageContext {
	state: SessionStore;
	keymap: KeymapAdapter;
	theme(): ResolvedTheme;
	client(): BackendClient | null;
	/** 显式读取当前 Team 的报告；只读，不触发模型或登记核对。 */
	readTeamReport?(assignment: string): Promise<string>;
	identity(): { sessionId: string; generation: string };
	closePage(): void;
	pushPage(page: Page): void;
	requestRender(): void;
	notify(message: string, error?: boolean): void;
	saveSettings(patch: Record<string, unknown>): void;
	themeNames(): string[];
	previewTheme(name: string): void;
	commitTheme(): void;
	/** 一次确认只由这里保存目标字段（theme/lightTheme/darkTheme）。 */
	commitThemeTarget(target: "theme" | "lightTheme" | "darkTheme", name: string): void;
	cancelTheme(): void;
	sessionTheme(): string;
	setDraft(text: string): void;
	jumpToMatch(query: string, offset?: number): void;
	setSearchHighlight?(query: string, selected: number): void;
	lastFrame(): { frameId: number; epoch: number; rawWatermark: number; decodedWatermark: number; columns: number; rows: number } | null;
	/** 停靠区第一行在视口中的屏幕行（frame 顶边框之前）。 */
	dockOriginY(): number;
	/** 当前按键序列来源（Full Access 二次确认需要新鲜 PRESS）。 */
	evidence(): { startReceipt: number; endReceipt: number; decodedSeq: number } | null;
	settings(): TuiSettings | null;
}

export interface Page {
	readonly title: string;
	hint(): string;
	render(width: number): string[];
	/** 页框裁剪后通知安全敏感页；不可见的确认控件不能发布命中。 */
	setBodyWindow?(start: number, capacity: number): void;
	bodyScrollOffset?(): number;
	/** 只选择正文行；确认/保存仍走显式按键路径。 */
	handleBodyPress?(row: number, column: number): boolean;
	handleInput(data: string): boolean;
	invalidate(): void;
	theme(): ResolvedTheme;
}

export class PageStack {
	private readonly stack: Page[] = [];

	push(page: Page): void {
		this.stack.push(page);
	}

	/** @returns 被关闭页面；没有页面时 undefined。 */
	pop(): Page | undefined {
		if (this.stack.length > 1) return this.stack.pop();
		return this.stack.pop();
	}

	current(): Page | undefined {
		return this.stack[this.stack.length - 1];
	}

	size(): number { return this.stack.length; }

	clear(): void { this.stack.length = 0; }

	handleInput(data: string): boolean {
		return this.current()?.handleInput(data) ?? false;
	}

	render(width: number): string[] {
		return this.current()?.render(width) ?? [];
	}
}
