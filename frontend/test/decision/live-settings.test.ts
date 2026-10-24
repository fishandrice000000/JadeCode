import assert from "node:assert/strict";
import { test } from "node:test";
import { Application } from "../../src/app/Application.ts";

test("live settings forward wheel, scrollbar and copy-on-select without restarting", () => {
	const app = new Application();
	const fields = app as unknown as { [key: string]: unknown };
	const calls: Array<[string, unknown]> = [];
	const settings = { showHardwareCursor: true, clearOnShrink: false, fullscreenWheelScrollLines: 0,
		fullscreenScrollbar: "always", fullscreenCopyOnSelect: true, mode: "fullscreen", showTerminalProgress: false };
	fields["session"] = { settings: () => settings, isActive: () => false };
	fields["host"] = {
		setHardwareCursor: (value: unknown) => calls.push(["cursor", value]),
		setClearOnShrink: (value: unknown) => calls.push(["shrink", value]),
		setWheelScrollLines: (value: unknown) => calls.push(["wheel", value]),
		setScrollbar: (value: unknown) => calls.push(["scrollbar", value]),
		setCopyOnSelect: (value: unknown) => calls.push(["copyOnSelect", value]),
		setMode: (value: unknown) => calls.push(["mode", value]),
		setTerminalProgress: (value: unknown) => calls.push(["progress", value]),
	};
	fields["editor"] = { applySettings: () => {} };
	fields["theme"] = { settingsChanged: () => {} };
	(app as unknown as { applyLiveSettings(): void }).applyLiveSettings();
	assert.deepEqual(calls, [["cursor", true], ["shrink", false], ["wheel", "auto"], ["scrollbar", "always"],
		["copyOnSelect", true], ["mode", "fullscreen"]]);
	settings.fullscreenWheelScrollLines = 3;
	settings.fullscreenScrollbar = "hidden";
	settings.fullscreenCopyOnSelect = false;
	calls.length = 0;
	(app as unknown as { applyLiveSettings(): void }).applyLiveSettings();
	assert.deepEqual(calls.filter(([name]) => ["wheel", "scrollbar", "copyOnSelect"].includes(name)),
		[["wheel", 3], ["scrollbar", "hidden"], ["copyOnSelect", false]]);
});
