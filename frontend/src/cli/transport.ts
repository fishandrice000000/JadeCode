import { connect, type Socket } from "node:net";
import { Duplex } from "node:stream";
import { spawn } from "node:child_process";
import { lstat, mkdir, readFile, open, realpath, access } from "node:fs/promises";
import { join, resolve, parse } from "node:path";
import { constants } from "node:fs";
import { UsageError } from "./options.ts";
import { homedir } from "node:os";

export interface Installation { buildId: string; protocolVersion: number; projectVersion: string; jar: string; }
export interface Connection { stream: Duplex; endpoint?: { pid: number; started: number }; }

/** 检查每级目录，拒绝符号链接；只创建缺失目录，不修改既有目录权限。 */
export async function privateDirectory(directory: string): Promise<void> {
	const absolute = resolve(directory);
	let cursor = parse(absolute).root;
	for (const part of absolute.slice(cursor.length).split("/").filter(Boolean)) {
		cursor = join(cursor, part);
		try { await mkdir(cursor, { mode: 0o700 }); } catch (e) { if ((e as NodeJS.ErrnoException).code !== "EEXIST") throw e; }
		if (!(await lstat(cursor)).isDirectory() || (await lstat(cursor)).isSymbolicLink()) throw new Error("Data directory contains a non-directory or symbolic link");
	}
}

/** 发行入口相对自身定位产物，绝不回退到源码或用户工作区。 */
export async function installation(): Promise<Installation> {
	const base = resolve(import.meta.dirname, "../..");
	let manifest;
	try { manifest = JSON.parse(await readFile(join(base, "frontend-manifest.json"), "utf8")); }
	catch { throw new UsageError("No valid paired client distribution; build with node scripts/build-release.mjs"); }
	if (typeof manifest.buildId !== "string" || !manifest.buildId || manifest.protocolVersion !== 1) throw new UsageError("Invalid client distribution; rebuild with node scripts/build-release.mjs");
	return { buildId: manifest.buildId, protocolVersion: manifest.protocolVersion, projectVersion: manifest.projectVersion, jar: resolve(base, "../jadecode.jar") };
}

/** 连接本机 socket；失败关闭本次 socket，不接管任何已有服务。 */
function socketAt(path: string): Promise<Socket> {
	return new Promise((accept, reject) => {
		const socket = connect(path);
		const timeout = setTimeout(() => socket.destroy(new Error("Service connection timed out")), 10_000);
		socket.once("error", reject);
		socket.once("connect", () => { clearTimeout(timeout); socket.setNoDelay(true); accept(socket); });
		socket.once("close", () => clearTimeout(timeout));
	});
}

/**
 * 取得本地直连或 SSH 字节通道。home 由调用者注入便于隔离测试，生产默认当前用户目录。
 * 本机服务用 setsid 独立启动；远端只执行固定服务入口，工作区参数另走协议 JSON。
 */
export async function connectService(info: Installation, remote?: string, home = homedir()): Promise<Connection> {
	if (remote) {
		if (!/^[A-Za-z0-9_][A-Za-z0-9_.@:\-]*$/.test(remote)) throw new Error("Invalid SSH target");
		const child = spawn("ssh", ["-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=2", "--", remote, "jade-server --service-relay"], { stdio: ["pipe", "pipe", "inherit"] });
		const stream = Duplex.from({ readable: child.stdout, writable: child.stdin });
		stream.on("error", () => { /* 在协议消费者安装前也不能产生未处理的 error 事件。 */ });
		child.on("error", (error) => stream.destroy(error));
		child.on("exit", (code, signal) => { if (code && !stream.destroyed) stream.destroy(new Error(`SSH exited: ${code ?? signal}`)); });
		stream.once("close", () => { if (child.exitCode === null) child.kill(); });
		return { stream };
	}
	const root = join(home, ".jadecode/service");
	await privateDirectory(root);
	const endpoint = join(root, "endpoint.json");
	const attempt = async (): Promise<Connection> => {
		if (!(await lstat(endpoint)).isFile()) throw new Error("Invalid service endpoint metadata");
		const metadata = JSON.parse(await readFile(endpoint, "utf8"));
		if (!Number.isSafeInteger(metadata.pid) || metadata.pid < 1 || !Number.isSafeInteger(metadata.started) || typeof metadata.socket !== "string") throw new Error("Invalid service endpoint metadata");
		return { stream: await socketAt(metadata.socket), endpoint: metadata };
	};
	try { return await attempt(); } catch (failure) {
		// 只对缺失/失效连接启动 daemon；畸形或被链接替换的端点不静默绕过。
		if (!['ENOENT', 'ECONNREFUSED'].includes((failure as NodeJS.ErrnoException).code ?? "")) throw failure;
	}
	const logPath = join(root, "daemon.log");
	try { if (!(await lstat(logPath)).isFile()) throw new Error("Invalid service log"); } catch (e) { if ((e as NodeJS.ErrnoException).code !== "ENOENT") throw e; }
	const log = await open(logPath, "a", 0o600);
	try {
		const java = join(process.env.JAVA21_HOME || "/usr/lib/jvm/java-21-openjdk-amd64", "bin/java");
		try { await realpath(info.jar); await access(java, constants.X_OK); }
		catch { throw new UsageError("Local execution requires Java 21 and a paired server JAR; check JAVA21_HOME and rebuild the distribution"); }
		const child = spawn("/usr/bin/setsid", ["--", java, `-Duser.home=${home}`, "-jar", info.jar, "--service-daemon"], { stdio: ["ignore", log.fd, log.fd], detached: true });
		await new Promise<void>((accept, reject) => { child.once("spawn", accept); child.once("error", reject); });
		child.unref();
	} finally { await log.close(); }
	const until = Date.now() + 15_000;
	while (Date.now() < until) {
		try { return await attempt(); } catch (error) {
			if (!["ENOENT", "ECONNREFUSED"].includes((error as NodeJS.ErrnoException).code ?? "")) throw error;
			await new Promise((done) => setTimeout(done, 40));
		}
	}
	throw new Error(`Agent service did not start; see ${logPath}`);
}
