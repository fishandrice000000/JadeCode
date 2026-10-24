/**
 * 原始输入来源水位（Pi 补丁 P01/P02 的 Node 侧）。
 *
 * 为什么需要：审批只能在“用户看到申请之后”真正按下确认时生效。终端把一次 ESC 拆成两个
 * stdin 块（前置字节在旧块、尾字节在新块）时，整个序列必须算作旧输入。这里不解析任何
 * CSI/Kitty，只记录“第几个原始块”和“第几个解码序列”，由 Java 对比呈现水位做最终判断。
 *
 * 规则：
 * - 每个原始 stdin 块 receipt+1；若当前没有待派发块，记录为本序列起点；
 * - 每次 Pi 派发一个输入序列时，序列来源 = [最早未派发 receipt, 当前 receipt]；
 * - 派发结束清空起点，下一个块开启新序列；
 * - 同一块里多个按键共享同一 receipt，因此保守地一起被视为同源（不会让后半块变成新批准）。
 */
export interface SequenceSource {
	startReceipt: number;
	endReceipt: number;
	decodedSeq: number;
}

export class InputEvidence {
	private receipt = 0;
	private decoded = 0;
	private pendingStart: number | null = null;
	/** 最近一次 beginDispatch 收到的原始块 id；0 表示该路径没有来源（paste 等）。 */
	private lastChunk = 0;
	private current: SequenceSource | null = null;

	/** 由 ProcessTerminal 的 raw tap 在 StdinBuffer 之前调用。 */
	rawChunk(_data: string): void {
		this.receipt += 1;
		if (this.pendingStart === null) this.pendingStart = this.receipt;
	}

	/** Pi 即将把一个输入序列派发给 listeners/焦点组件。 */
	beginDispatch(chunk = 0): SequenceSource {
		// P06 给出“该序列第一个字节所在 raw 块”；没有来源时退回本块之前的保守水位。
		const start = chunk > 0 ? chunk : (this.pendingStart ?? this.receipt);
		this.lastChunk = chunk;
		this.decoded += 1;
		this.current = { startReceipt: start, endReceipt: this.receipt, decodedSeq: this.decoded };
		return this.current;
	}

	/** 派发完成；下一块重新开始一个序列。 */
	endDispatch(): void {
		this.current = null;
		this.pendingStart = null;
	}

	/** @returns 当前正在处理的序列来源；未处理时为 null。 */
	active(): SequenceSource | null {
		return this.current;
	}

	/** @returns 已收到的原始块数量；呈现证据使用它作为水位。 */
	rawWatermark(): number {
		return this.receipt;
	}

	/** @returns 当前序列的原始块 id（0 表示未知）。 */
	sequenceChunk(): number { return this.lastChunk; }

	/** @returns 已派发的序列数量。 */
	decodedSeq(): number {
		return this.decoded;
	}
}
