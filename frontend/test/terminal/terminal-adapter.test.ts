import assert from "node:assert/strict";
import { test } from "node:test";
import { JadeTerminal } from "../../src/terminal/terminal-adapter.ts";

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
