/**
 * 数字/耗时/用量格式化：纯函数，未知值保持 null，不补 0。
 *
 * 这些规则与旧 Java `UsageFormatter`/footer 一致：token 大数用 100K 这种一位小数缩写，
 * 耗时一位小数，未知用量显示为 "?"，避免把"没有报告"伪装成"测得为零"。
 */

/** 1000 → 1K，12_345 → 12K，100_000 → 100K，1_250_000 → 1.3M。 */
export function formatTokens(value: number | null | undefined): string {
	if (value === null || value === undefined || !Number.isFinite(value) || value < 0) return "?";
	if (value < 1000) return String(Math.trunc(value));
	if (value < 1_000_000) {
		const thousands = value / 1000;
		const digits = thousands >= 100 ? 0 : 1;
		return `${trimZero(thousands.toFixed(digits))}K`;
	}
	return `${trimZero((value / 1_000_000).toFixed(1))}M`;
}

/** 一位小数，例如 29.0s；用于耗时/百分比附近的小数值。 */
export function oneDecimal(value: number): string {
	return Number.isFinite(value) ? value.toFixed(1) : "?";
}

/** 毫秒 → 29.0s / 1m 05.2s / 0.0s；未知返回空串（不显示 0.0s）。 */
export function formatDuration(milliseconds: number | null | undefined): string {
	if (milliseconds === null || milliseconds === undefined || !Number.isFinite(milliseconds) || milliseconds < 0) return "";
	const seconds = milliseconds / 1000;
	if (seconds < 60) return `${seconds.toFixed(1)}s`;
	const minutes = Math.floor(seconds / 60);
	const rest = seconds - minutes * 60;
	return `${minutes}m ${rest.toFixed(1).padStart(4, "0")}s`;
}

/** 剩余上下文的整数百分比；未知返回 null。 */
export function formatPercent(value: number | null | undefined): string {
	if (value === null || value === undefined || !Number.isFinite(value)) return "?";
	return `${Math.trunc(value)}%`;
}

/** 会话列表相对时间（英文、无小数秒）。 */
export function relativeTime(updatedAt: string, now = Date.now()): string {
	const then = Date.parse(updatedAt);
	if (!Number.isFinite(then)) return "";
	const seconds = Math.max(0, Math.floor((now - then) / 1000));
	if (seconds < 60) return "just now";
	if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
	if (seconds < 86_400) return `${Math.floor(seconds / 3600)}h ago`;
	return `${Math.floor(seconds / 86_400)}d ago`;
}

function trimZero(value: string): string {
	return value.endsWith(".0") ? value.slice(0, -2) : value;
}
