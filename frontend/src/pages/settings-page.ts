import { Input, matchesKey } from "@earendil-works/pi-tui";
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
];

/** 菜单只暴露 18 个有效字段；codeBlockIndent 等非菜单配置仍由 Java 保存并被 Markdown 消费。 */
export const SETTINGS_FIELD_IDS = FIELDS.map((field) => field.id);

/** fullscreenWheelScrollLines 的既定离散候选；当前自定义值插在相邻档位之间。 */
function wheelCandidates(current: number): number[] {
	const base = [0, 1, 2, 3, 5, 10];
	if (base.includes(current)) return base;
	const sorted = [...base, current].sort((a, b) => a - b);
	return sorted;
}

export class SettingsPage implements Page {
	readonly title = "Settings";
	private readonly context: PageContext;
	private selected = 0;
	private offset = 0;
	private readonly input = new Input();
	private readonly rowFields = new Map<number, number>();
	private fields(): Field[] {
		const query = this.input.getValue().toLowerCase().trim();
		return FIELDS.filter((field) => !query || `${field.label} ${field.id}`.toLowerCase().includes(query));
	}
	/** 鼠标只改变选择，不直接保存设置；列由页面实际投影预算约束。 */
	handleBodyPress(row: number): boolean {
		const selected = this.rowFields.get(row);
		if (selected !== undefined) this.selected = selected;
		return true;
	}

	constructor(context: PageContext) { this.context = context; }

	theme(): ResolvedTheme { return this.context.theme(); }

	hint(): string {
		return "Type to filter · Up/Down select · Left/Right change · Enter open · Esc back";
	}

	invalidate(): void {}

	render(width: number): string[] {
		const theme = this.context.theme();
		const settings = this.context.settings();
		if (!settings) return [theme.style("dim", "Waiting for backend settings…")];
		const rows: string[] = this.input.render(Math.max(1, width));
		const fields = this.fields();
		this.selected = Math.min(this.selected, Math.max(0, fields.length - 1));
		this.rowFields.clear();
		if (fields.length === 0) return [...rows, theme.style("dim", "No matching settings")];
		const visible = Math.max(6, Math.min(fields.length, 14));
		if (this.selected < this.offset) this.offset = this.selected;
		if (this.selected >= this.offset + visible) this.offset = this.selected - visible + 1;
		for (let index = this.offset; index < Math.min(fields.length, this.offset + visible); index++) {
			const field = fields[index]!;
			this.rowFields.set(rows.length, index);
			const value = displayValue(settings, field.id);
			const marker = index === this.selected ? "▸ " : "  ";
			const label = `${marker}${field.label}`.padEnd(Math.min(34, Math.max(12, width / 2)));
			const line = `${label} ${value}`;
			rows.push(index === this.selected ? theme.bold(theme.style("accent", line)) : theme.style("text", line));
		}

		if (this.offset + visible < fields.length) rows.push(theme.style("dim", "  ↓ more"));
		return rows;
	}

	handleInput(data: string): boolean {
		const keymap = this.context.keymap;
		const fields = this.fields();
		if (keymap.matches("tui.select.cancel", data)) { this.context.closePage(); return true; }
		if (keymap.matches("tui.select.up", data)) { this.selected = (this.selected - 1 + Math.max(1, fields.length)) % Math.max(1, fields.length); return true; }
		if (keymap.matches("tui.select.down", data)) { this.selected = (this.selected + 1) % Math.max(1, fields.length); return true; }
		if (keymap.matches("tui.select.pageUp", data)) { this.selected = (this.selected - 10 + Math.max(1, fields.length)) % Math.max(1, fields.length); return true; }
		if (keymap.matches("tui.select.pageDown", data)) { this.selected = (this.selected + 10) % Math.max(1, fields.length); return true; }
		const field = fields[this.selected];
		if (!field) { this.input.handleInput(data); this.selected = this.offset = 0; return true; }
		if (keymap.matches("tui.select.confirm", data)) {
			if (field.kind === "theme") {
				this.context.pushPage(new ThemePage(this.context, field.id as "theme" | "lightTheme" | "darkTheme"));
			}
			return true;
		}
		if (!matchesKey(data, "left") && !matchesKey(data, "right") && !(data === " " && !this.input.getValue() && field.kind === "bool")) {
			const before = this.input.getValue();
			this.input.handleInput(data);
			if (this.input.getValue() !== before) this.selected = this.offset = 0;
			return true;
		}
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
				if (field.id === "fullscreenWheelScrollLines") {
					const candidates = wheelCandidates(typeof current === "number" ? current : 0);
					const index = candidates.indexOf(typeof current === "number" ? current : 0);
					next = candidates[(index + direction + candidates.length) % candidates.length];
					break;
				}
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
