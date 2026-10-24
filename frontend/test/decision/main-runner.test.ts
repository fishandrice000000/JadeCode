import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { test } from "node:test";
import { runMain, type RunnerApplication, type RunnerHooks } from "../../src/app/main-runner.ts";

function harness(run: () => Promise<number>, output = ""): {
	app: RunnerApplication; signals: EventEmitter; exits: number[]; stderr: string[]; exitCodes: number[]; hooks: RunnerHooks;
} {
	const signals = new EventEmitter();
	const exits: number[] = [];
	const exitCodes: number[] = [];
	const stderr: string[] = [];
	const app: RunnerApplication = {
		run, exitOutput: () => output, abort: () => { stderr.push("abort"); },
	};
	return { app, signals, exits, stderr, exitCodes, hooks: { signals, exit: (code) => { exits.push(code); },
		setExitCode: (code) => { exitCodes.push(code); }, writeStderr: (text) => { stderr.push(text); } } };
}

test("SIGINT wins over a run that finishes with 1", async () => {
	const { app, signals, exitCodes, hooks } = harness(async () => 1);
	const waiting = runMain(app, hooks);
	signals.emit("SIGINT");
	assert.equal(await waiting, 130);
	assert.deepEqual(exitCodes, [130], "the process exit state is set immediately");
});

test("SIGTERM wins over a rejected run and keeps the real error output", async () => {
	const { app, signals, stderr, exitCodes, hooks } = harness(async () => { throw new Error("socket failed"); });
	const waiting = runMain(app, hooks);
	signals.emit("SIGTERM");
	assert.equal(await waiting, 143);
	assert.ok(stderr.some((line) => line.includes("socket failed")));
	assert.deepEqual(exitCodes, [143]);
});

test("a pending run falls back to the signal code through the exit hook", async () => {
	const { app, signals, exits, hooks } = harness(() => new Promise<number>(() => {}));
	void runMain(app, hooks);
	signals.emit("SIGINT");
	await new Promise((resolve) => setTimeout(resolve, 260));
	assert.deepEqual(exits, [130]);
});

test("without a signal the normal run result is preserved", async () => {
	const { app, hooks } = harness(async () => 2);
	assert.equal(await runMain(app, hooks), 2);
});
