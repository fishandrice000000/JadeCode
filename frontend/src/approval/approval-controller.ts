import { isKeyRelease, matchesKey, type TuiMouseEvent } from "@earendil-works/pi-tui";
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
	teamActive: () => boolean;
	actorName: (actor: string) => string;
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
	presentedFrame: number;
	view: ApprovalView;
	// 选中/确认前必须有一帧成功提交的呈现证据。
	selectable: boolean;
	confirming: boolean;
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
			presentedFrame: -1,
			view: new ApprovalView(this.theme!, choices),
			selectable: false,
			confirming: false,
		};
		if (this.current) this.queue.push(item);
		else {
			this.current = item;
		}
		this.callbacks.requestRender();
	}

	/** blob 组装完成后补齐参数（LOADING → SELECTABLE 需要等下一帧围栏）。 */
	blobReady(ref: string, value: string): void {
		for (const item of [this.current, ...this.queue]) {
			if (!item || !item.loading) continue;
			// requested 时未保存 ref，这里用参数内容为空且 loading 判定；Application 只对当前审批调用。
			item.parameters = value;
			item.loading = false;
		}
		this.callbacks.requestRender();
		void ref;
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
		this.current.selectable = false;
		this.current.presentedFrame = -1;
		this.callbacks.requestRender();
	}

	/**
	 * 每帧 stdout 完成：为当前申请登记呈现证据。
	 * 参数仍在加载或审批已答复时跳过。
	 */
	onFrame(frame: FrameSnapshot): void {
		const item = this.current;
		if (!item || item.loading || item.confirming || !this.theme) return;
		const layout = item.view.lastLayout();
		if (!layout) return;
		if (this.lastPresented && this.lastPresented.requestId === item.requestId
			&& this.lastPresented.frameId === frame.frameId) return;
		this.lastPresented = { requestId: item.requestId, frameId: frame.frameId };
		const client = this.callbacks.client();
		if (!client) return;
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
			if (!result.ok) {
				item.selectable = false;
				this.callbacks.notify(result.message ?? "The approval layout was rejected; press again after the next frame.", true);
				this.callbacks.requestRender();
				return;
			}
			if (item.loading) return;
			item.selectable = true;
			item.presentedFrame = frame.frameId;
			this.callbacks.requestRender();
		}).catch(() => { /* 断连由 Application 统一处理。 */ });
	}

	/**
	 * 优先级输入：只在有待答申请时消费审批相关按键。
	 * @returns true 表示已由审批处理（不再交给编辑器/页面）。
	 */
	handleInput(data: string): boolean {
		const item = this.current;
		if (!item) return false;
		if (isKeyRelease(data)) {
			// release 不改变选择，也不构成确认。
			return matchesKey(data, "up") || matchesKey(data, "down") || matchesKey(data, "enter") || matchesKey(data, "escape");
		}
		if (matchesKey(data, "up") || matchesKey(data, "left") || matchesKey(data, "shift+tab")) {
			item.view.move(-1);
			this.callbacks.requestRender();
			return true;
		}
		if (matchesKey(data, "down") || matchesKey(data, "right") || matchesKey(data, "tab")) {
			item.view.move(1);
			this.callbacks.requestRender();
			return true;
		}
		if (matchesKey(data, "pageup" as never)) {
			item.view.scrollBy(-5);
			this.callbacks.requestRender();
			return true;
		}
		if (matchesKey(data, "pagedown" as never)) {
			item.view.scrollBy(5);
			this.callbacks.requestRender();
			return true;
		}
		if (matchesKey(data, "escape")) {
			this.answer(2, { keyAction: "approval.deny", mouse: undefined });
			return true;
		}
		if (matchesKey(data, "enter")) {
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
		if (!item.selectable || item.presentedFrame < 0) {
			this.callbacks.notify("Waiting for the approval frame to be presented; press again.", true);
			this.callbacks.requestRender();
			return;
		}
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
				terminalEpoch: this.callbacks.presented.currentEpoch(),
				rawStartReceipt: source.startReceipt,
				rawEndReceipt: source.endReceipt,
				decodedSeq: source.decodedSeq,
				keyAction: input.keyAction,
				mouse: input.mouse ?? null,
			},
		}, this.callbacks.identity()).then((result) => {
			item.confirming = false;
			if (result.ok) {
				// 真正移除由 approval.removed 事件完成，避免 Node 与 Java 状态分叉。
				this.callbacks.requestRender();
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
