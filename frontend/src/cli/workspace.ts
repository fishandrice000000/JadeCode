import { constants } from "node:fs";
import { access, lstat, readdir, realpath, open, mkdtemp, rm, link, unlink } from "node:fs/promises";
import { join, resolve, dirname, basename, relative } from "node:path";
import { tmpdir, homedir } from "node:os";
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { Zip, ZipDeflate } from "fflate";
import type { BackendClient } from "../bridge/client.ts";
import { privateDirectory } from "./transport.ts";
import type { Payload } from "../bridge/wire.ts";

const MAX_BYTES = 256 * 1024 * 1024, MAX_FILES = 30_000, CHUNK = 64 * 1024;
const EXCLUDED = new Set([".ssh", ".aws", ".gnupg", ".jadecode", ".codex", ".docker", ".kube", ".config", ".m2", ".npmrc", ".pypirc", ".netrc", ".git-credentials", ".git", ".agents", ".claude", ".vscode", ".idea", ".venv", "venv", "node_modules", "__pycache__"]);

/** 与服务端的快照保护契约一致；按路径过滤，不读取被排除文件的正文。 */
export function excluded(name: string): boolean {
	const parts = name.split("/");
	return parts.some((part) => EXCLUDED.has(part) || part === ".env" || part.startsWith(".env.")) || [".bashrc", ".zshrc", ".profile"].includes(parts.at(-1)!);
}

/** 对已落盘文件流式计算摘要，不把整个快照或成果放进内存。 */
async function hash(file: string): Promise<string> {
	const digest = createHash("sha256"), input = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW);
	try { for await (const chunk of input.createReadStream({ autoClose: false })) digest.update(chunk); }
	finally { await input.close(); }
	return digest.digest("hex");
}

/**
 * 生成有界、顺序及时间固定的 ZIP。只接受普通单链接文件，输出保持 executable 注释契约。
 * 文件按 64 KiB 顺序压缩并排空输出；任何筛选/读取失败都让整个上传失败。
 */
export async function snapshot(source: string, destination: string): Promise<void> {
	const root = await realpath(source);
	if (!(await lstat(root)).isDirectory() || excluded(root) || root === homedir() || root.split("/").filter(Boolean).length < 2) throw new Error("Choose a narrow ordinary project directory");
	const files: string[] = [];
	let visited = 0, total = 0;
	async function walk(directory: string): Promise<void> {
		if (++visited > 200_000) throw new Error("Workspace entry limit exceeded");
		for (const item of (await readdir(directory)).sort()) {
			const file = join(directory, item), name = relative(root, file);
			if (excluded(name)) continue;
			if (++visited > 200_000) throw new Error("Workspace entry limit exceeded");
			if (!name || name.length > 4096 || /[\\\x00-\x1f]/.test(name) || name.split("/").includes("..")) throw new Error("Invalid workspace-relative path");
			const stat = await lstat(file);
			if (stat.isDirectory()) await walk(file);
			else {
				if (!stat.isFile() || stat.nlink !== 1) throw new Error(`Only ordinary, unlinked files may be bundled: ${name}`);
				if (files.length >= MAX_FILES) throw new Error("Workspace snapshot limit exceeded");
				files.push(name);
			}
		}
	}
	await walk(root);
	total = 0;
	const output = await open(destination, "wx", 0o600);
	let writes = Promise.resolve();
	const zip = new Zip((error, data) => {
		if (error) throw error;
		writes = writes.then(async () => { let offset = 0; while (offset < data.length) offset += (await output.write(data, offset, data.length - offset)).bytesWritten; });
	});
	try {
		for (const name of files.sort()) {
			const file = join(root, name), stat = await lstat(file);
			if (!stat.isFile() || stat.nlink !== 1 || await realpath(file) !== file) throw new Error("Snapshot source changed");
			const entry = new ZipDeflate(name);
			entry.mtime = new Date("1980-01-01T00:00:00Z");
			try { await access(file, constants.X_OK); entry.comment = "jade:executable"; } catch { /* 无执行权限，无需附加注释。 */ }
			zip.add(entry);
			const input = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW);
			try {
				const buffer = Buffer.alloc(CHUNK);
				while (true) {
					const { bytesRead } = await input.read(buffer);
					if (!bytesRead) break;
					if ((total += bytesRead) > MAX_BYTES) throw new Error("Workspace snapshot limit exceeded");
					entry.push(buffer.subarray(0, bytesRead));
					await writes;
				}
				entry.push(new Uint8Array(), true); await writes;
			} finally { await input.close(); }
		}
		zip.end(); await writes;
		if ((await output.stat()).size > MAX_BYTES) throw new Error("Archive limit exceeded");
	} finally { await writes.catch(() => {}); await output.close(); }
}

/** 显式工作区管理请求，不经过 TUI；服务端仍拥有最终授权与校验。 */
export function workspaceRequest(client: BackendClient, action: string, workspaceId?: string, fields: Payload = {}): Promise<Payload> {
	return client.serviceRequest("service.workspace", { action, ...(workspaceId ? { workspaceId } : {}), ...fields });
}

/** 完成快照后按服务 offset 分块上传；临时 ZIP 无论成功失败都清理。 */
export async function upload(client: BackendClient, workspaceId: string, source: string): Promise<Payload> {
	const directory = await mkdtemp(join(tmpdir(), "jade-snapshot-"));
	try {
		const archive = join(directory, "snapshot.zip"); await snapshot(source, archive);
		let state = await workspaceRequest(client, "upload.begin", workspaceId, { size: (await lstat(archive)).size, sha256: await hash(archive) });
		if (state.state === "READY") return state;
		const file = await open(archive, "r");
		try {
			let offset = Number(state.offset);
			if (!Number.isSafeInteger(offset) || offset < 0 || offset > (await file.stat()).size) throw new Error("Invalid snapshot offset");
			const buffer = Buffer.alloc(CHUNK);
			while (true) {
				const { bytesRead } = await file.read(buffer, 0, CHUNK, offset);
				if (!bytesRead) break;
				const id = state.id;
				state = await workspaceRequest(client, "upload.chunk", workspaceId, { resourceId: id, offset, data: buffer.subarray(0, bytesRead).toString("base64") });
				offset += bytesRead;
				if (Number(state.offset) !== offset) throw new Error("Server snapshot offset mismatch");
				// 部分响应不重发 id，保留 begin 领取的身份。
				state.id = id;
			}
			return await workspaceRequest(client, "upload.finish", workspaceId, { resourceId: state.id });
		} finally { await file.close(); }
	} finally { await rm(directory, { recursive: true, force: true }); }
}

/** 用持久锁文件加内核锁；父进程死亡时管道 EOF 使持锁进程退出。 */
async function lock(path: string): Promise<{ release: () => Promise<void>; assertHeld: () => void }> {
	const descriptor = await open(path, constants.O_CREAT | constants.O_RDWR | constants.O_NOFOLLOW, 0o600);
	const child = spawn("/usr/bin/flock", ["-F", "-n", "-E", "75", path, process.execPath, join(import.meta.dirname, "lock-holder.js")], { stdio: ["pipe", "pipe", "inherit"] });
	try {
		await new Promise<void>((accept, reject) => {
			let response = "";
			const timer = setTimeout(() => reject(new Error("Download lock handshake timed out")), 10_000);
			const failure = (error: Error) => { clearTimeout(timer); reject(error); };
			child.once("error", failure);
			child.once("exit", () => failure(new Error("Another client is downloading this artifact, or the download lock failed")));
			child.stdout!.on("data", (chunk: Buffer) => {
				response += chunk.toString();
				if (response === "LOCKED\n") { clearTimeout(timer); accept(); }
				else if (response.includes("\n") || response.length > 64) failure(new Error("Invalid lock handshake"));
			});
		});
	} catch (error) { child.stdin!.destroy(); child.kill(); throw error; }
	finally { await descriptor.close(); }
	return {
		assertHeld: () => { if (child.exitCode !== null || child.signalCode !== null) throw new Error("Download lock was lost"); },
		release: async () => {
			if (child.exitCode !== null || child.signalCode !== null) return;
			await new Promise<void>((done) => { child.once("exit", () => done()); child.stdin!.end(); });
		},
	};
}

/**
 * 下载保留私有 .part 前缀，验证元数据、长度和摘要后硬链接发布，绝不覆盖用户目标。
 * 普通断连保留前缀；摘要损坏删除该前缀，下次从零开始。锁文件保留以防 inode 分裂。
 */
export async function download(client: BackendClient, workspaceId: string, artifact: string, destination: string): Promise<void> {
	if (!/^[A-Za-z0-9_-]{1,120}$/.test(artifact)) throw new Error("Invalid artifact identity");
	const target = resolve(destination);
	await privateDirectory(dirname(target));
	try { await lstat(target); throw new Error("Download destination already exists"); } catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
	const partial = join(dirname(target), `${basename(target)}.jade-${artifact}.part`);
	const lease = await lock(`${partial}.lock`);
	try {
		const file = await open(partial, constants.O_CREAT | constants.O_RDWR | constants.O_NOFOLLOW, 0o600);
		try {
			if (!(await file.stat()).isFile() || (await file.stat()).nlink !== 1) throw new Error("Invalid partial download path");
			await file.chmod(0o600);
			let offset = (await file.stat()).size, expected = -1, sha = "";
			while (true) {
				const part = await workspaceRequest(client, "artifact.read", workspaceId, { artifactId: artifact, offset });
				if (expected < 0) { expected = Number(part.size); sha = String(part.sha256); }
				if (!Number.isSafeInteger(expected) || expected < 0 || expected > MAX_BYTES || part.offset !== offset || part.size !== expected || part.sha256 !== sha || !/^[a-f0-9]{64}$/.test(sha) || typeof part.data !== "string" || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(part.data)) throw new Error("Artifact metadata or encoding changed");
				lease.assertHeld();
				const bytes = Buffer.from(part.data, "base64");
				if (bytes.length > CHUNK || offset + bytes.length > expected || !bytes.length && offset < expected) throw new Error("Invalid artifact chunk");
				let written = 0; while (written < bytes.length) written += (await file.write(bytes, written, bytes.length - written, offset + written)).bytesWritten; offset += bytes.length;
				if (part.eof === true) break;
			}
			await file.sync();
			if (offset !== expected || await hash(partial) !== sha) { await unlink(partial); throw new Error("Artifact digest or length mismatch; corrupt temporary data removed"); }
			lease.assertHeld();
			await link(partial, target); await unlink(partial);
		} finally { await file.close(); }
	} finally { await lease.release(); }
}
