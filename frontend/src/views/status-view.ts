import { visibleWidth } from "@earendil-works/pi-tui";
import { clipLine, singleLine } from "../presentation/text-safety.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import type { SessionStore } from "../state/session-store.ts";
import { formatDuration, formatPercent, formatTokens } from "../presentation/format.ts";

/**
 * 状态栏/页脚：真实 usage/context/permission/Team/buffer/background。
 *
 * 计时由 SessionStore 在 Java 的相对毫秒上插值；暂停/显示停止/结束后冻结。未知用量显示 "?"
 * 而不是 0；只有存在活动轮次时界面才安排重绘（Application 负责），空闲不空转。
 */
export class StatusView {
	constructor(private readonly session: SessionStore) {}

	render(theme: ResolvedTheme, width: number): string[] {
		const snapshot = this.session.snapshot();
		const settings = this.session.settings();
		if (!snapshot || !settings) return [];
		const parts: string[] = [];
		const elapsed = this.session.elapsedMs("leader");
		if (elapsed !== null && elapsed > 0) parts.push(`⏱ ${formatDuration(elapsed)}`);
		const usage = snapshot.usage;
		parts.push(`in ${formatTokens(usage.inputTokens)} · out ${formatTokens(usage.outputTokens)}`);
		if (usage.cacheReadTokens !== null) parts.push(`R ${formatTokens(usage.cacheReadTokens)}`);
		if (usage.cacheWriteTokens !== null) parts.push(`W ${formatTokens(usage.cacheWriteTokens)}`);
		// Java 使用同一次正式请求的真实分母；缺报隐藏，不能用累计输入伪造命中率。
		if (usage.recentCacheHitPercent != null) parts.push(`CH ${usage.recentCacheHitPercent}%`);
		// 新增内容使用本地估算时才加 ~；无新增内容的服务端锚点与累计账单分开显示。
		if (snapshot.context) parts.push(`ctx ${snapshot.context.estimated ? "~" : ""}${formatTokens(snapshot.context.occupiedTokens)}/${formatTokens(snapshot.context.windowTokens)} · ${formatPercent(snapshot.context.remainingPercent)} left`);
		if (snapshot.permission) parts.push(`perm ${snapshot.permission.profile}`);

		if (snapshot.bufferSummary) parts.push(`buffer: ${snapshot.bufferSummary}`);
		if (Object.keys(snapshot.background).length > 0) parts.push(`bg ${Object.keys(snapshot.background).length}`);
		const model = clipLine(singleLine(snapshot.model), Math.max(1, Math.floor(width / 3)));
		const stats = clipLine(singleLine(parts.join(" · ")), Math.max(0, width - visibleWidth(model) - (model ? 1 : 0)));
		const line = model ? stats + " ".repeat(Math.max(0, width - visibleWidth(stats) - visibleWidth(model))) + model : stats;
		const identity = `${snapshot.workspace ?? ""}${snapshot.gitBranch ? ` (${snapshot.gitBranch})` : ""} · ${snapshot.title ?? ""}`;
		const lines = [theme.style("dim", clipLine(singleLine(identity), width)),
			theme.style("dim", clipLine(singleLine(line), Math.max(0, width)))];
		if (snapshot.teamActive) {
			const members = this.session.team();
			const leader = snapshot.actors.find((actor) => actor.id === "leader");
			const state = leader?.state.startsWith("WAITING_") ? "Waiting" : leader?.state === "RUNNING" ? "Running" : "Idle";
			const running = members.filter((m) => m.state === "RUNNING").length;
			const pending = members.reduce((count, m) => count + m.pendingReports, 0);
			const review = members.reduce((count, m) => count + m.toReview, 0);
			const questions = members.reduce((count, m) => count + m.openQuestions, 0);
			const summary = `Leader · ${state}    Team · ${running} running · ${pending} pending report${pending === 1 ? "" : "s"}`
				+ (review ? ` · ${review} to review` : "") + (questions ? ` · ${questions} open question${questions === 1 ? "" : "s"}` : "") + "    /team";
			lines.push(clipLine(theme.style(questions ? "warning" : "muted", summary), width));
		}
		const hints: string[] = [];
		if (settings.mode === "fullscreen") hints.push("PgUp/PgDn scroll", "Ctrl+R search");
		else hints.push("native scroll");
		hints.push("Enter submit", "Ctrl+J newline", "Ctrl+O tools", "/settings");
		lines.push(theme.style("dim", clipLine(hints.join(" · "), Math.max(0, width))));
		return lines;
	}
}
