import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
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

test("Mermaid fences are transformed and still render without a Java engine", () => {
	const text = render("```mermaid\ngraph TD\nA-->B\n```", 60);
	assert.ok(text.trim().length > 0);
	assert.doesNotMatch(text, /```mermaid/);
});

test("model/tool control sequences are stripped before Markdown rendering", () => {
	const raw = "safe\x1b[31mred\x1b]8;;http://example\x07link\x1b]8;;\x07";
	const safe = display(raw);
	assert.equal(safe, "saferedlink");
	assert.doesNotMatch(render(safe), /\x1b\[/);
});
