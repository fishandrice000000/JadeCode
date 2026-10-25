import { clipLine } from "../presentation/text-safety.ts";
import { matchesKey } from "@earendil-works/pi-tui";
import type { Page, PageContext } from "./page-stack.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";

/**
 * 主题页：列表预览/提交/取消。
 *
 * Up/Down 预览（立即影响 render，但不写文件）；Enter 提交（settings.patch）；Esc 取消并回到
 * 已保存主题。逻辑 theme 与 effective 变体分开：auto 由明暗自动配对，system 只留终端普通
 * 前景/背景。迟到 watch 结果由 ThemeController 的票据丢弃。
 */
export class ThemePage implements Page {
	readonly title: string;
	private readonly context: PageContext;
	private readonly target: "theme" | "lightTheme" | "darkTheme";
	private readonly names: string[];
	private selected: number;

	constructor(context: PageContext, target: "theme" | "lightTheme" | "darkTheme" = "theme") {
		this.context = context;
		this.target = target;
		this.names = context.themeNames();
		const selectedName = target === "theme" ? context.sessionTheme() : context.settings()?.[target] ?? context.sessionTheme();
		this.selected = Math.max(0, this.names.indexOf(selectedName));
		this.title = target === "theme" ? "Theme" : target === "lightTheme" ? "Light theme" : "Dark theme";
	}

	theme(): ResolvedTheme { return this.context.theme(); }

	hint(): string { return "Up/Down preview · Enter save · Esc cancel"; }

	invalidate(): void {}
	handleBodyPress(row: number): boolean {
		if (row >= 0 && row < this.names.length) {
			this.selected = row; this.context.previewTheme(this.names[row]!);
		}
		return true;
	}

	render(width: number): string[] {
		const theme = this.context.theme();
		const lines: string[] = [];
		for (let index = 0; index < this.names.length; index++) {
			const name = this.names[index]!;
			const marker = index === this.selected ? "▸ " : "  ";
			const suffix = name === this.names[this.selected] ? theme.style("dim", "  (preview)") : "";
			const line = `${marker}${name}${suffix}`;
			lines.push(index === this.selected ? theme.bold(theme.style("accent", line)) : theme.style("text", line));
		}
		lines.push("");
		lines.push(theme.style("dim", `Effective: ${theme.name} (${theme.appearance})`));
		if (width < 20) return lines.map((line) => clipLine(line, width));
		return lines;
	}

	handleInput(data: string): boolean {
		const keymap = this.context.keymap;
		if (keymap.matches("tui.select.cancel", data)) {
			this.context.cancelTheme();
			this.context.closePage();
			return true;
		}
		if (keymap.matches("tui.select.up", data)) {
			this.selected = (this.selected - 1 + this.names.length) % this.names.length;
			this.context.previewTheme(this.names[this.selected]!);
			return true;
		}
		if (keymap.matches("tui.select.down", data)) {
			this.selected = (this.selected + 1) % this.names.length;
			this.context.previewTheme(this.names[this.selected]!);
			return true;
		}
		if (keymap.matches("tui.select.confirm", data)) {
			const name = this.names[this.selected]!;
			this.context.previewTheme(name);
			// 一次 Enter 只发一次保存；页面不再额外 saveSettings。
			this.context.commitThemeTarget(this.target, name);
			this.context.closePage();
			return true;
		}
		return false;
	}
}
