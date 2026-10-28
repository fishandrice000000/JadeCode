import { matchesKey, wrapTextWithAnsi } from "@earendil-works/pi-tui";
import type { Page, PageContext } from "./page-stack.ts";
import type { TeamAssignment, TeamMember } from "../state/session-store.ts";
import { clipLine, display, singleLine } from "../presentation/text-safety.ts";

/** 运行状态来自后端；等待原因统一显示 Waiting，业务结果由报告单独表达。 */
export function teamState(state: string): string {
	if (state.startsWith("WAITING_")) return "Waiting";
	return ({ RUNNING: "Running", IDLE: "Idle", CLOSED: "Closed", PERSISTENCE_FAILED: "Storage error" } as Record<string, string>)[state] || state || "Unknown";
}

/** 显示提交与核对事实，不能把空闲或 completed 自动提升成已核对。 */
export function reportState(a: TeamAssignment | undefined): string {
	if (!a) return "—";
	if (a.abandoned) return "Not delivered";
	if (a.deliveryError) return "Delivery error";
	if (!a.reportId) return "Pending";
	if (!a.finished) return "Not delivered";
	return a.reviewNotes ? "Reviewed" : "Submitted";
}

/**
 * Team 入口页使用现有 dock/page 栈，不抢占 Agent 运行或改动编辑器草稿。
 * 名称通过 SessionStore 区分重名，当前任务只取运行上下文指定的派工。
 */
export class TeamPage implements Page {
	readonly title = "Team";
	private selected = 0;
	constructor(private readonly context: PageContext) {}
	theme() { return this.context.theme(); }
	invalidate(): void {}
	hint(): string { return "Up/Down select · Enter details · Esc close"; }

	render(width: number): string[] {
		const theme = this.theme();
		const members = this.context.state.team();
		this.selected = Math.max(0, Math.min(this.selected, members.length - 1));
		const leader = this.context.state.actors().find((actor) => actor.id === "leader");
		const lines = [theme.style("muted", `Leader · ${teamState(leader?.state ?? "IDLE")}`), ""];
		if (!members.length) lines.push(theme.style("dim", "No teammates have been created."));
		for (const [index, member] of members.entries()) {
			const current = member.assignments.find((a) => a.id === member.currentAssignment);
			const queued = member.assignments.filter((a) => !a.finished && !a.abandoned && a.id !== member.currentAssignment);
			const marker = index === this.selected ? "▸ " : "  ";
			const name = singleLine(this.context.state.displayActor(member.id));
			lines.push(theme.style(index === this.selected ? "accent" : "text", `${marker}${name} · ${teamState(member.state)}`));
			lines.push(theme.style("text", `  Task: ${singleLine(current?.subject ?? "—")} · Report: ${reportState(current)}`));
			const latest = member.assignments.at(-1);
			if (!current && latest) lines.push(theme.style("muted", `  Latest: ${singleLine(latest.subject)} · ${reportState(latest)}`));
			if (queued.length) lines.push(theme.style("dim", `  ${queued.length} queued or awaiting resolution`));
		}
		return lines.map((line) => clipLine(line, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		const keys = this.context.keymap;
		if (keys.matches("tui.select.cancel", data)) { this.context.closePage(); return true; }
		if (keys.matches("tui.select.up", data)) { this.selected = Math.max(0, this.selected - 1); return true; }
		if (keys.matches("tui.select.down", data)) { this.selected = Math.min(this.context.state.team().length - 1, this.selected + 1); return true; }
		if (keys.matches("tui.select.confirm", data)) {
			const member = this.context.state.team()[this.selected];
			if (member) this.context.pushPage(new TeamDetailPage(this.context, member.id));
			return true;
		}
		return false;
	}
}

/**
 * 成员详情默认定位正在执行的派工，左右切换历史。只有显式 Enter 才读取完整报告。
 * 正文按终端宽度换行并滚动，所有外部文本先清除终端控制序列；阅读不登记 Leader 核对。
 */
export class TeamDetailPage implements Page {
	readonly title = "Team details";
	private assignmentId = "";
	private offset = 0;
	private readonly reports = new Map<string, string>();
	private loading = "";
	private error = "";
	constructor(private readonly context: PageContext, private readonly memberId: string) {
		const member = this.member();
		this.assignmentId = member?.currentAssignment || member?.assignments.at(-1)?.id || "";
	}
	theme() { return this.context.theme(); }
	invalidate(): void {}
	hint(): string { return "Left/Right assignment · Enter read report · PgUp/PgDn scroll · Esc back"; }
	bodyScrollOffset(): number { return this.offset; }
	setBodyWindow(start: number): void { this.offset = start; }
	private member(): TeamMember | undefined { return this.context.state.team().find((m) => m.id === this.memberId); }
	private assignment(): TeamAssignment | undefined { return this.member()?.assignments.find((a) => a.id === this.assignmentId); }

	render(width: number): string[] {
		const theme = this.theme();
		const member = this.member();
		if (!member) return [theme.style("dim", "This Team is no longer active.")];
		const lines: string[] = [];
		// 标签与正文分别取色，长路径/报告可换行，不把正文都压成灰色。
		const field = (label: string, value: string): void => {
			lines.push(theme.style("muted", label));
			lines.push(...wrapTextWithAnsi(display(value), Math.max(1, width - 2)).map((line) => "  " + theme.style("text", line)));
		};
		field("Member", `${this.context.state.displayActor(member.id)} · ${teamState(member.state)}`);
		field("Responsibilities", member.responsibilities);
		for (const q of member.questions) field(`Question · ${q.answered ? "Answered" : "Open"}`, `${q.summary}\n${q.id}`);
		const a = this.assignment();
		if (!a) { field("Assignments", "No assignment selected."); return lines; }
		field("Task", `${a.subject}\n${a.taskId}`);
		field("Assignment", `${a.id}\n${member.currentAssignment === a.id ? "Current" : "History / queued"} · ${a.required ? "Required" : "Detached"}`);
		field("Assigned scope · read", a.scope.readPaths.join("\n") || "Not specified");
		field("Assigned scope · write", a.scope.writePaths.join("\n") || "Not specified");
		if (a.progress) field("Latest update", a.progress);
		for (const q of a.questions) field(`Question · ${q.answered ? "Answered" : "Open"}`, `${q.summary}\n${q.id}`);
		field("Report", `${reportState(a)}${a.outcome ? ` · ${a.outcome.toLowerCase()}` : ""}${a.reportSummary ? `\n${a.reportSummary}` : ""}`);
		if (a.deliveryError) field("Delivery error", a.deliveryError);
		if (a.reviewNotes) field("Leader review", a.reviewNotes);
		if (this.loading === a.id) field("Report body", "Loading…");
		else if (this.reports.has(a.id)) field("Report body", this.reports.get(a.id)!);
		else if (a.reportId) field("Report body", "Press Enter to read the full report.");
		if (this.error) field("Error", this.error);
		field("History", `${member.assignments.findIndex((entry) => entry.id === a.id) + 1}/${member.assignments.length} shown · ${member.historyCount} total`);
		return lines.map((line) => clipLine(line, Math.max(0, width)));
	}

	handleInput(data: string): boolean {
		const keys = this.context.keymap;
		if (keys.matches("tui.select.cancel", data)) { this.context.closePage(); return true; }
		if (keys.matches("tui.select.pageDown", data)) { this.offset += 8; return true; }
		if (keys.matches("tui.select.pageUp", data)) { this.offset = Math.max(0, this.offset - 8); return true; }
		if (matchesKey(data, "left") || matchesKey(data, "right")) {
			const items = this.member()?.assignments ?? [];
			const index = items.findIndex((a) => a.id === this.assignmentId);
			const next = Math.max(0, Math.min(items.length - 1, index + (matchesKey(data, "left") ? -1 : 1)));
			this.assignmentId = items[next]?.id ?? ""; this.offset = 0; this.error = "";
			return true;
		}
		if (keys.matches("tui.select.confirm", data)) {
			const a = this.assignment();
			if (a?.reportId && !this.loading && !this.reports.has(a.id)) void this.readReport(a.id);
			return true;
		}
		return false;
	}

	/** 异步只更新对应派工的缓存；读取失败可重试，切换派工不会把旧正文放到新报告下。 */
	private async readReport(id: string): Promise<void> {
		this.loading = id; this.error = "";
		try {
			if (!this.context.readTeamReport) throw new Error("Report reading is unavailable");
			this.reports.set(id, await this.context.readTeamReport(id));
		} catch (failure) { this.error = (failure as Error).message; }
		finally { this.loading = ""; this.context.requestRender(); }
	}
}
