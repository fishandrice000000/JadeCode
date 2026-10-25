import type { ResolvedTheme, Role } from "../theme/theme-resolver.ts";
import { singleLine } from "../presentation/text-safety.ts";

/**
 * 宿主状态的语义颜色；仅消费 Entry.status，不从模型/工具正文猜测成功或失败。
 * HISTORICAL 和未知状态保持低调，不能为恢复的记录补一个成功标记。
 */
export function statusRole(status: string): Role {
	switch (status) {
		case "ERROR": case "HISTORICAL ERROR": case "INTERRUPTED": return "error";
		case "INCOMPLETE": case "BLOCKED": case "NOT_RUN": case "STOP_REQUESTED": return "warning";
		case "DONE": case "RETURNED": return "success";
		case "STREAMING": case "RUNNING": return "accent";
		case "PREVIEW": case "QUEUED": case "SUBMITTING": return "warning";
		default: return "dim";
	}
}

/**
 * 给一段标题着色：先净化外部文本，各段独立取色，由调用方最后按终端列裁剪。
 * 分隔符不随错误变红；NO_COLOR 的样式函数退回原文，仍保留所有文字标签。
 */
export function headingPart(theme: ResolvedTheme, role: Role, text: string, bold = false): string {
	const styled = theme.style(role, singleLine(text));
	return bold ? theme.bold(styled) : styled;
}

/** 辅助段统一使用淡色分隔符；传入内容先净化，防止工具名/状态夹带控制码。 */
export function headingDetail(theme: ResolvedTheme, role: Role, text: string): string {
	return theme.style("dim", " · ") + headingPart(theme, role, text);
}
