import assert from "node:assert/strict";
import { test } from "node:test";
import { StdinBuffer } from "@earendil-works/pi-tui";
import { InputEvidence } from "../../src/terminal/input-evidence.ts";

test("a sequence that started in an older raw chunk keeps its true start receipt", async () => {
	const buffer = new StdinBuffer({ escapeTimeout: 5, timeout: 5 });
	const evidence = new InputEvidence();
	const sources: Array<{ sequence: string; start: number }> = [];
	buffer.on("data", (sequence, chunk) => {
		evidence.beginDispatch(chunk);
		sources.push({ sequence, start: evidence.active()!.startReceipt });
		evidence.endDispatch();
	});
	// 块 1 同时包含一个完整按键和一个尚未完成的 ESC。
	evidence.rawChunk("x\x1b");
	buffer.process("x\x1b", 1);
	const watermark = evidence.rawWatermark();
	// 块 2 补齐 CSI-u Enter。
	evidence.rawChunk("[13u");
	buffer.process("[13u", 2);
	await new Promise((resolve) => setTimeout(resolve, 30));
	buffer.destroy();
	assert.equal(watermark, 1);
	const enter = sources.find((item) => item.sequence === "\x1b[13u");
	assert.ok(enter, `expected the split Enter sequence, got ${JSON.stringify(sources)}`);
	assert.equal(enter!.start, 1, "the Enter sequence started in chunk 1, so it must not look fresh after a frame");
});

test("a key that arrives entirely after the frame is fresh", () => {
	const evidence = new InputEvidence();
	evidence.rawChunk("x");
	evidence.beginDispatch(1);
	evidence.endDispatch();
	evidence.rawChunk("\r");
	evidence.beginDispatch(2);
	assert.equal(evidence.active()!.startReceipt, 2);
	assert.ok(evidence.active()!.startReceipt > 1);
});
