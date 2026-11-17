import { BackendClient } from "../bridge/client.ts";
import { PayloadAssembler } from "../bridge/payload-assembler.ts";
import type { WireMessage } from "../bridge/wire.ts";
import { prepareSession, type SessionStartup } from "./session.ts";
import { display } from "../presentation/control-text.ts";

/**
 * 无界面协议消费者：只以最终 task 结果决定完成，不打印流式预览，不读取 stdin。
 * 快照屏障和大结果校验与 TUI 同协议；正常结果后关闭本次专属会话。
 */
export async function runHeadless(startup: SessionStartup): Promise<number> {
	const assembler = new PayloadAssembler();
	let identity: { sessionId: string; generation: string } = { sessionId: "", generation: "" };
	let snapshot = "", sourceSeq = "", finished = false, code = 1;
	let complete!: (value: number) => void, fail!: (error: Error) => void;
	const result = new Promise<number>((resolve, reject) => { complete = resolve; fail = reject; });
	// 先安装拒绝处理；连接准备失败时不会遗留未处理的结果 Promise。
	void result.catch(() => {});
	const client = new BackendClient({ transport: startup.connection.stream,
		onFatal: (reason) => fail(new Error(reason)),
		onClosed: (reason) => finished ? complete(code) : fail(new Error(reason)),
		onEvent: (message) => {
			try { accept(message); } catch (error) { fail(error as Error); client.close(); }
		} });
	function accept(message: WireMessage): void {
		const p = message.payload;
		switch (message.type) {
			case "session.snapshot.begin":
				identity = { sessionId: message.sessionId!, generation: message.generation! };
				snapshot = String(p.snapshotId); sourceSeq = String(p.sourceSeq); break;
			case "session.snapshot.end":
				if (p.snapshotId !== snapshot) throw new Error("Snapshot identity mismatch");
				client.send("session.ready", { snapshotId: snapshot, generation: identity.generation, lastSnapshotSeq: sourceSeq }, identity); break;
			case "payload.begin": assembler.begin(String(p.id), String(p.kind), Number(p.totalBytes), String(p.sha256)); break;
			case "payload.chunk": assembler.chunk(String(p.id), Number(p.index), String(p.text)); break;
			case "payload.end": assembler.end(String(p.id), Number(p.count), String(p.sha256)); break;
			case "task.result": case "task.failed": {
				if (finished) break;
				const value = p.textBlobRef ? assembler.resolve(String(p.textBlobRef)) : p.text;
				if (message.type === "task.result" && typeof value !== "string") throw new Error("Missing complete task result");
				code = message.type === "task.failed" ? 1 : 0;
				if (code) process.stderr.write(`${String(p.message)}\n`);
				else if (String(value).trim()) process.stdout.write(`${display(String(value))}\n`);
				finished = true;
				void client.command("session.close", {}, identity).then((reply) => { if (!reply.ok) fail(new Error(reply.message)); }, fail);
				break;
			}
			case "server.stopped": if (!finished) throw new Error("Service stopped before task result"); complete(code); break;
		}
	}
	try {
		await client.open();
		const credential = await prepareSession(client, startup);
		await client.hello(credential, startup.installation.buildId);
		return await result;
	} finally { client.close(); }
}
