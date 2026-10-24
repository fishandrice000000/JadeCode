/**
 * Ctrl+Z/fg：把整个前台进程组挂起，恢复后重建终端与输入证据。
 *
 * Node 与 Java 在同一进程组；直接给自己发 SIGTSTP 会同时暂停后端（正确行为）。恢复绘制后
 * 递增 terminalEpoch，旧审批几何/按键证据全部作废，必须重新呈现；不重连、不重提任务。
 */
export interface JobControlHandoff {
	beforeSuspend: () => Promise<void>;
	afterResume: () => void;
}

export function suspendToShell(handoff: JobControlHandoff): void {
	void (async () => {
		await handoff.beforeSuspend();
		let resumed = false;
		let timer: NodeJS.Timeout | undefined;
		const resume = (): void => {
			if (resumed) return;
			resumed = true;
			if (timer) clearTimeout(timer);
			process.removeListener("SIGCONT", resume);
			handoff.afterResume();
		};
		process.once("SIGCONT", resume);
		// 某些无作业控制的环境里 kill(2) 成功返回但进程并不会真正停止；保留安全回退，
		// 不能让界面永久停在已暂停状态。真正停止时该定时器随之冻结，SIGCONT 会先清除它。
		timer = setTimeout(resume, 500);
		timer.unref?.();
		try {
			process.kill(0, "SIGTSTP");
		} catch {
			// 明确的不可挂起环境：立即恢复，不伪造成功。
			resume();
		}
	})();
}
