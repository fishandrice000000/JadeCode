import assert from "node:assert/strict";
import { test } from "node:test";
import { StreamReducer } from "../../src/state/stream-reducer.ts";
import { TranscriptStore } from "../../src/state/transcript-store.ts";

test("appends deltas by channel and keeps text when a stream fails", () => {
	const store = new TranscriptStore();
	const diagnostics: string[] = [];
	const reducer = new StreamReducer(store, (message) => diagnostics.push(message));
	reducer.started({ requestId: "r1", actor: "leader" });
	reducer.delta({ requestId: "r1", channel: "REPLY", offset: 0, text: "Hello" });
	reducer.delta({ requestId: "r1", channel: "THINKING", offset: 0, text: "hmm" });
	// 错误 offset 不丢正文，但会留下诊断。
	reducer.delta({ requestId: "r1", channel: "REPLY", offset: 99, text: " world" });
	reducer.ended({ requestId: "r1", failed: true, stopReason: "error" });
	const reply = store.entries().find((entry) => entry.id === "stream-r1-REPLY")!;
	assert.equal(reply.body, "Hello world");
	assert.equal(reply.status, "INCOMPLETE");
	const thinking = store.entries().find((entry) => entry.id === "stream-r1-THINKING")!;
	assert.equal(thinking.body, "hmm");
	assert.equal(diagnostics.length, 1);
});

test("tool previews update one card and never claim execution", () => {
	const store = new TranscriptStore();
	const reducer = new StreamReducer(store, () => {});
	reducer.previewStarted({ requestId: "r1", actor: "leader", blockIndex: 1, callId: "tool-1", name: "bash", initialInputJson: "{\"command\":" });
	reducer.previewDelta({ requestId: "r1", blockIndex: 1, fragment: "\"ls\"}" });
	reducer.previewClosed({ requestId: "r1", blockIndex: 1 });
	const tool = store.entries().find((entry) => entry.kind === "TOOL")!;
	assert.equal(tool.status, "PREVIEW");
	assert.match(tool.arguments, /"ls"/);
});
