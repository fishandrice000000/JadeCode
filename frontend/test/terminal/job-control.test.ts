import assert from "node:assert/strict";
import { test } from "node:test";
import { suspendToShell } from "../../src/services/job-control.ts";

const originalKill = process.kill;

test("Ctrl+Z suspends after preparing the terminal and resumes on SIGCONT", async () => {
	const events: string[] = [];
	let signal: NodeJS.Signals | number | undefined;
	(process as unknown as { kill: typeof process.kill }).kill = ((pid: number, value: NodeJS.Signals) => {
		events.push(`kill:${pid}:${value}`);
		signal = value;
	}) as typeof process.kill;
	try {
		suspendToShell({
			beforeSuspend: async () => { events.push("before"); },
			afterResume: () => { events.push("after"); },
		});
		await new Promise((resolve) => setTimeout(resolve, 0));
		assert.deepEqual(events, ["before", "kill:0:SIGTSTP"]);
		assert.equal(signal, "SIGTSTP");
		process.emit("SIGCONT");
		assert.equal(events.at(-1), "after");
		await new Promise((resolve) => setTimeout(resolve, 600));
		assert.deepEqual(events, ["before", "kill:0:SIGTSTP", "after"], "the fallback timer must not resume twice");
		assert.equal(process.listenerCount("SIGCONT"), 0, "no SIGCONT listener may be left behind");
	} finally {
		(process as unknown as { kill: typeof process.kill }).kill = originalKill;
	}
});

test("a shell without job control restores the UI instead of pretending to suspend", async () => {
	const events: string[] = [];
	(process as unknown as { kill: typeof process.kill }).kill = (() => {
		throw new Error("no job control");
	}) as typeof process.kill;
	try {
		suspendToShell({
			beforeSuspend: async () => { events.push("before"); },
			afterResume: () => { events.push("after"); },
		});
		await new Promise((resolve) => setTimeout(resolve, 0));
		assert.deepEqual(events, ["before", "after"]);
	} finally {
		(process as unknown as { kill: typeof process.kill }).kill = originalKill;
	}
});


test("kill succeeding without an actual stop falls back and resumes once", async () => {
	const events: string[] = [];
	const original = process.kill;
	(process as unknown as { kill: typeof process.kill }).kill = (() => true) as typeof process.kill;
	try {
		suspendToShell({
			beforeSuspend: async () => { events.push("before"); },
			afterResume: () => { events.push("after"); },
		});
		await new Promise((resolve) => setTimeout(resolve, 50));
		assert.deepEqual(events, ["before"], "the UI is still waiting while SIGTSTP appears to have been sent");
		await new Promise((resolve) => setTimeout(resolve, 550));
		assert.deepEqual(events, ["before", "after"], "a non-stopping environment must not leave the UI suspended");
		assert.equal(process.listenerCount("SIGCONT"), 0, "the fallback removes its listener");
	} finally {
		(process as unknown as { kill: typeof process.kill }).kill = original;
	}
});
