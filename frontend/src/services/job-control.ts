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
		try {
			process.kill(0, "SIGTSTP");
		} catch {
			// 不可挂起的环境（无作业控制）恢复界面并继续，不伪造成功。
			handoff.afterResume();
			return;
		}
		process.once("SIGCONT", () => handoff.afterResume());
	})();
}
