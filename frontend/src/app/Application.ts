import { createHash } from "node:crypto";
import { resolve } from "node:path";
import {
	KeybindingsManager,
	TUI_KEYBINDINGS,
	matchesKey,
	setKeybindings,
	isKeyRelease,
	type Component,
	type TUI,
	type TuiMouseEvent,
	type TerminalColors,
	type OverlayHandle,
} from "@earendil-works/pi-tui";
import { BackendClient } from "../bridge/client.ts";
import { PayloadAssembler } from "../bridge/payload-assembler.ts";
import { UiEventBarrier } from "./ui-barrier.ts";
import { isGlobalActionPress } from "../terminal/global-keys.ts";
import { arrayValue, numberValue, text, type Payload, type WireMessage } from "../bridge/wire.ts";
import { SessionStore, type TuiSettings } from "../state/session-store.ts";
import { TranscriptStore, type Entry, type ToolIdentity } from "../state/transcript-store.ts";
import { StreamReducer } from "../state/stream-reducer.ts";
import { TuiHost, type UiMode } from "../terminal/tui-host.ts";
import { InputEvidence } from "../terminal/input-evidence.ts";
import { PresentedEvidence, type FrameSnapshot } from "../terminal/presented-evidence.ts";
import { ThemeRepository } from "../theme/theme-repository.ts";
import { ThemeController } from "../theme/theme-controller.ts";
import { MarkdownFactory } from "../markdown/markdown-factory.ts";
import { ConversationView } from "../views/conversation-view.ts";
import { StatusView } from "../views/status-view.ts";
import { DockView } from "../views/dock-view.ts";
import { FullscreenLayout } from "../views/fullscreen-layout.ts";
import { KeymapAdapter } from "../editor/keymap-adapter.ts";
import { JadeCompletionProvider } from "../editor/completion-provider.ts";
import { EditorController } from "../editor/editor-controller.ts";
import { ApprovalController } from "../approval/approval-controller.ts";
import { ApprovalResultToast } from "../approval/approval-result-toast.ts";
import { PageStack, type Page, type PageContext, type SessionItem } from "../pages/page-stack.ts";
import { SettingsPage } from "../pages/settings-page.ts";
import { ResumePage } from "../pages/resume-page.ts";
import { HotkeysPage, McpPage, ModelPage, SessionPage } from "../pages/information-pages.ts";
import { TeamPage } from "../pages/team-page.ts";
import { PermissionPage } from "../pages/permission-page.ts";
import { SearchPage } from "../pages/search-page.ts";
import { ClipboardController } from "../services/clipboard-controller.ts";
import { ExternalEditor } from "../services/external-editor.ts";
import { suspendToShell } from "../services/job-control.ts";
import { clipLine, display, singleLine, visibleEscapes } from "../presentation/text-safety.ts";

/**
 * Application：唯一 Node 事件循环中的装配与协调者。
 *
 * 输入优先级：审批（P02 priority listener + mousePriorityHandler）> 完整页 > 全局动作 >
 * 编辑器。所有可变 UI 状态只在这里/被它拥有的 store 中更新；socket 事件先更新 store，再请求
 * Pi 重绘（不每帧取全历史）。
 *
 * 关闭：client.shutdown → 等待 server.stopped/EOF → 恢复终端 → 打印一次真实结果。断连不重连、
 * 不重提任务。
 */
export class Application {
	private readonly session = new SessionStore();
	private readonly transcript = new TranscriptStore();
	private readonly assembler = new PayloadAssembler();
	private readonly evidence = new InputEvidence();
	private readonly clipboard = new ClipboardController(undefined, async (value) => {
		// OSC52 与普通帧共用唯一写队列，不能越过尚未完成的屏幕输出。
		this.host.terminal.write(value);
		await this.host.terminal.flushOutput();
	});
	private readonly externalEditor = new ExternalEditor();
	private readonly pages = new PageStack();
	private readonly markdown = new MarkdownFactory();
	private readonly conversation: ConversationView;
	private readonly status: StatusView;
	private readonly dock = new DockView();
	private readonly approval: ApprovalController;
	private readonly approvalResult = new ApprovalResultToast(() => this.theme.snapshot(), () => this.host?.requestRender());
	private approvalResultOverlay: OverlayHandle | undefined;
	private readonly root: Component;
	private client: BackendClient | undefined;
	private theme!: ThemeController;
	private host!: TuiHost;
	private editor!: EditorController;
	private keymap!: KeymapAdapter;
	private presented!: PresentedEvidence;
	private presentedFrame: FrameSnapshot | undefined;
	private timer: NodeJS.Timeout | undefined;
	private closing = false;
	private exitMessage = "";
	private snapshotBuffer: { id: string; chunks: string[]; generation: string; sourceSeq: string } | undefined;
	private readonly openedAt = Date.now();
	/** UI（主题/终端/编辑器/host）是否已建立；bootstrap 后的业务事件必须等它完成。 */
	private readonly uiBarrier = new UiEventBarrier<WireMessage>();
	private stoppedReceived = false;
    private detachedReceived = false;
	private closingAt = 0;
	private lastClearAt = 0;
	/** 用户退出后等待后端收尾的有界预算；超时保留错误而非当作成功。 */
	private static readonly EXIT_GRACE_MS = 10_000;
	/** 最近 64 条协议元数据；只在失败时输出，不倾倒 payload。 */
	private readonly traceBuffer: Array<{ at: number; direction: string; type: string; seq: string; connection: string; uiReady: boolean }> = [];

	constructor() {
		this.conversation = new ConversationView(this.transcript, this.session, this.markdown);
		this.status = new StatusView(this.session);
		this.approval = new ApprovalController({
			client: () => this.client ?? null,
			evidence: this.evidence,
			presented: undefined as unknown as PresentedEvidence,
			revision: () => this.session.permissionRevision(),
			identity: () => this.identity(),
			requestRender: () => this.host?.requestRender(),
			notify: (message, error = false) => this.notify(message, error),
			terminalHealthy: () => this.host !== undefined && !this.host.terminal.hasFailed(),
			keymap: () => this.keymap,
			teamActive: () => this.session.teamActive(),
			actorName: (actor) => this.session.displayActor(actor) || actor,
			onAnswered: (data, choice) => {
				if (this.closing) return;
				this.approvalResult.show(data, choice, this.session.teamActive() ? this.session.displayActor(data.actor) || data.actor : "");
				this.installApprovalResultOverlay();
			},
		});
		const layout = this;
		this.root = {
			render: (width: number) => layout.renderRoot(width),
			invalidate: () => {
				layout.conversation.invalidate();
				layout.dock.invalidate();
			},
		};
	}

	/**
	 * UI 初始化完成后消费暂存事件。
	 *
	 * <p>socket 可能把 hello/bootstrap/完整快照合成一次写入；Node 的 reader 会同步消费完这批帧。
	 * 因此“bootstrap 已 apply”不等于“editor/host 已建立”。先按序入队，初始化完成后再消费，
	 * 快照 end 才会在编辑器存在时安装并发送 ready。不用 sleep，也不丢快照。</p>
	 */
	private activateUi(): void {
		// 消费顺序严格等于 socket 到达顺序；快照 end 在编辑器/host 建立后才会发 ready。
		this.uiBarrier.activate((message) => this.handleEvent(message));
	}

	/** 启动：连接、握手、bootstrap、主题、终端，然后进入快照同步。 */
	async run(): Promise<number> {
		const socketPath = process.env.JADE_BRIDGE_SOCKET ?? "";
		const credential = process.env.JADE_BRIDGE_CREDENTIAL ?? "";
		const buildId = process.env.JADE_BRIDGE_BUILD_ID ?? "";
		if (!socketPath || !credential) {
			process.stderr.write("JadeCode frontend: missing bridge environment\n");
			return 2;
		}
		const client = new BackendClient({
			socketPath,
			onEvent: (message) => this.onEvent(message),
			onClosed: (reason) => this.onClosed(reason),
			onFatal: (reason, error) => this.reportFailure(reason, error),
		});
		this.client = client;
		try {
			await client.open();
			await client.hello(credential, buildId);
			await this.waitForBootstrap();
			this.setupThemeAndTerminal();
			this.host.start();
			if (this.session.bootstrap()?.startup.mode === "picker") this.openResumePage();
			this.activateUi();
			this.armTimer();
			return await this.waitForExit();
		} catch (failure) {
			this.reportFailure("The frontend failed to start", failure);
			this.dumpTrace();
			return 1;
		} finally {
			await this.shutdown();
			if (this.exitMessage) this.dumpTrace();
		}
	}

	private bootstrapResolve: (() => void) | undefined;

	private waitForBootstrap(): Promise<void> {
		if (this.session.bootstrap()) return Promise.resolve();
		return new Promise((resolve, reject) => {
			const timer = setTimeout(() => reject(new Error("Timed out waiting for server.bootstrap")), 10_000);
			this.bootstrapResolve = () => {
				clearTimeout(timer);
				resolve();
			};
		});
	}

	private setupThemeAndTerminal(): void {
		const bootstrap = this.session.bootstrap()!;
		const assetRoot = new URL("../../assets/themes/", import.meta.url).pathname;
		const repository = new ThemeRepository(assetRoot, bootstrap.themeDirectory);
		this.theme = new ThemeController({
			repository,
			settings: () => {
				const value = this.session.settings();
				return value ?? { theme: "system", lightTheme: "light", darkTheme: "dark" };
			},
			save: (patch) => this.saveSettings(patch),
			onError: (message) => this.notify(message, true),
			onChange: () => this.onThemeChanged(),
		});
		this.conversation.setThemeAccessor(() => this.theme.snapshot());
		// 审批呈现由 afterFrame 回调统一处理；这里不再重复通知，避免同一帧两次 presented。
		this.presented = new PresentedEvidence(this.evidence, () => {});
		this.approval.setPresented(this.presented);
		this.approval.setTheme(this.theme.snapshot());
		const settings = bootstrap.tui;
		this.keymap = new KeymapAdapter(settings.keybindings);
		// 用户覆盖必须交给 Pi 的全局键表，Editor/选择器才会按覆盖执行；Jade 自己的全局动作
		// 则通过 KeymapAdapter.matches 读取同一份覆盖。
		setKeybindings(new KeybindingsManager(TUI_KEYBINDINGS, this.keymap.piOverrides() as never));
		const completion = new JadeCompletionProvider({
			workspace: bootstrap.workspace,
            paths: async (prefix, signal) => {
                const result = await this.client?.command("workspace.complete", { prefix }, this.identity());
                if (!result?.ok || signal.aborted) return null;
                return { prefix, items: arrayValue(result.payload, "items") as { value: string; label: string }[] };
            },
			commandSuggestions: () => this.session.settings()?.commandSuggestions !== false,
			sessions: async () => (await this.listSessions()).map((item) => ({
				value: item.id, label: item.title || item.id, description: item.id.slice(0, 8),
			})),
			thinking: () => this.session.snapshot()?.thinkingChoices ?? [],
			permissions: () => ["read-only", "workspace", "auto-review", "full-access"],
		});
		const lazyTui = {
			get terminal() { return undefined as never; },
			requestRender: () => this.host?.requestRender(),
		};
		// Editor 只用到 terminal.rows 与 requestRender；用指向宿主终端的代理避免先有 TUI 后建编辑器。
		const tuiProxy = new Proxy(lazyTui, {
			get: (target, property) => {
				if (property === "terminal") return this.host.terminal;
				return Reflect.get(target, property);
			},
		}) as unknown as TUI;
		this.editor = new EditorController(tuiProxy, this.theme.snapshot(), settings, completion, {
			submitPrompt: (value) => this.submitPrompt(value),
			runSlash: (name, argument, raw) => this.runSlash(name, argument, raw),
			promoteBuffer: () => this.promoteBuffer(),
			error: (message) => this.notify(message, true),
		});
		this.host = new TuiHost({
			mode: settings.mode as UiMode,
			wheelScrollLines: settings.fullscreenWheelScrollLines === 0 ? "auto" : settings.fullscreenWheelScrollLines,
			scrollbar: settings.fullscreenScrollbar as "auto" | "always" | "hidden",
			copyOnSelect: settings.fullscreenCopyOnSelect,
			copySelection: async (value) => {
				const result = await this.clipboard.copy(value);
				if (result.status === "FAILED") return result.error ?? false;
				// 成功写出请求即给短反馈；OSC52 无客户端ACK，状态不冒充DESKTOP。
				// Pi 的选区复制路径负责一次限时 flash；不要再追加永久 Notice 卡。
				return true;
			},
			showHardwareCursor: settings.showHardwareCursor,
			escapeTimeoutMillis: settings.escapeTimeoutMillis,
			onFrameWritten: () => {
				const epoch = this.presented.currentEpoch();
				this.host.terminal.afterFrame(() => this.afterFrame(epoch));
			},
			onWritable: () => this.host.notifyRenderReady(),
			onWriteFailure: (message) => this.notify(message, true),
			onResize: () => this.onTerminalInvalidate("resize"),
			onInputStart: (chunk) => this.evidence.beginDispatch(chunk),
			onInputEnd: () => this.evidence.endDispatch(),
			onRawInput: (chunk) => this.evidence.rawChunk(chunk),
			onScreenReady: () => this.installScreenListeners(),
		});
		this.host.setRoot(this.root);
		// Pi 叶子组件只裁剪、不滚动；全屏必须显式挂载转录 ScrollView 与固定底部布局。
		const fullscreen = new FullscreenLayout({
			size: () => ({ columns: this.host.terminal.columns, rows: this.host.terminal.rows }),
			scrollbarTrackStyle: (value) => this.theme.snapshot().style("scrollbarTrack", value),
			scrollbarThumbStyle: (value) => this.theme.snapshot().style("scrollbarThumb", value),
			conversation: {
				render: (width) => {
					this.conversation.setViewportRows(this.host.terminal.rows);
					return this.conversation.render(width);
				},
				invalidate: () => this.conversation.invalidate(),
			},
			renderFooter: (width) => this.renderFooter(width),
			renderDock: (width, bottom, origin) => this.renderFullscreenDock(width, bottom, origin),
		});
		this.host.setFullscreenLayout(fullscreen, fullscreen.scroll);
		this.host.setMousePriorityHandler((event) => {
			if (this.approval.hasPending()) return this.approval.handleMouse(event);
			if (this.pages.current() instanceof SearchPage) return false; // 搜索浮层交给 Pi 命中，不能拦成 dock 点击。
			if (this.pages.current()) {
				if (event.type === "press" && event.button === "left") {
					this.dock.handlePagePress(event.y - this.dockOriginY, event.x); this.host.requestRender();
				}
				return true;
			}
			if (event.type !== "press" || event.button !== "left" || event.y >= this.dockOriginY) return false;
			const changed = this.conversation.toggleHeaderAt(event.y + this.host.viewportTop());
			if (changed) this.host.requestRender();
			return changed;
		});
		this.host.setFocus(this.editor.editor);
		this.transcript.install([]);
		this.syncTranscriptHistoryMessage();
	}

	/**
	 * screen 创建/模式重建后安装监听与焦点。
	 *
	 * <p>这段必须在 Pi screen 存在之后执行；第一轮把它放在 host.start() 之前，导致监听被静默
	 * 跳过、焦点落在没有 handleInput 的 root 上。TuiHost 会记住优先级监听/鼠标处理并在重建时
	 * 重新安装，这里只补普通的全局输入监听。</p>
	 */
	private installScreenListeners(): void {
		// 模式重建/恢复时旧浮层已属于旧screen；统一关闭，避免遗留输入捕获或正文高亮。
		this.closeSearchOverlay();
		this.clearApprovalResult();
		const tui = this.host.tui();
		if (!tui) return;
		this.installTerminalColors();
		this.priorityDisposer?.();
		this.priorityDisposer = this.host.addPriorityInputListener((data) => {
			// SGR 鼠标交给 Pi 解码，再由 mousePriorityHandler 做审批命中。
			// 若先交给审批键盘分支，其“吞掉未知文字”规则会让所有真实鼠标事件永远到不了按钮。
			if (data.startsWith("\x1b[<")) return undefined;
			// 暂停是终端交接动作，审批/页面打开时也必须能交回 shell；仍只接收新 PRESS。
			if (isGlobalActionPress(data) && this.keymap.matches("app.suspend", data)) return this.onGlobalInput(data);
			if (this.approval.hasPending()) {
				return this.approval.handleInput(data) ? { consume: true } : undefined;
			}
			// 页面由应用统一消费，先于 Pi 自身的视口翻页/搜索分支，避免局部键穿透。
			if (this.pages.current()) return this.onGlobalInput(data);
			if (isGlobalActionPress(data) && this.keymap.matches("tui.altScreen.search", data)) return this.onGlobalInput(data);
			return undefined;
		});
		this.offscreenDisposer?.();
		const dispose = tui.addInputListener((data) => this.onGlobalInput(data));
		this.offscreenDisposer = dispose;
		this.host.setFocus(this.pages.current() || this.approval.hasPending() ? this.root : this.editor.editor);
	}

	/** 同名主题内容改变时也必须刷新卡片、补全、审批，不能只重画旧缓存。 */
	private onThemeChanged(): void {
		if (!this.theme) return; // ThemeController 构造中的首次通知早于字段赋值。
		const theme = this.theme.snapshot();
		this.conversation.invalidate();
		this.editor?.setTheme(theme);
		this.approval.setTheme(theme);
		this.onTerminalInvalidate("theme");
		this.host?.requestRender(true);
	}

	private colorDisposer: (() => void) | undefined;
	private colorTicket = 0;
	private installTerminalColors(): void {
		const screen = this.host.tui();
		if (!screen) return;
		this.colorDisposer?.();
		const ticket = ++this.colorTicket;
		const receive = (colors: TerminalColors): void => {
			if (ticket !== this.colorTicket || this.closing || this.host.tui() !== screen) return;
			const rgb = (color: TerminalColors["foreground"]) => color ? { kind: "rgb" as const, ...color } : undefined;
			if (colors.background) {
				const { r, g, b } = colors.background;
				this.theme.setScheme(0.2126 * r + 0.7152 * g + 0.0722 * b < 128);
			}
			this.theme.setTerminalColors(rgb(colors.foreground), rgb(colors.background));
		};
		this.colorDisposer = screen.onTerminalColorSchemeChange((scheme) => {
			this.theme.setScheme(scheme === "dark");
			void screen.queryTerminalColors({ timeoutMs: 100, onLateReply: receive }).then(receive);
		});
		screen.setTerminalColorSchemeNotifications(true);
		void screen.queryTerminalColors({ timeoutMs: 100, onLateReply: receive }).then(receive);
	}

	private offscreenDisposer: (() => void) | undefined;
	private priorityDisposer: (() => void) | undefined;

	/** 全局输入：页面优先，然后 97 动作；编辑器自身处理文本/Enter。 */
	private onGlobalInput(data: string): { consume?: boolean; data?: string } | undefined {
		if (isGlobalActionPress(data) && this.keymap.matches("app.suspend", data)) {
			suspendToShell({
				beforeSuspend: async () => { this.onTerminalInvalidate("suspend"); this.host.suspendTerminal(); await this.host.terminal.flushOutput(); },
				afterResume: () => { this.onTerminalInvalidate("resume"); this.host.resumeTerminal(); this.theme.reload(); },
			});
			return { consume: true };
		}
		// 审批/权限页自行拒绝 release/repeat/paste；编辑器的长按重复键不能被全局吞掉。
		if (this.pages.current()) {
			// Pi 只在 focusedComponent 分支过滤 release；我们在 listener 中接管页面，必须自行过滤。
			// 否则 Kitty 的一次 press + release 会让方向移动和可打印字符各执行两遍。
			if (isKeyRelease(data)) return { consume: true };
			if (this.pages.handleInput(data)) {
				this.host.requestRender();
				return { consume: true };
			}
			// 页面打开时不允许字符穿透到编辑器（除页面显式处理的键）。
			return { consume: true };
		}
		if (this.approval.hasPending()) return { consume: true };
		// repeat/release 交回编辑器（长按输入），但不得触发全局退出/复制等动作。
		if (!isGlobalActionPress(data)) {
			// 重复方向/退格仍交给编辑器；提交也会发起业务请求，必须由新 PRESS 触发。
			return this.keymap.matches("tui.input.submit", data) ? { consume: true } : undefined;
		}
		if (this.keymap.matches("app.interrupt", data)) {
			// Esc 先关闭编辑器候选；交还 Pi 处理这一次按键，不能同时取消正在运行的任务。
			if (this.editor.editor.isShowingAutocomplete() && this.keymap.matches("tui.select.cancel", data)) return undefined;
			// 只依据后端的主会话状态取消；显示计时在等待审批/后台时可能暂停，不能用动画判断忙闲。
			const leader = this.session.actors().find((actor) => actor.id === "leader");
			if (leader && ["RUNNING", "WAITING_BACKGROUND", "WAITING_TEAM"].includes(leader.state)) this.stopCurrentTurn();
			this.editor.focus(this.host.tui()!);
			return { consume: true };
		}
		if (this.keymap.matches("app.clear", data)) {
			if (this.host.hasSelection()) {
				void this.host.copyActiveSelection();
				return { consume: true };
			}
			if (this.editor.draft().length > 0) {
				this.editor.setDraft("");
				this.lastClearAt = Date.now();
				this.host.requestRender(); // 消费输入后 Pi 不会再走 focusedComponent 的自动重绘。
				return { consume: true };
			}
			const now = Date.now();
			if (this.lastClearAt > 0 && now - this.lastClearAt <= 500) void this.quit();
			else this.flash("Press Ctrl+C again to exit");
			this.lastClearAt = now;
			return { consume: true };
		}
		if (this.keymap.matches("app.exit", data)) {
			if (this.editor.draft().length === 0) {
				void this.quit();
				return { consume: true };
			}
			return undefined;
		}
		if (this.keymap.matches("app.message.copy", data)) {
			if (this.host.hasSelection()) { void this.host.copyActiveSelection(); return { consume: true }; }
			const value = this.transcript.lastReplyText();
			if (!value) this.notify("No assistant reply to copy", true);
			else void this.copy(value);
			return { consume: true };
		}
		if (this.keymap.matches("app.tools.expand", data)) {
			if (this.conversation && this.transcript.entries().length === 0) {
				this.conversation.toggleWelcomeResources(); this.host.requestRender(); return { consume: true };
			}
			const expanded = !this.transcript.entries().filter((entry) => entry.kind === "TOOL").some((entry) => entry.expanded);
			this.transcript.setToolsExpanded(expanded);
			this.host.requestRender();
			return { consume: true };
		}
		if (this.keymap.matches("app.thinking.toggle", data)) {
			const settings = this.session.settings();
			if (settings) this.saveSettings({ thinkingVisible: !settings.thinkingVisible });
			return { consume: true };
		}
		if (this.keymap.matches("app.editor.external", data)) {
			void this.openExternalEditor();
			return { consume: true };
		}
		if (this.keymap.matches("app.clipboard.pasteImage", data)) {
			void this.pasteClipboard();
			return { consume: true };
		}
		if (this.keymap.matches("app.thinking.cycle", data)) {
			this.cycleThinking();
			return { consume: true };
		}
		if (this.keymap.matches("tui.altScreen.search", data) && this.host.mode() === "fullscreen") {
			this.openSearchPage();
			return { consume: true };
		}
		if (this.keymap.matches("app.permission.menu", data)) {
			this.pages.push(new PermissionPage(this.pageContext()));
			this.host.requestRender();
			return { consume: true };
		}
		if (this.keymap.matches("app.session.new", data)) {
			this.newSession();
			return { consume: true };
		}
		if (this.keymap.matches("app.session.resume", data)) {
			this.openResumePage();
			return { consume: true };
		}
		// Pi Editor 的 LF/Ctrl+J 兼容分支绕过 keybinding manager；用户覆盖换行键后，
		// 这里阻止旧默认再次插入换行，避免“显示已换键但旧键仍生效”。
		const lf = data === "\n" || matchesKey(data, "ctrl+j");
		const submitReassigned = lf && this.keymap.isOverridden("tui.input.submit")
			&& this.keymap.matches("tui.input.submit", data);
		if (this.keymap.isOverridden("tui.input.newLine")
				&& lf
				&& !this.keymap.matches("tui.input.newLine", data)
				&& !submitReassigned
				&& this.keymap.matchAnyExcept(["EDITOR"], data, ["tui.input.newLine", "tui.input.submit"]) === null) {
			// 旧换行键未被重新分配才拦截；若 LF 已绑定给光标/删除等编辑动作，
			// 交给 Editor（P07 保证不会落入硬编码换行分支）。
			return { consume: true };
		}
		return undefined;
	}

	private dockOriginY = 0;

	/** 全屏停靠区只使用已预算的屏幕行，审批几何不再随历史长度漂出视口。 */
	private renderFullscreenDock(width: number, bottom: number, origin: number): string[] {
		this.dockOriginY = origin;
		const approval = this.approval.active();
		if (approval) {
			const current = this.approval.currentData();
			return approval.render({
				title: "Approval required", tool: current?.tool ?? "", scope: current?.scope ?? "",
				parameters: current?.parameters ?? "", actor: current?.actor ?? "", loading: current?.loading ?? true,
				teamActive: this.session.snapshot()?.teamActive === true,
				actorName: (actor) => this.session.displayActor(actor) || actor,
			}, width, origin, this.host.terminal.rows, width, bottom - origin);
		}
		const page = this.pages.current();
		this.dock.setContent({ page: page instanceof SearchPage ? undefined : page, editor: this.editor?.editor, maxRows: bottom });
		return this.dock.render(width);
	}

	private renderRoot(width: number): string[] {
		this.conversation.setViewportRows(this.host.terminal.rows);
		const conversation = this.conversation.render(width);
		const footer = this.renderFooter(width);
		const available = Math.max(1, this.host.terminal.rows - footer.length);
		const measured = this.renderFullscreenDock(width, available, 0);
		// regular 把完整文档写进 scrollback，但按钮坐标属于当前屏幕的文档尾部。
		// 不能拿 conversation.length 当屏幕行号，否则超过一屏后审批永远无可见按钮。
		const origin = Math.min(conversation.length, Math.max(0, available - measured.length));
		const dock = this.renderFullscreenDock(width, available, origin);
		return [...conversation, ...dock, ...footer];
	}

	private identity(): { sessionId: string; generation: string } {
		return { sessionId: this.session.sessionId(), generation: this.session.generation() };
	}

	private lastServerSeq = 0n;
	private lastServerConnection = "";

	/** 记录一条协议元数据（方向/type/seq/连接/UI 状态），不记录正文或凭据。 */
	private trace(direction: string, message: WireMessage): void {
		this.traceBuffer.push({
			at: Date.now(), direction, type: message.type, seq: message.seq,
			connection: message.connectionId.slice(0, 12), uiReady: this.uiBarrier.isReady(),
		});
		if (this.traceBuffer.length > 64) this.traceBuffer.shift();
	}

	/**
	 * 把异步失败（socket 回调/render/未捕获异常）送进可报告的出口。
	 * 不假装正常关闭；保留第一次原因。
	 */
	reportFailure(reason: string, error?: unknown): void {
		if (!this.exitMessage) {
			const detail = error instanceof Error ? `${reason}: ${error.message}` : reason;
			this.exitMessage = detail;
		}
		if (error instanceof Error) process.stderr.write(`${error.stack ?? error.message}\n`);
	}

	/**
	 * 接收方向隔离：只接受本连接、递增 seq，且会话事件属于当前代次。
	 * bootstrap/hello/新快照是代次切换边界，不按旧代次过滤。
	 */
	private admissible(message: WireMessage): boolean {
		if (message.type === "server.hello") return true;
		if (message.type === "server.bootstrap") return true;
		if (this.lastServerConnection && message.connectionId !== this.lastServerConnection) return false;
		let seq: bigint;
		try { seq = BigInt(message.seq); } catch { return false; }
		if (seq <= this.lastServerSeq) return false;
		this.lastServerSeq = seq;
		if (this.lastPublishConnection() !== message.connectionId) this.lastServerConnection = message.connectionId;
		const sessionBound = !message.type.startsWith("session.snapshot.")
			&& message.type !== "command.result" && message.type !== "command.error"
			&& message.type !== "server.stopped" && message.type !== "diagnostic"
			&& message.type !== "payload.begin" && message.type !== "payload.chunk" && message.type !== "payload.end";
		if (sessionBound) {
			const generation = this.session.generation();
			const sessionId = this.session.sessionId();
			if (message.generation !== null && generation && message.generation !== generation) return false;
			if (message.sessionId !== null && sessionId && message.sessionId !== sessionId) return false;
		}
		return true;
	}

	private lastPublishConnection(): string {
		return this.lastServerConnection;
	}

	private onEvent(message: WireMessage): void {
		if (!this.admissible(message)) return;
		this.trace("in", message);
		// bootstrap 先建立设置；其余需要编辑器/终端的事件在 UI 初始化后按序消费。
		if (message.type !== "server.hello" && message.type !== "server.bootstrap") {
			this.uiBarrier.offer(message, (queued) => this.handleEvent(queued));
			return;
		}
		this.handleEvent(message);
	}

	/** 真正处理一条已通过身份/序号校验的事件；队列重放也走这里（不重复做 seq 校验）。 */
	private handleEvent(message: WireMessage): void {
		switch (message.type) {
			case "server.bootstrap":
				this.session.applyBootstrap(message.payload);
				this.bootstrapResolve?.();
				this.bootstrapResolve = undefined;
				return;
			case "session.snapshot.begin":
				if (!this.session.bootstrap()) return;
				this.snapshotBuffer = {
					id: text(message.payload, "snapshotId"), chunks: [],
					generation: text(message.payload, "generation"),
					sourceSeq: text(message.payload, "sourceSeq", "0"),
				};
				return;
			case "session.snapshot.chunk":
				if (!this.session.bootstrap()) return;
				if (!this.snapshotBuffer || this.snapshotBuffer.id !== text(message.payload, "snapshotId")) return;
				this.snapshotBuffer.chunks.push(this.resolveText(message.payload, "encodedEntries", "entriesBlobRef"));
				return;
			case "session.snapshot.end":
				if (!this.session.bootstrap()) return;
				this.finishSnapshot(message.payload);
				return;
			case "state.snapshot":
				this.session.applySnapshot(this.resolveSnapshot(message.payload));
				this.onSessionStateChanged();
				return;
			case "stream.started":
			case "stream.delta":
			case "stream.ended":
			case "stream.preview.started":
			case "stream.preview.delta":
			case "stream.preview.closed":
				this.handleStream(message);
				return;
			case "execution.notice":
				this.handleNotice(message.payload);
				return;
			case "display.clock":
				this.session.applyClock(message.payload);
				this.armTimer();
				return;
			case "message":
				this.transcript.message(text(message.payload, "actor"), text(message.payload, "label"),
					display(text(message.payload, "text")), message.payload.error === true);
				return;
			case "task.submitted":
				this.transcript.userRequest(text(message.payload, "submissionId"),
					display(this.resolveText(message.payload, "text", "textBlobRef")));
				return;
			case "task.result":
				this.taskFinished(message.payload, false);
				return;
			case "task.failed":
				this.taskFinished(message.payload, true);
				return;
			case "approval.requested":
				this.closeSearchOverlay(); // 搜索框不能覆盖审批，也不能继续持有输入焦点。
				if (this.pages.current()) {
					this.theme.cancel(); this.conversation.setSearchQuery("", 0); this.pages.clear(); this.host.setFocus(this.editor.editor);
				}
				this.approval.requested(message.payload, (ref) => this.assembler.resolve(ref));
				return;
			case "approval.removed":
				this.approval.removed(message.payload);
				return;
			case "settings.saved": {
				const saved = this.pendingSettings.shift();
				if (saved?.theme) this.theme.commitSucceededFor(saved.theme);
				this.session.applySettingsSaved(message.payload);
				this.applyLiveSettings();
				return;
			}
			case "settings.failed": {
				const failure = text(message.payload, "message", "Settings were not saved");
				const failed = this.pendingSettings.shift();
				if (failed?.theme) this.theme.commitFailedFor(failed.theme, failure);
				else this.notify(failure, true);
				return;
			}
			case "diagnostic":
				this.notify(text(message.payload, "message"), true);
				return;
			case "command.error":
				return;
			case "client.detached":
                this.detachedReceived = true;
                this.closing = true;
                return;
            case "server.stopped":
                if (text(message.payload, "cleanupError")) this.reportFailure(text(message.payload, "cleanupError"));
				this.stoppedReceived = true;
				this.closing = true;
				return;
			case "payload.begin":
				this.payloadBegin(message.payload);
				return;
			case "payload.chunk":
				this.payloadChunk(message.payload);
				return;
			case "payload.end":
				this.payloadEnd(message.payload);
				return;
			default:
				return;
		}
	}

	private streamReducer: StreamReducer | undefined;

	private handleStream(message: WireMessage): void {
		this.streamReducer ??= new StreamReducer(this.transcript, (diagnostic) => this.notify(diagnostic, true));
		switch (message.type) {
			case "stream.started": this.streamReducer.started(message.payload); break;
			case "stream.delta": this.streamReducer.delta(message.payload); break;
			case "stream.ended": this.streamReducer.ended(message.payload); break;
			case "stream.preview.started": this.streamReducer.previewStarted(message.payload); break;
			case "stream.preview.delta": this.streamReducer.previewDelta(message.payload); break;
			case "stream.preview.closed": this.streamReducer.previewClosed(message.payload); break;
		}
		this.host.requestRender();
	}

	private handleNotice(payload: Payload): void {
		const kind = text(payload, "kind");
		const actor = text(payload, "actor", "leader");
		const state = text(payload, "state");
		const detail = this.resolveText(payload, "detail", "detailBlobRef");
		const identityNode = payload.toolIdentity;
		const identity: ToolIdentity | null = typeof identityNode === "object" && identityNode !== null
			? {
				kind: text(identityNode as Payload, "kind"),
				serverName: text(identityNode as Payload, "serverName"),
				originalName: text(identityNode as Payload, "originalName"),
				hostReadOnly: (identityNode as Payload).hostReadOnly === true,
			}
			: null;
		switch (kind) {
			case "TOOL_START":
			case "TOOL_END":
				this.transcript.toolNotice(actor, text(payload, "id"), text(payload, "name"), detail, state,
					numberValue(payload, "elapsedMs"), identity);
				break;
			case "TURN_END":
				if (state === "DONE" && text(payload, "name") === "Reply") {
					this.transcript.attachTurnElapsed(actor, numberValue(payload, "elapsedMs"));
				}
				break;
			case "SESSION":
				if (state === "CLOSED") this.transcript.closeActiveTools();
				break;
			case "COMPACT":
				this.transcript.notice(actor, "Context", detail, false);
				break;
			case "SUBAGENT":
				this.transcript.notice(actor, "Subagent", detail, false);
				break;

			case "TEAM": {
				if (state === "UPDATED") break;
				const entry = this.transcript.notice(actor, text(payload, "name", "Team"), detail, state === "ERROR");
				this.transcript.update(entry.id, (current) => ({ ...current, status: state === "ERROR" ? "ERROR" : "" }));
				break;
			}
			case "GOAL": {
				// 判断结果来自宿主独立调用；显示状态不授予权限，也不把模型自述升级为验收。
				const failed = state === "IMPOSSIBLE";
				const entry = this.transcript.notice(actor, "Goal", `${state}: ${detail}`, failed);
				this.transcript.update(entry.id, (current) => ({ ...current, status: failed ? "ERROR" : "" }));
				break;
			}
			case "WORKFLOW": {
				// 进度只显示宿主事件，不变成用户指令；最终成功与否仍由工具结果和任务状态决定。
				const failed = state === "failed" || state === "cancelled";
				const entry = this.transcript.notice(actor, "Workflow", `${detail} · ${state}`, failed);
				// 普通 notice 默认标 Historical；这里是当前运行事实，状态已在正文，不能误标成历史恢复。
				this.transcript.update(entry.id, (current) => ({ ...current, status: failed ? "ERROR" : "" }));
				break;
			}
			case "BACKGROUND":
				this.transcript.notice(actor, "Background", `${text(payload, "id")} ${state}`, false);
				break;
			default:
				break;
		}
		this.armTimer();
		this.host.requestRender();
	}

	private taskFinished(payload: Payload, failed: boolean): void {
		const submissionId = text(payload, "submissionId");
		const id = `task-${submissionId}`;
		const message = failed ? text(payload, "message", "Task failed") : "";
		this.transcript.update(id, (entry) => ({
			...entry,
			status: failed ? "ERROR" : "DONE",
			body: failed && message && !entry.body.includes(message) ? `${entry.body}\n${message}`.trim() : entry.body,
		}));
		if (!failed) {
			const finalText = this.resolveText(payload, "text", "textBlobRef");
			if (finalText.trim() && !this.lastReplyContains(finalText)) {
				this.transcript.message("leader", "Reply", display(finalText), false);
			}
		}
		this.host.requestRender();
	}

	private lastReplyContains(value: string): boolean {
		const last = this.transcript.lastReplyText();
		return last.trim() === value.trim();
	}

	/** snapshot.end：校验 hash → 安装条目/状态 → 发 session.ready。 */
	private finishSnapshot(payload: Payload): void {
		const buffer = this.snapshotBuffer;
		this.snapshotBuffer = undefined;
		const snapshotId = text(payload, "snapshotId");
		if (!buffer || buffer.id !== snapshotId) return;
		const expected = text(payload, "hash");
		const digest = createHash("sha256");
		for (const chunk of buffer.chunks) digest.update(Buffer.from(chunk, "utf8")).update(Buffer.from("\n", "utf8"));
		const actual = digest.digest("hex");
		if (actual !== expected) {
			this.notify("The session snapshot failed its integrity check; not installing it.", true);
			return;
		}
		const entries: Entry[] = [];
		for (const chunk of buffer.chunks) {
			let parsed: unknown;
			try { parsed = JSON.parse(chunk); } catch { continue; }
			if (!Array.isArray(parsed)) continue;
			for (const item of parsed) {
				const entry = parseEntry(item);
				if (entry) entries.push(entry);
			}
		}
		// 完整快照是会话切换边界；旧界面结果/排队申请不跨代次复活。
		this.clearApprovalResult();
		this.approval.clear();
		this.transcript.install(entries);
        this.streamReducer = new StreamReducer(this.transcript, (diagnostic) => this.notify(diagnostic, true));
        this.streamReducer.restore(entries);
		this.session.applySnapshot(this.resolveSnapshot(payload));
		this.editor.rememberInputs(this.transcript.userTexts());
		this.syncTranscriptHistoryMessage();
		this.client?.send("session.ready", {
			snapshotId,
			generation: buffer.generation,
			lastSnapshotSeq: buffer.sourceSeq,
		}, this.identity());
		this.host.requestRender(true);
	}

	private payloadBegin(payload: Payload): void {
		try {
			this.assembler.begin(text(payload, "id"), text(payload, "kind"), numberValue(payload, "totalBytes"),
				text(payload, "sha256"));
		} catch (error) { this.notify((error as Error).message, true); }
	}

	private payloadChunk(payload: Payload): void {
		try {
			this.assembler.chunk(text(payload, "id"), numberValue(payload, "index"), text(payload, "chunk", text(payload, "text")));
		} catch (error) { this.notify((error as Error).message, true); }
	}

	private payloadEnd(payload: Payload): void {
		const id = text(payload, "id");
		try {
			this.assembler.end(id, numberValue(payload, "count"), text(payload, "sha256"));
		} catch (error) {
			this.notify((error as Error).message, true);
			return;
		}
		const value = this.assembler.resolve(id);
		if (value !== undefined) this.approval.blobReady(id, value);
		this.host.requestRender();
	}

	/** 大状态快照在 begin/chunk/end 校验完成后解析；缺失正文明确失败，不能用空 Team 覆盖。 */
	private resolveSnapshot(payload: Payload): Payload {
		const id = text(payload, "snapshotBlobRef");
		if (!id) return payload;
		const value = this.assembler.resolve(id);
		if (value === undefined) throw new Error("The state snapshot payload is incomplete");
		const snapshot: unknown = JSON.parse(value);
		if (typeof snapshot !== "object" || snapshot === null || Array.isArray(snapshot)) throw new Error("Invalid state snapshot payload");
		this.assembler.discard(id);
		return { ...payload, snapshot };
	}

	private resolveText(payload: Payload, field: string, blobField: string): string {
		const ref = text(payload, blobField);
		if (ref) {
			const value = this.assembler.resolve(ref);
			return value ?? "";
		}
		return text(payload, field);
	}

	/** 帧围栏：写失败或渲染后终端已失效（resize/暂停）时不发布呈现证据。 */
	private afterFrame(epoch: number): void {
		if (!this.presented || !this.host || this.host.terminal.hasFailed()) return;
		this.presented.publishedAt(this.host.terminal.columns, this.host.terminal.rows, epoch, (value) => {
			this.presentedFrame = value;
			this.approval.onFrame(value);
		});
	}

	private lastTerminalTitle = "";
	private onSessionStateChanged(): void {
		const title = singleLine(`JadeCode · ${this.session.snapshot()?.title ?? ""}`);
		if (title !== this.lastTerminalTitle) { this.lastTerminalTitle = title; this.host?.terminal?.setTitle(title); }
		this.applyLiveSettings();
		this.armTimer();
		this.host?.requestRender();
	}

	private applyLiveSettings(): void {
		const settings = this.session.settings();
		if (!settings || !this.host) return;
		this.armProgress();
		const signature = JSON.stringify(settings);
		if (signature === this.lastSettingsSignature) return;
		this.lastSettingsSignature = signature;
		this.host.setHardwareCursor(settings.showHardwareCursor);
		this.host.setClearOnShrink(settings.clearOnShrink);
		this.host.setWheelScrollLines(settings.fullscreenWheelScrollLines === 0 ? "auto" : settings.fullscreenWheelScrollLines);
		this.host.setScrollbar(settings.fullscreenScrollbar as "auto" | "always" | "hidden");
		this.host.setCopyOnSelect(settings.fullscreenCopyOnSelect);
		this.host.setMode(settings.mode as UiMode);
		this.editor.applySettings(settings);
		if (this.lastToolsExpanded !== settings.toolsExpanded) {
			this.lastToolsExpanded = settings.toolsExpanded;
			this.transcript.setToolsExpanded(settings.toolsExpanded);
		}
		const themeConfig = JSON.stringify([settings.theme, settings.lightTheme, settings.darkTheme]);
		if (themeConfig !== this.lastThemeConfig) { this.lastThemeConfig = themeConfig; this.theme.settingsChanged(); }
		this.armProgress();
	}

	private lastSettingsSignature = "";
	private lastThemeConfig = "";
	private lastToolsExpanded: boolean | undefined;
	private progressActive = false;

	/** terminal progress 只在真实活动任务且配置启用时登记；结束/空闲清除。 */
	private armProgress(): void {
		const want = this.session.isActive() && (this.session.settings()?.showTerminalProgress ?? false);
		if (want === this.progressActive) return;
		this.progressActive = want;
		this.host.setTerminalProgress(want);
	}

	/** 单次定时器：活动时每 250ms 重绘；空闲/结束后不再安排。 */
	private armTimer(): void {
		if (this.timer) clearTimeout(this.timer);
		this.timer = undefined;
		this.armProgress();
		if (!this.session.isActive()) return;
		this.timer = setTimeout(() => {
			this.host.requestRender();
			this.armTimer();
		}, 250);
		this.timer.unref?.();
	}

	/**
	 * 终端失效：递增 epoch、作废已呈现审批/确认证据，并把新 epoch 交给 Java。
	 * 业务连接与任务保持；不重连、不重提。
	 */
	private onTerminalInvalidate(reason: string): void {
		if (!this.presented) return;
		const epoch = this.presented.invalidate();
		this.presentedFrame = undefined;
		this.approval.invalidate();
		const client = this.client;
		if (client) {
			void client.command("terminal.invalidate", { reason, terminalEpoch: epoch }, this.identity()).catch(() => {});
		}
		this.host?.requestRender(true);
	}

	private openSettingsPage(): void {
		this.pages.push(new SettingsPage(this.pageContext()));
		this.host.setFocus(this.root);
		this.host.requestRender();
	}

	private openResumePage(): void {
		this.pages.push(new ResumePage(this.pageContext()));
		this.host.setFocus(this.root);
		this.host.requestRender();
	}

	private searchOverlay: OverlayHandle | undefined;

	/** 恢复 Pi 风格右上角浮层；页面栈仍负责输入路由，底部保留原草稿。 */
	private openSearchPage(): void {
		const screen = this.host.tui();
		if (!screen || this.pages.current()) return;
		const page = new SearchPage(this.pageContext(), (query) =>
			this.conversation.search(query, Math.max(1, this.host.terminal.columns - (this.session.settings()?.fullscreenScrollbar === "always" ? 1 : 0))));
		this.pages.push(page);
		this.host.setFocus(this.root);
		const overlay = {
			render: (width: number) => page.renderOverlay(width), invalidate: () => page.invalidate(),
			get focused() { return page.focused; }, set focused(value: boolean) { page.focused = value; },
		};
		this.searchOverlay = screen.showOverlay(overlay, { anchor: "top-right", width: "40%", minWidth: 32, margin: 1 });
		this.host.requestRender();
	}

	/** 关闭浮层及其页面/高亮；没有搜索时不干扰其它页面。 */
	private closeSearchOverlay(): void {
		this.searchOverlay?.hide();
		this.searchOverlay = undefined;
		if (this.pages.current() instanceof SearchPage) {
			this.pages.pop();
			this.conversation.setSearchQuery("", 0);
			this.host.setFocus(this.editor.editor);
		}
	}

	private pageContext(): PageContext {
		return {
			state: this.session,
			keymap: this.keymap,
			theme: () => this.theme.snapshot(),
			client: () => this.client ?? null,
			readTeamReport: async (assignment) => {
				if (!this.client) throw new Error("The backend connection is closed");
				const identity = this.identity();
				const result = await this.client.command("team.report", { assignmentId: assignment }, identity);
				if (!result.ok) throw new Error(result.message ?? "Unable to read the report");
				if (identity.sessionId !== this.session.sessionId() || identity.generation !== this.session.generation())
					throw new Error("The session changed while reading the report");
				const id = text(result.payload, "textBlobRef");
				const value = this.assembler.resolve(id);
				if (value === undefined) throw new Error("The report payload is incomplete");
				this.assembler.discard(id);
				return value;
			},
			identity: () => this.identity(),
			closePage: () => {
				if (this.pages.current() instanceof SearchPage) this.closeSearchOverlay();
				else this.pages.pop();
				this.editor.focus(this.host.tui()!);
				this.host.requestRender();
			},
			pushPage: (page: Page) => { this.pages.push(page); this.host.requestRender(); },
			requestRender: () => this.host.requestRender(),
			notify: (message, error = false) => this.notify(message, error),
			saveSettings: (patch) => this.saveSettings(patch),
			themeNames: () => this.theme.names(),
			previewTheme: (name) => this.theme.preview(name),
			commitTheme: () => this.theme.commit(),
			commitThemeTarget: (target, name) => {
				if (target === "theme") {
					this.theme.commit();
					return;
				}
				// 配对主题只保存目标字段；不要顺带保存当前逻辑 theme。
				this.theme.clearPreview();
				this.saveSettings({ [target]: name });
			},
			cancelTheme: () => this.theme.cancel(),
			sessionTheme: () => this.session.settings()?.theme ?? "system",
			setDraft: (value) => this.editor.setDraft(value),
			setSearchHighlight: (query, selected) => this.conversation.setSearchQuery(query, selected),
			jumpToMatch: (query, selectedOffset) => {
				const offset = selectedOffset ?? this.conversation.findOffset(query);
				if (offset === null) return;
				this.host.scrollToTop();
				this.host.scrollBy(offset);
			},
			lastFrame: () => this.presentedFrame ? {
				frameId: this.presentedFrame.frameId,
				epoch: this.presentedFrame.epoch,
				rawWatermark: this.presentedFrame.rawWatermark,
				decodedWatermark: this.presentedFrame.decodedWatermark,
				columns: this.presentedFrame.columns,
				rows: this.presentedFrame.rows,
			} : null,
			dockOriginY: () => this.dockOriginY,
			evidence: () => this.evidence.active(),
			settings: () => this.session.settings(),
		};
	}

	/** 串行设置保存的待回执队列；Java PreferenceService 串行执行，saved/failed 顺序与提交一致。 */
	private readonly pendingSettings: Array<{ patch: Record<string, unknown>; theme: string | null; commandId: string | null; removed: boolean }> = [];

	private saveSettings(patch: Record<string, unknown>): void {
		const client = this.client;
		if (!client) return;
		const entry = { patch, theme: typeof patch.theme === "string" ? patch.theme : null, commandId: null as string | null, removed: false };
		this.pendingSettings.push(entry);
		const handleFailure = (failure: string): void => {
			if (entry.removed) return;
			entry.removed = true;
			const index = this.pendingSettings.indexOf(entry);
			if (index >= 0) this.pendingSettings.splice(index, 1);
			if (entry.theme) this.theme.commitFailedFor(entry.theme, failure);
			else this.notify(failure, true);
		};
		void client.command("settings.patch", {
			expectedConfigRevision: Number(this.session.configRevision()),
			patch,
		}, this.identity(), {
			onCommandId: (id) => { entry.commandId = id; },
			onCommandError: (message) => handleFailure(message),
		}).then((result) => {
			if (!result.ok) handleFailure(result.message ?? "The setting was not saved");
		});
	}

	private submitPrompt(value: string): boolean {
		const client = this.client;
		if (!client) return false;
		const submissionId = `sub-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
		try {
			this.sendSubmit(client, submissionId, value);
			return true;
		} catch (error) {
			this.notify((error as Error).message, true);
			return false;
		}
	}

	/** 大 prompt 走 blob；小 prompt 内联。提交意图由 Java 发 task.submitted。 */
	private sendSubmit(client: BackendClient, submissionId: string, value: string): void {
		if (value.length > 32_768) {
			const id = `prompt-${submissionId}`;
			const bytes = Buffer.from(value, "utf8");
			const hash = createHash("sha256").update(bytes).digest("hex");
			client.send("payload.begin", { id, kind: "prompt", totalBytes: bytes.length, sha256: hash }, this.identity());
			let index = 0;
			for (let offset = 0; offset < value.length;) {
				let end = Math.min(value.length, offset + 8192);
				// Java 按 UTF-8 校验 blob；不能把一个 emoji 的 UTF-16 代理对拆到两块。
				if (end < value.length && /[\uD800-\uDBFF]/.test(value[end - 1]!)) end--;
				client.send("payload.chunk", { id, index: index++, text: value.slice(offset, end) }, this.identity());
				offset = end;
			}
			client.send("payload.end", { id, count: index, sha256: hash }, this.identity());
			void client.command("session.submit", { textBlobRef: id, clientSubmissionId: submissionId }, this.identity());
			return;
		}
		void client.command("session.submit", { text: value, clientSubmissionId: submissionId }, this.identity());
	}

	private promoteBuffer(): boolean {
		const client = this.client;
		if (!client) return false;
		void client.command("session.promoteBuffer", {}, this.identity());
		return true;
	}

	private runSlash(name: string, argument: string, raw: string): boolean {
		switch (name) {
			case "/settings": this.openSettingsPage(); return true;
			case "/hotkeys": this.pages.push(new HotkeysPage(this.pageContext(), this.keymap)); this.host.requestRender(); return true;
			case "/model": this.pages.push(new ModelPage(this.pageContext())); this.host.requestRender(); return true;
			case "/session": this.pages.push(new SessionPage(this.pageContext())); this.host.requestRender(); return true;
			case "/team": this.pages.push(new TeamPage(this.pageContext())); this.host.requestRender(); return true;
			case "/mcp": this.pages.push(new McpPage(this.pageContext())); this.host.requestRender(); return true;
			// 使用既有用户提交协议；只有后端用户入口解析命令，工具结果/模型正文不能设置目标。
			case "/goal": return this.submitPrompt(raw);
			case "/permission": {
				const parts = argument.trim().split(/\s+/).filter(Boolean);
				const intent = parts[0] === "default" ? "default" : "runtime";
				const profile = parts[intent === "default" ? 1 : 0];
				if (profile && !["read-only", "workspace", "auto-review", "full-access"].includes(profile)) {
					this.notify(`Unknown permission profile: ${profile}. The draft was kept.`, true); return false;
				}
				const page = new PermissionPage(this.pageContext(), intent, profile);
				this.pages.push(page);
				// 命令中的档位已是明确意图；Full Access 仍只 prepare，必须再过可见的二次确认。
				if (profile) page.handleInput("\r");
				this.host.requestRender();
				return true;
			}
			case "/resume":
				if (argument.trim()) {
					const client = this.client;
					if (client) void client.command("session.find", { name: argument.trim() }, this.identity()).then((result) => {
						if (!result.ok) { this.notify(result.message ?? "Session lookup failed", true); return; }
						const sessions = arrayValue(result.payload, "sessions");
						if (sessions.length === 1) {
							const item = sessions[0] as Payload;
							void client.command("session.resume", { targetSessionId: text(item, "id") }, this.identity());
						} else if (sessions.length > 1) this.openResumePage();
						else this.notify("No session matched that name", true);
					});
				} else this.openResumePage();
				return true;
			case "/copy": {
				const value = this.transcript.lastReplyText();
				if (!value) this.notify("No assistant reply to copy", true);
				else void this.copy(value);
				return true;
			}
			case "/new": this.newSession(); return true;
			case "/quit": void this.quit(); return true;
			case "/stop": this.stopCurrentTurn(); return true;
            case "/close": {
                const client = this.client;
                if (client) void client.command("session.close", {}, this.identity()).then((result) => {
                    if (!result.ok) this.notify(result.message ?? "Session operation failed", true);
                }).catch(() => {});
                return true;
            }
			case "/name": {
				const client = this.client;
				if (!argument.trim()) { this.notify("Usage: /name <title>", true); return true; }
				if (client) void client.command("session.rename", { title: argument.trim() }, this.identity()).then((result) => {
					if (!result.ok) this.notify(result.message ?? "Rename failed", true);
				});
				return true;
			}
			case "/thinking": this.changeThinking(argument.trim()); return true;
			default:
				this.notify(`Unknown command: ${raw.trim().split(/\s+/)[0]}. The draft was kept.`, true);
				return false;
		}
	}

	/**
	 * Esc 和 /stop 共用已有服务取消入口，只发出请求，不自行宣告任务已停止。
	 * 保留草稿、连接和历史；后端后续的任务事件与快照负责更新真实状态。
	 * 退出过程中不再发送命令；拒绝或连接失败通过界面反馈，不假装取消成功。
	 */
	private stopCurrentTurn(): void {
		const client = this.client;
		if (!client || this.closing) return;
		void client.command("session.stop", {}, this.identity()).then((result) => {
			if (!result.ok) this.notify(result.message ?? "Unable to stop the current turn", true);
		}).catch(() => this.notify("Unable to send the stop request", true));
	}

	private newSession(): void {
		const client = this.client;
		if (client) void client.command("session.new", { expectedSessionId: this.session.sessionId(),
			generation: Number(this.session.generation()) }, this.identity()).then((result) => {
			if (!result.ok) this.notify(result.message ?? "Could not start a new session", true);
		});
	}

	private changeThinking(value: string): void {
		const client = this.client;
		const choices = this.session.snapshot()?.thinkingChoices ?? [];
		if (!value) {
			this.notify(`Thinking: ${this.session.snapshot()?.thinking || "default"} · ${choices.join(", ")}`, false);
			return;
		}
		if (!client) return;
		void client.command("session.thinking", { value }, this.identity()).then((result) => {
			if (!result.ok) this.notify(result.message ?? "Thinking level was not accepted", true);
		});
	}

	private cycleThinking(): void {
		const choices = this.session.snapshot()?.thinkingChoices ?? [];
		if (choices.length === 0) return;
		const current = this.session.snapshot()?.thinking ?? "default";
		const index = Math.max(0, choices.indexOf(current));
		this.changeThinking(choices[(index + 1) % choices.length]!);
	}

	private async listSessions(): Promise<SessionItem[]> {
		const client = this.client;
		if (!client) return [];
		const result = await client.command("session.list", {}, this.identity());
		if (!result.ok) return [];
		return arrayValue(result.payload, "sessions") as SessionItem[];
	}

	/** 复制反馈只短暂显示，不写进持久转录；regular 通过页脚承载。 */
	private toast = "";
	private toastTimer: ReturnType<typeof setTimeout> | undefined;
	private flash(message: string, error = false): void {
		this.toast = message;
		if (this.toastTimer) clearTimeout(this.toastTimer);
		this.toastTimer = setTimeout(() => { this.toast = ""; this.host?.requestRender(); }, error ? 5000 : 1000);
		this.toastTimer.unref?.();
		this.host?.requestRender();
	}

	/**
	 * 结果固定在当前终端视口右上角，不增加文档行，也不改变焦点/审批按钮几何。
	 * 宽度每帧跟随 resize，最多 56 列，四周留一格；高度空间不足时隐藏，避免覆盖输入栏。
	 */
	private installApprovalResultOverlay(): void {
		this.approvalResultOverlay?.hide();
		const screen = this.host?.tui();
		if (!screen) return;
		const app = this;
		const overlayWidth = (): number => Math.max(1, Math.min(56, app.host.terminal.columns - 2));
		this.approvalResultOverlay = screen.showOverlay(this.approvalResult, {
			get width() { return overlayWidth(); },
			anchor: "top-right",
			margin: 1,
			nonCapturing: true,
			visible: () => app.approvalResult.hasResult() && !app.closing && !app.approval.hasPending() && !app.pages.current()
				&& app.dockOriginY >= 1 + app.approvalResult.height(overlayWidth()),
		});
	}

	/** 释放旧 screen 上的浮层句柄和唯一临时结果；不修改转录或后端审批状态。 */
	private clearApprovalResult(): void {
		this.approvalResultOverlay?.hide();
		this.approvalResultOverlay = undefined;
		this.approvalResult.clear();
	}

	private renderFooter(width: number): string[] {
		const lines = this.status.render(this.theme.snapshot(), width);
		if (this.toast) lines[0] = clipLine(singleLine(visibleEscapes(this.toast)), width);
		return lines;
	}

	private async copy(value: string): Promise<void> {
		const result = await this.clipboard.copy(value);
		if (result.status === "FAILED") this.flash(result.error ?? "Copy failed", true);
		else this.flash("Copied!", false);
	}

	private async pasteClipboard(): Promise<void> {
		const path = await this.clipboard.saveImage();
		if (path) {
			const draft = this.editor.draft();
			this.editor.setDraft(`${draft}${draft ? " " : ""}${path}`);
			this.host.requestRender();
			return;
		}
		const clipboard = (await import("@earendil-works/pi-tui")).getNativeClipboard();
		try {
			const value = await clipboard?.getText();
			if (value) {
				this.editor.setDraft(this.editor.draft() + value);
				this.host.requestRender();
			} else this.notify("No image clipboard backend available", true);
		} catch {
			this.notify("Clipboard paste failed", true);
		}
	}

	private async openExternalEditor(): Promise<void> {
		const settings = this.session.settings();
		if (!settings?.externalEditor) {
			this.notify("No external editor is configured (tui.externalEditor).", true);
			return;
		}
		const draft = this.editor.draft();
		const result = await this.externalEditor.edit(settings.externalEditor, draft, {
			suspend: async () => {
				this.onTerminalInvalidate("external-editor");
				this.host.suspendTerminal();
				await this.host.terminal.flushOutput();
			},
			resume: () => {
				this.host.resumeTerminal();
				this.onTerminalInvalidate("external-editor-return");
			},
		});
		if (result && this.editor.draft() === draft) {
			this.editor.setDraft(result.text);
			this.host.requestRender(true);
		}
	}

	private notify(message: string, error: boolean): void {
		if (!message) return;
		this.transcript.notice("System", error ? "Warning" : "Notice", display(message), error);
		this.host?.requestRender();
	}

	private syncTranscriptHistoryMessage(): void {
		// 恢复后历史空时由 ConversationView 显示品牌；正常恢复不额外插入固定提示。
	}

	private waitForExit(): Promise<number> {
		return new Promise((resolve) => {
			const check = (): void => {
				// 退出意图不等于收口完成：必须等到 server.stopped 或真实断连。
				if (this.stoppedReceived || this.detachedReceived || this.client?.isClosed()) {
					clearInterval(interval);
					resolve(this.exitMessage ? 1 : 0);
					return;
				}
				if (this.closing && Date.now() - this.closingAt >= Application.EXIT_GRACE_MS) {
					clearInterval(interval);
					this.reportFailure("Timed out waiting for the backend to stop after client.shutdown");
					resolve(1);
				}
			};
			const interval = setInterval(check, 100);
			interval.unref?.();
			check();
		});
	}

	private async quit(): Promise<void> {
		if (this.closing) return;
		this.closing = true;
		this.closingAt = Date.now();
		const client = this.client;
		if (client) {
			try { await client.command("client.shutdown", { reason: "user" }, this.identity()); } catch { /* 已断开。 */ }
		}
	}

	private onClosed(reason: string): void {
		this.clearApprovalResult();
		if (this.closing) {
			// 只有收到 server.stopped 后的关闭才是正常收口；否则保留真实原因。
			if (!this.stoppedReceived && !this.detachedReceived && !this.exitMessage) this.exitMessage = `The backend closed unexpectedly: ${reason}`;
			return;
		}
		this.closing = true;
		if (!this.stoppedReceived && !this.detachedReceived) this.exitMessage = `The backend closed before server.stopped: ${reason}`;
	}

	private async shutdown(): Promise<void> {
		this.clearApprovalResult();
		if (this.timer) clearTimeout(this.timer);
		if (this.toastTimer) clearTimeout(this.toastTimer);
		this.colorTicket++; this.colorDisposer?.();
		this.timer = undefined;
		try {
			this.host?.stop();
			if (this.host) await this.host.terminal.flushOutput();
		} catch (error) { this.reportFailure("Terminal restoration failed", error); }
		try { this.theme?.close(); } catch { /* 无 watch。 */ }
		try { this.clipboard.cleanup(); } catch (error) { this.reportFailure("Temporary clipboard cleanup failed", error); }
		finally { this.client?.close(); }
	}

	/** 失败时输出协议元数据环形缓冲（无正文/凭据），便于区分双方先后顺序。 */
	private dumpTrace(): void {
		if (this.traceBuffer.length === 0) return;
		process.stderr.write("--- frontend protocol trace (metadata only) ---\n");
		for (const item of this.traceBuffer) {
			process.stderr.write(`${item.at} ${item.direction} ${item.type} seq=${item.seq} conn=${item.connection} uiReady=${item.uiReady}\n`);
		}
	}

	/** 未捕获异常/异步失败统一收口：报告、停终端、关闭连接。 */
	abort(reason: string, error?: unknown): void {
		this.reportFailure(reason, error);
		try { this.client?.close(); } catch { /* 已关闭。 */ }
		try { this.host?.stop(); } catch { /* 终端可能已恢复。 */ }
	}

	/** 终端恢复后由入口输出一次。regular 已进入 scrollback，不重复回放。 */
	normalExitOutput(): string {
		const snapshot = this.session.snapshot();
		const settings = this.session.settings();
		if (!snapshot || !settings || this.exitMessage) return "";
		const replay = settings.mode === "fullscreen" && settings.fullscreenExitOutput === "transcript"
			? this.conversation.render(this.host.terminal.columns).join("\n") + "\n" : "";
		const title = singleLine(visibleEscapes(snapshot.title));
		return `${replay}\nSession: ${title || "Untitled"}\nID: ${snapshot.sessionId}\nResume: jade --resume=${snapshot.sessionId}\nGoodbye!\n`;
	}

	/** 退出失败原因由入口输出一次。 */
	exitOutput(): string {
		return this.exitMessage;
	}
}

function parseEntry(value: unknown): Entry | null {
	if (typeof value !== "object" || value === null) return null;
	const node = value as Payload;
	const kind = text(node, "kind");
	if (!["USER", "REPLY", "THINKING", "TOOL", "NOTICE"].includes(kind)) return null;
	const toolNode = node.tool;
	const elapsed = node.elapsedMs;
	return {
		id: text(node, "entryId"),
		kind: kind as Entry["kind"],
		actor: text(node, "actor"),
		title: text(node, "title"),
		body: text(node, "body"),
		arguments: text(node, "arguments"),
		status: text(node, "status"),
		requestId: typeof node.requestId === "string" ? node.requestId : null,
		callId: typeof node.callId === "string" ? node.callId : null,
		blockIndex: typeof node.blockIndex === "number" ? node.blockIndex : null,
		elapsedMs: typeof elapsed === "number" ? elapsed : null,
		historical: node.historical === true,
		tool: typeof toolNode === "object" && toolNode !== null ? {
			kind: text(toolNode as Payload, "kind"),
			serverName: text(toolNode as Payload, "serverName"),
			originalName: text(toolNode as Payload, "originalName"),
			hostReadOnly: (toolNode as Payload).hostReadOnly === true,
		} : null,
		revision: 0,
		expanded: kind === "THINKING",
	};
}

/** 导出供测试：解析一条历史条目（真实 JSON 形状）。 */
export { parseEntry };
