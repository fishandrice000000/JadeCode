import assert from "node:assert/strict";
import { createServer, type Socket } from "node:net";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { BackendClient } from "../../src/bridge/client.ts";

/** 真实 UDS peer 暂停读取，验证 Node 不把内核背压转成无界 writable buffer。 */
test("a paused UDS peer drains ordered frames and overload closes once", async () => {
	const directory = mkdtempSync(join(tmpdir(), "jade-node-slow-"));
	const socketPath = join(directory, "s");
	let peer: Socket | undefined;
	let ready!: () => void;
	const connected = new Promise<void>((resolve) => { ready = resolve; });
	const received: string[] = [];
	let buffer = "";
	const server = createServer((socket) => {
		peer = socket;
		socket.pause();
		socket.on("data", (data) => {
			buffer += data.toString();
			let end: number;
			while ((end = buffer.indexOf("\n")) >= 0) {
				received.push(JSON.parse(buffer.slice(0, end)).seq);
				buffer = buffer.slice(end + 1);
			}
		});
		ready();
	});
	await new Promise<void>((resolve) => server.listen(socketPath, resolve));
	const failures: string[] = [];
	let closed = 0;
	const client = new BackendClient({ socketPath, onEvent: () => {}, onFatal: (message) => failures.push(message), onClosed: () => { closed += 1; } });
	try {
		await client.open();
		await connected;
		for (let i = 0; i < 200; i++) client.send("state.refresh", { text: "x".repeat(1024) }, {});
		assert.equal(client.isClosed(), false);
		peer!.resume();
		const deadline = Date.now() + 2000;
		while (received.length < 200 && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 5));
		assert.deepEqual(received, Array.from({ length: 200 }, (_, i) => String(i + 1)));
		peer!.pause();
		assert.throws(() => {
			for (let i = 0; i < 100; i++) client.send("state.refresh", { text: "x".repeat(128 * 1024) }, {});
		}, /outbound connection budget/);
		assert.equal(client.isClosed(), true);
		assert.equal(closed, 1);
		assert.equal(failures.length, 1);
		await assert.rejects(client.command("state.refresh", {}), /closed/);
	} finally {
		client.close();
		peer?.destroy();
		await new Promise<void>((resolve) => server.close(() => resolve()));
		rmSync(directory, { recursive: true, force: true });
	}
});
