import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile, readFile, rm, chmod, symlink, link, access } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createHash } from "node:crypto";
import { unzipSync } from "fflate";
import { snapshot, download } from "../../src/cli/workspace.ts";
import type { BackendClient } from "../../src/bridge/client.ts";
import type { Payload } from "../../src/bridge/wire.ts";

test("snapshot preserves bytes and executable marker, filters protected paths, rejects links", async () => {
	const root = await mkdtemp(join(tmpdir(), "jade-zip-")), source = join(root, "source"); await mkdir(source);
	try {
		await writeFile(join(source, "run.sh"), "echo hello\n"); await chmod(join(source, "run.sh"), 0o700);
		await writeFile(join(source, ".env"), "excluded fixture"); await mkdir(join(source, "node_modules")); await writeFile(join(source, "node_modules/x"), "excluded");
		await snapshot(source, join(root, "a.zip")); await snapshot(source, join(root, "b.zip"));
		const bytes = await readFile(join(root, "a.zip"));
		assert.deepEqual(bytes, await readFile(join(root, "b.zip")));
		assert.deepEqual(Object.keys(unzipSync(bytes)), ["run.sh"]); assert.ok(bytes.includes(Buffer.from("jade:executable")));
		await symlink("run.sh", join(source, "alias")); await assert.rejects(snapshot(source, join(root, "bad.zip")), /ordinary/);
		await rm(join(source, "alias")); await link(join(source, "run.sh"), join(source, "hard")); await assert.rejects(snapshot(source, join(root, "hard.zip")), /unlinked/);
	} finally { await rm(root, { recursive: true, force: true }); }
});

test("download resumes exact bytes, refuses overwrites and removes only corrupt partial data", async () => {
	const root = await mkdtemp(join(tmpdir(), "jade-download-"));
	const bytes = Buffer.alloc(180_000); for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
	const sha256 = createHash("sha256").update(bytes).digest("hex"), offsets: number[] = [];
	const client = { serviceRequest: async (_type: string, p: Payload) => {
		const offset = Number(p.offset); offsets.push(offset); const data = bytes.subarray(offset, offset + 65536);
		return { size: bytes.length, sha256, offset, data: data.toString("base64"), eof: offset + data.length === bytes.length };
	} } as unknown as BackendClient;
	try {
		const target = join(root, "result.bin"), partial = `${target}.jade-artifact-fixture.part`;
		await writeFile(partial, bytes.subarray(0, 23000));
		await download(client, "workspace", "artifact-fixture", target);
		assert.equal(offsets[0], 23000); assert.deepEqual(await readFile(target), bytes);
		await assert.rejects(access(partial)); await assert.rejects(download(client, "workspace", "artifact-fixture", target), /already exists/);
		const broken = join(root, "broken.bin"), prefix = `${broken}.jade-artifact-fixture.part`;
		await writeFile(prefix, Buffer.alloc(23000));
		await assert.rejects(download(client, "workspace", "artifact-fixture", broken), /digest/);
		await assert.rejects(access(prefix)); await assert.rejects(access(broken));
		await download(client, "workspace", "artifact-fixture", broken); assert.deepEqual(await readFile(broken), bytes);
	} finally { await rm(root, { recursive: true, force: true }); }
});

test("a concurrent downloader cannot enter while another process holds the partial-file lock", async () => {
	const root = await mkdtemp(join(tmpdir(), "jade-download-lock-"));
	let entered!: () => void, release!: () => void;
	const ready = new Promise<void>((done) => { entered = done; });
	const wait = new Promise<void>((done) => { release = done; });
	const bytes = Buffer.from("lock fixture"), sha256 = createHash("sha256").update(bytes).digest("hex");
	const client = { serviceRequest: async () => { entered(); await wait; return { size: bytes.length, sha256, offset: 0, data: bytes.toString("base64"), eof: true }; } } as unknown as BackendClient;
	const target = join(root, "result.bin");
	const first = download(client, "workspace", "artifact-fixture", target);
	try {
		await ready;
		await assert.rejects(download(client, "workspace", "artifact-fixture", target), /Another client/);
		release(); await first;
		assert.deepEqual(await readFile(target), bytes);
	} finally { release(); await first.catch(() => {}); await rm(root, { recursive: true, force: true }); }
});
