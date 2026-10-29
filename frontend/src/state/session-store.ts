import { numberValue, text, type Payload } from "../bridge/wire.ts";

/**
 * 只读业务/配置快照 + 显示计时插值。
 *
 * 唯一采用 Java 发来的 display/usage/permission/Team/busy 资料；Node 不自造领域事件，也不
 * 累加 token 或猜测成功。计时只在 Java 给的相对毫秒上叠加本地增量，暂停/显示停止/结束后
 * 冻结——绝不相减两个进程的 nanoTime。
 */

export interface TuiSettings {
	theme: string;
	lightTheme: string;
	darkTheme: string;
	mode: "fullscreen" | "regular";
	fullscreenExitOutput: string;
	fullscreenScrollbar: string;
	fullscreenCopyOnSelect: boolean;
	fullscreenWheelScrollLines: number;
	showHardwareCursor: boolean;
	editorPaddingX: number;
	outputPad: number;
	autocompleteMaxVisible: number;
	thinkingVisible: boolean;
	toolsExpanded: boolean;
	commandSuggestions: boolean;
	clearOnShrink: boolean;
	showTerminalProgress: boolean;
	quietStartup: boolean;
	codeBlockIndent: string;
	externalEditor: string;
	escapeTimeoutMillis: number;
	keybindings: Record<string, string[]>;
	showImages: boolean;
	imageWidthCells: number;
	mermaidRendering: string;
}

export interface UsageSnapshot {
	recentCacheHitPercent?: number | null;
	byActor?: Record<string, UsageSnapshot>;
	byPurpose?: Record<string, UsageSnapshot>;
	byModel?: Record<string, UsageSnapshot>;
	inputTokens: number | null;
	outputTokens: number | null;
	cacheReadTokens: number | null;
	cacheWriteTokens: number | null;
	reportedRequests: number | null;
	missingRequests: number | null;
}

export interface ContextSnapshot {
	occupiedTokens: number | null;
	windowTokens: number | null;
	remainingPercent: number | null;
	/** 可选字段兼容旧快照；true 表示有效 usage 加新增消息估算或全量估算。 */
	estimated?: boolean;
}

export interface PermissionSnapshot {
	profile: string;
	label: string;
	revision: string;
	defaultProfile: string;
	rules: string[];
	disabledDefaultCount: number;
}

/** Team 展示只采用后端事实；运行状态、报告与核对状态分别保存。 */
export interface TeamQuestion { id: string; summary: string; to: string; answered: boolean; }
export interface TeamAssignment {
	id: string; taskId: string; subject: string; leaderRunId: string; required: boolean;
	scope: { readPaths: string[]; writePaths: string[] };
	finished: boolean; abandoned: boolean; outcome: string; reportId: string; reportSummary: string;
	reportPath: string; reviewNotes: string; deliveryError: string; progress: string; questions: TeamQuestion[];
}
export interface TeamMember {
	id: string; name: string; responsibilities: string; state: string; closed: boolean;
	currentAssignment: string; assignments: TeamAssignment[]; historyCount: number;
	pendingReports: number; toReview: number; openQuestions: number; questions: TeamQuestion[];
}

export interface ActorState {
	id: string;
	state: string;
}

export interface McpServerSnapshot {
	name: string;
	enabled: boolean;
	connected: boolean;
	tools: string[];
}

export interface Capabilities {
	thinking: boolean;
	permission: boolean;
	resume: boolean;
	mcp: boolean;
	externalEditor: boolean;
	browserImages: boolean;
}

export interface ClockTick {
	runId: string;
	elapsedMs: number;
	running: boolean;
	paused: boolean;
	displayStopped: boolean;
	revision: string;
}

export interface BackendSnapshot {
	resources?: Record<string, string[]>;
	sessionId: string;
	title: string;
	generation: string;
	workspace: string;
	gitBranch: string;
	model: string;
	modelSource: string;
	contextWindowTokens: number | null;
	maxInputTokens: number | null;
	maxOutputTokens: number | null;
	thinking: string;
	thinkingLabel: string;
	thinkingChoices: string[];
	usage: UsageSnapshot;
	context: ContextSnapshot | null;
	permission: PermissionSnapshot | null;
	actors: ActorState[];
	teamActive: boolean;
	team: TeamMember[];
	mcp: McpServerSnapshot[];
	background: Record<string, string>;
	bufferSummary: string;
	bufferState: string;
	tui: TuiSettings;
	configRevision: string;
	capabilities: Capabilities;
	clocks: Record<string, ClockTick>;
}

export interface StartupIntent {
	mode: string;
	autoSubmit: boolean;
	resumeTarget: string;
}

export interface Bootstrap {
    executionHost?: string;
    serviceSessionId?: string;
	projectVersion: string;
	buildId: string;
	protocolVersion: number;
	workspace: string;
	themeDirectory: string;
	configRevision: string;
	tui: TuiSettings;
	startup: StartupIntent;
}

export type ChangeListener = () => void;

export class SessionStore {
	private bootstrapValue: Bootstrap | null = null;
	private snapshotValue: BackendSnapshot | null = null;
	private settingsValue: TuiSettings | null = null;
	private readonly clocks = new Map<string, { tick: ClockTick; receivedAt: number }>();
	private readonly listeners = new Set<ChangeListener>();

	onChange(listener: ChangeListener): () => void {
		this.listeners.add(listener);
		return () => this.listeners.delete(listener);
	}

	bootstrap(): Bootstrap | null { return this.bootstrapValue; }

	snapshot(): BackendSnapshot | null { return this.snapshotValue; }

	settings(): TuiSettings | null { return this.settingsValue; }

	generation(): string { return this.snapshotValue?.generation ?? ""; }

	sessionId(): string { return this.snapshotValue?.sessionId ?? ""; }

	configRevision(): string { return this.snapshotValue?.configRevision ?? this.bootstrapValue?.configRevision ?? "0"; }

	/** bootstrap 先于终端主题初始化：设置/启动意图都从这里取。 */
	applyBootstrap(payload: Payload): void {
		const tui = payload.tui;
		const startup = payload.startup;
		if (typeof tui !== "object" || tui === null) throw new Error("server.bootstrap is missing tui settings");
		const value: Bootstrap = {
			projectVersion: text(payload, "projectVersion"),
            executionHost: text(payload, "executionHost"),
            serviceSessionId: text(payload, "serviceSessionId"),
			buildId: text(payload, "buildId"),
			protocolVersion: numberValue(payload, "protocolVersion", 1),
			workspace: text(payload, "workspace"),
			themeDirectory: text(payload, "themeDirectory"),
			configRevision: text(payload, "configRevision", "0"),
			tui: parseTui(tui as Payload),
			startup: {
				mode: typeof startup === "object" && startup !== null ? text(startup as Payload, "mode", "new") : "new",
				autoSubmit: typeof startup === "object" && startup !== null && (startup as Payload).autoSubmit === true,
				resumeTarget: typeof startup === "object" && startup !== null ? text(startup as Payload, "resumeTarget") : "",
			},
		};
		this.bootstrapValue = value;
		this.settingsValue = value.tui;
		this.emit();
	}

	/** 采用完整/增量后端快照；clock 一并替换为新的权威基线。 */
	applySnapshot(payload: Payload): void {
		const node = payload.snapshot;
		if (typeof node !== "object" || node === null) throw new Error("state.snapshot is missing snapshot");
		this.snapshotValue = parseSnapshot(node as Payload);
		this.settingsValue = this.snapshotValue.tui;
		this.clocks.clear();
		for (const [actor, tick] of Object.entries(this.snapshotValue.clocks)) {
			this.clocks.set(actor, { tick, receivedAt: Date.now() });
		}
		this.emit();
	}

	applyClock(payload: Payload): void {
		const actor = text(payload, "actor");
		if (!actor) return;
		this.clocks.set(actor, {
			tick: {
				runId: text(payload, "runId"),
				elapsedMs: numberValue(payload, "elapsedMs"),
				running: payload.running === true,
				paused: payload.paused === true,
				displayStopped: payload.displayStopped === true,
				revision: text(payload, "clockRevision", "0"),
			},
			receivedAt: Date.now(),
		});
		this.emit();
	}

	/** settings.saved 只更新 tui 与 revision；完整快照随后由 state.snapshot 更新。 */
	applySettingsSaved(payload: Payload): void {
		const revision = text(payload, "configRevision");
		const tui = payload.tui;
		if (typeof tui === "object" && tui !== null) {
			const settings = parseTui(tui as Payload);
			this.settingsValue = settings;
			if (this.snapshotValue) this.snapshotValue = { ...this.snapshotValue, tui: settings };
		}
		if (revision) {
			if (this.snapshotValue) this.snapshotValue = { ...this.snapshotValue, configRevision: revision };
			if (this.bootstrapValue) this.bootstrapValue = { ...this.bootstrapValue, configRevision: revision };
		}
		this.emit();
	}

	/** @returns 当前 actor 的显示耗时（毫秒），未知返回 null。 */
	elapsedMs(actor: string): number | null {
		const entry = this.clocks.get(actor);
		if (!entry) return null;
		const { tick, receivedAt } = entry;
		if (!tick.running) return tick.elapsedMs;
		const frozen = tick.paused || tick.displayStopped;
		return tick.elapsedMs + (frozen ? 0 : Math.max(0, Date.now() - receivedAt));
	}

	/** @returns 是否有活动轮次（用于动画/进度登记）。 */
	isActive(actor = "leader"): boolean {
		const entry = this.clocks.get(actor);
		return entry !== undefined && entry.tick.running && !entry.tick.paused && !entry.tick.displayStopped;
	}

	actors(): ActorState[] { return this.snapshotValue?.actors ?? []; }

	teamActive(): boolean { return this.snapshotValue?.teamActive === true; }
	/** 当前 Team 快照；前端不自行创建成员或派工状态。 */
	team(): TeamMember[] { return this.snapshotValue?.team ?? []; }

	permissionRevision(): string { return this.snapshotValue?.permission?.revision ?? "0"; }

	/** 显示名：单人会话隐藏 Leader；Team 模式显示真实 actor。 */
	displayActor(actor: string): string {
		if (!this.teamActive()) {
			if (actor === "leader") return "";
			if (actor.startsWith("leader/")) return actor.slice("leader/".length);
			return actor;
		}
		const member = this.team().find((item) => actor === item.id || actor.startsWith(`${item.id}/`));
		if (member) {
			const duplicate = this.team().some((item) => item.id !== member.id && item.name === member.name);
			const label = duplicate ? `${member.name} (${member.id.slice(-8)})` : member.name;
			return label + actor.slice(member.id.length).replace(/^\//, " / ");
		}
		return actor === "leader" ? "Leader" : actor.startsWith("leader/") ? `Leader / ${actor.slice(7)}` : actor;
	}

	private emit(): void {
		for (const listener of [...this.listeners]) listener();
	}
}

/** 解析 Java 的 tui 子快照；缺字段用旧批准默认值，不读取 AppConfig。 */
export function parseTui(node: Payload): TuiSettings {
	const keybindings: Record<string, string[]> = {};
	const raw = node.keybindings;
	if (typeof raw === "object" && raw !== null && !Array.isArray(raw)) {
		for (const [action, keys] of Object.entries(raw as Payload)) {
			if (Array.isArray(keys)) keybindings[action] = keys.filter((key): key is string => typeof key === "string");
		}
	}
	return {
		theme: text(node, "theme", "system"),
		lightTheme: text(node, "lightTheme", "light"),
		darkTheme: text(node, "darkTheme", "dark"),
		mode: text(node, "mode", "fullscreen") === "regular" ? "regular" : "fullscreen",
		fullscreenExitOutput: text(node, "fullscreenExitOutput", "transcript"),
		fullscreenScrollbar: text(node, "fullscreenScrollbar", "auto"),
		fullscreenCopyOnSelect: node.fullscreenCopyOnSelect !== false,
		fullscreenWheelScrollLines: numberValue(node, "fullscreenWheelScrollLines"),
		showHardwareCursor: node.showHardwareCursor === true,
		editorPaddingX: numberValue(node, "editorPaddingX"),
		outputPad: numberValue(node, "outputPad", 1),
		autocompleteMaxVisible: numberValue(node, "autocompleteMaxVisible", 5),
		thinkingVisible: node.thinkingVisible !== false,
		toolsExpanded: node.toolsExpanded === true,
		commandSuggestions: node.commandSuggestions !== false,
		clearOnShrink: node.clearOnShrink === true,
		showTerminalProgress: node.showTerminalProgress === true,
		quietStartup: node.quietStartup === true,
		codeBlockIndent: text(node, "codeBlockIndent", "  "),
		externalEditor: text(node, "externalEditor"),
		escapeTimeoutMillis: numberValue(node, "escapeTimeoutMillis"),
		keybindings,
		showImages: node.showImages === true,
		imageWidthCells: numberValue(node, "imageWidthCells", 60),
		mermaidRendering: text(node, "mermaidRendering", "streaming"),
	};
}

function parseSnapshot(node: Payload): BackendSnapshot {
	const resources: Record<string, string[]> = {};
	if (node.resources && typeof node.resources === "object" && !Array.isArray(node.resources)) {
		for (const [group, values] of Object.entries(node.resources)) {
			if (Array.isArray(values)) resources[group] = values.filter((value): value is string => typeof value === "string");
		}
	}
	const tui = typeof node.tui === "object" && node.tui !== null ? parseTui(node.tui as Payload) : parseTui({});
	const usageNode = typeof node.usage === "object" && node.usage !== null ? (node.usage as Payload) : {};
	const contextNode = typeof node.context === "object" && node.context !== null ? (node.context as Payload) : null;
	const permissionNode = typeof node.permission === "object" && node.permission !== null ? (node.permission as Payload) : null;
	const capabilitiesNode = typeof node.capabilities === "object" && node.capabilities !== null
		? (node.capabilities as Payload) : {};
	const actors: ActorState[] = [];
	if (Array.isArray(node.actors)) {
		for (const item of node.actors) {
			if (typeof item !== "object" || item === null) continue;
			actors.push({ id: text(item as Payload, "id"), state: text(item as Payload, "state") });
		}
	}
	const mcp: McpServerSnapshot[] = [];
	if (Array.isArray(node.mcp)) {
		for (const item of node.mcp) {
			if (typeof item !== "object" || item === null) continue;
			const server = item as Payload;
			mcp.push({
				name: text(server, "name"),
				enabled: server.enabled === true,
				connected: server.connected === true,
				tools: Array.isArray(server.tools) ? server.tools.filter((tool): tool is string => typeof tool === "string") : [],
			});
		}
	}
	const background: Record<string, string> = {};
	if (typeof node.background === "object" && node.background !== null) {
		for (const [key, value] of Object.entries(node.background as Payload)) {
			if (typeof value === "string") background[key] = value;
		}
	}
	const clocks: Record<string, ClockTick> = {};
	if (typeof node.clocks === "object" && node.clocks !== null) {
		for (const [actor, value] of Object.entries(node.clocks as Payload)) {
			if (typeof value !== "object" || value === null) continue;
			const tick = value as Payload;
			clocks[actor] = {
				runId: text(tick, "runId"),
				elapsedMs: numberValue(tick, "elapsedMs"),
				running: tick.running === true,
				paused: tick.paused === true,
				displayStopped: tick.displayStopped === true,
				revision: text(tick, "revision", "0"),
			};
		}
	}
	const rules = permissionNode && Array.isArray(permissionNode.rules)
		? permissionNode.rules.filter((rule): rule is string => typeof rule === "string") : [];
	return {
		sessionId: text(node, "sessionId"),
		title: text(node, "title"),
		generation: text(node, "generation", "0"),
		workspace: text(node, "workspace"),
		gitBranch: text(node, "gitBranch"),
		model: text(node, "model"),
		modelSource: text(node, "modelSource"), resources,
		contextWindowTokens: nullableNumber(node, "contextWindowTokens"),
		maxInputTokens: nullableNumber(node, "maxInputTokens"),
		maxOutputTokens: nullableNumber(node, "maxOutputTokens"),
		thinking: text(node, "thinking"),
		thinkingLabel: text(node, "thinkingLabel"),
		thinkingChoices: Array.isArray(node.thinkingChoices)
			? node.thinkingChoices.filter((choice): choice is string => typeof choice === "string") : [],
		usage: {
			recentCacheHitPercent: nullableNumber(usageNode, "recentCacheHitPercent"),
			byActor: usageGroups(usageNode.byActor), byPurpose: usageGroups(usageNode.byPurpose), byModel: usageGroups(usageNode.byModel),
			inputTokens: nullableNumber(usageNode, "inputTokens"),
			outputTokens: nullableNumber(usageNode, "outputTokens"),
			cacheReadTokens: nullableNumber(usageNode, "cacheReadTokens"),
			cacheWriteTokens: nullableNumber(usageNode, "cacheWriteTokens"),
			reportedRequests: nullableNumber(usageNode, "reportedRequests"),
			missingRequests: nullableNumber(usageNode, "missingRequests"),
		},
		context: contextNode ? {
			occupiedTokens: nullableNumber(contextNode, "occupiedTokens"),
			windowTokens: nullableNumber(contextNode, "windowTokens"),
			remainingPercent: nullableNumber(contextNode, "remainingPercent"),
			estimated: contextNode.estimated === true,
		} : null,
		permission: permissionNode ? {
			profile: text(permissionNode, "profile"),
			label: text(permissionNode, "label"),
			revision: text(permissionNode, "revision", "0"),
			defaultProfile: text(permissionNode, "defaultProfile"),
			rules,
			disabledDefaultCount: numberValue(permissionNode, "disabledDefaultCount"),
		} : null,
		actors,
		teamActive: node.teamActive === true,
		team: parseTeam(node.team),
		mcp,
		background,
		bufferSummary: text(node, "bufferSummary"),
		bufferState: text(node, "bufferState"),
		tui,
		configRevision: text(node, "configRevision", "0"),
		capabilities: {
			thinking: capabilitiesNode.thinking !== false,
			permission: capabilitiesNode.permission !== false,
			resume: capabilitiesNode.resume !== false,
			mcp: capabilitiesNode.mcp === true,
			externalEditor: capabilitiesNode.externalEditor === true,
			browserImages: capabilitiesNode.browserImages === true,
		},
		clocks,
	};
}

function nullableNumber(node: Payload, field: string): number | null {
	const value = node[field];
	if (value === null || value === undefined) return null;
	const parsed = typeof value === "number" ? value : typeof value === "string" ? Number.parseFloat(value) : Number.NaN;
	return Number.isFinite(parsed) ? parsed : null;
}

export { parseSnapshot };


/** 分类只取标量，不递归读任意嵌套对象，也不重复累加账本。 */
function usageGroups(value: unknown): Record<string, UsageSnapshot> {
	if (!value || typeof value !== "object" || Array.isArray(value)) return {};
	const groups: Record<string, UsageSnapshot> = Object.create(null);
	for (const [name, raw] of Object.entries(value)) {
		if (!raw || typeof raw !== "object" || Array.isArray(raw)) continue;
		const node = raw as Payload;
		groups[name] = { inputTokens: nullableNumber(node, "inputTokens"), outputTokens: nullableNumber(node, "outputTokens"),
			cacheReadTokens: nullableNumber(node, "cacheReadTokens"), cacheWriteTokens: nullableNumber(node, "cacheWriteTokens"),
			reportedRequests: nullableNumber(node, "reportedRequests"), missingRequests: nullableNumber(node, "missingRequests") };
	}
	return groups;
}

/** 逐层复制后端快照，缺省字段只表示未知/空，不从摘要推断状态。 */
function parseTeam(value: unknown): TeamMember[] {
	const objects = (items: unknown): Payload[] => Array.isArray(items)
		? items.filter((item): item is Payload => typeof item === "object" && item !== null) : [];
	const strings = (items: unknown): string[] => Array.isArray(items) ? items.filter((item): item is string => typeof item === "string") : [];
	return objects(value).map((member) => ({
		id: text(member, "id"), name: text(member, "name"), responsibilities: text(member, "responsibilities"),
		state: text(member, "state"), closed: member.closed === true, currentAssignment: text(member, "currentAssignment"),
		historyCount: numberValue(member, "historyCount"), pendingReports: numberValue(member, "pendingReports"),
		toReview: numberValue(member, "toReview"), openQuestions: numberValue(member, "openQuestions"),
		questions: objects(member.questions).map((q) => ({ id: text(q, "id"), summary: text(q, "summary"), to: text(q, "to"), answered: q.answered === true })),
		assignments: objects(member.assignments).map((a) => {
			const scope = typeof a.scope === "object" && a.scope !== null ? a.scope as Payload : {};
			return { id: text(a, "id"), taskId: text(a, "taskId"), subject: text(a, "subject"), leaderRunId: text(a, "leaderRunId"),
				required: a.required === true, scope: { readPaths: strings(scope.readPaths), writePaths: strings(scope.writePaths) },
				finished: a.finished === true, abandoned: a.abandoned === true, outcome: text(a, "outcome"),
				reportId: text(a, "reportId"), reportSummary: text(a, "reportSummary"), reportPath: text(a, "reportPath"),
				reviewNotes: text(a, "reviewNotes"), deliveryError: text(a, "deliveryError"), progress: text(a, "progress"),
				questions: objects(a.questions).map((q) => ({ id: text(q, "id"), summary: text(q, "summary"), to: text(q, "to"), answered: q.answered === true })) };
		}),
	}));
}
