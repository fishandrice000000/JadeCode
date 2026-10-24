import { isKeyRelease, isKeyRepeat } from "@earendil-works/pi-tui";

/**
 * 判断一个 Pi 输入序列是否可以作为“新的显式确认”。
 *
 * release/repeat 不是用户重新按下；bracketed paste 也不是确认。两者都必须在应用层拒绝，
 * 不能把它们改写成 PRESS 再交给后端——后端只能看到改写后的证据，无法自行辨别。
 */
export function isConfirmationPress(data: string): boolean {
	if (!data) return false;
	if (isKeyRelease(data) || isKeyRepeat(data)) return false;
	if (data.startsWith("\x1b[200~") || data.includes("\x1b[200~")) return false;
	return true;
}
