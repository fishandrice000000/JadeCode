import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

/**
 * 外部编辑器交接：停止绘制/退出 raw → 编辑临时文件 → 恢复终端 → 采用同票据草稿。
 *
 * 编辑器继承真实终端；socket 继续接收事件（不暂停 Java 任务）。返回后只有“票据未变化”的
 * 结果被采用，避免覆盖用户在此期间形成的更新草稿。
 */
export interface EditorHandoff {
	/** 暂停 TUI（等 stdout 完成）。 */
	suspend: () => Promise<void>;
	/** 恢复 TUI。 */
	resume: () => void;
}

export class ExternalEditor {
	private counter = 0;

	/**
	 * @param command 配置的编辑器；空串表示未配置（按钮不可用）
	 * @returns 编辑后的完整草稿，用户取消/失败返回 null
	 */
	async edit(command: string, draft: string, handoff: EditorHandoff): Promise<{ text: string; ticket: number } | null> {
		if (!command.trim()) return null;
		const ticket = ++this.counter;
		const directory = mkdtempSync(join(tmpdir(), "jade-editor-"));
		const path = join(directory, "draft.md");
		try {
			writeFileSync(path, draft, "utf8");
			await handoff.suspend();
			let code: number;
			try {
				code = await new Promise<number>((resolve, reject) => {
					// 命令不经过 shell；普通程序与参数原样传递，文件路径始终是独立 argv。
					const parts = command.trim().split(/\s+/);
					const child = spawn(parts[0]!, [...parts.slice(1), path], { stdio: "inherit" });
					child.once("error", reject);
					child.once("exit", (exitCode) => resolve(exitCode ?? 1));
				});
			} finally {
				handoff.resume();
			}
			// 后一次编辑已经启动时，迟到的旧结果不能覆盖新草稿。
			if (code !== 0 || ticket !== this.counter) return null;
			return { text: readFileSync(path, "utf8"), ticket };
		} catch {
			return null;
		} finally {
			// 启动失败、非零退出、恢复失败和正常结果都经过同一临时资源清理。
			try { rmSync(directory, { recursive: true, force: true }); } catch { /* 临时目录清理失败不影响草稿。 */ }
		}
	}
}
