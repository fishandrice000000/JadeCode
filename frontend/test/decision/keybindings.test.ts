import assert from "node:assert/strict";
import { test } from "node:test";
import { KeybindingsManager, TUI_KEYBINDINGS } from "@earendil-works/pi-tui";
import { KeymapAdapter } from "../../src/editor/keymap-adapter.ts";
import { Application } from "../../src/app/Application.ts";

test("user overrides change global matching and do not leave the default active", () => {
	const keymap = new KeymapAdapter({ "app.tools.expand": ["ctrl+p"], "tui.input.newLine": ["ctrl+n"] });
	assert.equal(keymap.matches("app.tools.expand", "\x10"), true);
	assert.equal(keymap.matches("app.tools.expand", "\x0f"), false, "the replaced default ctrl+o must not fire");
	assert.equal(keymap.keys("app.session.tree").length, 0, "HIDDEN actions keep no default binding");
	const pi = new KeybindingsManager(TUI_KEYBINDINGS, keymap.piOverrides() as never);
	assert.equal(pi.matches("\x0e", "tui.input.newLine"), true);
	assert.equal(pi.matches("\n", "tui.input.newLine"), false, "the editor must use the override, not Pi's default");
	assert.equal("app.tools.expand" in keymap.piOverrides(), false, "app-level actions are routed by Jade");
});

test("Application global routing executes the overridden key", () => {
	const app = new Application();
	const fields = app as unknown as { [key: string]: unknown };
	const calls: string[] = [];
	fields["keymap"] = new KeymapAdapter({ "app.tools.expand": ["ctrl+p"] });
	fields["transcript"] = {
		entries: () => [{ kind: "TOOL", expanded: false }], setToolsExpanded: (value: boolean) => { calls.push(`expand:${value}`); },
		notice: () => {},
	};
	fields["host"] = { hasSelection: () => false, requestRender: () => {}, tui: () => ({}), mode: () => "fullscreen" };
	fields["editor"] = { draft: () => "", setDraft: () => {}, focus: () => {} };
	const route = (app as unknown as { onGlobalInput(input: string): unknown }).onGlobalInput.bind(app);
	route("\x10"); // ctrl+p
	route("\x0f"); // ctrl+o (replaced)
	assert.deepEqual(calls, ["expand:true"]);
});
