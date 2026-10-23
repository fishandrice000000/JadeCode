import type { InputEvidence } from "./input-evidence.ts";

/**
 * 已提交帧证据：把“这一帧的按钮几何/revision”绑定到真实 stdout 写入完成之后。
 *
 * 注意边界：stdout 完成只证明本进程把字节交给了终端流，不证明终端像素已刷新，更不证明人类
 * 看过。因此它只用于“拒绝更旧的按键”，不做安全承诺之外的推断。
 *
 * 任何使旧几何失效的事件（resize、双屏切换、暂停、主题重排、换请求）都必须递增 epoch 并
 * 作废旧证据；重新渲染的新帧会带新 frameId。
 */
export interface FrameSnapshot {
	frameId: number;
	epoch: number;
	columns: number;
	rows: number;
	rawWatermark: number;
	decodedWatermark: number;
}

export interface FramePublisher {
	(frame: FrameSnapshot): void;
}

export class PresentedEvidence {
	private frameId = 0;
	private epoch = 0;
	private readonly evidence: InputEvidence;
	private readonly publish: FramePublisher;
	private readonly dirty: { callback: (frame: FrameSnapshot) => void }[] = [];

	constructor(evidence: InputEvidence, publish: FramePublisher) {
		this.evidence = evidence;
		this.publish = publish;
	}

	/** @returns 当前终端代次；resize/切换后必须变化。 */
	currentEpoch(): number {
		return this.epoch;
	}

	/** 作废旧证据并请求重新呈现（不改变 Agent 任务）。 */
	invalidate(): number {
		this.epoch += 1;
		return this.epoch;
	}

	/**
	 * 一帧已写入 stdout 完成：递增 frameId，生成快照并交给订阅者（审批控制器会发
	 * approval.presented）。
	 *
	 * @param layout 该帧渲染时记录的几何（尺寸/按钮矩形摘要由订阅者自己保存）
	 */
	published(columns: number, rows: number, deliver: (frame: FrameSnapshot) => void): void {
		this.frameId += 1;
		const frame: FrameSnapshot = {
			frameId: this.frameId,
			epoch: this.epoch,
			columns,
			rows,
			rawWatermark: this.evidence.rawWatermark(),
			decodedWatermark: this.evidence.decodedSeq(),
		};
		deliver(frame);
		for (const item of [...this.dirty]) item.callback(frame);
		this.dirty.length = 0;
		this.publish(frame);
	}

	/** 当前帧身份（给需要等待下一帧的调用方）。 */
	currentFrameId(): number {
		return this.frameId;
	}
}
