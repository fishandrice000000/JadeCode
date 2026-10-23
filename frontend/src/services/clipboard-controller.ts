import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { getNativeClipboard } from "@earendil-works/pi-tui";

/**
 * 剪贴板：复制文本/读取图片路径。
 *
 * - 复制：优先真实桌面剪贴板；不可用时发 OSC 52（只表示“已发出”，不伪造客户端 ACK），
 *   内部状态区分 DESKTOP/OSC52_SENT/FAILED；成功显示 Copied! 约 1 秒。
 * - 图片：只显式读取剪贴板图片，保存到本进程拥有的临时文件并把**路径**插入草稿；
 *   JadeCode 没有 vision 能力，这不是图像消息，也不自动读取文件内容。
 */
export type CopyStatus = "DESKTOP" | "OSC52_SENT" | "FAILED";

export class ClipboardController {
	private readonly owned = new Set<string>();

	/** @returns 真实复制状态；失败时 error 为可显示原因。 */
	async copy(text: string): Promise<{ status: CopyStatus; error?: string }> {
		if (!text) return { status: "FAILED", error: "Nothing to copy" };
		const clipboard = getNativeClipboard();
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
			process.stdout.write(`\x1b]52;c;${encoded}\x07`);
			return { status: "OSC52_SENT" };
		} catch (error) {
			return { status: "FAILED", error: (error as Error).message };
		}
	}

	/**
	 * 读取剪贴板图片并保存为临时 PNG；没有图片时返回 null。
	 * @returns 本次拥有的文件路径（调用方只清理自己登记的文件）
	 */
	async saveImage(): Promise<string | null> {
		const clipboard = getNativeClipboard();
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
		writeFileSync(path, image);
		this.owned.add(path);
		return path;
	}

	/** 清理本次会话拥有的临时图片路径（不碰其它文件）。 */
	cleanup(): void {
		this.owned.clear();
	}
}
