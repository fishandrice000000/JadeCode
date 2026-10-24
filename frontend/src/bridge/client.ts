import { connect, type Socket } from "node:net";
import { Buffer } from "node:buffer";
import { MAX_FRAME_BYTES, parseFrame, ProtocolViolation, type Payload, type WireMessage } from "./wire.ts";

/**
 * 到 Java 后端的唯一控制连接。
 *
 * 职责：UTF-8 NDJSON 分帧、单向 seq、命令/结果关联、EOF/错误通知。它不操作终端、不渲染、
 * 不解析业务载荷。分帧必须按字节累积到 LF：一次 read 可能只有半帧，也可能包含多帧；UTF-8
 * 多字节字符同样可能被切开，所以用 Buffer 拼接后再严格解码。
 */
export interface BackendClientOptions {
	socketPath: string;
	onEvent: (message: WireMessage) => void;
	onClosed: (reason: string) => void;
	/** 协议/传输层致命错误：保留第一次原因并进入统一收口。 */
	onFatal: (reason: string, error?: Error) => void;
}

export interface CommandResult {
	ok: boolean;
	type: string;
	payload: Payload;
	code?: string;
	message?: string;
}

export class BackendClient {
	private readonly options: BackendClientOptions;
	private socket: Socket | undefined;
	private buffer: Buffer<ArrayBufferLike> = Buffer.alloc(0);
	private seq = 0n;
	private connectionId = "";
	private closed = false;
	private readonly pending = new Map<string, { resolve: (value: CommandResult) => void; reject: (error: Error) => void; onCommandError?: (message: string) => void }>();
	private decoder = new TextDecoder("utf-8", { fatal: true });

	constructor(options: BackendClientOptions) {
		this.options = options;
	}

	/** @returns 后端分配的连接身份；握手后可用。 */
	currentConnectionId(): string {
		return this.connectionId;
	}

	/** @returns 是否已关闭（EOF/主动关闭/错误）。 */
	isClosed(): boolean {
		return this.closed;
	}

	/** 建立 socket；真正的协议握手由 {@link hello} 完成。 */
	async open(): Promise<void> {
		await new Promise<void>((resolve, reject) => {
			const socket = connect(this.options.socketPath);
			this.socket = socket;
			socket.setNoDelay(true);
			socket.once("connect", () => resolve());
			socket.once("error", (error) => {
				reject(error);
				this.options.onFatal(`Socket error: ${error.message}`, error);
				this.fail(`Socket error: ${error.message}`);
			});
			socket.on("data", (chunk) => this.ingest(chunk));
			socket.on("end", () => this.fail("The backend closed the connection"));
			socket.on("close", () => this.fail("The backend connection closed"));
		});
	}

	/**
	 * 发送 hello 并等待 server.hello。
	 * @param credential 一次性凭据（仅来自子进程环境）
	 */
	async hello(credential: string, buildId: string): Promise<WireMessage> {
		const wait = this.waitFor("server.hello", 10_000);
		this.send("client.hello", {
			protocolVersion: 1,
			buildId,
			credential,
			frontendCapabilities: ["snapshot", "approval-evidence", "terminal-progress", "path-completion"],
		}, {});
		const hello = await wait;
		this.connectionId = hello.connectionId;
		return hello;
	}

	/** 发送一条命令并等待 command.result/command.error。 */
	command(type: string, payload: Payload, identity: { sessionId?: string | null; generation?: string | null } = {}, hooks?: { onCommandId?: (id: string) => void; onCommandError?: (message: string) => void }): Promise<CommandResult> {
		const commandId = `c-${++this.commandCounter}`;
		hooks?.onCommandId?.(commandId);
		const promise = new Promise<CommandResult>((resolve, reject) => {
			this.pending.set(commandId, { resolve, reject, onCommandError: hooks?.onCommandError });
		});
		this.send(type, payload, { commandId, sessionId: identity.sessionId ?? null, generation: identity.generation ?? null });
		return promise;
	}

	/** 只发送不等待答复的帧（payload 分块、关闭等）。 */
	send(type: string, payload: Payload, identity: { commandId?: string | null; sessionId?: string | null; generation?: string | null }): void {
		if (this.closed || !this.socket) throw new Error("The backend connection is closed");
		const frame = {
			version: 1,
			connectionId: this.connectionId,
			seq: String(++this.seq),
			type,
			commandId: identity.commandId ?? null,
			sessionId: identity.sessionId ?? null,
			generation: identity.generation ?? null,
			payload,
		};
		const bytes = Buffer.from(`${JSON.stringify(frame)}\n`, "utf8");
		if (bytes.length > MAX_FRAME_BYTES) throw new Error("An outbound frame exceeds the protocol limit");
		this.socket.write(bytes);
	}

	/** 主动关闭；不再接受新命令，未完成命令收到真实错误。 */
	close(): void {
		this.fail("The frontend closed the connection");
	}

	private commandCounter = 0;

	private ingest(chunk: Buffer): void {
		if (this.closed) return;
		this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk]);
		while (!this.closed) {
			const newline = this.buffer.indexOf(0x0a);
			if (newline < 0) {
				if (this.buffer.length > MAX_FRAME_BYTES) {
					this.options.onFatal("An inbound frame exceeded the protocol limit");
					this.fail("An inbound frame exceeded the protocol limit");
				}
				return;
			}
			// 限额按“单帧 UTF-8 字节 + LF”计算；带 LF 的完整超长帧也必须拒绝。
			if (newline + 1 > MAX_FRAME_BYTES) {
				this.options.onFatal("An inbound frame exceeded the protocol limit");
				this.fail("An inbound frame exceeded the protocol limit");
				return;
			}
			const lineBytes = this.buffer.subarray(0, newline);
			this.buffer = this.buffer.subarray(newline + 1);
			let line: string;
			try {
				line = this.decoder.decode(lineBytes);
			} catch {
				this.options.onFatal("The backend sent invalid UTF-8");
				this.fail("The backend sent invalid UTF-8");
				return;
			}
			if (!line.trim()) continue;
			let message: WireMessage;
			try {
				message = parseFrame(line);
			} catch (error) {
				const reason = error instanceof ProtocolViolation ? error.message : String(error);
				this.options.onFatal(`Invalid backend message: ${reason}`);
				this.fail(`Invalid backend message: ${reason}`);
				return;
			}
			this.dispatch(message);
			// dispatch 可能因致命帧关闭连接；本批剩余字节不能再进入业务派发。
		}
	}

	private dispatch(message: WireMessage): void {
		if (message.type === "command.result" || message.type === "command.error") {
			const id = message.commandId;
			if (!id) {
				// 握手/ready 的错误可能没有 commandId；不能再静默丢弃。
				const detail = message.type === "command.error"
					? `${String(message.payload.code ?? "ERROR")}: ${String(message.payload.message ?? "")}`
					: "A command result arrived without a commandId";
				this.options.onFatal(detail);
				this.fail(detail);
				return;
			}
			const waiter = this.pending.get(id);
			if (!waiter) {
				// 已关闭/已超时的答复：记录真实错误类别，但不影响连接。
				this.options.onFatal(`Orphan command response: ${String(message.payload.code ?? message.type)}`);
				return;
			}
			this.pending.delete(id);
			if (message.type === "command.error") {
				// 同步通知：同一批帧里后续 settings.failed 之前先移除对应保存项。
				waiter.onCommandError?.(typeof message.payload.message === "string" ? message.payload.message : "The command failed");
				waiter.resolve({
					ok: false,
					type: message.type,
					payload: message.payload,
					code: typeof message.payload.code === "string" ? message.payload.code : undefined,
					message: typeof message.payload.message === "string" ? message.payload.message : undefined,
				});
				return;
			}
			waiter.resolve({ ok: true, type: message.type, payload: message.payload });
			return;
		}
		if (message.type === "server.hello") this.connectionId = message.connectionId;
		for (const waiter of [...this.waiters]) waiter(message);
		this.options.onEvent(message);
	}

	/** 等待某个事件类型（握手用）；超时返回真实错误。 */
	private waitFor(type: string, timeoutMs: number): Promise<WireMessage> {
		return new Promise((resolve, reject) => {
			const timer = setTimeout(() => {
				cleanup();
				reject(new Error(`Timed out waiting for ${type}`));
			}, timeoutMs);
			const listener = (message: WireMessage): void => {
				if (message.type !== type) return;
				cleanup();
				resolve(message);
			};
			const cleanup = (): void => {
				clearTimeout(timer);
				this.waiters = this.waiters.filter((item) => item !== listener);
			};
			this.waiters.push(listener);
		});
	}

	private waiters: Array<(message: WireMessage) => void> = [];

	private fail(reason: string): void {
		if (this.closed) return;
		this.closed = true;
		this.buffer = Buffer.alloc(0);
		try { this.socket?.destroy(); } catch { /* 已断开。 */ }
		for (const waiter of this.pending.values()) waiter.reject(new Error(reason));
		this.pending.clear();
		this.options.onClosed(reason);
	}
}
