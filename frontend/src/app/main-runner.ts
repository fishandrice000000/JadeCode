import type { EventEmitter } from "node:events";

/**
 * 前端入口的退出编排；与真实 Application 解耦，便于回归信号时序。
 *
 * 关键不变量：一旦收到 SIGINT/SIGTERM，退出身份固定为 128+signal，
 * 不会被 run 的正常返回、异常返回或顶层赋值覆盖；终端清理仍先于退出。
 */
export interface RunnerApplication {
	run(): Promise<number>;
	exitOutput(): string;
	abort(reason: string, error?: unknown): void;
}

export interface RunnerHooks {
	/** 提供 once(signal, listener)；生产为 process，测试可注入独立事件源。 */
	signals: Pick<EventEmitter, "once">;
	/** 顶层强退入口；生产为 process.exit。 */
	exit(code: number): void;
	/** 立即写入进程退出状态；即使随后事件循环先清空，也不能退化成未完成 await 的 13。 */
	setExitCode(code: number): void;
	writeStderr(text: string): void;
}

export async function runMain(application: RunnerApplication, hooks: RunnerHooks): Promise<number> {
	let signalExitCode: number | undefined;
	for (const [signal, code] of [["SIGINT", 130], ["SIGTERM", 143]] as const) {
		hooks.signals.once(signal, () => {
			signalExitCode = code;
			hooks.setExitCode(code);
			application.abort(`Interrupted by ${signal}`);
			// 给同步终端恢复/最后一次写入一个极短窗口，不让关闭流程无限挂住。
			const timer = setTimeout(() => hooks.exit(code), 200);
			timer.unref?.();
		});
	}
	try {
		const code = await application.run();
		const failure = application.exitOutput();
		if (failure) hooks.writeStderr(`JadeCode frontend: ${failure}\n`);
		// abort 让 run 收尾时可能返回 1；信号码仍是本次进程的真实退出身份。
		return signalExitCode ?? code;
	} catch (error) {
		hooks.writeStderr(`JadeCode frontend failed: ${(error as Error).message}\n`);
		return signalExitCode ?? 1;
	}
}
