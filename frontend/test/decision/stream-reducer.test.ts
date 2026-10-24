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
	assert.equal(store.entries().filter((entry) => entry.kind === "REPLY").map((entry) => entry.body).join(""), "Hello world");
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


test("text/thinking/text keeps its order and the final message does not duplicate segments", () => {
	const store = new TranscriptStore();
	const reducer = new StreamReducer(store, assert.fail);
	reducer.started({ requestId: "r", actor: "leader" });
	reducer.delta({ requestId: "r", channel: "THINKING", offset: 0, text: "reason first" });
	reducer.delta({ requestId: "r", channel: "REPLY", offset: 0, text: "first" });
	reducer.delta({ requestId: "r", channel: "THINKING", offset: 12, text: "more reason" });
	reducer.delta({ requestId: "r", channel: "REPLY", offset: 5, text: "second" });
	reducer.ended({ requestId: "r", failed: false });
	store.message("leader", "Reply", "first\nsecond", false);
	assert.deepEqual(store.entries().map((entry) => [entry.kind, entry.body]), [
		["THINKING", "reason first"], ["REPLY", "first"], ["THINKING", "more reason"], ["REPLY", "second"],
	]);
	assert.equal(store.lastReplyText(), "first\nsecond");
});

test("an output-limit stop preserves incomplete status after the final message", () => {
	const store = new TranscriptStore();
	const reducer = new StreamReducer(store, assert.fail);
	reducer.started({ requestId: "r" });
	reducer.delta({ requestId: "r", channel: "REPLY", offset: 0, text: "partial" });
	reducer.ended({ requestId: "r", stopReason: "max_tokens" });
	store.message("leader", "Reply", "partial", false);
	assert.equal(store.entries()[0]!.status, "INCOMPLETE");
	assert.match(store.entries()[1]!.body, /output limit/);
});
