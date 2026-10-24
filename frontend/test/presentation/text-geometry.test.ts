import assert from "node:assert/strict";
import { test } from "node:test";
import { truncateToWidth, visibleWidth, wrapTextWithAnsi } from "@earendil-works/pi-tui";

test("CJK, emoji and combining marks use terminal cell widths and wrap without loss", () => {
	assert.equal(visibleWidth("中文"), 4);
	assert.equal(visibleWidth("🙂"), 2);
	assert.equal(visibleWidth("e\u0301"), 1);
	const text = "中文🙂组合e\u0301 END";
	const wrapped = wrapTextWithAnsi(text, 6);
	assert.ok(wrapped.length > 1);
	for (const line of wrapped) assert.ok(visibleWidth(line) <= 6, `${JSON.stringify(line)} exceeds 6 columns`);
	assert.equal(wrapped.join("").replace(/\s/g, ""), text.replace(/\s/g, ""), "wrapping must not drop characters");
	const cut = truncateToWidth("中文🙂", 4, "");
	assert.ok(visibleWidth(cut) <= 4);
	assert.equal(cut.startsWith("中"), true);
});
