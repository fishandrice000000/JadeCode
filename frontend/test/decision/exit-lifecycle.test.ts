import assert from "node:assert/strict";
import { test } from "node:test";
import { Application } from "../../src/app/Application.ts";

type Internals = {
	onClosed(reason: string): void; waitForExit(): Promise<number>; quit(): Promise<void>;
	stoppedReceived: boolean; closing: boolean; closingAt: number;
	client: { isClosed(): boolean };
};

function harness(): { app: Application; internals: Internals; state: { closed: boolean } } {
	const app = new Application();
	const state = { closed: false };
	const internals = app as unknown as Internals;
	internals.client = {
		isClosed: () => state.closed,
		command: async () => ({ ok: true, type: "command.result", payload: {} }),
	} as never;
	(app as unknown as { session: unknown }).session = { sessionId: () => null, generation: () => null };
	return { app, internals, state };
}

test("an unexpected close before server.stopped exits non-zero", async () => {
	const { app, internals, state } = harness();
	internals.onClosed("socket EOF");
	state.closed = true;
	assert.match(app.exitOutput(), /closed before server\.stopped: socket EOF/);
	assert.equal(await internals.waitForExit(), 1);
});

test("a close after server.stopped is a clean exit", async () => {
	const { app, internals, state } = harness();
	internals.stoppedReceived = true;
	internals.onClosed("socket EOF");
	state.closed = true;
	assert.equal(app.exitOutput(), "");
	assert.equal(await internals.waitForExit(), 0);
});

test("a user quit waits for the backend to stop instead of returning success early", async () => {
	const { app, internals, state } = harness();
	const waiting = internals.waitForExit();
	await internals.quit();
	let settled = false;
	void waiting.then(() => { settled = true; });
	await new Promise((resolve) => setTimeout(resolve, 150));
	assert.equal(settled, false, "quit intent alone must not settle the exit code");
	internals.stoppedReceived = true;
	state.closed = true;
	assert.equal(await waiting, 0);
	assert.equal(app.exitOutput(), "");
});

test("a premature EOF after quit keeps the real error and exits non-zero", async () => {
	const { app, internals, state } = harness();
	const waiting = internals.waitForExit();
	await internals.quit();
	internals.onClosed("connection reset");
	state.closed = true;
	assert.equal(await waiting, 1);
	assert.match(app.exitOutput(), /closed unexpectedly: connection reset/);
});

test("a backend that never stops hits a bounded timeout and exits non-zero", async () => {
	const { app, internals } = harness();
	const waiting = internals.waitForExit();
	await internals.quit();
	internals.closingAt = Date.now() - 20_000;
	assert.equal(await waiting, 1);
	assert.match(app.exitOutput(), /Timed out waiting for the backend to stop/);
});
