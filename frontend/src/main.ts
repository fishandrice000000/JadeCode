import { Application } from "./app/Application.ts";

/**
 * Node 前端进程入口。
 *
 * 只接受内部 bridge 环境（socket/凭据/构建身份）与标准终端环境；不读取 Java 认证、不解析
 * CLI 任务、不执行 Agent。顶层异常先恢复终端（Application.shutdown），再把真实原因写到
 * stderr，避免留下 raw mode / alternate screen。
 */
async function main(): Promise<number> {
	const application = new Application();
	// SIGINT/SIGTERM 默认会直接终止进程，raw mode/alternate screen 来不及恢复。
	// 先走 abort 同步停终端，再用约定的 128+signal 退出；重复信号不会二次清理。
	for (const [signal, code] of [["SIGINT", 130], ["SIGTERM", 143]] as const) {
		process.once(signal, () => {
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
		return code;
	} catch (error) {
		process.stderr.write(`JadeCode frontend failed: ${(error as Error).message}\n`);
		return 1;
	}
}

const code = await main();
process.exitCode = code;
