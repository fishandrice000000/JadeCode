import { ProcessTerminal, type Terminal } from "@earendil-works/pi-tui";

/**
 * 终端适配：唯一 stdout 写者 + 串行写队列 + 帧围栏 + 背压门控。
 *
 * Pi 的 `Terminal.write` 返回 void，而 `ProcessTerminal` 的其它输出方法直接写
 * `process.stdout`。如果只包一层 `write`，帧内容（排队）与光标移动（直接写）会交错，屏幕会
 * 花。因此本类自己实现全部“写终端”的方法，并统一走同一个队列；输入、尺寸、raw mode 仍委托
 * 给固定版 ProcessTerminal（含 Kitty 协商），不复制输入解码。
 *
 * 背压：队列已有未完成写入时，`canRender()` 返回 false，Pi 的 renderGate 会保留 dirty 帧而
 * 不继续生成整屏差分；`drain` 到达后唤醒一次最新渲染。围栏：一帧的写全部完成后才回调
 * `afterFrame`，用于登记审批呈现证据（不代表终端像素已显示）。
 */

const MAX_PENDING_CHARS = 256 * 1024;
const MAX_PENDING_WRITES = 4;
const PROGRESS_ACTIVE = "\x1b]9;4;3\x07";
const PROGRESS_CLEAR = "\x1b]9;4;0\x07";

export interface JadeTerminalOptions {
	onWritable: () => void;
	onWriteFailure: (message: string) => void;
	onRawInput: (data: string) => void;
	/** Pi 派发一个输入序列前后的钩子；用于把来源水位绑定到这次按键。 */
	onInputStart?: () => void;
	onInputEnd?: () => void;
}

type QueueItem =
	| { kind: "data"; data: string; chars: number }
	| { kind: "sentinel"; callback: () => void };

export class JadeTerminal implements Terminal {
	private readonly inner: ProcessTerminal;
	private readonly options: JadeTerminalOptions;
	private readonly queue: QueueItem[] = [];
	private pendingWrites = 0;
	private queuedChars = 0;
	private blocked = false;
	private draining = false;
	private progressTimer: NodeJS.Timeout | undefined;
	private progressActive = false;
	private failureReported = false;

	constructor(options: JadeTerminalOptions) {
		this.options = options;
		this.inner = new ProcessTerminal();
		this.inner.setRawInputTap((data) => this.options.onRawInput(data));
		process.stdout.on("error", (error) => this.fail(`Terminal output failed: ${error.message}`));
	}

	/** 给 Pi 的 renderGate 使用：false 时不要生成新帧。 */
	canRender(): boolean {
		return !this.blocked && this.pendingWrites < MAX_PENDING_WRITES && this.queuedChars < MAX_PENDING_CHARS;
	}

	/** 安装自定义 Esc 重组超时（0 = 沿用 Pi 的自动策略）。 */
	setEscapeTimeoutMs(milliseconds: number): void {
		this.inner.setEscapeTimeoutMs(milliseconds > 0 ? milliseconds : undefined);
	}

	/** 一帧的全部写入完成后再回调；用于审批呈现围栏。 */
	afterFrame(callback: () => void): void {
		this.queue.push({ kind: "sentinel", callback });
		this.pump();
	}

	start(onInput: (data: string) => void, onResize: () => void): void {
		this.inner.start((data) => {
			this.options.onInputStart?.();
			try {
				onInput(data);
			} finally {
				this.options.onInputEnd?.();
			}
		}, onResize);
	}

	stop(): void {
		this.clearProgress();
		this.inner.stop();
	}

	drainInput(maxMs?: number, idleMs?: number): Promise<void> {
		return this.inner.drainInput(maxMs, idleMs);
	}

	get columns(): number { return this.inner.columns; }

	get rows(): number { return this.inner.rows; }

	get kittyProtocolActive(): boolean { return this.inner.kittyProtocolActive; }

	write(data: string): void {
		if (!data) return;
		this.queue.push({ kind: "data", data, chars: data.length });
		this.queuedChars += data.length;
		this.pump();
	}

	moveBy(lines: number): void {
		if (lines > 0) this.write(`\x1b[${lines}B`);
		else if (lines < 0) this.write(`\x1b[${-lines}A`);
	}

	hideCursor(): void { this.write("\x1b[?25l"); }

	showCursor(): void { this.write("\x1b[?25h"); }

	clearLine(): void { this.write("\x1b[K"); }

	clearFromCursor(): void { this.write("\x1b[J"); }

	clearScreen(): void { this.write("\x1b[2J\x1b[H"); }

	setTitle(title: string): void { this.write(`\x1b]0;${title}\x07`); }

	/**
	 * OSC 7501 程序状态：固定 Pi 只在终端确认支持时发送；Node 侧没有可靠的支持探测，
	 * 因此保持 no-op，而不是向任意终端发送未确认的协议。
	 */
	setProgramStatus(_status: unknown): void { /* 未探测支持，不发送。 */ }

	/** 真实活跃任务才登记 progress；关闭/空闲/暂退清除，避免 tab 永远转圈。 */
	setProgress(active: boolean): void {
		if (active === this.progressActive) return;
		this.progressActive = active;
		this.write(active ? PROGRESS_ACTIVE : PROGRESS_CLEAR);
		if (active) {
			this.progressTimer = setInterval(() => this.write(PROGRESS_ACTIVE), 1000);
			this.progressTimer.unref?.();
		} else {
			this.clearProgress();
		}
	}

	private clearProgress(): void {
		if (this.progressTimer) {
			clearInterval(this.progressTimer);
			this.progressTimer = undefined;
		}
		if (this.progressActive) {
			this.progressActive = false;
			this.write(PROGRESS_CLEAR);
		}
	}

	/** 串行把队列写进 stdout；返回 false 时停止继续写，等 drain 再唤醒。 */
	private pump(): void {
		if (this.draining) return;
		while (this.queue.length > 0) {
			if (this.blocked) return;
			const item = this.queue[0]!;
			if (item.kind === "sentinel") {
				if (this.pendingWrites > 0) return; // 等这一帧的写完成
				this.queue.shift();
				try { item.callback(); } catch { /* 围栏回调失败不能破坏输出顺序。 */ }
				continue;
			}
			this.queue.shift();
			this.queuedChars -= item.chars;
			this.pendingWrites += 1;
			const accepted = process.stdout.write(item.data, () => {
				this.pendingWrites -= 1;
				if (!this.blocked) this.pump();
			});
			if (!accepted && !this.blocked) {
				this.blocked = true;
				// 只注册一次 drain；到达后解除阻塞并唤醒一次最新渲染（不轮询）。
				process.stdout.once("drain", () => {
					this.blocked = false;
					try { this.options.onWritable(); } finally { this.pump(); }
				});
				return;
			}
		}
	}

	private fail(message: string): void {
		if (this.failureReported) return;
		this.failureReported = true;
		this.options.onWriteFailure(message);
	}
}
