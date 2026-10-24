import assert from "node:assert/strict";
import { test } from "node:test";
import { hyperlink } from "../../vendor/pi-tui/src/terminal-image.ts";

test("untrusted hyperlinks cannot inject controls or executable URL schemes", () => {
	for (const url of ["javascript:alert(1)", "data:text/html,unsafe", "https://example.test\x1b\\\x1b]52;c;payload\x07", "https://example.test\nnext"]) {
		assert.equal(hyperlink("label", url), "label");
	}
	assert.equal(hyperlink("label", "https://example.test"), "\x1b]8;;https://example.test\x1b\\label\x1b]8;;\x1b\\");
});
import { stripTerminalSequences, truncateToWidth, visibleWidth, wrapTextWithAnsi } from "@earendil-works/pi-tui";

test("CJK, emoji and combining marks use terminal cell widths and wrap without loss", () => {
	assert.equal(visibleWidth("中文"), 4);
	assert.equal(visibleWidth("🙂"), 2);
	assert.equal(visibleWidth("e\u0301"), 1);
	const text = "中文🙂组合e\u0301END";
	const wrapped = wrapTextWithAnsi(text, 6);
	assert.ok(wrapped.length > 1);
	for (const line of wrapped) assert.ok(visibleWidth(line) <= 6, `${JSON.stringify(line)} exceeds 6 columns`);
	assert.equal(wrapped.join(""), text, "wrapping must not drop or reorder characters");
	const cut = truncateToWidth("中文🙂", 4, "");
	assert.ok(visibleWidth(cut) <= 4);
	assert.equal(stripTerminalSequences(cut), "中文", "truncation keeps every character that fits");
	assert.equal(visibleWidth("👨‍👩‍👧‍👦"), 2, "a ZWJ family emoji is one two-cell grapheme");
	const styled = "\x1b[31m中文🙂\x1b[39m";
	const styledLines = wrapTextWithAnsi(styled, 4);
	assert.equal(styledLines.map(stripTerminalSequences).join(""), "中文🙂");
});
