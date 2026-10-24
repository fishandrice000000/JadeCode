import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ApprovalController } from "../../src/approval/approval-controller.ts";
import { InputEvidence } from "../../src/terminal/input-evidence.ts";
import { PresentedEvidence } from "../../src/terminal/presented-evidence.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";
import type { BackendClient } from "../../src/bridge/client.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

test("one stable presentation serves multiple frames; selection re-presents before confirming", async () => {
	const commands: Array<{ type: string; payload: Record<string, unknown> }> = [];
	const client = {
		command: async (type: string, payload: Record<string, unknown>) => {
			commands.push({ type, payload });
			return { ok: true, type: "command.result", payload: {} };
		},
	} as unknown as BackendClient;
	const evidence = new InputEvidence();
	const presented = new PresentedEvidence(evidence, () => {});
	let renders = 0;
	const approval = new ApprovalController({
		client: () => client, evidence, presented,
		revision: () => "0", identity: () => ({ sessionId: "s", generation: "1" }),
		requestRender: () => { renders += 1; }, notify: () => {},
		terminalHealthy: () => true, teamActive: () => false, actorName: (actor) => actor,
	});
	approval.setPresented(presented);
	approval.setTheme(theme);
	approval.requested({
		requestId: "r1", actor: "leader", runId: "run", tool: "bash", cwd: "/tmp", revision: "0",
		inputJson: "{\"command\":\"echo hi\"}", suggestionId: null, suggestionScope: "",
	} as never, () => undefined);
	// 第一帧：登记呈现。
	approval.active()!.render({ title: "Approval required", tool: "bash", scope: "s", parameters: "{}", actor: "leader",
		loading: false, teamActive: false, actorName: (a) => a }, 80, 0, 40, 80);
	renders = 0; // 忽略 requested() 的一次初始重绘
	approval.onFrame({ frameId: 1, epoch: 0, columns: 80, rows: 40, rawWatermark: 0, decodedWatermark: 0 });
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(commands.filter((c) => c.type === "approval.presented").length, 1);
	assert.equal(renders, 0, "a successful presentation must not request another render");
	// 同一布局的计时帧不再重复 presented。
	approval.active()!.render({ title: "Approval required", tool: "bash", scope: "s", parameters: "{}", actor: "leader",
		loading: false, teamActive: false, actorName: (a) => a }, 80, 0, 40, 80);
	approval.onFrame({ frameId: 2, epoch: 0, columns: 80, rows: 40, rawWatermark: 0, decodedWatermark: 0 });
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(commands.filter((c) => c.type === "approval.presented").length, 1);
	// 选中变化 → 需要新一帧呈现；此前 Enter 不能确认。
	approval.handleInput("\x1b[B");
	approval.handleInput("\r");
	assert.equal(commands.filter((c) => c.type === "approval.answer").length, 0, "selection change invalidates the old frame");
	approval.active()!.render({ title: "Approval required", tool: "bash", scope: "s", parameters: "{}", actor: "leader",
		loading: false, teamActive: false, actorName: (a) => a }, 80, 0, 40, 80);
	approval.onFrame({ frameId: 3, epoch: 0, columns: 80, rows: 40, rawWatermark: 0, decodedWatermark: 0 });
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(commands.filter((c) => c.type === "approval.presented").length, 2);
	// repeat / release / paste 不是确认。
	evidence.rawChunk("x");
	approval.handleInput("\x1b[13;1:2u");
	approval.handleInput("\x1b[13;1:3u");
	approval.handleInput("\x1b[200~pasted\x1b[201~");
	assert.equal(commands.filter((c) => c.type === "approval.answer").length, 0);
	// 新鲜 Enter 使用最后一次成功呈现的 frame 3。
	evidence.rawChunk("\r");
	evidence.beginDispatch(1);
	approval.handleInput("\r");
	evidence.endDispatch();
	await new Promise((resolve) => setTimeout(resolve, 0));
	const answers = commands.filter((c) => c.type === "approval.answer");
	assert.equal(answers.length, 1);
	const answer = answers[0]!.payload as { choice: number; evidence: { frameId: string; phase: string; terminalEpoch: number } };
	assert.equal(answer.choice, 0);
	assert.equal(answer.evidence.frameId, "3");
	assert.equal(answer.evidence.phase, "PRESS");
	assert.equal(answer.evidence.terminalEpoch, 0);
});
