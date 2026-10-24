import assert from "node:assert/strict";
import { test } from "node:test";
import { getScrollbarGeometry } from "../../vendor/pi-tui/src/layout.ts";

function box(options: { scrollbar?: "auto" | "always" | "hidden"; visible?: boolean; scrollTop?: number;
	content?: number; height?: number; x?: number; clipX?: number; clipWidth?: number } = {}): never {
	const height = options.height ?? 10;
	const x = options.x ?? 20;
	return {
		component: {}, rect: { x, y: 5, width: 30, height }, clip: { x: options.clipX ?? 0, y: 0, width: options.clipWidth ?? 80, height: 40 },
		children: [{ rect: { x, y: 5, width: 30, height: options.content ?? 100 } }], layer: 1,
		scrollView: { scrollbar: options.scrollbar ?? "always", isScrollbarVisible: options.visible ?? true,
			scrollTop: options.scrollTop ?? 0 },
	} as never;
}

test("scrollbar thumb keeps a minimum height and stays inside the track", () => {
	const top = getScrollbarGeometry(box())!;
	assert.deepEqual(top, { column: 49, trackTop: 5, trackHeight: 10, thumbTop: 5, thumbHeight: 2, maxScrollTop: 90 });
	const middle = getScrollbarGeometry(box({ scrollTop: 45 }))!;
	assert.equal(middle.thumbTop, 9, "halfway scroll moves the thumb halfway through the free track");
	const bottom = getScrollbarGeometry(box({ scrollTop: 90 }))!;
	assert.equal(bottom.thumbTop, 13);
	assert.equal(bottom.thumbTop + bottom.thumbHeight, 15, "the thumb never leaves the track bottom");
	const tiny = getScrollbarGeometry(box({ height: 1 }))!;
	assert.equal(tiny.thumbHeight, 1, "a one-row track still renders a thumb");
});

test("hidden/auto scrollbars only render under their policy and clip", () => {
	assert.equal(getScrollbarGeometry(box({ scrollbar: "hidden", visible: false })), undefined);
	assert.equal(getScrollbarGeometry(box({ scrollbar: "auto", visible: false })), undefined);
	assert.ok(getScrollbarGeometry(box({ scrollbar: "auto", visible: false }), true),
		"auto can be revealed while hovering/scrolling");
	assert.equal(getScrollbarGeometry(box({ scrollbar: "auto", visible: false, content: 5 })), undefined,
		"auto disappears when content fits");
	assert.equal(getScrollbarGeometry(box({ x: 100, clipX: 0, clipWidth: 80 })), undefined,
		"a scrollbar column clipped by its parent is not interactive");
});
