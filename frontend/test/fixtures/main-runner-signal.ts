import { runMain } from "../../src/app/main-runner.js";

let keepAlive: NodeJS.Timeout | undefined = setInterval(() => {}, 1_000);
const application = {
	run: () => new Promise<number>(() => {}),
	exitOutput: () => "",
	// 模拟 abort 关闭最后一个真实 handle：此后事件循环若先清空，仍必须保留信号退出码。
	abort: () => { if (keepAlive) clearInterval(keepAlive); keepAlive = undefined; },
};
void runMain(application, {
	signals: process,
	exit: (code) => process.exit(code),
	setExitCode: (code) => { process.exitCode = code; },
	writeStderr: () => {},
});
process.stdout.write("READY\n");
