import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { SETTINGS_FIELD_IDS, SettingsPage } from "../../src/pages/settings-page.ts";
import { ThemePage } from "../../src/pages/theme-page.ts";
import type { PageContext } from "../../src/pages/page-stack.ts";
import { SessionStore, parseTui } from "../../src/state/session-store.ts";
import { KeymapAdapter } from "../../src/editor/keymap-adapter.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

function page(wheel: number): { value: SettingsPage; patches: Array<Record<string, unknown>> } {
	const state = new SessionStore();
	state.applyBootstrap({ buildId: "b", protocolVersion: 1, projectVersion: "t", workspace: "/tmp", themeDirectory: "/tmp",
		configRevision: "0", tui: {}, startup: { mode: "new", autoSubmit: false, resumeTarget: "" } } as never);
	const settings = { ...parseTui({}), fullscreenWheelScrollLines: wheel };
	state.applySnapshot({ snapshot: { sessionId: "s", generation: "1", tui: settings, configRevision: "0" } } as never);
	const patches: Array<Record<string, unknown>> = [];
	const context: PageContext = {
		state, keymap: new KeymapAdapter({}), theme: () => theme, client: () => null, identity: () => ({ sessionId: "s", generation: "1" }),
		closePage: () => {}, pushPage: () => {}, requestRender: () => {}, notify: () => {},
		saveSettings: (patch) => {
			patches.push(patch);
			if (typeof patch.fullscreenWheelScrollLines === "number") settings.fullscreenWheelScrollLines = patch.fullscreenWheelScrollLines;
		},
		themeNames: () => [], previewTheme: () => {}, commitTheme: () => {}, commitThemeTarget: () => {}, cancelTheme: () => {},
		sessionTheme: () => "system", setDraft: () => {}, jumpToMatch: () => {},
		lastFrame: () => null, dockOriginY: () => 0, evidence: () => null, settings: () => settings,
	};
	return { value: new SettingsPage(context), patches };
}

function selectWheel(value: SettingsPage): void {
	// theme, light, dark, mode, exit, scrollbar, copy, wheel
	for (let i = 0; i < 7; i++) value.handleInput("\x1b[B");
}

test("the settings menu exposes exactly the 18 approved fields", () => {
	assert.equal(SETTINGS_FIELD_IDS.length, 18);
	assert.equal(SETTINGS_FIELD_IDS.includes("codeBlockIndent"), false);
});

test("wheel setting cycles through the approved candidates and wraps to auto", () => {
	const { value, patches } = page(0);
	selectWheel(value);
	for (let i = 0; i < 6; i++) value.handleInput("\x1b[C");
	assert.deepEqual(patches.map((patch) => patch.fullscreenWheelScrollLines), [1, 2, 3, 5, 10, 0]);
});

test("a custom wheel value stays in the cycle between 5 and 10", () => {
	const { value, patches } = page(7);
	selectWheel(value);
	value.handleInput("\x1b[C");
	value.handleInput("\x1b[C");
	assert.deepEqual(patches.map((patch) => patch.fullscreenWheelScrollLines), [10, 0]);
});


test("one theme confirmation sends exactly one save", () => {
	const { value } = page(0);
	const commits: string[] = [];
	const patches: Array<Record<string, unknown>> = [];
	const context = (value as unknown as { context: PageContext }).context;
	const themeContext = { ...context, themeNames: () => ["system"], sessionTheme: () => "system",
		previewTheme: () => {}, commitThemeTarget: (target: string, name: string) => { commits.push(`${target}:${name}`); },
		saveSettings: (patch: Record<string, unknown>) => { patches.push(patch); } };
	const themePage = new ThemePage(themeContext, "theme");
	themePage.handleInput("\r");
	assert.deepEqual(commits, ["theme:system"]);
	assert.deepEqual(patches, [], "ThemePage must not call saveSettings separately");
});
