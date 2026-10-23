import { matchesKey } from "@earendil-works/pi-tui";
import type { Page, PageContext } from "./page-stack.ts";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";
import { ThemePage } from "./theme-page.ts";

/**
 * 设置页：18 个本轮有效字段（含非菜单 config 字段的展示值），三项延期媒体字段不露入口。
 *
 * 操作：Up/Down 选行，Left/Right 循环取值并立即发送白名单 patch（成功静默、失败真实告警），
 * Enter 打开主题子页；Esc 返回。所有当前值来自 Java 快照，不在 Node 另存默认配置。
 */
interface Field {
	id: string;
	label: string;
	kind: "bool" | "int" | "enum" | "theme" | "string";
	values?: string[];
	min?: number;
	max?: number;
}

const FIELDS: Field[] = [
	{ id: "theme", label: "Theme", kind: "theme" },
	{ id: "lightTheme", label: "Light theme", kind: "theme" },
	{ id: "darkTheme", label: "Dark theme", kind: "theme" },
	{ id: "mode", label: "TUI mode", kind: "enum", values: ["fullscreen", "regular"] },
	{ id: "fullscreenExitOutput", label: "Fullscreen exit output", kind: "enum", values: ["transcript", "resume-hint"] },
	{ id: "fullscreenScrollbar", label: "Fullscreen scrollbar", kind: "enum", values: ["auto", "always", "hidden"] },
	{ id: "fullscreenCopyOnSelect", label: "Copy on select", kind: "bool" },
	{ id: "fullscreenWheelScrollLines", label: "Wheel scroll lines", kind: "int", min: 0, max: 100 },
	{ id: "showHardwareCursor", label: "Hardware cursor", kind: "bool" },
	{ id: "editorPaddingX", label: "Editor padding X", kind: "int", min: 0, max: 3 },
	{ id: "outputPad", label: "Output padding", kind: "int", min: 0, max: 1 },
	{ id: "autocompleteMaxVisible", label: "Autocomplete items", kind: "int", min: 3, max: 20 },
	{ id: "clearOnShrink", label: "Clear on shrink", kind: "bool" },
	{ id: "showTerminalProgress", label: "Terminal progress", kind: "bool" },
	{ id: "quietStartup", label: "Quiet startup", kind: "bool" },
	{ id: "thinkingVisible", label: "Thinking visible", kind: "bool" },
	{ id: "toolsExpanded", label: "Tools expanded", kind: "bool" },
	{ id: "commandSuggestions", label: "Command suggestions", kind: "bool" },
	{ id: "codeBlockIndent", label: "Code block indent", kind: "string" },
];

export class SettingsPage implements Page {
	readonly title = "Settings";
	private readonly context: PageContext;
	private selected = 0;
	private offset = 0;

	constructor(context: PageContext) { this.context = context; }

	theme(): ResolvedTheme { return this.context.theme(); }

	hint(): string {
		return "Up/Down select · Left/Right change · Enter open · Esc back";
	}

	invalidate(): void {}

	render(width: number): string[] {
		const theme = this.context.theme();
		const settings = this.context.settings();
		if (!settings) return [theme.style("dim", "Waiting for backend settings…")];
		const rows: string[] = [];
		const visible = Math.max(6, Math.min(FIELDS.length, 14));
		if (this.selected < this.offset) this.offset = this.selected;
		if (this.selected >= this.offset + visible) this.offset = this.selected - visible + 1;
		for (let index = this.offset; index < Math.min(FIELDS.length, this.offset + visible); index++) {
			const field = FIELDS[index]!;
			const value = displayValue(settings, field.id);
			const marker = index === this.selected ? "▸ " : "  ";
			const label = `${marker}${field.label}`.padEnd(Math.min(34, Math.max(12, width / 2)));
			const line = `${label} ${value}`;
			rows.push(index === this.selected ? theme.bold(theme.style("accent", line)) : theme.style("text", line));
		}
		if (this.offset > 0) rows.unshift(theme.style("dim", "  ↑ more"));
		if (this.offset + visible < FIELDS.length) rows.push(theme.style("dim", "  ↓ more"));
		return rows;
	}

	handleInput(data: string): boolean {
		if (matchesKey(data, "escape")) { this.context.closePage(); return true; }
		if (matchesKey(data, "up")) { this.selected = (this.selected - 1 + FIELDS.length) % FIELDS.length; return true; }
		if (matchesKey(data, "down")) { this.selected = (this.selected + 1) % FIELDS.length; return true; }
		const field = FIELDS[this.selected]!;
		if (matchesKey(data, "enter")) {
			if (field.kind === "theme") {
				this.context.pushPage(new ThemePage(this.context, field.id as "theme" | "lightTheme" | "darkTheme"));
			}
			return true;
		}
		if (!matchesKey(data, "left") && !matchesKey(data, "right")) return false;
		const direction = matchesKey(data, "right") ? 1 : -1;
		const settings = this.context.settings();
		if (!settings) return true;
		const current = (settings as unknown as Record<string, unknown>)[field.id];
		let next: unknown;
		switch (field.kind) {
			case "bool":
				next = !(current === true);
				break;
			case "int": {
				const min = field.min ?? 0;
				const max = field.max ?? 100;
				const value = typeof current === "number" ? current : 0;
				next = Math.max(min, Math.min(max, value + direction));
				break;
			}
			case "enum":
				next = cycle(field.values ?? [], String(current ?? ""), direction);
				break;
			default:
				return true;
		}
		this.context.saveSettings({ [field.id]: next });
		return true;
	}
}

function displayValue(settings: object, id: string): string {
	const values = settings as Record<string, unknown>;
	const value = values[id];
	if (typeof value === "boolean") return value ? "true" : "false";
	if (id === "fullscreenWheelScrollLines" && value === 0) return "auto";
	if (typeof value === "string") return value === "  " ? JSON.stringify(value) : value;
	return String(value ?? "");
}

function cycle(values: string[], current: string, direction: number): string {
	const index = values.indexOf(current);
	return values[(index + direction + values.length) % values.length] ?? values[0]!;
}
