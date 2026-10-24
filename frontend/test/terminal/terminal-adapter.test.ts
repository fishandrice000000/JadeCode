import assert from "node:assert/strict";
import { test } from "node:test";
import { JadeTerminal } from "../../src/terminal/terminal-adapter.ts";
import { TuiAltScreen } from "../../vendor/pi-tui/src/tui-alt-screen.ts";
import { VirtualTerminal } from "../../vendor/pi-tui/test/virtual-terminal.ts";

test("a failed stdout write suppresses the frame fence and reports once", async () => {
	const originalWrite = process.stdout.write;
	let failures = 0;
	let fenced = false;
	const terminal = new JadeTerminal({
		onWritable: () => {},
		onWriteFailure: () => { failures += 1; },
		onRawInput: () => {},
	});
	try {
		(process.stdout as unknown as { write: typeof process.stdout.write }).write = ((_data: string, callback?: (error?: Error | null) => void) => {
			queueMicrotask(() => callback?.(new Error("synthetic write failure")));
			return false;
		}) as typeof process.stdout.write;
		terminal.write("frame");
		terminal.afterFrame(() => { fenced = true; });
		await new Promise((resolve) => setTimeout(resolve, 20));
		assert.equal(fenced, false, "a failed frame must not publish presented evidence");
		assert.equal(failures, 1);
		assert.equal(terminal.hasFailed(), true);
	} finally {
		(process.stdout as unknown as { write: typeof process.stdout.write }).write = originalWrite;
	}
});

test("slow stdout keeps state updates flowing while Pi coalesces blocked renders until drain", async () => {
	const original = process.stdout.write;
	const viewport = new VirtualTerminal(30, 5);
	let tui: TuiAltScreen;
	const terminal = new JadeTerminal({ onWritable: () => tui.notifyRenderReady(), onWriteFailure: assert.fail, onRawInput: () => {} });
	let complete!: () => void;
	let renders = 0;
	let value = 0;
	try {
		process.stdout.write = ((text: string, callback: () => void) => {
			if (text !== "held frame") return Reflect.apply(original, process.stdout, [text, callback]);
			complete = callback;
			return false;
		}) as typeof process.stdout.write;
		terminal.write("held frame");
		tui = new TuiAltScreen(viewport);
		tui.renderGate = () => terminal.canRender();
		tui.addChild({ render: () => { renders++; return [`latest-${value}`]; }, invalidate: () => {} });
		tui.start();
		for (let index = 1; index <= 100; index++) { value = index; tui.requestRender(); }
		await new Promise((resolve) => setTimeout(resolve, 45));
		assert.equal(renders, 0, "blocked output must not cause repeated layout/parse work");
		complete();
		process.stdout.emit("drain");
		await viewport.waitForRender();
		assert.equal(renders, 1, "drain renders the newest accumulated state once");
		assert.match(viewport.getViewport().join("\n"), /latest-100/);
		tui.stop();
	} finally { process.stdout.write = original; }
});
