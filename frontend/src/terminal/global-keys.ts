import { isKeyRelease, isKeyRepeat } from "@earendil-works/pi-tui";

/**
 * 全局动作（Ctrl+C 退出/复制、Ctrl+G、Ctrl+V、Ctrl+Z 等）只接受新的按下事件。
 *
 * repeat/release 仍会继续传给编辑器，从而保留长按方向键/退格的输入行为；但全局路由必须
 * 在到达这些动作前拒绝它们，否则一次按住 Ctrl+C 会先清空草稿、再由 repeat 触发退出。
 * 审批与权限页有各自更严格的确认过滤，不使用此函数。
 */
export function isGlobalActionPress(data: string): boolean {
	return !isKeyRelease(data) && !isKeyRepeat(data);
}
