/**
 * 显示转录：用户请求、回复、思考、工具卡和通知。
 *
 * 它不是模型 history：压缩只追加说明，不删除已经显示的内容；从日志恢复的条目保持
 * HISTORICAL，不补实时耗时或假 DONE。每条记录有稳定 id 与递增 revision，视图按 id+revision
 * +宽度缓存卡片，稳定旧卡不会每 token 重建。
 *
 * 工具卡关联：参数预览按 `actor/requestId/callId` 建卡；真实执行 notice 的 id 是
 * `batchId/callId`，batchId 在正常路径下等于该请求 id，因此能复用同一张卡；找不到时新建，
 * 不靠"最近同名工具"猜配。
 */
export type EntryKind = "USER" | "REPLY" | "THINKING" | "TOOL" | "NOTICE";

export interface ToolIdentity {
	kind: string;
	serverName: string;
	originalName: string;
	hostReadOnly: boolean;
}

export interface Entry {
	id: string;
	kind: EntryKind;
	actor: string;
	title: string;
	body: string;
	arguments: string;
	status: string;
	requestId: string | null;
	callId: string | null;
	blockIndex: number | null;
	elapsedMs: number | null;
	historical: boolean;
	tool: ToolIdentity | null;
	revision: number;
	expanded: boolean;
}

export type ChangeListener = () => void;

export class TranscriptStore {
	private toolsExpanded = false;
	private readonly list: Entry[] = [];
	private readonly byId = new Map<string, Entry>();
	private readonly previews = new Map<string, string>();
	private readonly listeners = new Set<ChangeListener>();
	private counter = 0;
	private revisionValue = 0;
	private lastReply = "";

	onChange(listener: ChangeListener): () => void {
		this.listeners.add(listener);
		return () => this.listeners.delete(listener);
	}

	entries(): readonly Entry[] { return this.list; }

	/** 显示侧修订：任何条目的新增/更新都会递增。 */
	revision(): number { return this.revisionValue; }

	/** 最近一次助手回复原文（复制/Ctrl+X 使用）。 */
	lastReplyText(): string { return this.lastReply; }

	/** 安装恢复/切换后的完整条目集合（旧代次内容整体替换）。 */
	install(entries: readonly Entry[]): void {
		this.list.length = 0;
		this.byId.clear();
		this.previews.clear();
		this.lastReply = "";
		for (const entry of entries) {
			const copy: Entry = { ...entry, revision: entry.revision ?? 0, expanded: entry.kind === "TOOL" ? this.toolsExpanded : entry.expanded ?? false };
			this.list.push(copy);
			this.byId.set(copy.id, copy);
			if (copy.kind === "REPLY" && copy.body) this.lastReply = copy.body;
		}
		this.revisionValue += 1;
		this.emit();
	}

	/** 新增或按 id 更新一条记录；返回实际记录。 */
	upsert(entry: Entry): Entry {
		const existing = this.byId.get(entry.id);
		if (existing) return this.update(entry.id, () => entry) ?? existing;
		// 新工具继承当前全局展开策略；单卡的后续更新仍保留自己的选择。
		if (entry.kind === "TOOL") entry = { ...entry, expanded: this.toolsExpanded };
		this.list.push(entry);
		this.byId.set(entry.id, entry);
		this.touch(entry);
		return entry;
	}

	/** 按 id 修改记录；不存在时返回 undefined。 */
	update(id: string, change: (entry: Entry) => Entry): Entry | undefined {
		const existing = this.byId.get(id);
		if (!existing) return undefined;
		const next = change(existing);
		const updated: Entry = { ...next, revision: existing.revision + 1 };
		const index = this.list.indexOf(existing);
		if (index >= 0) this.list[index] = updated;
		this.byId.set(id, updated);
		this.touch(updated);
		return updated;
	}

	newId(prefix = "e"): string {
		this.counter += 1;
		return `${prefix}${this.counter}`;
	}

	/** 新增用户请求卡；同一 submissionId 已存在时复用，避免重绘重复加卡。 */
	userRequest(submissionId: string, text: string): Entry {
		const id = `task-${submissionId}`;
		const existing = this.byId.get(id);
		if (existing) {
			return this.update(id, (entry) => ({ ...entry, body: text, status: "SUBMITTING" })) ?? existing;
		}
		return this.upsert({
			id, kind: "USER", actor: "You", title: "Request", body: text, arguments: "", status: "SUBMITTING",
			requestId: null, callId: null, blockIndex: null, elapsedMs: null, historical: false, tool: null,
			revision: 0, expanded: false,
		});
	}

	/** 程序整条消息：Reply 与流式卡内容相同时不重复建卡。 */
	message(actor: string, label: string, body: string, error: boolean): Entry {
		const kind: EntryKind = label === "Reply" ? "REPLY" : "NOTICE";
		if (kind === "REPLY") {
			const last = this.lastReplyEntry(actor);
			if (last?.requestId) {
				const segments = this.list.filter((entry) => entry.kind === "REPLY" && entry.requestId === last.requestId && entry.actor === last.actor);
				if ([segments.map((entry) => entry.body).join(""), segments.map((entry) => entry.body).join("\n")].some((value) => value.trim() === body.trim())) {
					// 最终整条回复只确认已显示的有序分段，不把它重复塞回最后一段。
					this.lastReply = body;
					return last;
				}
			}
			if (last && last.body.trim() === body.trim()) {
				this.update(last.id, (entry) => ({ ...entry, status: "DONE" }));
				return this.byId.get(last.id)!;
			}
			const merged = this.replyEntry(actor);
			if (merged) {
				this.update(merged.id, (entry) => ({ ...entry, body, status: error ? "INCOMPLETE" : "DONE" }));
				this.lastReply = body;
				return this.byId.get(merged.id)!;
			}
		}
		const entry: Entry = {
			id: this.newId("msg"), kind, actor: actor || "System", title: label || (error ? "Error" : "Notice"),
			body, arguments: "", status: error ? "ERROR" : (kind === "REPLY" ? "DONE" : "HISTORICAL"),
			requestId: null, callId: null, blockIndex: null, elapsedMs: null, historical: false, tool: null,
			revision: 0, expanded: false,
		};
		this.upsert(entry);
		if (kind === "REPLY") this.lastReply = body;
		return entry;
	}

	/** 通知卡（压缩/子代理/后台/历史 warning）：不写模型消息。 */
	notice(actor: string, title: string, body: string, error = false): Entry {
		return this.upsert({
			id: this.newId("n"), kind: "NOTICE", actor: actor || "System", title, body,
			arguments: "", status: error ? "ERROR" : "HISTORICAL", requestId: null, callId: null, blockIndex: null,
			elapsedMs: null, historical: false, tool: null, revision: 0, expanded: false,
		});
	}

	/** 流式回复/思考卡：每个连续通道段一张卡，保留 text/thinking 交替顺序。 */
	streamEntry(requestId: string, actor: string, kind: "REPLY" | "THINKING", segment = 0): Entry {
		const id = `stream-${requestId}-${kind}${segment ? `-${segment}` : ""}`;
		const existing = this.byId.get(id);
		if (existing) return existing;
		return this.upsert({
			id, kind, actor: actor || "leader", title: kind === "REPLY" ? "Reply" : "Thinking", body: "",
			arguments: "", status: "STREAMING", requestId, callId: null, blockIndex: null, elapsedMs: null,
			historical: false, tool: null, revision: 0, expanded: kind === "THINKING",
		});
	}

	/** 参数预览开始：以请求/调用为键建工具卡（PREVIEW，不代表已执行）。 */
	previewStarted(requestId: string, actor: string, blockIndex: number, callId: string, name: string,
		initialInput: string): Entry {
		const key = `${actor}/${requestId}/${callId}`;
		const existingId = this.previews.get(key);
		if (existingId) {
			this.update(existingId, (entry) => ({ ...entry, arguments: initialInput, status: "PREVIEW" }));
			return this.byId.get(existingId)!;
		}
		const id = `tool-${requestId}-${blockIndex}-${callId}`;
		const entry: Entry = {
			id, kind: "TOOL", actor: actor || "leader", title: name, body: "", arguments: initialInput,
			status: "PREVIEW", requestId, callId, blockIndex, elapsedMs: null, historical: false, tool: null,
			revision: 0, expanded: false,
		};
		this.previews.set(key, id);
		return this.upsert(entry);
	}

	previewDelta(requestId: string, blockIndex: number, fragment: string): Entry | undefined {
		const id = this.findPreviewByBlock(requestId, blockIndex);
		if (!id) return undefined;
		return this.update(id, (entry) => ({ ...entry, arguments: entry.arguments + fragment, status: "PREVIEW" }));
	}

	previewClosed(requestId: string, blockIndex: number): Entry | undefined {
		const id = this.findPreviewByBlock(requestId, blockIndex);
		if (!id) return undefined;
		return this.update(id, (entry) => ({ ...entry, status: entry.status === "PREVIEW" ? "PREVIEW" : entry.status }));
	}

	/**
	 * 真实执行事实：TOOL_START/TOOL_END 复用同一张卡（有预览时），否则新建。
	 * @param executionId notice.id，格式 `requestId/callId`
	 */
	toolNotice(actor: string, executionId: string, name: string, detail: string, state: string,
		elapsedMs: number, identity: ToolIdentity | null): Entry {
		const slash = executionId.indexOf("/");
		const requestId = slash > 0 ? executionId.slice(0, slash) : "";
		const callId = slash > 0 ? executionId.slice(slash + 1) : executionId;
		const previewId = this.previews.get(`${actor}/${requestId}/${callId}`);
		const isStart = state === "QUEUED" || state === "RUNNING";
		if (previewId) {
			const next = this.update(previewId, (entry) => ({
				...entry,
				// 开始通知携带参数，结束通知携带结果；不能把结果覆盖到参数栏。
				arguments: isStart ? detail || entry.arguments : entry.arguments,
				body: isStart ? entry.body : detail,
				status: state,
				title: name || entry.title,
				elapsedMs: isStart ? entry.elapsedMs : elapsedMs,
				tool: identity ?? entry.tool,
			}))!;
			if (!isStart) this.previews.delete(`${actor}/${requestId}/${callId}`);
			return next;
		}
		const id = `tool-${executionId}`;
		const existing = this.byId.get(id);
		if (existing) {
			return this.update(id, (entry) => ({
				...entry,
				arguments: isStart ? detail || entry.arguments : entry.arguments,
				body: isStart ? entry.body : detail,
				status: state,
				elapsedMs: isStart ? entry.elapsedMs : elapsedMs,
				tool: identity ?? entry.tool,
			}))!;
		}
		return this.upsert({
			id, kind: "TOOL", actor: actor || "leader", title: name, body: isStart ? "" : detail,
			arguments: isStart ? detail : "", status: state, requestId, callId, blockIndex: null,
			elapsedMs: isStart ? null : elapsedMs, historical: false, tool: identity, revision: 0, expanded: false,
		});
	}

	/** 会话关闭：仍在跑的工具卡按事实收尾，不伪造成功。 */
	closeActiveTools(): void {
		for (const entry of [...this.list]) {
			if (entry.kind !== "TOOL") continue;
			if (!["PREVIEW", "QUEUED", "RUNNING"].includes(entry.status)) continue;
			this.update(entry.id, (current) => ({
				...current,
				status: current.status === "QUEUED" ? "NOT_RUN" : "INTERRUPTED",
				body: current.body || "Session closed before a result was recorded.",
			}));
		}
	}

	/** 把整轮耗时附到最后一张已完成回复卡（Java 只在真实 TURN_END 且回答非空时给）。 */
	attachTurnElapsed(actor: string, elapsedMs: number): void {
		const entry = this.lastReplyEntry(actor);
		if (!entry) return;
		this.update(entry.id, (current) => ({ ...current, elapsedMs }));
	}

	toggleExpanded(id: string): void {
		this.update(id, (entry) => ({ ...entry, expanded: !entry.expanded }));
	}

	/** 全局展开/折叠工具；Thinking 单块与全局显隐保持独立。 */
	setToolsExpanded(value: boolean): void {
		this.toolsExpanded = value;
		for (const entry of [...this.list]) {
			if (entry.kind === "TOOL") {
				if (entry.expanded !== value) this.update(entry.id, (current) => ({ ...current, expanded: value }));
			}
		}
	}

	/** 用户输入历史：只取真实 USER 文本。 */
	userTexts(): string[] {
		return this.list.filter((entry) => entry.kind === "USER" && entry.body.trim()).map((entry) => entry.body);
	}

	/** 供搜索：可搜索正文（含工具结果）。 */
	searchable(): Array<{ id: string; text: string }> {
		return this.list.map((entry) => ({ id: entry.id, text: `${entry.title}\n${entry.arguments}\n${entry.body}` }));
	}

	private findPreviewByBlock(requestId: string, blockIndex: number): string | undefined {
		for (const entry of this.list) {
			if (entry.kind === "TOOL" && entry.requestId === requestId && entry.blockIndex === blockIndex) return entry.id;
		}
		return undefined;
	}

	private lastReplyEntry(actor: string): Entry | undefined {
		for (let i = this.list.length - 1; i >= 0; i--) {
			const entry = this.list[i]!;
			if (entry.kind === "REPLY" && entry.actor === (actor || "leader")) return entry;
		}
		return undefined;
	}

	/** 最后一个仍可合并的流式回复卡（无 requestId 的整条 Reply 使用）。 */
	private replyEntry(actor: string): Entry | undefined {
		for (let i = this.list.length - 1; i >= 0; i--) {
			const entry = this.list[i]!;
			if (entry.kind !== "REPLY" || entry.actor !== (actor || "leader")) continue;
			if (entry.status === "STREAMING" || entry.status === "DONE") return entry;
			return undefined;
		}
		return undefined;
	}

	private touch(entry: Entry): void {
		this.revisionValue += 1;
		if (entry.kind === "REPLY" && entry.body) this.lastReply = entry.body;
		this.emit();
	}

	private emit(): void {
		for (const listener of [...this.listeners]) listener();
	}
}
