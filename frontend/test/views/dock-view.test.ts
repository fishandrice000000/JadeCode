import assert from "node:assert/strict";
import { test } from "node:test";
import { visibleWidth } from "@earendil-works/pi-tui";
import { DockView, framePage } from "../../src/views/dock-view.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const theme = resolveTheme(parseThemeDocument(JSON.stringify({ name: "t", colors: {} })), "dark",
	{ mode: "256color", noColor: true });

function page(title: string, body: string[], hint = ""): never {
	return {
		title, theme: () => theme, render: () => body, hint: () => hint,
		invalidate: () => {},
	} as never;
}

test("a page frame keeps the title on the right and every line inside the width budget", () => {
	const lines = framePage(page("Settings", ["  field one", "  field two"], "enter save"), 20);
	assert.equal(visibleWidth(lines[0]!), 20);
	assert.ok(lines[0]!.endsWith(" Settings "));
	assert.deepEqual(lines.slice(1, 3), ["  field one", "  field two"]);
	assert.equal(lines[3], "enter save");
	assert.equal(lines.at(-1), "─".repeat(20));
	assert.equal(lines.length, 5);

	const narrow = framePage(page("Very long title", ["body"], "a hint that is too long"), 8);
	assert.equal(visibleWidth(narrow[0]!), 8);
	assert.equal(narrow[2], "a hint t");
	assert.equal(narrow.at(-1), "─".repeat(8));
});

test("dock priority is approval, then page, then editor", () => {
	const dock = new DockView();
	assert.deepEqual(dock.render(20), []);
	dock.setContent({ approval: {} as never, page: page("P", ["page"]), editor: { render: () => ["editor"] } as never });
	assert.deepEqual(dock.render(20), [], "approval is rendered by AppLayout, not the dock");
	dock.setContent({ page: page("P", ["page"]), editor: { render: () => ["editor"] } as never });
	assert.deepEqual(dock.render(20), ["─".repeat(17) + " P ", "page", "─".repeat(20)]);
	dock.setContent({ editor: { render: () => ["editor"] } as never });
	assert.deepEqual(dock.render(20), ["editor"]);
});


test("title truncation keeps VS16/ZWJ graphemes intact and never overflows", () => {
	const emoji = framePage(page("❤️XYZ", ["body"]), 2);
	assert.equal(visibleWidth(emoji[0]!), 2);
	assert.doesNotMatch(emoji[0]!, /❤(?!️)/, "VS16 must not be split from the heart");
	assert.ok(emoji[0]!.endsWith(" "), "only the leading space remains before the border");

	const family = framePage(page("👨‍👩‍👧‍👦XYZ", ["body"]), 1);
	assert.equal(family[0], " ", "a width-1 budget can only keep the leading space");

	const fits = framePage(page("❤️", ["body"]), 4);
	assert.ok(fits[0]!.includes("❤️"), "a complete grapheme that fits is preserved");
	assert.equal(visibleWidth(fits[0]!), 4);
});
