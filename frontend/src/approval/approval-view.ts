import { visibleWidth, wrapTextWithAnsi } from "@earendil-works/pi-tui";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import { approvalPalette } from "../theme/theme-adapters.ts";
import { visibleEscapes } from "../presentation/text-safety.ts";

/**
 * 审批卡片：独立闭合框、panelBg、左右留白，三选项始终竖排。
 *
 * 已批准契约（不能在迁移中丢）：
 * - 无默认选择；未选中项中性色；选中项整标签绿/黄/红底色；
 * - 无建议时 "Allow similar" 禁用（dim 且不能确认）；
 * - 显示完整参数与 scope，不显示 reason；单人会话不显示 Leader 身份；
 * - NO_COLOR 下仍有箭头/括号等文字区分。
 *
 * 同时记录本帧按钮矩形和布局摘要，供输出围栏完成后上报 Java 做新鲜输入校验。
 */
export interface ApprovalChoice {
	index: 0 | 1 | 2;
	label: string;
	enabled: boolean;
}

export interface ApprovalLayout {
	rects: Array<{ x: number; y: number; width: number; height: number; choice: number }>;
	digest: string;
	columns: number;
	rows: number;
}

export interface ApprovalRenderInput {
	title: string;
	tool: string;
	scope: string;
	parameters: string;
	actor: string;
	teamActive: boolean;
	actorName: (actor: string) => string;
}

export class ApprovalView {
	private readonly theme: ResolvedTheme;
	private readonly choices: ApprovalChoice[];
	private selected = -1;
	private layout: ApprovalLayout | undefined;
	private scroll = 0;

	constructor(theme: ResolvedTheme, choices: ApprovalChoice[]) {
		this.theme = theme;
		this.choices = choices;
	}

	setTheme(theme: ResolvedTheme): void {
		(this as unknown as { theme: ResolvedTheme }).theme = theme;
	}

	/** @returns 当前选择；-1 表示尚未选择（无默认）。 */
	choice(): number { return this.selected; }

	setChoice(index: number): void {
		this.selected = index;
	}

	move(delta: number): void {
		const enabled = this.choices.filter((choice) => choice.enabled);
		if (enabled.length === 0) return;
		const current = this.choices.findIndex((choice) => choice.index === this.selected);
		let cursor = current < 0 ? (delta > 0 ? -1 : 0) : current;
		for (let step = 0; step < this.choices.length; step++) {
			cursor = (cursor + delta + this.choices.length) % this.choices.length;
			if (this.choices[cursor]!.enabled) {
				this.selected = this.choices[cursor]!.index;
				return;
			}
		}
	}

	scrollBy(lines: number): void {
		this.scroll = Math.max(0, this.scroll + lines);
	}

	lastLayout(): ApprovalLayout | undefined { return this.layout; }

	invalidate(): void { this.layout = undefined; }

	/**
	 * @param width 终端列宽
	 * @param originY 本卡片第一行在视口中的屏幕行（0 基）；main screen 用文档行
	 * @param rows 终端行数
	 */
	render(input: ApprovalRenderInput, width: number, originY: number, rows: number, columns: number): string[] {
		const theme = this.theme;
		const palette = approvalPalette(theme);
		const margin = width >= 44 ? 2 : width >= 30 ? 1 : 0;
		const boxWidth = Math.max(8, width - margin * 2);
		const innerWidth = Math.max(4, boxWidth - 4);
		const border = (text: string) => theme.style("border", text);
		const accent = (text: string) => theme.bold(theme.style("accent", text));
		const titleText = ` ${input.title} `;
		const lines: string[] = [];
		const left = Math.max(0, boxWidth - 2 - visibleWidth(titleText));
		lines.push(border("╭" + "─".repeat(left)) + accent(titleText) + border("╮"));

		const pushLine = (content: string): void => {
			const padded = content + " ".repeat(Math.max(0, innerWidth - visibleWidth(content)));
			lines.push(border("│") + " " + theme.withBackground("text", "panelBg", padded) + " " + border("│"));
		};
		const pushWrapped = (label: string, text: string): void => {
			pushLine(theme.bold(theme.style("muted", label)));
			const wrapped = wrapTextWithAnsi(visibleEscapes(text), innerWidth);
			for (const line of wrapped) pushLine(theme.style("text", line));
		};

		if (input.teamActive && input.actor) pushLine(theme.style("muted", `Actor: ${input.actorName(input.actor)}`));
		pushLine(theme.style("muted", `Tool: ${input.tool}`));
		pushLine(theme.style("muted", `Scope: ${input.scope || "(this request only)"}`));
		pushWrapped("Parameters", input.parameters);
		pushLine("");
		// 选项从当前行开始；矩形稍后加上 originY。
		const optionStart = lines.length - 1;
		const buttonX = margin + 3;
		const buttonWidth = Math.max(8, boxWidth - 6);
		const rects: ApprovalLayout["rects"] = [];
		for (const [position, choice] of this.choices.entries()) {
			const isSelected = this.selected === choice.index;
			const label = choice.label;
			let text: string;
			if (!choice.enabled) {
				text = palette.disabled(`[ ${label} ]`);
			} else if (isSelected) {
				text = choice.index === 0 ? palette.once(` ${label} `)
					: choice.index === 1 ? palette.similar(` ${label} `) : palette.deny(` ${label} `);
			} else {
				text = `[ ${label} ]`;
			}
			const marker = isSelected ? "▸ " : "  ";
			const padding = " ".repeat(Math.max(0, innerWidth - visibleWidth(marker + `[ ${label} ]`)));
			pushLine("  " + marker + text + padding);
			if (choice.enabled) {
				rects.push({ x: buttonX, y: 0, width: Math.min(buttonWidth, visibleWidth(` ${label} `) + 2), height: 1, choice: choice.index });
			}
			void position;
		}
		lines.push(border("╰" + "─".repeat(Math.max(0, boxWidth - 2)) + "╯"));

		// 把选项的局部行号换算为视口行；main 模式没有滚动偏移，originY 已经是文档行。
		const offset = Math.max(0, optionStart);
		for (const rect of rects) {
			rect.y = clamp(originY + offset + rects.indexOf(rect), 0, Math.max(0, rows - 1));
			rect.x = clamp(rect.x, 0, Math.max(0, columns - 1));
			rect.width = Math.max(1, Math.min(rect.width, columns - rect.x));
		}
		this.layout = { rects, digest: digest(rects, this.selected), columns, rows };
		const padded: string[] = [];
		const marginText = " ".repeat(margin);
		for (const line of lines) padded.push(marginText + line + marginText);
		return padded;
	}

	/** @returns 命中该格坐标的选项序号；无命中 -1。 */
	hit(x: number, y: number): number {
		if (!this.layout) return -1;
		const rect = this.layout.rects.find((item) => x >= item.x && x < item.x + item.width
			&& y >= item.y && y < item.y + item.height);
		return rect ? rect.choice : -1;
	}
}

function clamp(value: number, min: number, max: number): number {
	return Math.max(min, Math.min(max, value));
}

function digest(rects: ApprovalLayout["rects"], selected: number): string {
	let hash = 2166136261;
	for (const rect of rects) {
		const text = `${rect.x},${rect.y},${rect.width},${rect.height},${rect.choice};`;
		for (let i = 0; i < text.length; i++) {
			hash ^= text.charCodeAt(i);
			hash = Math.imul(hash, 16777619);
		}
	}
	hash ^= selected;
	return (hash >>> 0).toString(16);
}
