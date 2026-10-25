import assert from "node:assert/strict";
import { test } from "node:test";
import { renderLayoutFrame } from "@earendil-works/pi-tui/dist/layout.js";
import { FullscreenLayout } from "../../src/views/fullscreen-layout.ts";

/** 验证生产布局确实安装 ScrollView，而不只是单独验证 Pi 的滚动算法。 */
test("long transcripts scroll independently while the editor and footer remain mounted", () => {
	let rows = 12;
	let messages = Array.from({ length: 80 }, (_, i) => `message ${i}`);
	const origins: number[] = [];
	const layout = new FullscreenLayout({
		size: () => ({ columns: 40, rows }),
		conversation: { render: () => messages, invalidate: () => {} },
		renderDock: (_width, _available, origin) => { origins.push(origin); return ["EDITOR", "DRAFT"]; },
		renderFooter: () => ["STATUS"],
	});
	const frame = () => renderLayoutFrame(layout, 40, rows, () => {});
	const first = frame();
	assert.equal(first.primaryScrollView, layout.scroll);
	assert.deepEqual(first.lines.slice(-3), ["EDITOR", "DRAFT", "STATUS"]);
	assert.ok(first.lines.includes("message 79"));
	layout.scroll.scrollToStart();
	assert.ok(frame().lines[0]!.startsWith("message 0"));
	messages = [...messages, "new message"];
	assert.ok(frame().lines[0]!.startsWith("message 0"), "new output preserves a manually scrolled history anchor");
	layout.scroll.scrollToEnd();
	assert.ok(frame().lines.some((line) => line.startsWith("new message")));
	rows = 8;
	assert.deepEqual(frame().lines.slice(-3), ["EDITOR", "DRAFT", "STATUS"]);
	assert.equal(origins.at(-1), 5, "dock geometry follows the resized viewport, not transcript length");
});
