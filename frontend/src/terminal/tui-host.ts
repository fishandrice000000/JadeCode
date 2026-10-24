import {
	type Component,
	type TUI,
	TuiAltScreen,
	TuiMainScreen,
	type TuiInputListener,
	type TuiMouseEvent,
	type WheelScrollLines,
} from "@earendil-works/pi-tui";
import { JadeTerminal } from "./terminal-adapter.ts";

/**
 * 双屏终端宿主：Pi 的 TuiMainScreen/TuiAltScreen 是唯一 renderer。
 *
 * 切换模式只重建终端层与根布局：会话、草稿、输入历史和显示内容都在 Node 状态里，不重启
 * Java Agent。regular 模式不捕获鼠标（终端原生选择/滚动优先）；fullscreen 由 Pi 处理滚动、
 * 选区、搜索和滚轮加速。退出/暂停由 Host 负责恢复终端。
 *
 * 受控补丁接线：renderGate（P03 背压）、afterFrameRendered（P03 围栏）、priority input（P02）、
 * mousePriorityHandler（P02）与 setScrollbar（P05）。
 */
export type UiMode = "fullscreen" | "regular";

export interface TuiHostOptions {
	mode: UiMode;
	wheelScrollLines: number | "auto";
	scrollbar: "auto" | "always" | "hidden";
	copyOnSelect: boolean;
	copySelection?: (text: string) => Promise<boolean | string>;
	showHardwareCursor: boolean;
	escapeTimeoutMillis: number;
	onFrameWritten: () => void;
	onWritable: () => void;
	onWriteFailure: (message: string) => void;
	onResize: () => void;
	onInputStart: (chunk: number) => void;
	onInputEnd: () => void;
	onRawInput: (data: string) => void;
	/** screen 创建/重建完成后调用；宿主在这里安装监听与焦点，保证不只生效一次。 */
	onScreenReady: () => void;
}

type JadeScreen = (TuiMainScreen | TuiAltScreen) & {
	renderGate?: () => boolean;
	addPriorityInputListener(listener: TuiInputListener): () => void;
	removePriorityInputListener(listener: TuiInputListener): void;
	mousePriorityHandler?: (event: TuiMouseEvent) => boolean;
	setScrollbar?: (scrollbar: "auto" | "always" | "hidden") => void;
};

export class TuiHost {
	readonly terminal: JadeTerminal;
	private readonly options: TuiHostOptions;
	private screen: JadeScreen | undefined;
	private root: Component | undefined;
	private modeValue: UiMode;
	private readonly priorityListeners = new Set<TuiInputListener>();
	private mouseHandler: ((event: TuiMouseEvent) => boolean) | undefined;
	private focusTarget: Component | null = null;

	constructor(options: TuiHostOptions) {
		this.options = options;
		this.modeValue = options.mode;
		this.terminal = new JadeTerminal({
			onWritable: options.onWritable,
			onWriteFailure: options.onWriteFailure,
			onRawInput: options.onRawInput,
			onInputStart: options.onInputStart,
			onInputEnd: options.onInputEnd,
		});
		this.terminal.setEscapeTimeoutMs(options.escapeTimeoutMillis);
	}

	/** @returns 当前模式。 */
	mode(): UiMode {
		return this.modeValue;
	}

	/** 设置根组件；切换模式后自动安装到新 renderer。 */
	setRoot(root: Component): void {
		this.root = root;
	}

	/** @returns Pi 的 TUI 实例；未启动前为 undefined。 */
	tui(): JadeScreen | undefined {
		return this.screen;
	}

	/** 启动当前模式；重复调用只会刷新渲染。 */
	start(): void {
		if (!this.root) throw new Error("The TUI root is not installed yet");
		if (!this.screen) this.screen = this.createScreen(this.modeValue);
		this.screen.setFocus(this.focusTarget ?? this.root);
		this.screen.renderGate = () => this.terminal.canRender();
		this.screen.start();
		this.options.onScreenReady();
	}

	/** regular ↔ fullscreen：保留根组件，重建终端层。 */
	setMode(mode: UiMode): void {
		if (mode === this.modeValue) return;
		this.modeValue = mode;
		if (!this.screen) return;
		this.screen.stop({ preserveScreen: true });
		this.screen = undefined;
		this.start();
	}

	/** 请求重绘（不改变状态）。 */
	requestRender(force = false): void {
		this.screen?.requestRender(force);
	}

	/** 使全部组件缓存失效。 */
	invalidate(): void {
		this.screen?.invalidate();
	}

	/** 聚焦组件（编辑器/页面/审批由 Application 决定）；记住以便重建后恢复。 */
	setFocus(component: Component | null): void {
		this.focusTarget = component;
		this.screen?.setFocus(component);
	}

	/** P02：优先级输入监听（审批首选）；screen 重建时自动重新注册。 */
	addPriorityInputListener(listener: TuiInputListener): () => void {
		this.priorityListeners.add(listener);
		this.screen?.addPriorityInputListener(listener);
		return () => {
			this.priorityListeners.delete(listener);
			this.screen?.removePriorityInputListener(listener);
		};
	}

	/** P02：鼠标优先级处理器（只在 fullscreen 有意义）；screen 重建后仍生效。 */
	setMousePriorityHandler(handler: ((event: TuiMouseEvent) => boolean) | undefined): void {
		this.mouseHandler = handler;
		if (this.screen) this.screen.mousePriorityHandler = handler;
	}

	/** P03：stdout drain 后唤醒一次最新帧。 */
	notifyRenderReady(): void {
		this.screen?.notifyRenderReady();
	}

	setHardwareCursor(enabled: boolean): void {
		this.screen?.setShowHardwareCursor(enabled);
	}

	setClearOnShrink(enabled: boolean): void {
		this.screen?.setClearOnShrink(enabled);
	}

	setWheelScrollLines(lines: number | "auto"): void {
		if (this.screen instanceof TuiAltScreen) this.screen.setWheelScrollLines(lines as WheelScrollLines);
	}

	setScrollbar(scrollbar: "auto" | "always" | "hidden"): void {
		if (this.screen instanceof TuiAltScreen) this.screen.setScrollbar?.(scrollbar);
	}

	setCopyOnSelect(enabled: boolean): void {
		if (this.screen instanceof TuiAltScreen) this.screen.setCopyOnSelect(enabled);
	}

	setTerminalProgress(active: boolean): void {
		this.terminal.setProgress(active);
	}

	/** @returns fullscreen 当前视口顶部行；regular 为 0。审批矩形/搜索跳转使用。 */
	viewportTop(): number {
		return this.screen instanceof TuiAltScreen ? this.screen.viewportTop : 0;
	}

	/** fullscreen 专有：滚动控制（搜索跳转使用被公开的相对滚动 API）。 */
	scrollToTop(): void {
		if (this.screen instanceof TuiAltScreen) this.screen.scrollToTop();
	}

	scrollToBottom(): void {
		if (this.screen instanceof TuiAltScreen) this.screen.scrollToBottom();
	}

	scrollBy(lines: number): void {
		if (this.screen instanceof TuiAltScreen) this.screen.scrollBy(lines);
	}

	hasSelection(): boolean {
		return this.screen instanceof TuiAltScreen ? this.screen.hasActiveSelection() : false;
	}

	async copyActiveSelection(): Promise<boolean> {
		return this.screen instanceof TuiAltScreen ? this.screen.copyActiveSelectionToClipboard() : false;
	}

	/** @returns 最近一帧实际写入的行（测试/诊断；不含终端状态）。 */
	screenLines(): string[] {
		return this.screen instanceof TuiAltScreen ? this.screen.getScreenLines() : [];
	}

	/** 恢复终端（幂等由 Pi/JadeTerminal 保证）。 */
	stop(): void {
		this.terminal.setProgress(false);
		this.screen?.stop();
		this.screen = undefined;
	}

	/** 暂停/外部编辑器交接前调用：停止绘制并退出 raw mode。 */
	suspendTerminal(): void {
		this.terminal.setProgress(false);
		this.screen?.stop({ preserveScreen: true });
	}

	/** 恢复绘制（fg/外部编辑器返回后）。 */
	resumeTerminal(): void {
		this.start();
	}

	private createScreen(mode: UiMode): JadeScreen {
		const root = this.root!;
		const onFrame = this.options.onFrameWritten;
		let screen: JadeScreen;
		if (mode === "regular") {
			const main = new JadeMainScreen(this.terminal, this.options.showHardwareCursor, onFrame);
			main.addChild(root);
			screen = main as unknown as JadeScreen;
		} else {
			const alt = new JadeAltScreen(this.terminal, {
				showHardwareCursor: this.options.showHardwareCursor,
				wheelScrollLines: this.options.wheelScrollLines,
				scrollbar: this.options.scrollbar,
				copyOnSelect: this.options.copyOnSelect,
				copySelection: this.options.copySelection,
				onFrame,
			});
			alt.setLayoutRoot(root);
			screen = alt as unknown as JadeScreen;
		}
		// 每次新建 screen 都重新挂优先级监听/鼠标处理；旧 screen 已 stop，不会重复消费输入。
		for (const listener of this.priorityListeners) screen.addPriorityInputListener(listener);
		screen.mousePriorityHandler = this.mouseHandler;
		screen.setFocus(this.focusTarget ?? root);
		return screen;
	}
}

/** Pi 主屏（regular）：输出进终端 scrollback，原生选择/滚动优先，不捕获鼠标。 */
class JadeMainScreen extends TuiMainScreen {
	private readonly frameHook: () => void;

	constructor(terminal: JadeTerminal, showHardwareCursor: boolean, frameHook: () => void) {
		super(terminal, showHardwareCursor);
		this.frameHook = frameHook;
	}

	protected override afterFrameRendered(): void {
		this.frameHook();
	}
}

/** Pi 全屏：差分渲染 + 滚动/选区/搜索；补丁提供帧围栏与滚动条设置。 */
class JadeAltScreen extends TuiAltScreen {
	private readonly frameHook: () => void;

	constructor(terminal: JadeTerminal, options: {
		showHardwareCursor: boolean;
		wheelScrollLines: number | "auto";
		scrollbar: "auto" | "always" | "hidden";
		copyOnSelect: boolean;
		copySelection?: (text: string) => Promise<boolean | string>;
		onFrame: () => void;
	}) {
		super(terminal, options.showHardwareCursor, undefined, {
			mouse: true,
			wheelScrollLines: options.wheelScrollLines,
			copyOnSelect: options.copyOnSelect,
			copySelection: options.copySelection,
		});
		this.frameHook = options.onFrame;
		this.setScrollbar?.(options.scrollbar);
	}

	protected override afterFrameRendered(): void {
		this.frameHook();
	}
}

export type { TUI };
