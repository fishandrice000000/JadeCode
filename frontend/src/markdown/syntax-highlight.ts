import hljs from "highlight.js";
import type { ResolvedTheme, Role } from "../theme/theme-resolver.ts";

/**
 * 代码高亮：固定 highlight.js 10.7.3 的语言集，scope → 同一主题语法角色。
 *
 * 为什么不用 highlight.js 自带主题：那会成为第二套色板，与 Jade 主题/审批/Markdown 不一致。
 * 这里直接消费 highlight.js 词法结果（HTML span class），把已知 class 映射到主题角色；
 * 未知语言/解析失败保留源文，不抛错、不丢代码。
 */

const CLASS_ROLES: Record<string, Role> = {
	"hljs-keyword": "syntaxKeyword",
	"hljs-selector-tag": "syntaxKeyword",
	"hljs-built_in": "syntaxType",
	"hljs-type": "syntaxType",
	"hljs-literal": "syntaxNumber",
	"hljs-number": "syntaxNumber",
	"hljs-string": "syntaxString",
	"hljs-regexp": "syntaxString",
	"hljs-comment": "syntaxComment",
	"hljs-quote": "syntaxComment",
	"hljs-title": "syntaxFunction",
	"hljs-section": "syntaxFunction",
	"hljs-function": "syntaxFunction",
	"hljs-name": "syntaxFunction",
	"hljs-attr": "syntaxVariable",
	"hljs-attribute": "syntaxVariable",
	"hljs-variable": "syntaxVariable",
	"hljs-template-variable": "syntaxVariable",
	"hljs-params": "syntaxVariable",
	"hljs-operator": "syntaxOperator",
	"hljs-punctuation": "syntaxPunctuation",
	"hljs-meta": "syntaxPunctuation",
	"hljs-symbol": "syntaxNumber",
	"hljs-bullet": "syntaxPunctuation",
	"hljs-addition": "toolDiffAdded",
	"hljs-deletion": "toolDiffRemoved",
};

/**
 * @param code 源码（不含围栏）
 * @param language Markdown 围栏语言；未知/空时按纯文本处理
 * @returns 每一行已着色字符串；行数与原代码一致（Markdown 组件按行渲染）
 */
export function highlightCode(code: string, language: string | undefined, theme: ResolvedTheme): string[] {
	const plain = code.split("\n");
	if (theme.noColor) return plain;
	const lang = (language ?? "").trim().toLowerCase();
	if (!lang) return plain;
	if (!hljs.getLanguage(lang)) return plain;
	try {
		const html = hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
		return toAnsiLines(html, theme);
	} catch {
		// 解析失败保留源文；代码永远比配色重要。
		return plain;
	}
}

/** 把 highlight.js 的 HTML 片段转成主题 ANSI 行；只识别它自己的 span class。 */
function toAnsiLines(html: string, theme: ResolvedTheme): string[] {
	const lines: string[] = [];
	let current = "";
	let reset = false;
	let i = 0;
	const flushLine = (): void => {
		lines.push(reset ? `${current}\x1b[39m` : current);
		current = "";
		reset = false;
	};
	while (i < html.length) {
		const char = html[i]!;
		if (char === "<") {
			const end = html.indexOf(">", i);
			if (end < 0) {
				current += html.slice(i);
				break;
			}
			const tag = html.slice(i + 1, end);
			if (tag === "/span") {
				if (reset) {
					current += "\x1b[39m";
					reset = false;
				}
			} else if (tag.startsWith("span ")) {
				const match = /class="([^"]+)"/.exec(tag);
				const role = match ? CLASS_ROLES[match[1]!] : undefined;
				if (role) {
					const color = theme.colors.get(role);
					if (color) {
						current += theme.style(role, "\u0000").replace("\u0000", "");
						reset = true;
					}
				}
			}
			i = end + 1;
			continue;
		}
		if (char === "&") {
			const semi = html.indexOf(";", i);
			if (semi > i) {
				const entity = html.slice(i, semi + 1);
				current += decodeEntity(entity);
				i = semi + 1;
				continue;
			}
		}
		if (char === "\n") {
			flushLine();
			i++;
			continue;
		}
		current += char;
		i++;
	}
	flushLine();
	return lines;
}

function decodeEntity(entity: string): string {
	switch (entity) {
		case "&amp;": return "&";
		case "&lt;": return "<";
		case "&gt;": return ">";
		case "&quot;": return '"';
		case "&#39;": return "'";
		default: return entity;
	}
}
