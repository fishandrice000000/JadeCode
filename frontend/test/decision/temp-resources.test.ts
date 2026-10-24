import assert from "node:assert/strict";
import { test } from "node:test";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { ClipboardController } from "../../src/services/clipboard-controller.ts";
import { ExternalEditor } from "../../src/services/external-editor.ts";

test("clipboard OSC fallback reports actual write failure and cleans only owned image directories", async () => {
	let osc = "";
	const backend = () => ({ getImage: async () => new Uint8Array([1, 2, 3]) });
	const controller = new ClipboardController(backend as never, async (value) => { osc = value; });
	assert.equal((await controller.copy("中文")).status, "OSC52_SENT");
	assert.equal(osc, `\x1b]52;c;${Buffer.from("中文").toString("base64")}\x07`);
	const path = await controller.saveImage();
	assert.ok(path && existsSync(path));
	controller.cleanup();
	assert.equal(existsSync(dirname(path!)), false);
	controller.cleanup();
	const broken = new ClipboardController(() => undefined, async () => { throw new Error("pipe closed"); });
	assert.deepEqual(await broken.copy("x"), { status: "FAILED", error: "pipe closed" });
});

test("real external editor process restores UI and removes its temporary draft on success/failure", async () => {
	const root = mkdtempSync(join(tmpdir(), "jade-editor-fixture-"));
	try {
		const script = join(root, "editor.mjs");
		const marker = join(root, "path.txt");
		writeFileSync(script, `import{writeFileSync}from'node:fs';writeFileSync(${JSON.stringify(marker)},process.argv[2]);writeFileSync(process.argv[2],'edited');`);
		const events: string[] = [];
		const handoff = { suspend: async () => { events.push("suspend"); }, resume: () => { events.push("resume"); } };
		const editor = new ExternalEditor();
		const result = await editor.edit(`${process.execPath} ${script}`, "draft", handoff);
		assert.equal(result?.text, "edited");
		const { readFileSync } = await import("node:fs");
		assert.equal(existsSync(dirname(readFileSync(marker, "utf8"))), false);
		assert.equal(await editor.edit(join(root, "missing-editor"), "draft", handoff), null);
		assert.deepEqual(events, ["suspend", "resume", "suspend", "resume"]);
	} finally { rmSync(root, { recursive: true, force: true }); }
});
