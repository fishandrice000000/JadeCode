import { Application } from "./app/Application.ts";
import { runMain } from "./app/main-runner.ts";

/**
 * Node 前端进程入口。
 *
 * 只接受内部 bridge 环境（socket/凭据/构建身份）与标准终端环境；不读取 Java 认证、不解析
 * CLI 任务、不执行 Agent。顶层异常先恢复终端（Application.shutdown），再把真实原因写到
 * stderr，避免留下 raw mode / alternate screen。
 */
async function main(): Promise<number> {
	const application = new Application();
	// socket 回调 / Pi render 中的异常不能只交给未捕获处理器静默退出；先恢复终端。
	process.on("uncaughtException", (error) => {
		application.abort("Uncaught frontend exception", error);
		process.exitCode = 1;
	});
	process.on("unhandledRejection", (error) => {
		application.abort("Unhandled frontend rejection", error);
		process.exitCode = 1;
	});
	return runMain(application, {
		signals: process,
		exit: (code) => process.exit(code),
		setExitCode: (code) => { process.exitCode = code; },
		writeStderr: (text) => process.stderr.write(text),
		writeStdout: (text) => process.stdout.write(text),
	});
}

const code = await main();
process.exitCode = code;
