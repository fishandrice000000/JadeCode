import assert from "node:assert/strict";
import { test } from "node:test";
import { parseFrame, ProtocolViolation } from "../../src/bridge/wire.ts";

const valid = JSON.stringify({
	version: 1, connectionId: "conn-1", seq: "7", type: "stream.delta",
	commandId: null, sessionId: "s-1", generation: "3", payload: { requestId: "r", channel: "REPLY", offset: 0, text: "hi" },
});

test("parses a real envelope and keeps long ids as decimal strings", () => {
	const message = parseFrame(valid);
	assert.equal(message.type, "stream.delta");
	assert.equal(message.seq, "7");
	assert.equal(message.generation, "3");
});

test("rejects unknown versions, types and malformed sequences", () => {
	assert.throws(() => parseFrame(valid.replace("\"version\":1", "\"version\":2")), ProtocolViolation);
	assert.throws(() => parseFrame(valid.replace("stream.delta", "javascript.execute")), ProtocolViolation);
	assert.throws(() => parseFrame(valid.replace("\"seq\":\"7\"", "\"seq\":7")), ProtocolViolation);
	assert.throws(() => parseFrame("not json"), ProtocolViolation);
});
