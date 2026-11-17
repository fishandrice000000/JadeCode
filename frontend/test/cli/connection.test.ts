import test from "node:test";
import assert from "node:assert/strict";
import { createServer, connect, type Socket } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BackendClient } from "../../src/bridge/client.ts";
import { prepareSession } from "../../src/cli/session.ts";
import { Duplex } from "node:stream";
import { chmod, writeFile, readFile } from "node:fs/promises";
import { connectService } from "../../src/cli/transport.ts";

function frame(type: string, payload = {}, seq = 1): string {
	return JSON.stringify({ version: 1, connectionId: type.startsWith("service.") ? "" : "connection", seq: String(seq), type, commandId: null, sessionId: null, generation: null, payload }) + "\n";
}

test("direct socket delivers small requests without EOF and preserves coalesced handshake events", async () => {
	const root = await mkdtemp(join(tmpdir(), "jade-direct-")), socket = join(root, "s");
	const peers: Socket[] = [];
	const server = createServer((peer) => {
		peers.push(peer); let buffer = "";
		peer.on("data", (data) => {
			buffer += data.toString();
			let newline;
			while ((newline = buffer.indexOf("\n")) >= 0) {
				const request = JSON.parse(buffer.slice(0, newline)); buffer = buffer.slice(newline + 1);
				if (request.type === "service.identity") peer.write(frame("service.result", { pid: 12, started: 34, paired: true, buildId: "fixture", protocolVersion: 1 }));
				else if (request.type === "service.open") peer.write(frame("service.result", { credential: "test-only" }));
				else if (request.type === "client.hello") {
					const bytes = Buffer.from(frame("server.hello") + frame("server.bootstrap", { text: "中文🙂" }, 2) + frame("state.snapshot", {}, 3));
					peer.write(bytes.subarray(0, bytes.length - 7)); peer.write(bytes.subarray(bytes.length - 7));
				}
			}
		});
	});
	await new Promise<void>((done) => server.listen(socket, done));
	const events: string[] = [];
	const stream = connect(socket);
	const client = new BackendClient({ transport: stream, onEvent: (m) => { events.push(m.type); }, onClosed: () => {}, onFatal: (reason) => assert.fail(reason) });
	try {
		await client.open();
		const credential = await prepareSession(client, { connection: { stream, endpoint: { pid: 12, started: 34 } }, installation: { buildId: "fixture", protocolVersion: 1, projectVersion: "test", jar: "/unused" }, request: {} });
		await client.hello(credential, "fixture");
		assert.deepEqual(events, ["server.hello", "server.bootstrap", "state.snapshot"]);
		assert.equal(client.isClosed(), false);
	} finally { client.close(); for (const peer of peers) peer.destroy(); await new Promise<void>((done) => server.close(() => done())); await rm(root, { recursive: true, force: true }); }
});

test("identity mismatch rejects before service.open", async () => {
	let opens = 0;
	const fake = { serviceRequest: async (type: string) => { if (type === "service.open") opens++; return { pid: 12, started: 35, paired: true, buildId: "fixture", protocolVersion: 1 }; } };
	await assert.rejects(prepareSession(fake as unknown as BackendClient, { connection: { stream: null!, endpoint: { pid: 12, started: 34 } }, installation: { buildId: "fixture", protocolVersion: 1, projectVersion: "test", jar: "/unused" }, request: {} }), /identity changed/);
	assert.equal(opens, 0);
});

test("closing during hello rejects immediately instead of leaving the handshake timer alive", async () => {
	const stream = new Duplex({ read() {}, write(_chunk, _encoding, done) { done(); } });
	const client = new BackendClient({ transport: stream, onEvent: () => {}, onClosed: () => {}, onFatal: () => {} });
	await client.open();
	const hello = client.hello("fixture", "fixture");
	client.close();
	await assert.rejects(hello, /closed/);
	// 关闭后再次发起握手也必须立即拒绝，不能先创建一个无人接收的超时等待。
	await assert.rejects(client.hello("fixture", "fixture"), /closed/);
});

test("Node owns SSH directly and uses a fixed remote server command", async () => {
	const root = await mkdtemp(join(tmpdir(), "jade-ssh-")), record = join(root, "arguments");
	const prior = process.env.PATH;
	try {
		await writeFile(join(root, "ssh"), `#!/bin/sh\nprintf '%s\\n' "$@" > '${record}'\nexec /bin/cat\n`);
		await chmod(join(root, "ssh"), 0o700); process.env.PATH = root;
		const { stream } = await connectService({ buildId: "fixture", protocolVersion: 1, projectVersion: "test", jar: "/unused" }, "fixture-host");
		try {
			const received = new Promise<Buffer>((done, reject) => { stream.once("data", done); stream.once("error", reject); });
			stream.write("small-frame\n");
			assert.equal((await received).toString(), "small-frame\n");
			const args = (await readFile(record, "utf8")).trim().split("\n");
			assert.equal(args.at(-1), "jade-server --service-relay"); assert.ok(args.includes("BatchMode=yes")); assert.ok(args.includes("fixture-host"));
		} finally { stream.destroy(); }
	} finally { process.env.PATH = prior; await rm(root, { recursive: true, force: true }); }
});
