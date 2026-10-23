import { text, type Payload } from "../bridge/wire.ts";
import type { Entry, TranscriptStore } from "./transcript-store.ts";

/**
 * 流式增量 → 转录卡。
 *
 * 只追加真正的新片段：同请求/同通道按 offset 校验，offset 以 UTF-16 code unit 计（与 JSON
 * 字符串一致）。若后端 offset 与本地长度不一致（丢帧/重连），代码不会丢掉正文：追加收到的
 * 文本并记录一次诊断，由下一份 state.refresh 对齐事实。
 *
 * retry 由 Java 生成新 requestId；旧卡保持 INCOMPLETE，新请求使用新卡，不会把 retry 正文
 * 续写进失败的那一条。
 */
export interface StreamDiagnostic {
	(message: string): void;
}

export class StreamReducer {
	private readonly store: TranscriptStore;
	private readonly diagnostic: StreamDiagnostic;
	private readonly offsets = new Map<string, number>();

	constructor(store: TranscriptStore, diagnostic: StreamDiagnostic) {
		this.store = store;
		this.diagnostic = diagnostic;
	}

	started(payload: Payload): void {
		const requestId = text(payload, "requestId");
		const actor = text(payload, "actor", "leader");
		if (!requestId) return;
		this.offsets.set(requestId, 0);
		this.store.streamEntry(requestId, actor, "REPLY");
	}

	delta(payload: Payload): void {
		const requestId = text(payload, "requestId");
		const channel = text(payload, "channel", "REPLY") === "THINKING" ? "THINKING" : "REPLY";
		const value = text(payload, "text");
		const offset = typeof payload.offset === "number" ? payload.offset : Number(payload.offset ?? 0);
		if (!requestId || !value) return;
		const key = `${requestId}:${channel}`;
		const length = this.offsets.get(key) ?? 0;
		if (Number.isFinite(offset) && offset !== length) {
			this.diagnostic(`Stream offset mismatch on ${channel} (expected ${length}, received ${offset}); appending without dropping text.`);
		}
		const entry = this.store.streamEntry(requestId, text(payload, "actor", "leader"),
			channel as "REPLY" | "THINKING");
		this.store.update(entry.id, (current) => ({ ...current, body: current.body + value, status: "STREAMING" }));
		this.offsets.set(key, length + value.length);
	}

	ended(payload: Payload): void {
		const requestId = text(payload, "requestId");
		if (!requestId) return;
		const failed = payload.failed === true;
		for (const kind of ["REPLY", "THINKING"] as const) {
			const id = `stream-${requestId}-${kind}`;
			const existing = this.store.entries().find((entry) => entry.id === id);
			if (!existing) continue;
			this.store.update(id, (current) => ({
				...current,
				status: failed ? "INCOMPLETE" : "DONE",
			}));
		}
		this.offsets.delete(`${requestId}:REPLY`);
		this.offsets.delete(`${requestId}:THINKING`);
	}

	/** 预览：参数块开始/追加/闭合；只影响工具卡显示，绝不代表执行。 */
	previewStarted(payload: Payload): void {
		const requestId = text(payload, "requestId");
		const blockIndex = numberField(payload, "blockIndex");
		const callId = text(payload, "callId");
		this.store.previewStarted(requestId, text(payload, "actor", "leader"), blockIndex, callId,
			text(payload, "name"), text(payload, "initialInputJson"));
	}

	previewDelta(payload: Payload): void {
		const requestId = text(payload, "requestId");
		const blockIndex = numberField(payload, "blockIndex");
		const fragment = text(payload, "fragment");
		if (!requestId || !fragment) return;
		this.store.previewDelta(requestId, blockIndex, fragment);
	}

	previewClosed(payload: Payload): void {
		this.store.previewClosed(text(payload, "requestId"), numberField(payload, "blockIndex"));
	}

	/** 取某请求当前已收到的回复长度（测试/诊断用）。 */
	received(requestId: string, channel: "REPLY" | "THINKING" = "REPLY"): number {
		return this.offsets.get(`${requestId}:${channel}`) ?? 0;
	}
}

function numberField(payload: Payload, field: string): number {
	const value = payload[field];
	if (typeof value === "number" && Number.isFinite(value)) return Math.trunc(value);
	if (typeof value === "string" && /^-?\d+$/.test(value)) return Number.parseInt(value, 10);
	return 0;
}

/** 供 Application：把 notice 的 actor 状态同步给 SessionStore 之外的视图。 */
export interface ToolNoticeTarget {
	toolNotice(actor: string, executionId: string, name: string, detail: string, state: string,
		elapsedMs: number, identity: Entry["tool"]): Entry;
}
