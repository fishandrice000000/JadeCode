import { createHash } from "node:crypto";
import { resolve } from "node:path";
import {
	isKeyRelease,
	matchesKey,
	type Component,
	type TUI,
	type TuiMouseEvent,
} from "@earendil-works/pi-tui";
import { BackendClient } from "../bridge/client.ts";
import { PayloadAssembler } from "../bridge/payload-assembler.ts";
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
import { KeymapAdapter } from "../editor/keymap-adapter.ts";
import { JadeCompletionProvider } from "../editor/completion-provider.ts";
import { EditorController } from "../editor/editor-controller.ts";
import { ApprovalController } from "../approval/approval-controller.ts";
import { PageStack, type Page, type PageContext, type SessionItem } from "../pages/page-stack.ts";
import { SettingsPage } from "../pages/settings-page.ts";
import { ResumePage } from "../pages/resume-page.ts";
import { HotkeysPage, McpPage, ModelPage, SessionPage } from "../pages/information-pages.ts";
import { PermissionPage } from "../pages/permission-page.ts";
import { SearchPage } from "../pages/search-page.ts";
import { ClipboardController } from "../services/clipboard-controller.ts";
import { ExternalEditor } from "../services/external-editor.ts";
import { suspendToShell } from "../services/job-control.ts";
import { display } from "../presentation/text-safety.ts";

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
	private readonly clipboard = new ClipboardController();
	private readonly externalEditor = new ExternalEditor();
	private readonly pages = new PageStack();
	private readonly markdown = new MarkdownFactory();
	private readonly conversation: ConversationView;
	private readonly status: StatusView;
	private readonly dock = new DockView();
	private readonly approval: ApprovalController;
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
	private snapshotBuffer: { id: string; chunks: string[]; generation: string } | undefined;
	private readonly openedAt = Date.now();

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
			teamActive: () => this.session.teamActive(),
			actorName: (actor) => this.session.displayActor(actor) || actor,
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
		});
		this.client = client;
		try {
			await client.open();
			await client.hello(credential, buildId);
			await this.waitForBootstrap();
			this.setupThemeAndTerminal();
			this.setupInput();
			this.host.start();
			if (this.session.bootstrap()?.startup.mode === "picker") this.openResumePage();
			this.armTimer();
			return await this.waitForExit();
		} catch (failure) {
			this.exitMessage = (failure as Error).message;
			return 1;
		} finally {
			this.shutdown();
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
			onChange: () => this.host?.requestRender(true),
		});
		this.conversation.setThemeAccessor(() => this.theme.snapshot());
		this.presented = new PresentedEvidence(this.evidence, (frame) => this.onFramePresented(frame));
		this.approval.setPresented(this.presented);
		this.approval.setTheme(this.theme.snapshot());
		const settings = bootstrap.tui;
		this.keymap = new KeymapAdapter(settings.keybindings);
		const completion = new JadeCompletionProvider({
			workspace: bootstrap.workspace,
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
				this.notify("Copied!", false);
				return true;
			},
			showHardwareCursor: settings.showHardwareCursor,
			escapeTimeoutMillis: settings.escapeTimeoutMillis,
			onFrameWritten: () => this.host.terminal.afterFrame(() => this.afterFrame()),
			onWritable: () => this.host.notifyRenderReady(),
			onWriteFailure: (message) => this.notify(message, true),
			onResize: () => this.onTerminalInvalidate("resize"),
			onInputStart: () => this.evidence.beginDispatch(),
			onInputEnd: () => this.evidence.endDispatch(),
			onRawInput: (chunk) => this.evidence.rawChunk(chunk),
		});
		this.host.setRoot(this.root);
		this.host.setMousePriorityHandler((event) => this.approval.handleMouse(event));
		this.transcript.install([]);
		this.syncTranscriptHistoryMessage();
	}

	private setupInput(): void {
		const tui = this.host.tui();
		if (!tui) return;
		tui.addPriorityInputListener((data) => {
			if (this.approval.hasPending()) return this.approval.handleInput(data) ? { consume: true } : undefined;
			return undefined;
		});
		tui.addInputListener((data) => this.onGlobalInput(data));
		this.host.setFocus(this.editor.editor);
	}

	/** 全局输入：页面优先，然后 97 动作；编辑器自身处理文本/Enter。 */
	private onGlobalInput(data: string): { consume?: boolean; data?: string } | undefined {
		if (this.pages.current()) {
			if (this.pages.handleInput(data)) {
				this.host.requestRender();
				return { consume: true };
			}
			// 页面打开时不允许字符穿透到编辑器（除页面显式处理的键）。
			return { consume: true };
		}
		if (this.approval.hasPending()) return { consume: true };
		if (isKeyRelease(data)) return undefined;
		if (matchesKey(data, "escape")) {
			this.editor.focus(this.host.tui()!);
			return { consume: true };
		}
		if (matchesKey(data, "ctrl+c")) {
			if (this.host.hasSelection()) {
				void this.host.copyActiveSelection();
				return { consume: true };
			}
			if (this.editor.draft().length > 0) {
				this.editor.setDraft("");
				return { consume: true };
			}
			void this.quit();
			return { consume: true };
		}
		if (matchesKey(data, "ctrl+d")) {
			if (this.editor.draft().length === 0) {
				void this.quit();
				return { consume: true };
			}
			return undefined;
		}
		if (matchesKey(data, "ctrl+x")) {
			const value = this.transcript.lastReplyText();
			if (!value) this.notify("No assistant reply to copy", true);
			else void this.copy(value);
			return { consume: true };
		}
		if (matchesKey(data, "ctrl+o")) {
			const expanded = !(this.session.settings()?.toolsExpanded ?? false);
			this.transcript.setToolsExpanded(expanded);
			this.notify(expanded ? "Tools expanded" : "Tools collapsed", false);
			return { consume: true };
		}
		if (matchesKey(data, "ctrl+t")) {
			const settings = this.session.settings();
			if (settings) this.saveSettings({ thinkingVisible: !settings.thinkingVisible });
			return { consume: true };
		}
		if (matchesKey(data, "ctrl+g")) {
			void this.openExternalEditor();
			return { consume: true };
		}
		if (matchesKey(data, "ctrl+v")) {
			void this.pasteClipboard();
			return { consume: true };
		}
		if (matchesKey(data, "ctrl+z")) {
			suspendToShell({
				beforeSuspend: async () => { this.onTerminalInvalidate("suspend"); this.host.suspendTerminal(); },
				afterResume: () => { this.onTerminalInvalidate("resume"); this.host.resumeTerminal(); this.theme.reload(); },
			});
			return { consume: true };
		}
		if (matchesKey(data, "shift+tab")) {
			this.cycleThinking();
			return { consume: true };
		}
		if (matchesKey(data, "ctrl+shift+f") && this.host.mode() === "fullscreen") {
			this.openSearchPage();
			return { consume: true };
		}
		return undefined;
	}

	private renderRoot(width: number): string[] {
		const settings = this.session.settings();
		const conversation = this.conversation.render(width);
		const lines = [...conversation];
		const approvalView = this.approval.active();
		const page = this.pages.current();
		this.dock.setContent(approvalView ? { approval: approvalView } : page ? { page } : { editor: this.editor?.editor });
		if (approvalView && this.theme) {
			const snapshot = this.session.snapshot();
			const rows = this.host.terminal.rows;
			const viewportTop = this.host.mode() === "fullscreen" ? this.host.viewportTop() : 0;
			const origin = Math.max(0, conversation.length - viewportTop);
			const current = this.currentApprovalValues;
			const approvalLines = approvalView.render({
				title: current?.title ?? "Approval required",
				tool: current?.tool ?? "",
				scope: current?.scope ?? "",
				parameters: current?.parameters ?? "",
				actor: current?.actor ?? "",
				teamActive: snapshot?.teamActive === true,
				actorName: (actor) => this.session.displayActor(actor) || actor,
			}, width, origin, rows, width);
			lines.push(...approvalLines);
		} else {
			const dockLines = this.dock.render(width);
			lines.push(...dockLines);
		}
		lines.push(...this.status.render(this.theme.snapshot(), width));
		return lines;
	}

	private currentApprovalValues: { title: string; tool: string; scope: string; parameters: string; actor: string } | null = null;

	private identity(): { sessionId: string; generation: string } {
		return { sessionId: this.session.sessionId(), generation: this.session.generation() };
	}

	private onEvent(message: WireMessage): void {
		switch (message.type) {
			case "server.bootstrap":
				this.session.applyBootstrap(message.payload);
				this.bootstrapResolve?.();
				this.bootstrapResolve = undefined;
				return;
			case "session.snapshot.begin":
				this.snapshotBuffer = { id: text(message.payload, "snapshotId"), chunks: [], generation: text(message.payload, "generation") };
				return;
			case "session.snapshot.chunk":
				if (!this.snapshotBuffer || this.snapshotBuffer.id !== text(message.payload, "snapshotId")) return;
				this.snapshotBuffer.chunks.push(text(message.payload, "encodedEntries"));
				return;
			case "session.snapshot.end":
				this.finishSnapshot(message.payload);
				return;
			case "state.snapshot":
				this.session.applySnapshot(message.payload);
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
				this.approval.requested(message.payload, (ref) => this.assembler.resolve(ref));
				this.currentApprovalValues = {
					title: "Approval required",
					tool: text(message.payload, "tool"),
					scope: text(message.payload, "suggestionScope") || "(this request only)",
					parameters: text(message.payload, "inputJson", "{}"),
					actor: text(message.payload, "actor"),
				};
				return;
			case "approval.removed":
				this.approval.removed(message.payload);
				if (!this.approval.hasPending()) this.currentApprovalValues = null;
				return;
			case "settings.saved":
				this.session.applySettingsSaved(message.payload);
				this.theme.settingsChanged();
				this.applyLiveSettings();
				return;
			case "settings.failed":
				this.notify(text(message.payload, "message", "Settings were not saved"), true);
				return;
			case "diagnostic":
				this.notify(text(message.payload, "message"), true);
				return;
			case "command.error":
				return;
			case "server.stopped":
				this.exitMessage = "";
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
		this.transcript.install(entries);
		this.session.applySnapshot(payload);
		this.editor.rememberInputs(this.transcript.userTexts());
		this.syncTranscriptHistoryMessage();
		this.client?.send("session.ready", {
			snapshotId,
			generation: buffer.generation,
			lastSnapshotSeq: text(payload, "sourceSeq", "0"),
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
		const request = this.currentApprovalValues;
		if (request && request.parameters === "{}") {
			const value = this.assembler.resolve(id);
			if (value !== undefined) {
				request.parameters = value;
				this.approval.blobReady(id, value);
			}
		}
		this.host.requestRender();
	}

	private resolveText(payload: Payload, field: string, blobField: string): string {
		const ref = text(payload, blobField);
		if (ref) {
			const value = this.assembler.resolve(ref);
			return value ?? "";
		}
		return text(payload, field);
	}

	private afterFrame(): void {
		if (!this.presented || !this.host) return;
		const frame = this.presented;
		frame.published(this.host.terminal.columns, this.host.terminal.rows, (value) => {
			this.presentedFrame = value;
			this.approval.onFrame(value);
		});
	}

	private onFramePresented(_frame: FrameSnapshot): void {
		this.approval.onFrame(_frame);
	}

	private onSessionStateChanged(): void {
		this.applyLiveSettings();
		this.armTimer();
		this.host?.requestRender();
	}

	private applyLiveSettings(): void {
		const settings = this.session.settings();
		if (!settings || !this.host) return;
		this.host.setHardwareCursor(settings.showHardwareCursor);
		this.host.setClearOnShrink(settings.clearOnShrink);
		this.host.setWheelScrollLines(settings.fullscreenWheelScrollLines === 0 ? "auto" : settings.fullscreenWheelScrollLines);
		this.host.setScrollbar(settings.fullscreenScrollbar as "auto" | "always" | "hidden");
		this.host.setCopyOnSelect(settings.fullscreenCopyOnSelect);
		this.host.setMode(settings.mode as UiMode);
		this.editor.applySettings(settings);
		this.theme.settingsChanged();
		this.armProgress();
	}

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

	private onTerminalInvalidate(reason: string): void {
		this.presented?.invalidate();
		this.approval.invalidate();
		const client = this.client;
		if (client) void client.command("terminal.invalidate", { reason }, this.identity()).catch(() => {});
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

	private openSearchPage(): void {
		this.pages.push(new SearchPage(this.pageContext(), (query) => {
			const needle = query.toLowerCase();
			const matches: Array<{ id: string; offset: number; snippet: string }> = [];
			for (const entry of this.transcript.entries()) {
				const haystack = `${entry.title}\n${entry.arguments}\n${entry.body}`;
				if (!haystack.toLowerCase().includes(needle)) continue;
				const offset = this.conversation.entryOffset(entry.id);
				if (offset === null) continue;
				const index = haystack.toLowerCase().indexOf(needle);
				matches.push({ id: entry.id, offset, snippet: haystack.slice(Math.max(0, index - 20), index + 60).replace(/\n/g, " ") });
			}
			return matches;
		}));
		this.host.setFocus(this.root);
		this.host.requestRender();
	}

	private pageContext(): PageContext {
		return {
			state: this.session,
			theme: () => this.theme.snapshot(),
			client: () => this.client ?? null,
			identity: () => this.identity(),
			closePage: () => {
				this.pages.pop();
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
			cancelTheme: () => this.theme.cancel(),
			sessionTheme: () => this.session.settings()?.theme ?? "system",
			setDraft: (value) => this.editor.setDraft(value),
			jumpToMatch: (query) => {
				const offset = this.conversation.findOffset(query);
				if (offset === null) return;
				this.host.scrollToTop();
				this.host.scrollBy(offset);
			},
			lastFrame: () => this.presentedFrame ? {
				frameId: this.presentedFrame.frameId,
				epoch: this.presentedFrame.epoch,
				rawWatermark: this.presentedFrame.rawWatermark,
				decodedWatermark: this.presentedFrame.decodedWatermark,
			} : null,
			evidence: () => this.evidence.active(),
			settings: () => this.session.settings(),
		};
	}

	private saveSettings(patch: Record<string, unknown>): void {
		const client = this.client;
		if (!client) return;
		void client.command("settings.patch", {
			expectedConfigRevision: Number(this.session.configRevision()),
			patch,
		}, this.identity()).then((result) => {
			if (!result.ok) this.notify(result.message ?? "The setting was not saved", true);
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
			for (let offset = 0; offset < value.length; offset += 8192) {
				client.send("payload.chunk", { id, index: index++, text: value.slice(offset, offset + 8192) }, this.identity());
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
			case "/mcp": this.pages.push(new McpPage(this.pageContext())); this.host.requestRender(); return true;
			case "/permission": this.pages.push(new PermissionPage(this.pageContext())); this.host.requestRender(); return true;
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
			case "/new": {
				const client = this.client;
				if (client) void client.command("session.new", { expectedSessionId: this.session.sessionId(),
					generation: Number(this.session.generation()) }, this.identity()).then((result) => {
					if (!result.ok) this.notify(result.message ?? "Could not start a new session", true);
				});
				return true;
			}
			case "/quit": void this.quit(); return true;
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

	private async copy(value: string): Promise<void> {
		const result = await this.clipboard.copy(value);
		if (result.status === "FAILED") this.notify(result.error ?? "Copy failed", true);
		else this.notify("Copied!", false);
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
			},
			resume: () => {
				this.host.resumeTerminal();
				this.onTerminalInvalidate("external-editor-return");
			},
		});
		if (result) {
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
				if (this.closing || this.client?.isClosed()) {
					clearInterval(interval);
					resolve(this.exitMessage ? 1 : 0);
					return;
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
		const client = this.client;
		if (client) {
			try { await client.command("client.shutdown", { reason: "user" }, this.identity()); } catch { /* 已断开。 */ }
		}
	}

	private onClosed(reason: string): void {
		if (this.closing) return;
		this.closing = true;
		if (!reason.includes("frontend closed") && !reason.includes("backend closed")) this.exitMessage = reason;
	}

	private shutdown(): void {
		if (this.timer) clearTimeout(this.timer);
		this.timer = undefined;
		try { this.host?.stop(); } catch { /* 终端已恢复。 */ }
		try { this.theme?.close(); } catch { /* 无 watch。 */ }
		this.clipboard.cleanup();
		this.client?.close();
	}

	/** 退出时只打印真实结果/回放提示一次；不重复 farewell。 */
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
		expanded: false,
	};
}

/** 导出供测试：解析一条历史条目（真实 JSON 形状）。 */
export { parseEntry };
