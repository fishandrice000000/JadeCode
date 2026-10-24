import { matchesKey, type TuiMouseEvent } from "@earendil-works/pi-tui";
import { isConfirmationPress } from "../terminal/input-kind.ts";
import type { KeymapAdapter } from "../editor/keymap-adapter.ts";
import { arrayValue, numberValue, text, type Payload } from "../bridge/wire.ts";
import type { BackendClient } from "../bridge/client.ts";
import type { InputEvidence, SequenceSource } from "../terminal/input-evidence.ts";
import type { FrameSnapshot, PresentedEvidence } from "../terminal/presented-evidence.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import { ApprovalView, type ApprovalChoice } from "./approval-view.ts";

/**
 * 审批控制器：队列、无默认选择、新鲜输入证据、陈旧错误恢复。
 *
 * 流程：
 * 1. approval.requested → 载入完整参数（大参数完成 blob 校验后才可选中）→ 显示；
 * 2. 一帧写入 stdout 完成 → 发 approval.presented（frameId/epoch/矩形/水位/revision）；
 * 3. 用户真实按键/鼠标按下 → 发 approval.answer（同一帧证据）；
 * 4. Java 拒绝旧证据 → 显示可恢复错误并等待下一帧，绝不把旧 Enter 应用到下一份申请。
 *
 * Node 不做权限判断、不保存规则、不上传替代参数：它只把一次明确选择送回 Java。
 */
export interface ApprovalCallbacks {
	client: () => BackendClient | null;
	evidence: InputEvidence;
	presented: PresentedEvidence;
	/** Java 当前权限 revision（来自快照）；仍以 Java 校验为准。 */
	revision: () => string;
	identity: () => { sessionId: string; generation: string };
	requestRender: () => void;
	notify: (message: string, error: boolean) => void;
	/** 终端写失败/失效后不得再发布批准证据。 */
	terminalHealthy: () => boolean;
	/** 审批上下文也必须使用同一份用户覆盖键表。 */
	keymap: () => KeymapAdapter;
	teamActive: () => boolean;
	actorName: (actor: string) => string;
}

export interface ApprovalDisplayData {
	requestId: string;
	tool: string;
	scope: string;
	parameters: string;
	actor: string;
	loading: boolean;
}

interface PendingApproval {
	requestId: string;
	actor: string;
	runId: string;
	revision: string;
	tool: string;
	scope: string;
	parameters: string;
	loading: boolean;
	blobRef: string;
	presentedFrame: number;
	presentedEpoch: number;
	presentedDigest: string;
	view: ApprovalView;
	// 选中/确认前必须有一帧成功提交的呈现证据。
	selectable: boolean;
	confirming: boolean;
	presenting: boolean;
	presentationFailures: number;
	/** 呈现代次；invalidate/切换请求后递增，迟到回执不能复活旧证据。 */
	presentationToken: number;
	/** 最近一次发送的呈现布局摘要；用于识别 ACK 在途时的布局变化。 */
	inflightDigest: string;
	/** 用户在呈现回执到达前按下 Enter：等该帧证据成立后立即答复，不要求再按一次。 */
	pendingChoice: { choice: number; keyAction: string; mouse: { type: string; button: string; x: number; y: number } | undefined; source: SequenceSource } | null;
}

export class ApprovalController {
	private readonly callbacks: ApprovalCallbacks;
	private readonly queue: PendingApproval[] = [];
	private current: PendingApproval | undefined;
	private theme: ResolvedTheme | undefined;
	private lastPresented: { requestId: string; frameId: number } | undefined;

	constructor(callbacks: ApprovalCallbacks) {
		this.callbacks = callbacks;
	}

	/** 由 Application 在 PresentedEvidence 创建后注入（两者互相依赖）。 */
	setPresented(presented: PresentedEvidence): void {
		(this.callbacks as { presented: PresentedEvidence }).presented = presented;
	}

	setTheme(theme: ResolvedTheme): void {
		this.theme = theme;
		this.current?.view.setTheme(theme);
	}

	active(): ApprovalView | undefined { return this.current?.view; }

	hasPending(): boolean { return this.current !== undefined; }

	/** approval.requested：登记队列项；参数可能需要等 blob 组装。 */
	requested(payload: Payload, resolveBlob: (ref: string) => string | undefined): void {
		const requestId = text(payload, "requestId");
		if (!requestId) return;
		const blobRef = text(payload, "inputBlobRef");
		let parameters = text(payload, "inputJson", "{}");
		let loading = false;
		if (blobRef) {
			const resolved = resolveBlob(blobRef);
			if (resolved === undefined) loading = true;
			else parameters = resolved;
		}
		const suggestion = text(payload, "suggestionId");
		const choices: ApprovalChoice[] = [
			{ index: 0, label: "Allow once", enabled: true },
			{ index: 1, label: "Allow similar", enabled: suggestion.length > 0 },
			{ index: 2, label: "Deny", enabled: true },
		];
		const item: PendingApproval = {
			requestId,
			actor: text(payload, "actor"),
			runId: text(payload, "runId"),
			revision: text(payload, "revision", this.callbacks.revision()),
			tool: text(payload, "tool"),
			scope: text(payload, "suggestionScope"),
			parameters,
			loading,
			blobRef,
			presentedFrame: -1,
			presentedEpoch: -1,
			presentedDigest: "",
			view: new ApprovalView(this.theme!, choices),
			selectable: false,
			confirming: false,
			presenting: false,
			presentationFailures: 0,
			presentationToken: 0,
			inflightDigest: "",
			pendingChoice: null,
		};
		if (this.current) this.queue.push(item);
		else {
			this.current = item;
		}
		this.callbacks.requestRender();
	}

	/**
	 * blob 组装完成后按引用补齐**对应**申请；不同请求的大参数不会互相串写。
	 */
	blobReady(ref: string, value: string): void {
		let changed = false;
		for (const item of [this.current, ...this.queue]) {
			if (!item || !item.loading || item.blobRef !== ref) continue;
			item.parameters = value;
			item.loading = false;
			item.selectable = false;
			item.presentedDigest = "";
			item.view.invalidate();
			changed = true;
		}
		if (changed) this.callbacks.requestRender();
	}

	/** @returns 当前正在显示的申请资料；渲染与答复共用这一份记录。 */
	currentData(): ApprovalDisplayData | null {
		const item = this.current;
		if (!item) return null;
		return {
			requestId: item.requestId,
			tool: item.tool,
			scope: item.scope,
			parameters: item.parameters,
			actor: item.actor,
			loading: item.loading,
		};
	}

	/** 打开下一份排队申请（前一份答复/撤回后）。 */
	next(): void {
		this.current = this.queue.shift();
		this.callbacks.requestRender();
	}

	/** approval.removed：只移除对应申请；其它申请不受影响。 */
	removed(payload: Payload): void {
		const id = text(payload, "requestId");
		const index = this.queue.findIndex((item) => item.requestId === id);
		if (index >= 0) this.queue.splice(index, 1);
		if (this.current?.requestId === id) {
			this.current = this.queue.shift();
			this.callbacks.requestRender();
			return;
		}
		this.callbacks.requestRender();
	}

	/** 终端 resize/模式切换/暂停：Java 侧证据作废，Node 重新等待下一帧呈现。 */
	invalidate(): void {
		if (!this.current) return;
		// 作废旧证据并让在途回执失效；下一帧必须重新呈现。
		this.current.presentationToken += 1;
		this.current.presenting = false;
		this.current.pendingChoice = null;
		this.current.selectable = false;
		this.current.presentedFrame = -1;
		this.current.presentedEpoch = -1;
		this.current.presentedDigest = "";
		this.callbacks.requestRender();
	}

	/**
	 * 布局/可见性变化：作废当前证据、在途 ACK 与挂起确认。
	 * 与选择色无关；选中变化不会走到这里。
	 */
	private clearEvidence(item: PendingApproval): void {
		item.presentationToken += 1;
		item.presenting = false;
		item.inflightDigest = "";
		item.selectable = false;
		item.presentedFrame = -1;
		item.presentedEpoch = -1;
		item.presentedDigest = "";
		item.pendingChoice = null;
	}

	/**
	 * 每帧 stdout 完成：为当前申请登记呈现证据。
	 * 参数仍在加载或审批已答复时跳过。
	 */
	onFrame(frame: FrameSnapshot): void {
		const item = this.current;
		if (!item || item.loading || item.confirming || !this.theme) return;
		if (!this.callbacks.terminalHealthy() || this.callbacks.presented.currentEpoch() !== frame.epoch) return;
		const layout = item.view.lastLayout();
		if (!layout) return;
		if (layout.rects.length === 0) {
			// 按钮不可见/几何不可用：旧证据与挂起 Enter 都不能在下一帧被自动采用。
			if (item.selectable || item.presenting || item.pendingChoice) this.clearEvidence(item);
			return;
		}
		// 布局移动/参数或可见性变化会改变按钮几何；必须作废旧证据与在途 ACK。
		// 只改变选中颜色时 digest 不变，因此快速 Down+Enter 仍可用同一份证据。
		if (item.presenting) {
			if (item.inflightDigest === layout.digest) return;
			this.clearEvidence(item);
		}
		const layoutChanged = item.presentedDigest !== "" && item.presentedDigest !== layout.digest;
		if (layoutChanged) this.clearEvidence(item);
		// 同一申请在同一 epoch 下已经有一份成功呈现证据：后续计时/状态重绘不再重复登记。
		if (item.selectable && item.presentedDigest === layout.digest && item.presentedEpoch === frame.epoch) return;
		const client = this.callbacks.client();
		if (!client) return;
		item.presenting = true;
		item.inflightDigest = layout.digest;
		const token = item.presentationToken;
		const rawWatermark = this.callbacks.evidence.rawWatermark();
		const decodedWatermark = this.callbacks.evidence.decodedSeq();
		client.command("approval.presented", {
			requestId: item.requestId,
			runId: item.runId,
			revision: item.revision,
			frameId: String(frame.frameId),
			terminalEpoch: frame.epoch,
			columns: layout.columns,
			rows: layout.rows,
			actionRects: layout.rects,
			layoutDigest: layout.digest,
			rawReceiptWatermark: rawWatermark,
			decodedWatermark,
		}, this.callbacks.identity()).then((result) => {
			// 失效/切换后迟到的回执不能重新启用旧 epoch/旧帧。
			if (item.presentationToken !== token || this.current !== item || item.loading) return;
			item.presenting = false;
			if (!result.ok) {
				item.selectable = false;
				item.pendingChoice = null;
				item.presentationFailures += 1;
				// 只在第一次失败提示一次；提示本身可能触发一次重绘，但不会在回调里再排重绘。
				// 后续自然帧仍会尝试；持续失败时等待用户输入（Enter/方向键）或 resize 触发恢复。
				if (item.presentationFailures === 1) {
					this.callbacks.notify(result.message ?? "The approval layout was rejected; it will be presented again on the next frame.", true);
				}
				return;
			}
			item.selectable = true;
			item.presentedFrame = frame.frameId;
			item.presentedEpoch = frame.epoch;
			item.presentedDigest = layout.digest;
			item.presentationFailures = 0;
			// 成功呈现不请求重绘：否则会再次产生帧与 presented，形成循环。
			const pending = item.pendingChoice;
			item.pendingChoice = null;
			if (pending) this.sendAnswer(item, pending.choice, pending.keyAction, pending.mouse, pending.source);
		}).catch(() => {
			if (item.presentationToken === token) item.presenting = false;
		});
	}

	/**
	 * 优先级输入：只在有待答申请时消费审批相关按键。
	 * @returns true 表示已由审批处理（不再交给编辑器/页面）。
	 */
	handleInput(data: string): boolean {
		const item = this.current;
		if (!item) return false;
		if (!isConfirmationPress(data)) {
			// release/repeat/paste 既不改变选择，也不构成确认；仍消费，避免落到编辑器。
			return true;
		}
		const keymap = this.callbacks.keymap();
		if (keymap.matches("approval.previous", data)) {
			item.view.move(-1);
			// 选择色不属于按钮几何：保持现有呈现证据，允许立即 Enter。
			this.callbacks.requestRender();
			return true;
		}
		if (keymap.matches("approval.next", data)) {
			item.view.move(1);
			this.callbacks.requestRender();
			return true;
		}
		if (keymap.matches("approval.pageUp", data)) {
			item.view.scrollBy(-5);
			this.callbacks.requestRender();
			return true;
		}
		if (keymap.matches("approval.pageDown", data)) {
			item.view.scrollBy(5);
			this.callbacks.requestRender();
			return true;
		}
		if (keymap.matches("approval.deny", data)) {
			this.answer(2, { keyAction: "approval.deny", mouse: undefined });
			return true;
		}
		if (keymap.matches("approval.confirm", data)) {
			const choice = item.view.choice();
			if (choice < 0) {
				// 无默认：Enter 无选择时不答复。
				return true;
			}
			this.answer(choice, { keyAction: "approval.confirm", mouse: undefined });
			return true;
		}
		// 审批期间普通字符不进入草稿（页面优先于编辑器）。
		return true;
	}

	/** 鼠标：只接受新鲜左键按下命中同帧按钮（Pi 已解码坐标）。 */
	handleMouse(event: TuiMouseEvent): boolean {
		const item = this.current;
		if (!item) return false;
		if (event.type !== "press" || event.button !== "left") return false;
		const choice = item.view.hit(event.x, event.y);
		if (choice < 0) return false;
		item.view.setChoice(choice);
		this.answer(choice, {
			keyAction: "",
			mouse: { type: "press", button: "left", x: event.x, y: event.y },
		});
		return true;
	}

	private answer(choice: number, input: { keyAction: string; mouse: { type: string; button: string; x: number; y: number } | undefined }): void {
		const item = this.current;
		if (!item || item.confirming) return;
		const source: SequenceSource | null = this.callbacks.evidence.active();
		if (!source) {
			this.callbacks.notify("Input evidence is unavailable; press again.", true);
			return;
		}
		if (!item.selectable || item.presentedFrame < 0 || item.presentedEpoch < 0) {
			if (item.loading) {
				this.callbacks.notify("The complete approval parameters are still loading; press again after they appear.", true);
				return;
			}
			// 画面已渲染但围栏/回执尚未完成：把这次新鲜 Enter 挂起，证据成立后立即答复。
			// 不要求用户再按一次，也不放松 Java 对帧/epoch 的校验。
			item.pendingChoice = { choice, keyAction: input.keyAction, mouse: input.mouse, source };
			this.callbacks.requestRender();
			return;
		}
		this.sendAnswer(item, choice, input.keyAction, input.mouse, source);
	}

	/** 用已成立的呈现证据发送答复；供正常路径与挂起的快速确认共用。 */
	private sendAnswer(item: PendingApproval, choice: number,
			keyAction: string, mouse: { type: string; button: string; x: number; y: number } | undefined,
			source: SequenceSource): void {
		const client = this.callbacks.client();
		if (!client) return;
		const layout = item.view.lastLayout();
		if (!layout) return;
		item.confirming = true;
		client.command("approval.answer", {
			requestId: item.requestId,
			runId: item.runId,
			revision: item.revision,
			choice,
			evidence: {
				phase: "PRESS",
				revision: Number(item.revision),
				frameId: String(item.presentedFrame),
				terminalEpoch: item.presentedEpoch,
				rawStartReceipt: source.startReceipt,
				rawEndReceipt: source.endReceipt,
				decodedSeq: source.decodedSeq,
				keyAction,
				mouse: mouse ?? null,
			},
		}, this.callbacks.identity()).then((result) => {
			item.confirming = false;
			if (result.ok) {
				// 真正移除由 approval.removed 事件完成；此处不请求重绘，避免确认回执再触发呈现循环。
				return;
			}
			item.selectable = false;
			this.callbacks.notify(result.message ?? "The approval answer was rejected; press again.", true);
			this.callbacks.requestRender();
		}).catch(() => {
			item.confirming = false;
		});
	}

	/** 供视图/诊断：当前等待的 requestId。 */
	currentRequestId(): string {
		return this.current?.requestId ?? "";
	}

	/** 关闭/断连：清空队列（Java 侧会把这些申请 DENY）。 */
	clear(): void {
		this.current = undefined;
		this.queue.length = 0;
	}

	/** 供测试：队列长度（含当前）。 */
	size(): number {
		return (this.current ? 1 : 0) + this.queue.length;
	}

	/** 供测试：构造一个无 UI 的选项集合（不发送任何命令）。 */
	static choices(hasSuggestion: boolean): ApprovalChoice[] {
		return [
			{ index: 0, label: "Allow once", enabled: true },
			{ index: 1, label: "Allow similar", enabled: hasSuggestion },
			{ index: 2, label: "Deny", enabled: true },
		];
	}
}

/** 供 Application 解析队列快照（测试/诊断）。 */
export function approvalParameterPreview(payload: Payload): string {
	const ref = text(payload, "inputBlobRef");
	if (ref) return `<blob:${ref}>`;
	const json = text(payload, "inputJson", "{}");
	return json.length > 80 ? json.slice(0, 77) + "..." : json;
}

/** 读取 blob 引用数组（Java 上报的 actionRects 等资料校验用）。 */
export function rectCount(payload: Payload): number {
	return arrayValue(payload, "actionRects").length + numberValue(payload, "columns", 0);
}
