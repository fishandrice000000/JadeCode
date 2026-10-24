import assert from "node:assert/strict";
import { test } from "node:test";
import { Application } from "../../src/app/Application.ts";

type Internals = { onClosed(reason: string): void; waitForExit(): Promise<number>;
	stoppedReceived: boolean; closing: boolean };

test("an unexpected backend close is reported and exits non-zero", async () => {
	const app = new Application();
	const internals = app as unknown as Internals;
	internals.onClosed("socket EOF");
	assert.match(app.exitOutput(), /closed before server\.stopped: socket EOF/);
	assert.equal(await internals.waitForExit(), 1, "an error exit is distinct from a clean quit");
});

test("a close after server.stopped is a clean exit without an error message", async () => {
	const app = new Application();
	const internals = app as unknown as Internals;
	internals.stoppedReceived = true;
	internals.onClosed("socket EOF");
	assert.equal(app.exitOutput(), "");
	assert.equal(await internals.waitForExit(), 0);
});

test("a user-initiated close still keeps an unexpected reason if server.stopped never arrived", async () => {
	const app = new Application();
	const internals = app as unknown as Internals;
	internals.closing = true;
	internals.onClosed("connection reset");
	assert.match(app.exitOutput(), /closed unexpectedly: connection reset/);
	assert.equal(await internals.waitForExit(), 1);
});
