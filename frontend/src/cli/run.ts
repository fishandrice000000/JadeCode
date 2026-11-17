import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { BackendClient } from "../bridge/client.ts";
import type { Payload } from "../bridge/wire.ts";
import { parseOptions, UsageError, HELP, type Options } from "./options.ts";
import { connectService, installation } from "./transport.ts";
import { verifyService } from "./session.ts";
import { runHeadless } from "./headless.ts";
import { workspaceRequest, upload, download } from "./workspace.ts";

/** 编译会话启动意图；路径在执行机器解释，远端仅得到配置路径，不上传配置正文。 */
export function sessionRequest(o: Options): Payload {
	const request: Payload = { workspace: o.workspace ? o.remote ? o.workspace : resolve(o.workspace) : process.cwd(),
		prompt: o.prompt, headless: o.headless, submissionId: randomUUID() };
	for (const [key, value] of Object.entries({ attach: o.attach, isolated: o.isolated || undefined, workspaceId: o.workspaceId,
		resume: o.resume, picker: o.resume === "" || undefined, permission: o.permission,
		modelConfig: o.modelConfig ? o.remote ? o.modelConfig : resolve(o.modelConfig) : undefined })) {
		if (value !== undefined) request[key] = value;
	}
	return request;
}

/**
 * 所有 jade 命令的唯一客户端入口。先校验参数和环境，再连接服务；仅交互分支动态加载 TUI。
 * 错误写 stderr：2 为用法错误，1 为执行或传输失败，0 为正常协议完成。
 */
export async function runCli(args: string[]): Promise<number> {
	let client: BackendClient | undefined;
	let connection: Awaited<ReturnType<typeof connectService>> | undefined;
	try {
		const o = parseOptions(args);
		if (o.help) { process.stdout.write(HELP); return 0; }
		const info = await installation();
		if (o.version) { process.stdout.write(`JadeCode ${info.projectVersion}\n`); return 0; }
		const management = o.list || o.stop || o.close || o.cancel;
		const workspaceManagement = o.workspaceList || o.workspaceCreate || o.workspaceDelete || o.artifacts || o.upload || o.artifact;
		if (!management && !workspaceManagement && !o.headless && (!process.stdin.isTTY || !process.stdout.isTTY)) throw new UsageError("The interactive UI needs a real terminal; use --headless");
		connection = await connectService(info, o.remote);
		if (management || workspaceManagement) {
			let failure: Error | undefined;
			client = new BackendClient({ transport: connection.stream, onEvent: () => {}, onClosed: () => {}, onFatal: (reason) => { failure = new Error(reason); } });
			await client.open();
			await verifyService(client, connection, info);
			if (management) {
				const reply = await client.serviceRequest(o.stop ? "service.stop" : o.close ? "service.close" : o.cancel ? "service.cancel" : "service.list", { ...(o.close || o.cancel ? { id: o.close || o.cancel } : {}) });
				if (o.list) process.stdout.write(`${JSON.stringify(reply, null, 2)}\n`);
			} else if (o.upload) process.stdout.write(`${JSON.stringify(await upload(client, o.workspaceId!, o.upload), null, 2)}\n`);
			else if (o.artifact) await download(client, o.workspaceId!, o.artifact, o.output!);
			else process.stdout.write(`${JSON.stringify(await workspaceRequest(client, o.workspaceCreate ? "create" : o.workspaceList ? "list" : o.workspaceDelete ? "delete" : "artifacts", o.workspaceId), null, 2)}\n`);
			if (failure) throw failure;
			return 0;
		}
		const startup = { connection, installation: info, request: sessionRequest(o) };
		if (o.headless) return await runHeadless(startup);
		const [{ Application }, { runMain }] = await Promise.all([import("../app/Application.ts"), import("../app/main-runner.ts")]);
		const application = new Application(startup);
		const fatal = (error: unknown) => { application.abort("Unhandled client failure", error); process.exitCode = 1; };
		process.on("uncaughtException", fatal); process.on("unhandledRejection", fatal);
		try {
			return await runMain(application, { signals: process, exit: (code) => process.exit(code), setExitCode: (code) => { process.exitCode = code; }, writeStderr: (value) => process.stderr.write(value), writeStdout: (value) => process.stdout.write(value) });
		} finally { process.removeListener("uncaughtException", fatal); process.removeListener("unhandledRejection", fatal); }
	} catch (error) {
		process.stderr.write(`JadeCode: ${(error as Error).message}\n`);
		return error instanceof UsageError ? 2 : 1;
	} finally { client?.close(); connection?.stream.destroy(); }
}
