import { Application } from "./app/Application.ts";

/**
 * Node 前端进程入口。
 *
 * 只接受内部 bridge 环境（socket/凭据/构建身份）与标准终端环境；不读取 Java 认证、不解析
 * CLI 任务、不执行 Agent。顶层异常先恢复终端（Application.shutdown），再把真实原因写到
 * stderr，避免留下 raw mode / alternate screen。
 */
/** 已收到终止信号后的退出身份；run 正常/异常收尾都不能覆盖它。 */
let signalExitCode: number | undefined;

async function main(): Promise<number> {
	const application = new Application();
	// SIGINT/SIGTERM 默认会直接终止进程，raw mode/alternate screen 来不及恢复。
	// 先走 abort 同步停终端，再用约定的 128+signal 退出；重复信号不会二次清理。
	for (const [signal, code] of [["SIGINT", 130], ["SIGTERM", 143]] as const) {
		process.once(signal, () => {
			signalExitCode = code;
			application.abort(`Interrupted by ${signal}`);
			process.exitCode = code;
			// 给同步终端恢复/最后一次写入一个极短窗口，不让关闭流程无限挂住。
			const timer = setTimeout(() => process.exit(code), 200);
			timer.unref?.();
		});
	}
	// socket 回调 / Pi render 中的异常不能只交给未捕获处理器静默退出；先恢复终端。
	process.on("uncaughtException", (error) => {
		application.abort("Uncaught frontend exception", error);
		process.exitCode = 1;
	});
	process.on("unhandledRejection", (error) => {
		application.abort("Unhandled frontend rejection", error);
		process.exitCode = 1;
	});
	try {
		const code = await application.run();
		const failure = application.exitOutput();
		if (failure) process.stderr.write(`JadeCode frontend: ${failure}\n`);
		// abort 让 run 收尾时可能返回 1；信号码仍是本次进程的真实退出身份。
		return signalExitCode ?? code;
	} catch (error) {
		process.stderr.write(`JadeCode frontend failed: ${(error as Error).message}\n`);
		return signalExitCode ?? 1;
	}
}

const code = await main();
process.exitCode = signalExitCode ?? code;
