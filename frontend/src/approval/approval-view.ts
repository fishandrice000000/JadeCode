import { visibleWidth, wrapTextWithAnsi, truncateToWidth } from "@earendil-works/pi-tui";
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
	/** 完整参数仍在装载时不能选择/确认。 */
	loading: boolean;
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

	/** 参数窗口滚动；render 时会按实际行数夹紧并参与布局。 */
	scrollBy(lines: number): void {
		this.scroll = Math.max(0, this.scroll + lines);
	}

	lastLayout(): ApprovalLayout | undefined { return this.layout; }

	invalidate(): void { this.layout = undefined; }

	/**
	 * 渲染审批卡，并记录“真实同帧按钮格坐标”。
	 *
	 * 长参数不是整个文档无限增长：这里按可用行数开一个参数窗口，PgUp/PgDn 只移动窗口。
	 * 因此卡片总能完整落在视口内；若 originY<0、或按钮行超出 0..rows-1、或参数仍在加载，
	 * 就不发布任何批准矩形（视觉上仍显示申请，但无法误点/误确认）。
	 *
	 * @param width 终端列宽
	 * @param originY 本卡片第一行在视口中的屏幕行（0 基）
	 * @param rows 终端行数
	 */
	render(input: ApprovalRenderInput, width: number, originY: number, rows: number, columns: number,
		availableRows = rows - originY): string[] {
		const budget = Math.max(0, Math.min(availableRows, rows - originY));
		// 太小的窗口不画可命中的批准按钮；参数/选项必须在同一可见卡片中才能发布证据。
		const tooSmall = (): string[] => {
			this.layout = { rects: [], digest: digest([]), columns, rows };
			return ["Approval pending", "Resize terminal to read and answer."].slice(0, Math.max(1, budget))
				.map((line) => truncateToWidth(line, Math.max(1, width), ""));
		};
		if (width < 30 || budget < 2) return tooSmall();
		const theme = this.theme;
		const palette = approvalPalette(theme);
		const margin = 0; // 与输入栏共用终端左右边界，卡内仍保留一列 padding。
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

		const pushWrapped = (value: string): void => {
			for (const line of wrapTextWithAnsi(value, innerWidth)) pushLine(line);
		};
		if (input.teamActive && input.actor) pushWrapped(theme.style("muted", `Actor: ${visibleEscapes(input.actorName(input.actor))}`));
		pushWrapped(theme.style("muted", `Tool: ${visibleEscapes(input.tool)}`));
		pushWrapped(theme.style("muted", `Scope: ${visibleEscapes(input.scope || "(this request only)")}`));
		pushLine(theme.bold(theme.style("muted", "Parameters")));
		if (input.loading) pushLine(theme.style("dim", "Loading the complete parameters…"));
		const headerLines = lines.length;
		// 底部：空行 + 三个选项 + 下边框；预留后剩余行数给参数窗口。
		const reserved = headerLines + 1 + this.choices.length + 1;
		if (budget <= reserved) return tooSmall();
		const baseCapacity = budget - reserved;
		const parameterLines = wrapTextWithAnsi(visibleEscapes(input.parameters), innerWidth);
		const overflow = parameterLines.length > baseCapacity;
		if (overflow && baseCapacity < 2) return tooSmall();
		// 提示行单独占一行预算，绝不用它覆盖最后一个真实参数行。
		const capacity = overflow ? Math.max(1, baseCapacity - 1) : baseCapacity;
		const maxScroll = Math.max(0, parameterLines.length - capacity);
		this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));
		const windowed = parameterLines.slice(this.scroll, this.scroll + capacity);
		const hint = overflow
			? theme.style("dim", `… ${this.scroll + 1}-${this.scroll + windowed.length} of ${parameterLines.length} · PgUp/PgDn`)
			: "";

		for (const line of windowed) pushLine(theme.style("text", line));
		if (hint) pushLine(hint);
		pushLine("");

		const buttonX = margin + 4;
		const buttonWidth = Math.max(8, boxWidth - 6);
		const rects: ApprovalLayout["rects"] = [];
		for (const choice of this.choices) {
			const isSelected = this.selected === choice.index;
			const label = choice.label;
			let text: string;
			if (!choice.enabled) text = palette.disabled(`[ ${label} ]`);
			else if (isSelected) {
				text = choice.index === 0 ? palette.once(` ${label} `)
					: choice.index === 1 ? palette.similar(` ${label} `) : palette.deny(` ${label} `);
			} else text = `[ ${label} ]`;
			const marker = isSelected ? "▸ " : "  ";
			const lineIndex = lines.length;
			pushLine("  " + marker + text);
			if (choice.enabled) {
				// 每个按钮绑定自己真实的行号；禁用项仍占行，但不会让后续选项的矩形错位。
				rects.push({ x: buttonX, y: originY + lineIndex, width: Math.min(buttonWidth, visibleWidth(`[ ${label} ]`) + 2), height: 1, choice: choice.index });
			}
		}
		lines.push(border("╰" + "─".repeat(Math.max(0, boxWidth - 2)) + "╯"));

		// 按钮真实视口行 = originY + 卡片内行号；越界/负原点/加载中都不允许发布命中。
		let publishable = !input.loading && originY >= 0;
		for (const rect of rects) {
			if (rect.y < 0 || rect.y >= rows) publishable = false;
			rect.x = Math.max(0, Math.min(rect.x, Math.max(0, columns - 1)));
			rect.width = Math.max(1, Math.min(rect.width, columns - rect.x));
		}
		this.layout = {
			rects: publishable ? rects : [],
			// 选择色/箭头不属于按钮几何；同帧矩形可用时，改变选择不需要重新呈现即可确认。
			digest: digest(publishable ? rects : []),
			columns,
			rows,
		};
		const padded: string[] = [];
		const marginText = " ".repeat(margin);
		for (const line of lines) padded.push(marginText + line + marginText);
		return padded.slice(0, Math.max(1, rows));
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

function digest(rects: ApprovalLayout["rects"]): string {
	let hash = 2166136261;
	for (const rect of rects) {
		const text = `${rect.x},${rect.y},${rect.width},${rect.height},${rect.choice};`;
		for (let i = 0; i < text.length; i++) {
			hash ^= text.charCodeAt(i);
			hash = Math.imul(hash, 16777619);
		}
	}
	return (hash >>> 0).toString(16);
}
