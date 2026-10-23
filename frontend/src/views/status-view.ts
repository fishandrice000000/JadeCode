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
		if (usage.cacheReadTokens) parts.push(`cache ${formatTokens(usage.cacheReadTokens)}`);
		if (snapshot.context) parts.push(`ctx ${formatPercent(snapshot.context.remainingPercent)}`);
		if (snapshot.permission) parts.push(`perm ${snapshot.permission.profile}`);
		if (snapshot.model) parts.push(snapshot.model);
		if (snapshot.bufferSummary) parts.push(`buffer: ${snapshot.bufferSummary}`);
		if (Object.keys(snapshot.background).length > 0) parts.push(`bg ${Object.keys(snapshot.background).length}`);
		const line = parts.join(" · ");
		const lines = [theme.style("dim", line.slice(0, Math.max(0, width)))];
		if (snapshot.actors.length > 1 || snapshot.teamActive) {
			const actors = snapshot.actors
				.filter((actor) => actor.id !== "leader" || snapshot.teamActive)
				.map((actor) => `${this.session.displayActor(actor.id) || actor.id}:${actor.state}`);
			if (actors.length > 0) lines.push(theme.style("dim", actors.join(" · ").slice(0, Math.max(0, width))));
		}
		const hints: string[] = [];
		if (settings.mode === "fullscreen") hints.push("PgUp/PgDn scroll", "Ctrl+Shift+F search");
		else hints.push("native scroll");
		hints.push("Enter submit", "Shift+Enter newline", "Ctrl+O tools", "/settings");
		lines.push(theme.style("dim", hints.join(" · ").slice(0, Math.max(0, width))));
		return lines;
	}
}
