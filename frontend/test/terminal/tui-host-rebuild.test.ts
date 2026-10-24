import assert from "node:assert/strict";
import { test } from "node:test";
import { TuiHost } from "../../src/terminal/tui-host.ts";
import type { TuiAltScreen } from "@earendil-works/pi-tui";

test("live fullscreen policies survive screen factory rebuilds", () => {
	const host = new TuiHost({
		mode: "fullscreen", wheelScrollLines: "auto", scrollbar: "always", copyOnSelect: true,
		showHardwareCursor: true, escapeTimeoutMillis: 20,
		onFrameWritten: () => {}, onWritable: () => {}, onWriteFailure: () => {}, onResize: () => {},
		onInputStart: () => {}, onInputEnd: () => {}, onRawInput: () => {}, onScreenReady: () => {},
	});
	host.setRoot({ render: () => [], invalidate: () => {} });
	host.setWheelScrollLines(3);
	host.setScrollbar("hidden");
	host.setCopyOnSelect(false);
	host.setHardwareCursor(false);
	const options = (host as unknown as { options: Record<string, unknown> }).options;
	assert.equal(options["wheelScrollLines"], 3);
	assert.equal(options["scrollbar"], "hidden");
	assert.equal(options["copyOnSelect"], false);
	assert.equal(options["showHardwareCursor"], false);
	const screen = (host as unknown as { createScreen(mode: string): TuiAltScreen }).createScreen("fullscreen");
	assert.equal(screen.getCopyOnSelect(), false, "a new screen reads the latest live copy policy");
	screen.stop();
});
