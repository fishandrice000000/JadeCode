/**
 * 外部内容 → 可安全显示的文本。
 *
 * 模型回复、工具结果、文件名和会话标题都不是本程序生成的样式；它们可能夹带 ESC/CSI/OSC
 * 序列（改标题、改颜色、写剪贴板）。这里与旧 Java `TextSanitizer` 保持同一语义：
 * - `display`：移除控制序列，保留换行/制表符；
 * - `singleLine`：再压成一行，用于标题/状态栏；
 * - `visibleEscapes`：不删除内容，把控制字节显示成 `\x1b`，用于审批参数阅读。
 *
 * 内部生成的样式（主题 ANSI）不经过这里；两者不能混为一谈。
 */

export function display(value: string | null | undefined): string {
	if (!value) return "";
	let out = "";
	let i = 0;
	while (i < value.length) {
		const c = value.charCodeAt(i);
		if (c === 0x1b) {
			i = skipEscape(value, i);
			continue;
		}
		if (c === 0x0a || c === 0x09) {
			out += value[i];
			i++;
			continue;
		}
		if (c === 0x9b || c === 0x9d || c === 0x90 || c === 0x9f || c === 0x98 || c === 0x9e) {
			i = skipC1(value, i, c);
			continue;
		}
		if (c < 0x20 || c === 0x7f || (c >= 0x80 && c <= 0x9f)) {
			i++;
			continue;
		}
		// 孤立代理项替换为可见占位，避免后续 UTF-8 编码崩溃。
		if (c >= 0xd800 && c <= 0xdbff) {
			const next = value.charCodeAt(i + 1);
			if (next >= 0xdc00 && next <= 0xdfff) {
				out += value.slice(i, i + 2);
				i += 2;
			} else {
				out += "\uFFFD";
				i++;
			}
			continue;
		}
		if (c >= 0xdc00 && c <= 0xdfff) {
			out += "\uFFFD";
			i++;
			continue;
		}
		out += value[i];
		i++;
	}
	return out;
}

export function singleLine(value: string | null | undefined): string {
	return display(value).replace(/\r\n/g, "\n").replace(/\r/g, "\n").replace(/[\n\t]/g, " ");
}

/** 把控制字节显示成转义文本；内容不会被删除（审批要看真实参数）。 */
export function visibleEscapes(value: string | null | undefined): string {
	if (!value) return "";
	let out = "";
	for (const char of value) {
		const c = char.codePointAt(0) ?? 0;
		if (c === 0x0a) out += "\n";
		else if (c === 0x09) out += "\\t";
		else if (c === 0x1b) out += "\\x1b";
		else if (c < 0x20 || c === 0x7f) out += `\\x${c.toString(16).padStart(2, "0")}`;
		else if (c >= 0x80 && c <= 0x9f) out += `\\u${c.toString(16).padStart(4, "0")}`;
		else out += char;
	}
	return out;
}

function skipC1(value: string, start: number, kind: number): number {
	let i = start + 1;
	if (kind === 0x9b) {
		while (i < value.length) {
			const c = value.charCodeAt(i++);
			if (c >= 0x40 && c <= 0x7e) return i;
		}
		return i;
	}
	if (kind === 0x9d || kind === 0x90 || kind === 0x9f || kind === 0x98 || kind === 0x9e) {
		while (i < value.length) {
			const c = value.charCodeAt(i);
			if (c === 0x07) return i + 1;
			if (c === 0x1b && value.charCodeAt(i + 1) === 0x5c) return i + 2;
			i++;
		}
		return i;
	}
	return i;
}

function skipEscape(value: string, start: number): number {
	let i = start + 1;
	if (i >= value.length) return i;
	const kind = value[i];
	if (kind === "[") {
		i++;
		while (i < value.length) {
			const c = value.charCodeAt(i++);
			if (c >= 0x40 && c <= 0x7e) return i;
		}
		return i;
	}
	if (kind === "]" || kind === "P" || kind === "_" || kind === "^" || kind === "X") {
		i++;
		while (i < value.length) {
			const c = value.charCodeAt(i);
			if (c === 0x07) return i + 1;
			if (c === 0x1b && value.charCodeAt(i + 1) === 0x5c) return i + 2;
			i++;
		}
		return i;
	}
	return Math.min(value.length, i + 1);
}
