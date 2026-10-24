import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const fixture = fileURLToPath(new URL("../fixtures/main-runner-signal.js", import.meta.url));

async function signal(sig: NodeJS.Signals): Promise<number | null> {
	const child = spawn(process.execPath, [fixture], { stdio: ["ignore", "pipe", "inherit"] });
	await new Promise<void>((resolve, reject) => {
		child.stdout.once("data", () => resolve());
		child.once("error", reject);
	});
	child.kill(sig);
	return await new Promise((resolve) => child.once("exit", (code) => resolve(code)));
}

test("the real entry exits with the signal code even when the event loop drains", async () => {
	assert.equal(await signal("SIGINT"), 130);
	assert.equal(await signal("SIGTERM"), 143);
});
