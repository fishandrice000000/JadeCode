import { stripTerminalSequences, truncateToWidth, visibleWidth, type Component } from "@earendil-works/pi-tui";
import { highlightCode } from "../markdown/syntax-highlight.ts";
import { singleLine, visibleEscapes } from "../presentation/text-safety.ts";
import type { ResolvedTheme, Role } from "../theme/theme-resolver.ts";
import type { ApprovalDisplayData } from "./approval-controller.ts";

/**
 * 后端接受审批答复后的临时展示副本，不是待确认窗口，也不参与授权。
 *
 * 只保留最新一份有界摘要；新结果覆盖旧结果，五秒后清空。Application 把本组件安装为
 * 不捕获焦点的 Pi overlay，不把它追加到 TranscriptStore，因此不会随历史积累。
 * 摘要先把控制码变成可见文字，再由可信主题编码；完整原参数仍属于原审批流程。
 */
export class ApprovalResultToast implements Component {
	private result: { choice: number; tool: string; scope: string; actor: string; preview: string } | undefined;
	private timer: ReturnType<typeof setTimeout> | undefined;
	static readonly DURATION_MS = 5000;

	constructor(private readonly theme: () => ResolvedTheme, private readonly changed: () => void) {}

	/**
	 * 输入必须来自成功的 approval.answer 回执；不修改传入参数、不保存完整 JSON，也没有 IO。
	 * actor 是 Application 已解析的显示名；空值表示当前界面无需区分执行者。
	 */
	show(data: ApprovalDisplayData, choice: number, actor = ""): void {
		if (choice !== 0 && choice !== 1 && choice !== 2) return;
		if (this.timer) clearTimeout(this.timer);
		const summary = (value: string, limit: number): string => {
			// 先截取再净化，避免超大文件参数只为一个临时提示分配成倍内存。
			return singleLine(visibleEscapes(value.slice(0, limit))) + (value.length > limit ? "…" : "");
		};
		const result = { choice, tool: summary(data.tool, 160), scope: summary(data.scope, 240),
			actor: summary(actor, 80), preview: summary(data.parameters, 512) };
		this.result = result;
		this.timer = setTimeout(() => {
			// 即使旧定时回调已排队，也不能清掉后来替换的新结果。
			if (this.result !== result) return;
			this.result = undefined;
			this.timer = undefined;
			this.changed();
		}, ApprovalResultToast.DURATION_MS);
		this.timer.unref?.();
		this.changed();
	}

	/** 会话/模式切换、断连或退出时丢弃摘要和定时器；不会清理后端审批记录。 */
	clear(): void {
		if (this.timer) clearTimeout(this.timer);
		this.timer = undefined;
		this.result = undefined;
		this.changed();
	}

	hasResult(): boolean { return this.result !== undefined; }

	/** 窄终端只画一行结果；正常宽度五行封闭卡片，给输入栏保留空间。 */
	height(width: number): number { return width < 32 ? 1 : 5; }

	/**
	 * 只渲染有界的临时摘要。按终端列裁剪，中文/emoji 不拆字；缺省主题角色沿用现有回退。
	 * 主题每帧从访问器取得，所以同名主题刷新不复用旧色；无色模式仍有图标和结果文字。
	 */
	render(width: number): string[] {
		const result = this.result;
		if (!result || width < 1) return [];
		const theme = this.theme();
		const role: Role = result.choice === 2 ? "error" : result.choice === 1 ? "warning" : "success";
		// 三种结果共用 Allow similar 的中性底色，只用边框和结果标题的红/黄/绿表达差异。
		const surface: Role = "toolPendingBg";
		const label = result.choice === 2 ? "× Denied" : result.choice === 1 ? "✓ Allowed similar" : "✓ Allowed once";
		const badge = theme.bold(theme.style(role, `[ ${label} ]`));
		const tool = theme.bold(theme.style("accent", result.tool));
		// Pi 裁剪纯文本也可能补 reset；输入不含可信样式时移除它，保持 NO_COLOR 与 JSON 原文。
		const crop = (value: string, columns: number): string => {
			const clipped = truncateToWidth(value, columns, "…");
			// 本组件内层只用前景/粗体；裁剪补的全量 reset 改为关闭这两项，保留外层卡片底色。
			return value.includes("\x1b") ? clipped.replace(/\x1b\[0m/g, "\x1b[22;39m") : stripTerminalSequences(clipped);
		};
		if (width < 32) return [crop(`${badge} ${tool}`, width)];

		// 不嵌套背景样式：内部只加前景/粗体，整行的浅色底不会被内层 reset 提前清掉。
		const inner = width - 4;
		const row = (body: string): string => {
			const clipped = crop(body, inner);
			const padded = clipped + " ".repeat(Math.max(0, inner - visibleWidth(clipped)));
			return theme.withBackground("text", surface, theme.style(role, "│ ") + padded + theme.style(role, " │"));
		};
		const edge = (left: string, right: string): string => theme.withBackground("text", surface,
			theme.style(role, left + "─".repeat(width - 2) + right));
		const scope = result.choice === 0 ? "This request only" : result.choice === 2 ? "No permission granted" : result.scope || "Similar requests";
		const actor = result.actor ? theme.style("dim", " · ") + theme.style("muted", result.actor) : "";
		// 摘要截短后可能不是完整 JSON；词法高亮只改颜色，不将其重新解析成授权参数。
		const preview = highlightCode(crop(result.preview, inner), "json", theme)[0] ?? "";
		return [edge("╭", "╮"), row(`${badge}  ${tool}`), row(theme.style("dim", "Scope  ") + theme.style("text", scope) + actor),
			row(preview), edge("╰", "╯")];
	}

	/** 无渲染缓存；Pi 的失效通知不修改当前结果或到期时间。 */
	invalidate(): void {}
}
