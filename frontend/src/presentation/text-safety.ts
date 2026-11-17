import { truncateToWidth } from "@earendil-works/pi-tui";
export { display, singleLine, visibleEscapes } from "./control-text.ts";

/** 按终端列/完整字素裁剪；纯文本输入不能因 Pi 的补位 reset 而新增 SGR（NO_COLOR）。 */
export function clipLine(value: string, width: number): string {
	const clipped = truncateToWidth(value, Math.max(0, width), "");
	return value.includes("\x1b") ? clipped : clipped.replace(/\x1b\[[0-9;]*m/g, "");
}
