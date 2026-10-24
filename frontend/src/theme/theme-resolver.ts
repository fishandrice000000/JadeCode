import {
	backgroundAnsi,
	type Color,
	colorToRgb,
	foregroundAnsi,
	mixColors,
	okhslColor,
	parseColor,
	rgbColor,
	styleText,
	type TerminalColorMode,
} from "@earendil-works/pi-tui";
import { DARK, LIGHT, SINGLE, type RawColor, type RawVariant, type ThemeDocument } from "./theme-document.ts";

/**
 * 主题解析：变量 → 语义角色 → 可渲染快照。
 *
 * 角色是界面的唯一取色入口：消息卡、工具卡、Markdown、代码高亮、品牌、审批和边框都从同一份
 * 快照取色，不存在第二套语法配色或硬编码蓝橙。`system` 只把终端普通前景/背景留给终端，
 * 强调色/品牌仍来自 Jade 主题（与旧 Java ThemeResolver 的语义一致）。
 */

export const ROLES = [
	"accent",
	"border",
	"borderAccent",
	"borderMuted",
	"success",
	"error",
	"warning",
	"muted",
	"dim",
	"text",
	"thinkingText",
	"surfaceBg",
	"selectedBg",
	"brandPrimary",
	"brandAccent",
	"brandFrame",
	"brandBannerBody",
	"brandBannerShadow",
	"scrollbarTrack",
	"scrollbarThumb",
	"searchMatchBg",
	"searchMatchText",
	"userMessageBg",
	"userMessageText",
	"customMessageBg",
	"customMessageText",
	"customMessageLabel",
	"requestTitle",
	"replyTitle",
	"thinkingTitle",
	"approval",
	"approvalDeny",
	"permission",
	"toolPendingBg",
	"toolSuccessBg",
	"toolErrorBg",
	"toolTitle",
	"toolOutput",
	"mdHeading",
	"mdLink",
	"mdLinkUrl",
	"mdCode",
	"mdCodeBlock",
	"mdCodeBlockBorder",
	"mdQuote",
	"mdQuoteBorder",
	"mdHr",
	"mdListBullet",
	"toolDiffAdded",
	"toolDiffRemoved",
	"toolDiffContext",
	"syntaxComment",
	"syntaxKeyword",
	"syntaxFunction",
	"syntaxVariable",
	"syntaxString",
	"syntaxNumber",
	"syntaxType",
	"syntaxOperator",
	"syntaxPunctuation",
	"thinkingOff",
	"thinkingMinimal",
	"thinkingLow",
	"thinkingMedium",
	"thinkingHigh",
	"thinkingXhigh",
	"thinkingMax",
	"bashMode",
	"panelBg",
	"approvalOnceBg",
	"approvalOnceFg",
	"approvalSimilarBg",
	"approvalSimilarFg",
	"approvalDenyBg",
	"approvalDenyFg",
] as const;

export type Role = (typeof ROLES)[number];

export interface ResolvedTheme {
	readonly name: string;
	readonly appearance: "dark" | "light";
	readonly mode: TerminalColorMode;
	readonly colors: ReadonlyMap<Role, Color>;
	readonly foreground: Color | undefined;
	readonly background: Color | undefined;
	/** 与 NO_COLOR 一致：样式函数不产生任何控制序列。 */
	readonly noColor: boolean;
	/** 前景色样式；未知角色退回正文，绝不使用 highlight.js 自带调色板。 */
	style(role: Role, text: string): string;
	/** 前景 + 背景样式（卡片/审批按钮）。 */
	withBackground(role: Role, background: Role | undefined, text: string): string;
	bold(text: string): string;
	dim(text: string): string;
	underline(text: string): string;
}

/** 旧自定义主题缺少新角色时的语义兜底，避免凭空出现内置蓝色。 */
const FALLBACKS: Partial<Record<Role, Role>> = {
	borderAccent: "border",
	borderMuted: "border",
	customMessageLabel: "accent",
	mdLinkUrl: "muted",
	mdHeading: "accent",
	mdLink: "accent",
	mdCode: "accent",
	mdCodeBlock: "text",
	mdQuote: "muted",
	userMessageText: "text",
	customMessageText: "text",
	mdQuoteBorder: "mdQuote",
	mdHr: "borderMuted",
	mdCodeBlockBorder: "borderMuted",
	mdListBullet: "accent",
	toolTitle: "text",
	toolOutput: "muted",
	toolDiffContext: "muted",
	scrollbarTrack: "borderMuted",
	scrollbarThumb: "border",
	searchMatchText: "text",
	syntaxComment: "muted",
	syntaxKeyword: "accent",
	syntaxFunction: "mdHeading",
	syntaxVariable: "text",
	syntaxString: "mdCodeBlock",
	syntaxNumber: "success",
	syntaxType: "accent",
	syntaxOperator: "text",
	syntaxPunctuation: "muted",
	thinkingOff: "thinkingText",
	thinkingMinimal: "thinkingText",
	thinkingLow: "thinkingText",
	thinkingMedium: "thinkingText",
	thinkingHigh: "thinkingText",
	thinkingXhigh: "thinkingText",
	thinkingMax: "thinkingXhigh",
	bashMode: "success",
	requestTitle: "customMessageLabel",
	replyTitle: "accent",
	thinkingTitle: "thinkingText",
	approval: "borderAccent",
	approvalDeny: "error",
	permission: "warning",
	toolPendingBg: "surfaceBg",
	toolSuccessBg: "surfaceBg",
	toolErrorBg: "surfaceBg",
	brandPrimary: "accent",
	brandAccent: "accent",
	brandFrame: "border",
	brandBannerBody: "brandPrimary",
	brandBannerShadow: "brandPrimary",
	selectedBg: "surfaceBg",
	userMessageBg: "surfaceBg",
	customMessageBg: "surfaceBg",
	searchMatchBg: "selectedBg",
	approvalOnceBg: "success",
	approvalSimilarBg: "warning",
	approvalDenyBg: "error",
};

/** 允许在角色中写 `auto` 的键；其它角色出现 auto 是配置错误。 */
const AUTO_ROLES: ReadonlySet<string> = new Set(["panelBg", "approvalOnceFg", "approvalSimilarFg", "approvalDenyFg"]);

export interface ResolveOptions {
	/** 终端色模式；决定 ANSI 是 256 色还是 truecolor。 */
	mode: TerminalColorMode;
	/** system 主题绑定：普通前景/背景跟随终端；undefined 表示让终端继承默认。 */
	system?: { foreground?: Color; background?: Color; dark: boolean };
	/** NO_COLOR：不输出任何颜色/属性（样式函数退化为原文）。 */
	noColor?: boolean;
}

/**
 * 解析一个文档变体。
 *
 * @param document 已通过结构校验的主题文件
 * @param variantKey dark/light，或 v1 的 single
 */
export function resolveTheme(document: ThemeDocument, variantKey: string, options: ResolveOptions): ResolvedTheme {
	const variant = pickVariant(document, variantKey);
	const system = options.system;
	// 显式 false 允许测试/宿主覆盖环境；未提供时才读取 NO_COLOR。
	const noColor = options.noColor ?? Boolean(process.env.NO_COLOR);
	const appearance = system ? (system.dark ? "dark" : "light") : variant.appearance;
	const vars = new Map<string, Color | undefined>();
	// system 主题：普通前景/背景先绑定终端事实，其余变量仍来自主题。
	if (system) {
		vars.set("foreground", system.foreground);
		vars.set("background", system.background);
	}
	for (const [name, raw] of Object.entries(variant.vars)) {
		if (system && (name === "foreground" || name === "background")) continue;
		vars.set(name, resolveVar(name, raw, variant, new Set()));
	}
	const colors = new Map<Role, Color>();
	const deferred = new Set<string>();
	for (const role of ROLES) {
		const raw = variant.colors[role];
		if (raw === undefined || raw.text === "" && raw.index === null) continue;
		if (raw.index === null && raw.text === "auto") {
			if (!AUTO_ROLES.has(role)) throw new Error(`Theme color ${role} cannot use auto`);
			deferred.add(role);
			continue;
		}
		const resolved = resolveColor(raw, vars, variant, role, new Set());
		if (resolved !== undefined) colors.set(role, resolved);
	}
	fillFallbacks(colors, variant);
	const background = colors.get("surfaceBg") ?? vars.get("background");
	if (deferred.has("panelBg")) {
		const explicit = colors.get("panelBg");
		if (explicit) colors.delete("panelBg");
		const derived = derivePanelBg(background, appearance === "dark", variant.panelBgMix);
		if (derived) colors.set("panelBg", derived);
	}
	for (const [fgRole, bgRole] of [
		["approvalOnceFg", "approvalOnceBg"],
		["approvalSimilarFg", "approvalSimilarBg"],
		["approvalDenyFg", "approvalDenyBg"],
	] as const) {
		if (!deferred.has(fgRole)) continue;
		const bg = colors.get(bgRole);
		const contrast = contrastForeground(bg);
		if (contrast) colors.set(fgRole, contrast);
	}
	return {
		name: variantKey === SINGLE ? document.name : `${document.name}:${variantKey}`,
		appearance,
		mode: options.mode,
		colors,
		foreground: system ? system.foreground : vars.get("foreground"),
		background: system ? system.background : vars.get("background"),
		noColor,
		style: (role, text) => {
			if (noColor) return text;
			const color = colors.get(role);
			return color ? styleText(text, { fg: color }, options.mode) : text;
		},
		withBackground: (role, backgroundRole, text) => {
			if (noColor) return text;
			const fg = colors.get(role);
			const bg = backgroundRole ? colors.get(backgroundRole) : undefined;
			if (!fg && !bg) return text;
			return styleText(text, { fg, bg }, options.mode);
		},
		bold: (text) => (noColor ? text : `\x1b[1m${text}\x1b[22m`),
		dim: (text) => (noColor ? text : `\x1b[2m${text}\x1b[22m`),
		underline: (text) => (noColor ? text : `\x1b[4m${text}\x1b[24m`),
	};
}

function pickVariant(document: ThemeDocument, key: string): RawVariant {
	const direct = document.variants[key];
	if (direct) return direct;
	if (document.schemaVersion === 1) return document.variants[SINGLE]!;
	const fallback = document.variants[DARK] ?? document.variants[LIGHT];
	if (!fallback) throw new Error("Theme has no usable variant");
	return fallback;
}

function resolveVar(name: string, raw: RawColor, variant: RawVariant, seen: Set<string>): Color | undefined {
	if (raw.index !== null) return parseColor(raw.index);
	const text = raw.text;
	if (text === "") return undefined;
	if (text === "auto") throw new Error(`Theme variable ${name} cannot use auto`);
	const literal = tryColorLiteral(text);
	if (literal) return literal;
	if (!(text in variant.vars)) throw new Error(`Unknown theme variable reference: ${name} -> ${text}`);
	if (seen.has(text)) throw new Error(`Theme variable cycle: ${[...seen, text].join(" -> ")}`);
	seen.add(name);
	return resolveVar(text, variant.vars[text]!, variant, seen);
}

function resolveColor(raw: RawColor, vars: Map<string, Color | undefined>, variant: RawVariant, role: string,
	seen: Set<string>): Color | undefined {
	if (raw.index !== null) return parseColor(raw.index);
	if (raw.text === "") return undefined;
	const literal = tryColorLiteral(raw.text);
	if (literal) return literal;
	if (raw.text in vars) return vars.get(raw.text);
	if (raw.text in variant.vars) {
		if (seen.has(raw.text)) throw new Error(`Theme variable cycle in ${role}`);
		seen.add(role);
		return resolveVar(raw.text, variant.vars[raw.text]!, variant, seen);
	}
	throw new Error(`Theme color ${role} references unknown variable: ${raw.text}`);
}

function tryColorLiteral(text: string): Color | undefined {
	const okhsl = /^okhsl\(\s*([\d.]+)\s+([\d.]+)%\s+([\d.]+)%\s*\)$/i.exec(text);
	if (okhsl) {
		const h = Number.parseFloat(okhsl[1]!);
		const s = Number.parseFloat(okhsl[2]!);
		const l = Number.parseFloat(okhsl[3]!);
		if (!Number.isFinite(h) || !Number.isFinite(s) || !Number.isFinite(l) || s < 0 || s > 100 || l < 0 || l > 100) {
			throw new Error(`Invalid okhsl color: ${text}`);
		}
		return okhslColor(h, s / 100, l / 100);
	}
	if (/^\d+$/.test(text)) {
		const index = Number.parseInt(text, 10);
		if (index >= 0 && index <= 255) return parseColor(index);
	}
	if (/^#[\da-f]{3,6}$/i.test(text) || /^rgb/i.test(text)) return parseColor(text);
	return undefined;
}

function fillFallbacks(colors: Map<Role, Color>, variant: RawVariant): void {
	for (const role of ROLES) {
		if (colors.has(role)) continue;
		const fallback = FALLBACKS[role];
		if (!fallback) continue;
		const value = colors.get(fallback) ?? lookupRaw(fallback, variant);
		if (value) colors.set(role, value);
	}
	// 最后兜底：正文色仍缺失时使用显式 vars.text/foreground，而不是固定蓝色。
	if (!colors.has("text")) {
		const fallback = lookupRaw("text", variant) ?? lookupRaw("foreground", variant);
		if (fallback) colors.set("text", fallback);
	}
}

function lookupRaw(role: string, variant: RawVariant): Color | undefined {
	const raw = variant.colors[role] ?? variant.vars[role];
	if (!raw || raw.index === null && raw.text === "") return undefined;
	if (raw.index !== null) return parseColor(raw.index);
	return tryColorLiteral(raw.text);
}

/** 面板底色：向对比方向（深色→白、浅色→黑）混合固定比例。 */
export function derivePanelBg(background: Color | undefined, dark: boolean, mixRatio: number): Color | undefined {
	const base = background ?? (dark ? rgbColor(0, 0, 0) : rgbColor(255, 255, 255));
	const weight = Number.isFinite(mixRatio) ? Math.max(0, Math.min(1, mixRatio)) : 0.1;
	return mixColors(base, dark ? rgbColor(255, 255, 255) : rgbColor(0, 0, 0), weight, "oklch");
}

/** 按 sRGB 线性亮度选黑/白前景；不可计算时返回 undefined。 */
export function contrastForeground(color: Color | undefined): Color | undefined {
	if (!color) return undefined;
	const rgb = colorToRgbSafe(color);
	if (!rgb) return undefined;
	const luminance = 0.2126 * linearChannel(rgb.r) + 0.7152 * linearChannel(rgb.g) + 0.0722 * linearChannel(rgb.b);
	return luminance >= 0.179 ? rgbColor(0, 0, 0) : rgbColor(255, 255, 255);
}

function colorToRgbSafe(color: Color): { r: number; g: number; b: number } | undefined {
	try {
		return colorToRgb(color);
	} catch {
		return undefined;
	}
}

function linearChannel(channel: number): number {
	const value = channel / 255;
	return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
}

/** 把角色色值转成 ANSI 前景序列；用于需要自行拼接样式的地方（如高亮 scope）。 */
export function ansi(theme: ResolvedTheme, role: Role, text: string): string {
	const color = theme.colors.get(role);
	return color ? foregroundAnsi(color, theme.mode) + text + "\x1b[39m" : text;
}

/** 背景 ANSI 序列（给原生行包装底色时使用）。 */
export function bgAnsi(theme: ResolvedTheme, role: Role, text: string): string {
	const color = theme.colors.get(role);
	return color ? backgroundAnsi(color, theme.mode) + text + "\x1b[49m" : text;
}
