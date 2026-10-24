import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { visibleWidth } from "@earendil-works/pi-tui";
import { MarkdownFactory } from "../../src/markdown/markdown-factory.ts";
import { display } from "../../src/presentation/text-safety.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

function render(text: string, width = 48, mermaid = true): string {
	const instance = new MarkdownFactory().create(theme, "  ", mermaid);
	instance.setText(text);
	return instance.markdown.render(width).join("\n");
}

test("tables keep CJK cells and long-cell tails, and unclosed fences still show code", () => {
	const table = render("| Name | Value |\n|---|---|\n| 中文字段 | abcdefghijklmnopqrstuvwxyz |");
	assert.match(table, /中文字段/);
	assert.match(table, /abcdefghijklmnopqrstuvwxyz/);
	const fence = render("```java\nclass A {}\n");
	assert.match(fence, /class A \{\}/);
});

test("an overflowing table cell wraps and keeps its tail, width budget and bottom border", () => {
	const width = 30;
	const tail = "TAIL_END";
	const table = render(`| Name | Value |\n|---|---|\n| 中文字段 | ${"x".repeat(40)}${tail} |`, width);
	const lines = table.split("\n");
	assert.match(table, /中文/);
	assert.match(table, /段/);
	assert.match(table, new RegExp(tail), "the tail of an overflowing cell must remain visible");
	for (const line of lines) assert.ok(visibleWidth(line) <= width, `line exceeds ${width}: ${JSON.stringify(line)}`);
	assert.match(table, /[└┴┘]/, "the table must render its bottom border");
});

test("Mermaid fences render real box art with node labels, not the source fallback", () => {
	const text = render("```mermaid\ngraph TD\nA-->B\n```", 60);
	assert.ok(text.trim().length > 0);
	assert.doesNotMatch(text, /```mermaid/);
	assert.match(text, /A/);
	assert.match(text, /B/);
	assert.match(text, /[┌│└╭╰─]/);
	assert.doesNotMatch(text, /A-->B/);
	assert.doesNotMatch(text, /graph TD/);
});

test("model/tool control sequences are stripped before Markdown rendering", () => {
	const raw = "safe\x1b[31mred\x1b]8;;http://example\x07link\x1b]8;;\x07";
	const safe = display(raw);
	assert.equal(safe, "saferedlink");
	assert.doesNotMatch(render(safe), /\x1b\[/);
});

test("NO_COLOR keeps Markdown emphasis text without emitting italic or strike attributes", () => {
	const text = render("**bold** *italic* ~~strike~~");
	assert.match(text, /bold/); assert.match(text, /italic/); assert.match(text, /strike/);
	assert.doesNotMatch(text, /\x1b\[/);
});
