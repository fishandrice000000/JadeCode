import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ApprovalController } from "../../src/approval/approval-controller.ts";
import { InputEvidence } from "../../src/terminal/input-evidence.ts";
import { PresentedEvidence } from "../../src/terminal/presented-evidence.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";
import type { BackendClient } from "../../src/bridge/client.ts";
import { KeymapAdapter } from "../../src/editor/keymap-adapter.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

interface Fake {
	commands: Array<{ type: string; payload: Record<string, unknown> }>;
	renders: number;
	notifications: number;
	client: BackendClient;
}

function setup(presentedImpl?: (payload: Record<string, unknown>) => Promise<{ ok: boolean; type: string; payload: Record<string, unknown> }>): {
	fake: Fake; approval: ApprovalController; evidence: InputEvidence; presented: PresentedEvidence;
} {
	const fake: Fake = { commands: [], renders: 0, notifications: 0, client: undefined as unknown as BackendClient };
	fake.client = {
		command: async (type: string, payload: Record<string, unknown>) => {
			fake.commands.push({ type, payload });
			if (type === "approval.presented" && presentedImpl) return presentedImpl(payload);
			return { ok: true, type: "command.result", payload: {} };
		},
	} as unknown as BackendClient;
	const evidence = new InputEvidence();
	const presented = new PresentedEvidence(evidence, () => {});
	const approval = new ApprovalController({
		client: () => fake.client, evidence, presented,
		revision: () => "0", identity: () => ({ sessionId: "s", generation: "1" }),
		requestRender: () => { fake.renders += 1; }, notify: () => { fake.notifications += 1; fake.renders += 1; },
		terminalHealthy: () => true, keymap: () => new KeymapAdapter({}), teamActive: () => false, actorName: (actor) => actor,
	});
	approval.setPresented(presented);
	approval.setTheme(theme);
	return { fake, approval, evidence, presented };
}

function request(approval: ApprovalController): void {
	approval.requested({
		requestId: "r1", actor: "leader", runId: "run", tool: "bash", cwd: "/tmp", revision: "0",
		inputJson: "{\"command\":\"echo hi\"}", suggestionId: null, suggestionScope: "",
	} as never, () => undefined);
}

function render(approval: ApprovalController, origin = 0, rows = 40): void {
	approval.active()!.render({ title: "Approval required", tool: "bash", scope: "s", parameters: "{}", actor: "leader",
		loading: false, teamActive: false, actorName: (a) => a }, 80, origin, rows, 80);
}

function frame(id: number, epoch: number): { frameId: number; epoch: number; columns: number; rows: number; rawWatermark: number; decodedWatermark: number } {
	return { frameId: id, epoch, columns: 80, rows: 40, rawWatermark: 0, decodedWatermark: 0 };
}

function dispatch(evidence: InputEvidence, data: string, chunk: number): void {
	evidence.rawChunk(data);
	evidence.beginDispatch(chunk);
}

function end(evidence: InputEvidence): void { evidence.endDispatch(); }

test("the first mouse press only selects; another press on that option confirms with fresh evidence", async () => {
	const { fake, approval, evidence } = setup();
	request(approval); render(approval); approval.onFrame(frame(1, 0));
	await new Promise((resolve) => setTimeout(resolve, 0));
	const rect = approval.active()!.lastLayout()!.rects[0]!;
	const press = { type: "press", button: "left", x: rect.x, y: rect.y } as const;
	dispatch(evidence, "mouse1", 1); approval.handleMouse(press as never); end(evidence);
	assert.equal(approval.active()!.choice(), 0);
	assert.equal(fake.commands.filter((item) => item.type === "approval.answer").length, 0);
	dispatch(evidence, "mouse2", 2); approval.handleMouse(press as never); end(evidence);
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(fake.commands.filter((item) => item.type === "approval.answer").length, 1);
});

test("selection change keeps the valid geometry, so Down+Enter confirms immediately", async () => {
	const { fake, approval, evidence } = setup();
	request(approval);
	render(approval);
	fake.renders = 0;
	approval.onFrame(frame(1, 0));
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(fake.commands.filter((c) => c.type === "approval.presented").length, 1);
	dispatch(evidence, "\x1b[B", 1);
	approval.handleInput("\x1b[B");
	end(evidence);
	dispatch(evidence, "\r", 2);
	approval.handleInput("\r");
	end(evidence);
	await new Promise((resolve) => setTimeout(resolve, 0));
	const answers = fake.commands.filter((c) => c.type === "approval.answer");
	assert.equal(answers.length, 1, "a fresh Enter after selection must answer without another frame");
	const payload = answers[0]!.payload as { choice: number; evidence: { frameId: string; phase: string; terminalEpoch: number } };
	assert.equal(payload.choice, 0);
	assert.equal(payload.evidence.frameId, "1");
	assert.equal(payload.evidence.phase, "PRESS");
	assert.equal(payload.evidence.terminalEpoch, 0);
	// 选择不改变几何，因此没有第二次 presented；成功呈现也没有额外重绘。
	assert.equal(fake.commands.filter((c) => c.type === "approval.presented").length, 1);
	assert.equal(fake.renders, 1, "only the selection input requested a render");
});

test("an Enter pressed before the presentation ACK is answered once the frame is confirmed", async () => {
	let release: ((value: { ok: boolean; type: string; payload: Record<string, unknown> }) => void) | undefined;
	const { fake, approval, evidence } = setup(async () => new Promise((resolve) => { release = resolve; }));
	request(approval);
	render(approval);
	approval.onFrame(frame(1, 0));
	dispatch(evidence, "\x1b[B", 1);
	approval.handleInput("\x1b[B");
	end(evidence);
	dispatch(evidence, "\r", 2);
	approval.handleInput("\r");
	end(evidence);
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(fake.commands.filter((c) => c.type === "approval.answer").length, 0, "answer waits for the frame evidence");
	release?.({ ok: true, type: "command.result", payload: {} });
	await new Promise((resolve) => setTimeout(resolve, 0));
	const answers = fake.commands.filter((c) => c.type === "approval.answer");
	assert.equal(answers.length, 1, "the pending Enter is answered without another key press");
	const payload = answers[0]!.payload as { choice: number; evidence: { frameId: string; terminalEpoch: number } };
	assert.equal(payload.choice, 0);
	assert.equal(payload.evidence.frameId, "1");
});

test("repeat/release/paste never answer an approval", async () => {
	const { fake, approval, evidence } = setup();
	request(approval);
	render(approval);
	approval.onFrame(frame(1, 0));
	await new Promise((resolve) => setTimeout(resolve, 0));
	// 先建立一个真实选择，确保“没有 answer”不是因为无默认选择。
	dispatch(evidence, "\x1b[B", 1); approval.handleInput("\x1b[B"); end(evidence);
	dispatch(evidence, "\x1b[13;1:2u", 2); approval.handleInput("\x1b[13;1:2u"); end(evidence);
	dispatch(evidence, "\x1b[13;1:3u", 3); approval.handleInput("\x1b[13;1:3u"); end(evidence);
	dispatch(evidence, "\x1b[200~pasted\x1b[201~", 4); approval.handleInput("\x1b[200~pasted\x1b[201~"); end(evidence);
	assert.equal(fake.commands.filter((c) => c.type === "approval.answer").length, 0);
	// 之后的新鲜 PRESS Enter 仍能确认，证明过滤没有把页面弄坏。
	dispatch(evidence, "\r", 5); approval.handleInput("\r"); end(evidence);
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(fake.commands.filter((c) => c.type === "approval.answer").length, 1);
});

test("a late presented ACK after invalidation cannot restore the old epoch", async () => {
	let releaseFirst: ((value: { ok: boolean; type: string; payload: Record<string, unknown> }) => void) | undefined;
	let presentations = 0;
	const { fake, approval, evidence, presented } = setup(async () => {
		presentations += 1;
		if (presentations === 1) {
			return new Promise((resolve) => { releaseFirst = resolve; });
		}
		return { ok: true, type: "command.result", payload: {} };
	});
	request(approval);
	render(approval);
	approval.onFrame(frame(1, 0));
	assert.equal(presentations, 1, "first presentation is in flight");
	// 终端失效：epoch 推进，本地证据作废；旧回执仍在途。
	presented.invalidate();
	approval.invalidate();
	render(approval);
	approval.onFrame(frame(2, 1));
	assert.equal(presentations, 2, "the new epoch must present even while the old request is in flight");
	await new Promise((resolve) => setTimeout(resolve, 0));
	releaseFirst?.({ ok: true, type: "command.result", payload: {} });
	await new Promise((resolve) => setTimeout(resolve, 0));
	dispatch(evidence, "\x1b[B", 2);
	approval.handleInput("\x1b[B");
	end(evidence);
	dispatch(evidence, "\r", 3);
	approval.handleInput("\r");
	end(evidence);
	await new Promise((resolve) => setTimeout(resolve, 0));
	const answers = fake.commands.filter((c) => c.type === "approval.answer");
	assert.equal(answers.length, 1);
	const payload = answers[0]!.payload as { evidence: { frameId: string; terminalEpoch: number } };
	assert.equal(payload.evidence.frameId, "2");
	assert.equal(payload.evidence.terminalEpoch, 1);
});

test("layout movement while the ACK is in flight invalidates the old evidence and pending Enter", async () => {
	let releaseFirst: ((value: { ok: boolean; type: string; payload: Record<string, unknown> }) => void) | undefined;
	let presentations = 0;
	const { fake, approval, evidence } = setup(async () => {
		presentations += 1;
		if (presentations === 1) return new Promise((resolve) => { releaseFirst = resolve; });
		return { ok: true, type: "command.result", payload: {} };
	});
	request(approval);
	render(approval, 0);
	approval.onFrame(frame(1, 0));
	dispatch(evidence, "\x1b[B", 1);
	approval.handleInput("\x1b[B");
	end(evidence);
	dispatch(evidence, "\r", 2);
	approval.handleInput("\r");
	end(evidence);
	// 卡片因转录/视口变化下移：同一 epoch、不同几何。
	render(approval, 3);
	approval.onFrame(frame(2, 0));
	assert.equal(presentations, 2, "the new layout must present even while the old ACK is in flight");
	releaseFirst?.({ ok: true, type: "command.result", payload: {} });
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(fake.commands.filter((c) => c.type === "approval.answer").length, 0,
		"an Enter bound to the old layout must not be auto-sent");
	// 新布局的 ACK 成立后，新鲜 Down+Enter 使用 frame2。
	await new Promise((resolve) => setTimeout(resolve, 0));
	dispatch(evidence, "\x1b[B", 3); approval.handleInput("\x1b[B"); end(evidence);
	dispatch(evidence, "\r", 4); approval.handleInput("\r"); end(evidence);
	await new Promise((resolve) => setTimeout(resolve, 0));
	const answers = fake.commands.filter((c) => c.type === "approval.answer");
	assert.equal(answers.length, 1);
	assert.equal((answers[0]!.payload as { evidence: { frameId: string } }).evidence.frameId, "2");
});

test("buttons moved out of the viewport clear the evidence and cannot be answered", async () => {
	const { fake, approval, evidence } = setup();
	request(approval);
	render(approval, 0);
	approval.onFrame(frame(1, 0));
	await new Promise((resolve) => setTimeout(resolve, 0));
	// origin 36 + 按钮行超出 40 行视口：rects 为空。
	render(approval, 36);
	approval.onFrame(frame(2, 0));
	dispatch(evidence, "\x1b[B", 1); approval.handleInput("\x1b[B"); end(evidence);
	dispatch(evidence, "\r", 2); approval.handleInput("\r"); end(evidence);
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(fake.commands.filter((c) => c.type === "approval.answer").length, 0);
});

test("presentation failures notify once and do not schedule their own render loop", async () => {
	let presentations = 0;
	const { fake, approval } = setup(async () => {
		presentations += 1;
		return { ok: false, type: "command.error", payload: { code: "STALE_REVISION", message: "stale" } };
	});
	request(approval);
	fake.renders = 0;
	render(approval);
	approval.onFrame(frame(1, 0));
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(presentations, 1);
	assert.equal(fake.notifications, 1);
	assert.equal(fake.renders, 1, "the single notification may cause one render");
	// 模拟这次重绘产生的下一帧；失败不再通知，也不再请求重绘。
	render(approval);
	approval.onFrame(frame(2, 0));
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(presentations, 2);
	assert.equal(fake.notifications, 1);
	assert.equal(fake.renders, 1);
	// 用户再次操作/自然帧仍可重试，但不会自激。
	render(approval);
	approval.onFrame(frame(3, 0));
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(presentations, 3);
	assert.equal(fake.renders, 1);
});
