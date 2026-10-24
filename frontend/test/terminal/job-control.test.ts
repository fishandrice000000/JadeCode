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
