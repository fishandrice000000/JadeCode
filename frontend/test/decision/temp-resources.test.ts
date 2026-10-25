import assert from "node:assert/strict";
import { test } from "node:test";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { ClipboardController } from "../../src/services/clipboard-controller.ts";
import { ExternalEditor } from "../../src/services/external-editor.ts";

test("remote copy never mistakes the remote desktop clipboard for the client clipboard", async () => {
	let desktopWrites = 0;
	let sent = "";
	const native = () => ({ setText: async () => { desktopWrites++; } });
	const remote = new ClipboardController(native as never, async (value) => { sent = value; }, () => true, () => undefined);
	assert.equal((await remote.copy("remote")).status, "OSC52_SENT");
	assert.equal(desktopWrites, 0);
	assert.ok(sent.startsWith("\x1b]52;c;"));
	const local = new ClipboardController(native as never, async () => {}, () => false, () => undefined);
	assert.equal((await local.copy("local")).status, "DESKTOP");
	assert.equal(desktopWrites, 1);
});

test("tmux copy targets its attached client through stdin and reports missing support or command failure", async () => {
	const controller = new ClipboardController(() => undefined, async () => { throw new Error("raw OSC52 must not be used"); }, () => true, () => "%7");
	const calls: Array<{ args: string[]; input?: string }> = [];
	let outcome = "ok";
	(controller as unknown as { tmuxCommand(args: string[], input?: string): Promise<string> }).tmuxCommand = async (args, input) => {
		calls.push({ args, input });
		if (args[0] === "display-message") return "$2\n";
		if (args[0] === "list-clients") return `/dev/pts/9\t${outcome === "missing" ? "RGB" : "RGB,clipboard"}\t100\n`;
		if (outcome === "broken") throw new Error("tmux clipboard: pipe closed");
		return "";
	};
	const text = "中文\n$(literal shell text)";
	assert.equal((await controller.copy(text)).status, "TMUX_SENT");
	assert.deepEqual(calls[1]!.args.slice(0, 3), ["list-clients", "-t", "$2"]);
	assert.deepEqual(calls[2], { args: ["load-buffer", "-w", "-t", "/dev/pts/9", "-"], input: text });
	outcome = "missing";
	assert.match((await controller.copy("x")).error!, /does not advertise/);
	outcome = "broken";
	assert.deepEqual(await controller.copy("x"), { status: "FAILED", error: "tmux clipboard: pipe closed" });
});

test("clipboard OSC fallback reports actual write failure and cleans only owned image directories", async () => {
	let osc = "";
	const backend = () => ({ getImage: async () => new Uint8Array([1, 2, 3]) });
	const controller = new ClipboardController(backend as never, async (value) => { osc = value; }, () => true, () => undefined);
	assert.equal((await controller.copy("中文")).status, "OSC52_SENT");
	assert.equal(osc, `\x1b]52;c;${Buffer.from("中文").toString("base64")}\x07`);
	const path = await controller.saveImage();
	assert.ok(path && existsSync(path));
	controller.cleanup();
	assert.equal(existsSync(dirname(path!)), false);
	controller.cleanup();
	const broken = new ClipboardController(() => undefined, async () => { throw new Error("pipe closed"); }, () => true, () => undefined);
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
