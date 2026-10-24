import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { test } from "node:test";
import { PayloadAssembler } from "../../src/bridge/payload-assembler.ts";

test("assembles chunks only after length and hash verification", () => {
	const assembler = new PayloadAssembler();
	const text = "长参数? {\"command\":\"rm -rf build\"}";
	const bytes = Buffer.from(text, "utf8");
	const hash = createHash("sha256").update(bytes).digest("hex");
	assembler.begin("blob-1", "approval-input", bytes.length, hash);
	assembler.chunk("blob-1", 0, text.slice(0, 5));
	assembler.chunk("blob-1", 1, text.slice(5));
	assert.equal(assembler.end("blob-1", 2, hash), text);
	assert.equal(assembler.resolve("blob-1"), text);
	assert.equal(assembler.resolve("missing"), undefined);
});

test("rejects discontinuous chunks and hash mismatches", () => {
	const assembler = new PayloadAssembler();
	assembler.begin("b", "kind", 3, "00");
	assert.throws(() => assembler.chunk("b", 1, "abc"));
	const bad = new PayloadAssembler();
	bad.begin("c", "kind", 3, "00");
	bad.chunk("c", 0, "abc");
	assert.throws(() => bad.end("c", 1, "00"));
});
