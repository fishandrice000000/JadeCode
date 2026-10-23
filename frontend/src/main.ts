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
