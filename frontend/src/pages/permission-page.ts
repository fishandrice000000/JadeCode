import { matchesKey } from "@earendil-works/pi-tui";
import type { Page, PageContext } from "./page-stack.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";

/**
 * 权限页：四档 + Full Access 两步确认（默认 Cancel）。
 *
 * Java 仍是唯一权威：普通切档发送 permission.select；Full Access 先 prepare 拿一次性确认 ID，
 * 再在第二步按 Enter 时发送 permission.presented（当前帧证据）与 permission.select（新鲜输入），
 * 由 Java 校验 revision/帧/水位后才生效。Esc 取消确认不改变档位。
 */
const PROFILES = ["read-only", "workspace", "auto-review", "full-access"] as const;
const LABELS: Record<string, string> = {
	"read-only": "read-only",
	workspace: "workspace",
	"auto-review": "auto-review (Approve for Me)",
	"full-access": "full-access",
};

export class PermissionPage implements Page {
	readonly title = "Permission";
	private readonly context: PageContext;
	private selected: number;
	private confirmingFullAccess = false;
	private confirmationId = "";
	private error = "";
	private confirmSelected = 0; // 0 = Cancel, 1 = Confirm (默认 Cancel)

	private readonly intent: "runtime" | "default";
	private rects: Array<{ x: number; y: number; width: number; height: number; choice: number }> = [];

	constructor(context: PageContext, intent: "runtime" | "default" = "runtime") {
		this.context = context;
		this.intent = intent;
		const current = context.state.snapshot()?.permission?.profile ?? "workspace";
		this.selected = Math.max(0, PROFILES.indexOf(current as (typeof PROFILES)[number]));
	}

	theme(): ResolvedTheme { return this.context.theme(); }

	hint(): string {
		return this.confirmingFullAccess
			? "Left/Right choose · Enter confirm · Esc cancel"
			: "Up/Down select · Enter apply · Esc close";
	}

	invalidate(): void {}

	render(width: number): string[] {
		const theme = this.context.theme();
		const lines: string[] = [];
		if (this.confirmingFullAccess) {
			lines.push(theme.bold(theme.style("warning", `Full Access requires a second confirmation (${this.intent})`)));
			lines.push(theme.style("text", "This profile can run operations without asking again."));
			lines.push("");
			const cancel = this.confirmSelected === 0 ? theme.bold(theme.style("accent", "▸ [ Cancel ]")) : "  [ Cancel ]";
			const confirm = this.confirmSelected === 1 ? theme.bold(theme.style("error", "▸ [ Confirm Full Access ]")) : "  [ Confirm Full Access ]";
			lines.push(cancel);
			const confirmLine = lines.length;
			lines.push(confirm);
			if (this.error) lines.push(theme.style("error", this.error));
			// 真实按钮矩形：page body 从 dock 顶边框下一行开始（framePage 的 top border）。越界不发布。
			const frame = this.context.lastFrame();
			const origin = this.context.dockOriginY() + 1;
			const y = origin + confirmLine;
			this.rects = frame && y >= 0 && y < frame.rows
				? [{ x: 2, y, width: Math.max(8, Math.min(30, width - 4)), height: 1, choice: 1 }]
				: [];
			return lines.map((line) => line.slice(0, Math.max(0, width)));
		}
		this.rects = [];
		for (const [index, profile] of PROFILES.entries()) {
			const marker = index === this.selected ? "▸ " : "  ";
			const line = `${marker}${LABELS[profile]}${profile === "full-access" ? theme.style("dim", "  (asks for confirmation)") : ""}`;
			lines.push(index === this.selected ? theme.bold(theme.style("accent", line)) : theme.style("text", line));
		}
		if (this.error) lines.push(theme.style("error", this.error));
		return lines.map((line) => line.slice(0, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		if (this.confirmingFullAccess) return this.handleConfirm(data);
		if (matchesKey(data, "escape")) { this.context.closePage(); return true; }
		if (matchesKey(data, "up")) { this.selected = (this.selected - 1 + PROFILES.length) % PROFILES.length; return true; }
		if (matchesKey(data, "down")) { this.selected = (this.selected + 1) % PROFILES.length; return true; }
		if (!matchesKey(data, "enter")) return false;
		const profile = PROFILES[this.selected]!;
		const client = this.context.client();
		if (!client) return true;
		const snapshot = this.context.state.snapshot();
		const expected = snapshot?.permission?.revision ?? "0";
		if (profile === "full-access") {
			client.command("permission.prepareFullAccess", { expectedPermissionRevision: Number(expected) },
				this.context.identity()).then((result) => {
				if (!result.ok) {
					this.error = result.message ?? "Could not prepare the confirmation";
					this.context.requestRender();
					return;
				}
				this.confirmationId = String(result.payload.confirmationId ?? "");
				this.confirmingFullAccess = true;
				this.confirmSelected = 0;
				this.context.requestRender();
			});
			return true;
		}
		const type = this.intent === "default" ? "permission.default" : "permission.select";
		client.command(type, { profile, expectedPermissionRevision: Number(expected),
			fullAccessConfirmationId: null, evidence: null }, this.context.identity()).then((result) => {
			if (!result.ok) this.error = result.message ?? "Could not change permission";
			else this.context.closePage();
			this.context.requestRender();
		});
		return true;
	}

	private handleConfirm(data: string): boolean {
		if (matchesKey(data, "escape")) {
			const client = this.context.client();
			if (client) void client.command("permission.cancelFullAccess", { confirmationId: this.confirmationId }, this.context.identity());
			this.confirmingFullAccess = false;
			this.context.closePage();
			return true;
		}
		if (matchesKey(data, "left") || matchesKey(data, "right") || matchesKey(data, "up") || matchesKey(data, "down")) {
			this.confirmSelected = this.confirmSelected === 0 ? 1 : 0;
			return true;
		}
		if (!matchesKey(data, "enter")) return false;
		if (this.confirmSelected === 0) {
			const client = this.context.client();
			if (client) void client.command("permission.cancelFullAccess", { confirmationId: this.confirmationId }, this.context.identity());
			this.confirmingFullAccess = false;
			this.context.closePage();
			return true;
		}
		const client = this.context.client();
		const snapshot = this.context.state.snapshot();
		const frame = this.context.lastFrame();
		if (!client || !frame) {
			this.error = "The permission confirmation needs a committed frame; press Enter again.";
			return true;
		}
		this.context.requestRender();
		const revision = Number(snapshot?.permission?.revision ?? 0);
		const evidence = this.context.evidence();
		if (!evidence || this.rects.length === 0) {
			this.error = "The confirmation button is not fully visible; resize the terminal and press Enter again.";
			return true;
		}
		void client.command("permission.presented", {
			confirmationId: this.confirmationId,
			intent: this.intent,
			revision,
			frameId: String(frame.frameId),
			terminalEpoch: frame.epoch,
			columns: frame.columns,
			rows: frame.rows,
			actionRects: this.rects,
			rawReceiptWatermark: frame.rawWatermark,
			decodedWatermark: frame.decodedWatermark,
		}, this.context.identity()).then((presented) => {
			if (!presented.ok) {
				this.error = presented.message ?? "Could not record the confirmation layout";
				this.context.requestRender();
				return;
			}
			return client.command("permission.select", {
				profile: "full-access",
				expectedPermissionRevision: revision,
				fullAccessConfirmationId: this.confirmationId,
				evidence: {
					phase: "PRESS",
					revision,
					frameId: String(frame.frameId),
					terminalEpoch: frame.epoch,
					rawStartReceipt: evidence.startReceipt,
					rawEndReceipt: evidence.endReceipt,
					decodedSeq: evidence.decodedSeq,
					keyAction: "approval.confirm",
					mouse: null,
				},
			}, this.context.identity()).then((result) => {
				if (!result.ok) {
					this.error = result.message ?? "Full Access was not confirmed";
					this.context.requestRender();
					return;
				}
				this.confirmingFullAccess = false;
				this.context.closePage();
			});
		});
		return true;
	}
}
