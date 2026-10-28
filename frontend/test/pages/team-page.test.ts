import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import xterm from "@xterm/headless";
import { createHash } from "node:crypto";
import { Application } from "../../src/app/Application.ts";
import { PayloadAssembler } from "../../src/bridge/payload-assembler.ts";
import { TeamPage, TeamDetailPage } from "../../src/pages/team-page.ts";
import type { Page, PageContext } from "../../src/pages/page-stack.ts";
import { SessionStore } from "../../src/state/session-store.ts";
import { StatusView } from "../../src/views/status-view.ts";
import { KeymapAdapter } from "../../src/editor/keymap-adapter.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const document = parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8"));
const theme = resolveTheme(document, "dark", { mode: "256color", noColor: true });

/** 真正的状态解析与页面渲染共用一份后端快照；选中应指向正在执行的旧派工。 */
function fixture(): { context: PageContext; state: SessionStore; opened: Page[]; reads: string[] } {
	const state = new SessionStore();
	state.applySnapshot({ snapshot: {
		sessionId: "s", generation: "1", teamActive: true, actors: [{ id: "leader", state: "WAITING_TEAM" }], tui: {},
		team: [{ id: "agent_12345678", name: "Explorer", responsibilities: "Read source.", state: "RUNNING", closed: false,
			currentAssignment: "old", historyCount: 2, pendingReports: 1, toReview: 1, openQuestions: 1,
			assignments: [
				{ id: "old", taskId: "task_a", subject: "Trace flow", required: true, scope: { readPaths: ["src"], writePaths: [] },
					finished: true, reportId: "report_1", reportSummary: "Evidence collected", reviewNotes: "", questions: [], outcome: "COMPLETED" },
				{ id: "queued", taskId: "task_b", subject: "Queued work", required: true, scope: { readPaths: [], writePaths: ["src/B.java"] },
					finished: false, reportId: "", questions: [] },
			] }],
	} } as never);
	const opened: Page[] = []; const reads: string[] = [];
	const context = {
		state, theme: () => theme, keymap: new KeymapAdapter({}), closePage: () => {}, pushPage: (page: Page) => opened.push(page),
		requestRender: () => {}, readTeamReport: async (id: string) => { reads.push(id); return "Full report\n\x1b[31mEvidence\n" + "source line\n".repeat(40); },
	} as unknown as PageContext;
	return { context, state, opened, reads };
}

test("Team page uses current assignment, distinguishes queued work and opens details", () => {
	const { context, opened } = fixture(); const page = new TeamPage(context);
	const lines = page.render(80).join("\n");
	assert.match(lines, /Leader · Waiting/); assert.match(lines, /Explorer · Running/);
	assert.match(lines, /Task: Trace flow · Report: Submitted/); assert.doesNotMatch(lines, /Task: Queued work/);
	page.handleInput("\r"); assert.ok(opened[0] instanceof TeamDetailPage);
	assert.match(opened[0]!.render(80).join("\n"), /Assigned scope · read\n  src/);
	for (const line of page.render(15)) assert.ok(line.length <= 15);
});

test("reading a full report is explicit, scrollable, safe and does not mark it reviewed", async () => {
	const { context, state, reads } = fixture(); const page = new TeamDetailPage(context, "agent_12345678");
	assert.equal(reads.length, 0); page.handleInput("\r"); await new Promise<void>((resolve) => setImmediate(resolve));
	assert.deepEqual(reads, ["old"]); const text = page.render(60).join("\n");
	assert.match(text, /Full report/); assert.doesNotMatch(text, /\x1b/);
	assert.equal(state.team()[0]!.assignments[0]!.reviewNotes, "");
	page.handleInput("\x1b[6~"); assert.equal(page.bodyScrollOffset(), 8);
	page.handleInput("\x1b[C"); assert.match(page.render(80).join("\n"), /Queued work/);
	assert.doesNotMatch(page.render(80).join("\n"), /Full report/);
});

test("footer uses backend counts and English labels while duplicate names retain unique identities", () => {
	const { state } = fixture();
	const text = new StatusView(state).render(theme, 180).join("\n");
	assert.match(text, /Leader · Waiting/); assert.match(text, /1 pending report/); assert.match(text, /1 open question/);
	const snapshot = state.snapshot()!;
	state.applySnapshot({ snapshot: { ...snapshot, team: [...snapshot.team, { ...snapshot.team[0], id: "agent_87654321" }] } } as never);
	assert.equal(state.displayActor("agent_12345678"), "Explorer (12345678)");
	assert.equal(state.displayActor("agent_87654321/subagent"), "Explorer (87654321) / subagent");
});

/** 用真实终端单元格检查颜色和宽度，而不把存在 ANSI 字符当成上色成功。 */
test("Team hierarchy has distinct terminal cell colours in light and dark themes", async () => {
	for (const variant of ["dark", "light"]) {
		const coloured = resolveTheme(document, variant, { mode: "256color", noColor: false });
		const { context } = fixture();
		const page = new TeamPage({ ...context, theme: () => coloured });
		const terminal = new xterm.Terminal({ cols: 72, rows: 16, allowProposedApi: true });
		try {
			await new Promise<void>((resolve) => terminal.write(page.render(72).join("\r\n"), resolve));
			const rows = Array.from({ length: 16 }, (_, i) => terminal.buffer.active.getLine(i)!);
			const title = rows.find((row) => row.translateToString().includes("Explorer"))!;
			const body = rows.find((row) => row.translateToString().includes("Task:"))!;
			const selected = title.getCell(title.translateToString().indexOf("Explorer"))!;
			const task = body.getCell(body.translateToString().indexOf("Task:"))!;
			assert.ok(!selected.isFgDefault()); assert.notEqual(selected.getFgColor(), task.getFgColor());
		} finally { terminal.dispose(); }
	}
});

/** 长快照走完整组装/哈希校验，再交给真正的 Application/SessionStore，消费后释放载荷。 */
test("large Team snapshots resolve through the verified payload channel", () => {
    const { state } = fixture(); const assembler = new PayloadAssembler();
    const value = JSON.stringify({ ...state.snapshot(), title: "Long Team history ".repeat(2400) });
    const hash = createHash("sha256").update(value).digest("hex");
    assembler.begin("large-team", "state-snapshot", Buffer.byteLength(value), hash);
    assembler.chunk("large-team", 0, value.slice(0, 15000));
    assembler.chunk("large-team", 1, value.slice(15000));
    assembler.end("large-team", 2, hash);
    const app = Object.create(Application.prototype) as { assembler: PayloadAssembler; resolveSnapshot(value: Record<string, unknown>): Record<string, unknown> };
    app.assembler = assembler;
    state.applySnapshot(app.resolveSnapshot({ snapshotBlobRef: "large-team" }));
    assert.equal(state.team()[0]!.name, "Explorer");
    assert.ok(state.snapshot()!.title.length > 32_000);
    assert.equal(assembler.resolve("large-team"), undefined);
    assert.throws(() => app.resolveSnapshot({ snapshotBlobRef: "missing" }), /incomplete/);
});
