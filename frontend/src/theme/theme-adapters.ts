import type { EditorTheme, MarkdownTheme, SelectListTheme } from "@earendil-works/pi-tui";
import type { ResolvedTheme, Role } from "./theme-resolver.ts";
import { highlightCode } from "../markdown/syntax-highlight.ts";

/**
 * 把同一份 ThemeSnapshot 投影成 Pi 组件需要的主题接口。
 *
 * 这里不定义任何新颜色：Pi 的 Markdown/Editor/SelectList 只拿到“角色 → 样式函数”，
 * 高亮/品牌/审批也从同一快照取色。若某个组件存在独立调色板（例如 highlight.js 自带主题），
 * 一律覆盖为本项目角色。
 */

export function selectListTheme(theme: ResolvedTheme): SelectListTheme {
	return {
		selectedPrefix: (text) => theme.style("accent", text),
		selectedText: (text) => theme.bold(text),
		description: (text) => theme.style("muted", text),
		scrollInfo: (text) => theme.style("dim", text),
		noMatch: (text) => theme.style("dim", text),
	};
}

export function editorTheme(theme: ResolvedTheme): EditorTheme {
	return {
		borderColor: (text) => theme.style("border", text),
		selectList: selectListTheme(theme),
	};
}

/**
 * Markdown 主题；codeBlockIndent 来自配置，不另写代码块排版器。
 *
 * 所有强调色都来自 Jade 角色；`highlightCode` 使用同一主题的语法角色。
 */
export function markdownTheme(theme: ResolvedTheme, codeBlockIndent: string): MarkdownTheme {
	return {
		heading: (text) => theme.bold(theme.style("mdHeading", text)),
		link: (text) => theme.style("mdLink", text),
		linkUrl: (text) => theme.style("mdLinkUrl", text),
		code: (text) => theme.style("mdCode", text),
		codeBlock: (text) => theme.style("mdCodeBlock", text),
		codeBlockBorder: (text) => theme.style("mdCodeBlockBorder", text),
		quote: (text) => theme.style("mdQuote", text),
		quoteBorder: (text) => theme.style("mdQuoteBorder", text),
		hr: (text) => theme.style("mdHr", text),
		listBullet: (text) => theme.style("mdListBullet", text),
		bold: (text) => theme.bold(text),
		italic: (text) => `\x1b[3m${text}\x1b[23m`,
		strikethrough: (text) => `\x1b[9m${text}\x1b[29m`,
		underline: (text) => theme.underline(text),
		codeBlockIndent,
		highlightCode: (code, lang) => highlightCode(code, lang, theme),
	};
}

/** 审批三选项的取自主题的底色/前景角色；NO_COLOR 时退回文字区分。 */
export interface ApprovalPalette {
	once: (text: string) => string;
	similar: (text: string) => string;
	deny: (text: string) => string;
	disabled: (text: string) => string;
	title: (text: string) => string;
	border: (text: string) => string;
	panel: (text: string) => string;
}

export function approvalPalette(theme: ResolvedTheme): ApprovalPalette {
	return {
		once: (text) => theme.withBackground("approvalOnceFg", "approvalOnceBg", text),
		similar: (text) => theme.withBackground("approvalSimilarFg", "approvalSimilarBg", text),
		deny: (text) => theme.withBackground("approvalDenyFg", "approvalDenyBg", text),
		disabled: (text) => theme.dim(theme.style("muted", text)),
		title: (text) => theme.bold(theme.style("accent", text)),
		border: (text) => theme.style("border", text),
		panel: (text) => theme.style("panelBg", text),
	};
}

export interface BrandingPalette {
	primary: Role;
	accent: Role;
	frame: Role;
	bannerBody: Role;
	bannerShadow: Role;
}

/** 品牌使用固定角色，颜色值仍来自当前主题。 */
export function brandingPalette(): BrandingPalette {
	return {
		primary: "brandPrimary",
		accent: "brandAccent",
		frame: "brandFrame",
		bannerBody: "brandBannerBody",
		bannerShadow: "brandBannerShadow",
	};
}
