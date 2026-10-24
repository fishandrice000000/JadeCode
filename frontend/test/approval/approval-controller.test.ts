import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ApprovalController } from "../../src/approval/approval-controller.ts";
import { InputEvidence } from "../../src/terminal/input-evidence.ts";
import { PresentedEvidence } from "../../src/terminal/presented-evidence.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

function controller(): { approval: ApprovalController; evidence: InputEvidence } {
	const evidence = new InputEvidence();
	const presented = new PresentedEvidence(evidence, () => {});
	const approval = new ApprovalController({
		client: () => null,
		evidence,
		presented,
		revision: () => "0",
		identity: () => ({ sessionId: "s", generation: "1" }),
		requestRender: () => {},
		notify: () => {},
		terminalHealthy: () => true,
		teamActive: () => false,
		actorName: (actor) => actor,
	});
	approval.setPresented(presented);
	approval.setTheme(theme);
	return { approval, evidence };
}

function requested(id: string, tool: string, json: string): Record<string, unknown> {
	return {
		requestId: id, actor: "leader", runId: "r", tool, cwd: "/tmp", revision: "0",
		inputJson: json, suggestionId: null, suggestionScope: "", reason: "hidden",
	};
}

test("queued approvals show their own parameters, not a later request's", () => {
	const { approval } = controller();
	approval.requested(requested("A", "bash", "{\"command\":\"echo A\"}") as never, () => undefined);
	approval.requested(requested("B", "read_file", "{\"path\":\"/B\"}") as never, () => undefined);
	const shown = approval.currentData()!;
	assert.equal(shown.requestId, "A");
	assert.match(shown.parameters, /echo A/);
	assert.doesNotMatch(shown.parameters, /\/B/);
	approval.removed({ requestId: "A" } as never);
	assert.equal(approval.currentData()!.requestId, "B");
	assert.match(approval.currentData()!.parameters, /\/B/);
});

test("large parameter blobs bind to the exact request", () => {
	const { approval } = controller();
	approval.requested({ ...requested("A", "bash", ""), inputBlobRef: "blob-A" } as never, () => undefined);
	approval.requested({ ...requested("B", "bash", ""), inputBlobRef: "blob-B" } as never, () => undefined);
	assert.equal(approval.currentData()!.loading, true);
	approval.blobReady("blob-B", "SECOND-PARAMETERS");
	assert.equal(approval.currentData()!.loading, true, "B's blob must not fill A");
	approval.blobReady("blob-A", "FIRST-PARAMETERS");
	assert.equal(approval.currentData()!.parameters, "FIRST-PARAMETERS");
	assert.equal(approval.currentData()!.loading, false);
});

test("a handler without a selected option never answers, and Esc targets the current request", () => {
	const { approval, evidence } = controller();
	approval.requested(requested("A", "bash", "{}") as never, () => undefined);
	evidence.rawChunk("x");
	evidence.beginDispatch(1);
	assert.equal(approval.handleInput("\x1b"), true);
	// 尚未呈现/选择：Esc 只记录意图，不发送错误答复；申请仍在等待。
	assert.equal(approval.hasPending(), true);
});
