import assert from "node:assert/strict";
import { test } from "node:test";
import { BackendClient } from "../../src/bridge/client.ts";

function frame(seq: number, payload: Record<string, unknown> = {}, type = "stream.delta"): string {
	return JSON.stringify({ version: 1, connectionId: "conn-1", seq: String(seq), type,
		commandId: null, sessionId: "s-1", generation: "3", payload }) + "\n";
}

function client(): {
	ingest(chunk: Buffer): void; events: unknown[]; fatal: string[]; closed: string[];
} {
	const events: unknown[] = [];
	const fatal: string[] = [];
	const closed: string[] = [];
	const value = new BackendClient({
		socketPath: "/unused", onEvent: (message) => { events.push(message); },
		onFatal: (reason) => { fatal.push(reason); }, onClosed: (reason) => { closed.push(reason); },
	});
	return { ingest: (chunk) => (value as unknown as { ingest(chunk: Buffer): void }).ingest(chunk), events, fatal, closed };
}

test("fragmented UTF-8 frames and multiple frames in one read are reassembled in order", () => {
	const harness = client();
	const first = frame(1, { text: "你好" });
	const second = frame(2, { text: "world" });
	const bytes = Buffer.from(first + second, "utf8");
	harness.ingest(bytes.subarray(0, 7));
	harness.ingest(bytes.subarray(7, first.length + 2));
	assert.equal(harness.events.length, 0, "a partial frame is not dispatched");
	harness.ingest(bytes.subarray(first.length + 2));
	assert.deepEqual(harness.events.map((event) => (event as { seq: string }).seq), ["1", "2"]);
	assert.equal((harness.events[0] as { payload: { text: string } }).payload.text, "你好");
});

test("oversized, invalid UTF-8 and invalid JSON frames fail closed once", () => {
	const oversized = client();
	oversized.ingest(Buffer.alloc(1024 * 1024 + 1, 0x61));
	assert.match(oversized.fatal[0]!, /exceeded the protocol limit/);
	assert.equal(oversized.closed.length, 1);

	const invalidUtf8 = client();
	invalidUtf8.ingest(Buffer.from([0xff, 0x0a]));
	assert.match(invalidUtf8.fatal[0]!, /invalid UTF-8/);
	assert.equal(invalidUtf8.closed.length, 1);

	const invalidJson = client();
	invalidJson.ingest(Buffer.from("not json\n"));
	assert.match(invalidJson.fatal[0]!, /Invalid backend message/);
	assert.equal(invalidJson.closed.length, 1);
});

test("an orphan command response is reported without closing the connection", () => {
	const harness = client();
	harness.ingest(Buffer.from(JSON.stringify({ version: 1, connectionId: "conn-1", seq: "1",
		type: "command.result", commandId: "c-404", sessionId: null, generation: null, payload: {} }) + "\n"));
	assert.match(harness.fatal[0]!, /Orphan command response/);
	assert.equal(harness.closed.length, 0);
});
