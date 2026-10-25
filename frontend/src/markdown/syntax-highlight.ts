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

/**
 * 把 highlight.js 自己生成的 HTML span 转成主题 ANSI 行，不处理外部任意 HTML。
 *
 * 每段文字在输出时才套样式，避免先生成空样式再误把 reset 放到文字前面。栈保存嵌套角色：
 * 内层结束恢复父层，跨行注释也继续沿用角色；每段独立关闭样式，下一行不会污染终端。
 * 返回值保留源码行数与解码后的文字，没有文件或终端 IO。
 */
function toAnsiLines(html: string, theme: ResolvedTheme): string[] {
	const lines: string[] = [];
	let current = "";
	let segment = "";
	const roles: (Role | undefined)[] = [];
	let i = 0;
	// 先输出旧角色的文字，再更新栈；未知 span 继承父角色，不清除外层颜色。
	const flushSegment = (): void => {
		const role = roles.at(-1);
		current += role ? theme.style(role, segment) : segment;
		segment = "";
	};
	const flushLine = (): void => {
		flushSegment();
		lines.push(current);
		current = "";
	};
	while (i < html.length) {
		const char = html[i]!;
		if (char === "<") {
			const end = html.indexOf(">", i);
			if (end < 0) {
				segment += html.slice(i);
				break;
			}
			const tag = html.slice(i + 1, end);
			if (tag === "/span") {
				flushSegment();
				roles.pop();
			} else if (tag.startsWith("span ")) {
				flushSegment();
				const match = /class="([^"]+)"/.exec(tag);
				const role = match?.[1]?.split(/\s+/).map((name) => CLASS_ROLES[name]).find((value) => value !== undefined);
				roles.push(role ?? roles.at(-1));
			}
			i = end + 1;
			continue;
		}
		if (char === "&") {
			const semi = html.indexOf(";", i);
			if (semi > i) {
				const entity = html.slice(i, semi + 1);
				segment += decodeEntity(entity);
				i = semi + 1;
				continue;
			}
		}
		if (char === "\n") {
			flushLine();
			i++;
			continue;
		}
		segment += char;
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
