import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFile } from "node:child_process";
import { getNativeClipboard } from "@earendil-works/pi-tui";

/**
 * 剪贴板：复制文本/读取图片路径。
 *
 * - 复制：本地优先桌面后端；SSH 发 OSC52，tmux 使用 load-buffer -w 由服务器发送。
 *   发送成功的短提示不是客户端 ACK；内部仍区分桌面确认、终端发送与真实失败。
 * - 图片：只显式读取剪贴板图片，保存到本进程拥有的临时文件并把**路径**插入草稿；
 *   JadeCode 没有 vision 能力，这不是图像消息，也不自动读取文件内容。
 */
export type CopyStatus = "DESKTOP" | "OSC52_SENT" | "TMUX_SENT" | "FAILED";

export class ClipboardController {
	private readonly owned = new Set<string>();

	/** 生产使用 native 后端与 stdout；测试注入本地夹具，绝不改动用户桌面剪贴板。 */
	constructor(private readonly native = getNativeClipboard,
		private readonly writeOsc = (value: string): Promise<void> => new Promise((resolve, reject) => {
			process.stdout.write(value, (error) => error ? reject(error) : resolve());
		}),
		// 远端桌面的 setText 成功不等于用户客户端已复制；SSH/tmux 始终交由外层终端处理。
		private readonly remote = () => Boolean(process.env.SSH_CONNECTION || process.env.SSH_TTY || process.env.SSH_CLIENT || process.env.TMUX),
		private readonly tmuxPane = () => process.env.TMUX ? process.env.TMUX_PANE : undefined) {}

	/** @returns 真实复制状态；失败时 error 为可显示原因。 */
	async copy(text: string): Promise<{ status: CopyStatus; error?: string }> {
		if (!text) return { status: "FAILED", error: "Nothing to copy" };
		// tmux 默认 external 会丢弃应用写到 pane 的 OSC52；通过它自己的命令发送，
		// 不改全局选项。失败不能再退回已知可能被吞掉的路径并报告成功。
		const pane = this.tmuxPane();
		if (pane) {
			try { await this.copyThroughTmux(pane, text); return { status: "TMUX_SENT" }; }
			catch (error) { return { status: "FAILED", error: (error as Error).message }; }
		}
		const clipboard = this.remote() ? undefined : this.native();
		if (clipboard?.setText) {
			try {
				await clipboard.setText(text);
				return { status: "DESKTOP" };
			} catch {
				// 桌面剪贴板失败时退回 OSC 52，不直接报错。
			}
		}
		try {
			const encoded = Buffer.from(text, "utf8").toString("base64");
			await this.writeOsc(`\x1b]52;c;${encoded}\x07`);
			return { status: "OSC52_SENT" };
		} catch (error) {
			return { status: "FAILED", error: (error as Error).message };
		}
	}

	/**
	 * 找到本 pane 所在会话的客户端，再把正文经 stdin 交给 tmux；正文不进入 shell/argv/文件。
	 * 自动命名的 buffer 由 tmux 的 buffer-limit 管理，作为本次复制的正常副作用保留。
	 * 没有附着客户端或未声明 clipboard 能力时明确失败，不把服务器缓存当桌面复制成功。
	 */
	private async copyThroughTmux(pane: string, text: string): Promise<void> {
		const session = (await this.tmuxCommand(["display-message", "-p", "-t", pane, "#{session_id}"])).trim();
		if (!/^\$\d+$/.test(session)) throw new Error("tmux clipboard: pane has no session");
		// 不使用服务器默认客户端：它可能属于另一个会话。只向本会话最近活动的附着客户端发送。
		const info = await this.tmuxCommand(["list-clients", "-t", session, "-F", "#{client_tty}\t#{client_termfeatures}\t#{client_activity}"]);
		const clients = info.trim().split("\n").map((line) => line.split("\t"));
		clients.sort((left, right) => Number(right[2] ?? 0) - Number(left[2] ?? 0));
		const [client, features] = clients[0] ?? [];
		if (!client || !features?.split(",").includes("clipboard")) {
			throw new Error("tmux client does not advertise OSC52 clipboard support (Ms)");
		}
		await this.tmuxCommand(["load-buffer", "-w", "-t", client, "-"], text);
	}

	/** 不使用 shell；每条命令有界等待，管道提前关闭也会作为真实失败返回。 */
	private tmuxCommand(args: string[], input?: string): Promise<string> {
		return new Promise((resolve, reject) => {
			const child = execFile("tmux", args, { timeout: 1500, maxBuffer: 16 * 1024 }, (error, stdout) => {
				if (error) reject(new Error(`tmux clipboard: ${error.message}`));
				else resolve(stdout);
			});
			child.stdin?.on("error", reject);
			child.stdin?.end(input);
		});
	}

	/**
	 * 读取剪贴板图片并保存为临时 PNG；没有图片时返回 null。
	 * @returns 本次拥有的文件路径（调用方只清理自己登记的文件）
	 */
	async saveImage(): Promise<string | null> {
		const clipboard = this.native();
		if (!clipboard?.getImage) return null;
		let image: Uint8Array | null | undefined;
		try {
			image = await clipboard.getImage();
		} catch {
			return null;
		}
		if (!image || image.length === 0) return null;
		const directory = mkdtempSync(join(tmpdir(), "jade-paste-"));
		const path = join(directory, `clipboard-${Date.now()}.png`);
		// 先登记目录，即使写文件失败，退出清理也不会漏掉本次资源。
		this.owned.add(directory);
		writeFileSync(path, image);
		return path;
	}

	/** 清理本次会话拥有的临时图片路径（不碰其它文件）。 */
	cleanup(): void {
		// 只删除 mkdtemp 创建并由本对象登记的目录，绝不清理任意剪贴板/用户路径。
		for (const directory of this.owned) rmSync(directory, { recursive: true, force: true });
		this.owned.clear();
	}
}
