/**
 * 内部协议 v1 的 TypeScript 端结构校验。
 *
 * 只接受白名单类型与明确字段类型；未知字段忽略但不会被转发成业务动作，未知类型直接丢弃并
 * 报告。`seq`/`generation` 一律按十进制字符串处理：Java long 超出 JS 安全整数范围时仍不丢精度。
 *
 * 这个模块不依赖视图、终端或 Pi，因此可以在 node:test 里直接测真实字节解析结果。
 */

export const PROTOCOL_VERSION = 1;
export const MAX_FRAME_BYTES = 1024 * 1024;

export const COMMAND_TYPES = [
	"client.hello",
	"session.ready",
	"session.submit",
	"session.promoteBuffer",
	"session.new",
	"session.stop",
	"session.close",
	"session.list",
	"session.find",
	"session.resume",
	"session.rename",
	"session.thinking",
	"workspace.complete",
	"team.report",
	"settings.patch",
	"permission.select",
	"permission.prepareFullAccess",
	"permission.presented",
	"permission.cancelFullAccess",
	"permission.default",
	"approval.presented",
	"approval.answer",
	"terminal.suspend",
	"terminal.resume",
	"terminal.invalidate",
	"state.refresh",
	"client.shutdown",
	"payload.begin",
	"payload.chunk",
	"payload.end",
] as const;

export const EVENT_TYPES = [
	"server.hello",
	"server.bootstrap",
	"session.snapshot.begin",
	"session.snapshot.chunk",
	"session.snapshot.end",
	"command.result",
	"command.error",
	"stream.started",
	"stream.delta",
	"stream.preview.started",
	"stream.preview.delta",
	"stream.preview.closed",
	"stream.ended",
	"execution.notice",
	"display.clock",
	"state.snapshot",
	"message",
	"task.submitted",
	"task.result",
	"task.failed",
	"approval.requested",
	"approval.removed",
	"settings.saved",
	"settings.failed",
	"diagnostic",
	"server.stopped",
	"client.detached",
	"payload.begin",
	"payload.chunk",
	"payload.end",
] as const;

export type CommandType = (typeof COMMAND_TYPES)[number];
export type EventType = (typeof EVENT_TYPES)[number];

export type Payload = Record<string, unknown>;

export interface WireMessage {
	version: number;
	connectionId: string;
	/** 十进制字符串；比较时用 BigInt。 */
	seq: string;
	type: string;
	commandId: string | null;
	sessionId: string | null;
	generation: string | null;
	payload: Payload;
}

/** 协议/结构错误；调用方决定是否继续（可恢复）还是收口。 */
export class ProtocolViolation extends Error {
	constructor(message: string) {
		super(message);
		this.name = "ProtocolViolation";
	}
}

/**
 * 解析一行（不含换行）为消息。
 * @throws ProtocolViolation JSON/版本/类型/字段类型不合法
 */
export function parseFrame(line: string): WireMessage {
	let raw: unknown;
	try {
		raw = JSON.parse(line);
	} catch (error) {
		throw new ProtocolViolation(`Frame is not valid JSON: ${(error as Error).message}`);
	}
	if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
		throw new ProtocolViolation("Frame must be a JSON object");
	}
	const node = raw as Record<string, unknown>;
	if (node.version !== PROTOCOL_VERSION) {
		throw new ProtocolViolation(`Unsupported protocol version: ${String(node.version)}`);
	}
	const type = requireString(node, "type", false) as string;
	if (!isKnownType(type)) throw new ProtocolViolation(`Unknown message type: ${type}`);
	const seq = requireDecimal(node, "seq", false)!;
	const connectionId = requireString(node, "connectionId", true) ?? "";
	const commandId = requireString(node, "commandId", true);
	const sessionId = requireString(node, "sessionId", true);
	const generation = requireDecimal(node, "generation", true);
	const payload = node.payload;
	if (typeof payload !== "object" || payload === null || Array.isArray(payload)) {
		throw new ProtocolViolation("payload must be an object");
	}
	return { version: PROTOCOL_VERSION, connectionId, seq, type, commandId, sessionId, generation, payload: payload as Payload };
}

export function isKnownType(type: string): boolean {
	return (COMMAND_TYPES as readonly string[]).includes(type) || (EVENT_TYPES as readonly string[]).includes(type);
}

function requireString(node: Record<string, unknown>, field: string, nullable: boolean): string | null {
	const value = node[field];
	if (value === undefined || value === null) {
		if (nullable) return null;
		throw new ProtocolViolation(`${field} is required`);
	}
	if (typeof value !== "string") throw new ProtocolViolation(`${field} must be a string`);
	return value;
}

function requireDecimal(node: Record<string, unknown>, field: string, nullable: boolean): string | null {
	const value = requireString(node, field, nullable);
	if (value === null) return null;
	if (!/^\d+$/.test(value)) throw new ProtocolViolation(`${field} must be a decimal integer string`);
	return value;
}

/** 取字符串字段；缺失/类型不符时按需返回默认值。 */
export function text(payload: Payload, field: string, fallback = ""): string {
	const value = payload[field];
	return typeof value === "string" ? value : fallback;
}

export function numberValue(payload: Payload, field: string, fallback = 0): number {
	const value = payload[field];
	if (typeof value === "number" && Number.isFinite(value)) return value;
	if (typeof value === "string" && /^-?\d+$/.test(value)) return Number.parseInt(value, 10);
	return fallback;
}

export function booleanValue(payload: Payload, field: string, fallback = false): boolean {
	const value = payload[field];
	return typeof value === "boolean" ? value : fallback;
}

export function objectValue(payload: Payload, field: string): Payload | undefined {
	const value = payload[field];
	if (typeof value === "object" && value !== null && !Array.isArray(value)) return value as Payload;
	return undefined;
}

export function arrayValue(payload: Payload, field: string): unknown[] {
	const value = payload[field];
	return Array.isArray(value) ? value : [];
}
