import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import {
	Editor,
	KeybindingsManager,
	TUI_KEYBINDINGS,
	setKeybindings,
	type TUI,
} from "@earendil-works/pi-tui";
import { KeymapAdapter } from "../../src/editor/keymap-adapter.ts";
import { SettingsPage } from "../../src/pages/settings-page.ts";
import { PermissionPage } from "../../src/pages/permission-page.ts";
import { ApprovalController } from "../../src/approval/approval-controller.ts";
import { InputEvidence } from "../../src/terminal/input-evidence.ts";
import { PresentedEvidence } from "../../src/terminal/presented-evidence.ts";
import { SessionStore, parseTui } from "../../src/state/session-store.ts";
import { PageStack, type PageContext } from "../../src/pages/page-stack.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";
import { Application } from "../../src/app/Application.ts";
import type { BackendClient } from "../../src/bridge/client.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

function session(): SessionStore {
	const store = new SessionStore();
	store.applyBootstrap({ buildId: "b", protocolVersion: 1, projectVersion: "t", workspace: "/tmp", themeDirectory: "/tmp",
		configRevision: "0", tui: {}, startup: { mode: "new", autoSubmit: false, resumeTarget: "" } } as never);
	store.applySnapshot({ snapshot: { sessionId: "s", generation: "1", tui: parseTui({}), configRevision: "0" } } as never);
	return store;
}

function pageContext(keymap: KeymapAdapter): PageContext {
	const state = session();
	return {
		state, keymap, theme: () => theme, client: () => null,
		identity: () => ({ sessionId: "s", generation: "1" }), closePage: () => {}, pushPage: () => {},
		requestRender: () => {}, notify: () => {}, saveSettings: () => {}, themeNames: () => [],
		previewTheme: () => {}, commitTheme: () => {}, cancelTheme: () => {}, sessionTheme: () => "system",
		setDraft: () => {}, jumpToMatch: () => {}, lastFrame: () => null, dockOriginY: () => 0,
		evidence: () => null, settings: () => state.settings(),
	};
}

test("HIDDEN actions are cleared in the real Pi manager, so jump mode cannot execute", () => {
	const keymap = new KeymapAdapter({});
	const pi = new KeybindingsManager(TUI_KEYBINDINGS, keymap.piOverrides() as never);
	assert.deepEqual(pi.getKeys("tui.editor.jumpForward"), []);
	assert.deepEqual(pi.getKeys("tui.input.copy"), []);
	setKeybindings(pi);
	const editor = new Editor({ terminal: { rows: 24 }, requestRender: () => {} } as unknown as TUI, {
		borderColor: (text) => text,
		selectList: { selectedPrefix: (t) => t, selectedText: (t) => t, description: (t) => t, scrollInfo: (t) => t, noMatch: (t) => t },
	});
	for (const key of ["a", "b", "c"]) editor.handleInput(key);
	editor.handleInput("\x1b[H"); // Home
	editor.handleInput("\x1b[93;5u"); // Ctrl+] (Pi default jumpForward)
	editor.handleInput("c");
	// 若隐藏动作仍启用，Ctrl+] 会进入跳转模式并吞掉目标字符；这里应正常插入到行首。
	assert.equal(editor.getText(), "cabc", "the hidden jump action must not execute");
});

test("a page consumes the overridden select key and ignores the replaced default", () => {
	const keymap = new KeymapAdapter({ "tui.select.down": ["ctrl+n"] });
	const page = new SettingsPage(pageContext(keymap));
	const before = page.render(80).join("\n");
	assert.match(before, /▸ Theme/);
	page.handleInput("\x0e"); // ctrl+n
	const after = page.render(80).join("\n");
	assert.match(after, /▸ Light theme/);
	page.handleInput("\x1b[B"); // replaced default down
	assert.match(page.render(80).join("\n"), /▸ Light theme/);
});

test("approval confirm uses the overridden key and the old Enter no longer answers", async () => {
	const keymap = new KeymapAdapter({ "approval.confirm": ["ctrl+y"] });
	const commands: string[] = [];
	const client = {
		command: async (type: string) => {
			commands.push(type);
			return { ok: true, type: "command.result", payload: {} };
		},
	} as unknown as BackendClient;
	const evidence = new InputEvidence();
	const presented = new PresentedEvidence(evidence, () => {});
	const approval = new ApprovalController({
		client: () => client, evidence, presented, revision: () => "0",
		identity: () => ({ sessionId: "s", generation: "1" }), requestRender: () => {},
		notify: () => {}, terminalHealthy: () => true, keymap: () => keymap,
		teamActive: () => false, actorName: (actor) => actor,
	});
	approval.setPresented(presented);
	approval.setTheme(theme);
	approval.requested({ requestId: "r", actor: "leader", runId: "run", tool: "bash", cwd: "/tmp", revision: "0",
		inputJson: "{}", suggestionId: null, suggestionScope: "" } as never, () => undefined);
	approval.active()!.render({ title: "Approval required", tool: "bash", scope: "s", parameters: "{}", actor: "leader",
		loading: false, teamActive: false, actorName: (a) => a }, 80, 0, 40, 80);
	approval.onFrame({ frameId: 1, epoch: 0, columns: 80, rows: 40, rawWatermark: 0, decodedWatermark: 0 });
	await new Promise((resolve) => setTimeout(resolve, 0));
	evidence.rawChunk("\x1b[B"); evidence.beginDispatch(1); approval.handleInput("\x1b[B"); evidence.endDispatch();
	evidence.rawChunk("\r"); evidence.beginDispatch(2); approval.handleInput("\r"); evidence.endDispatch();
	assert.equal(commands.filter((type) => type === "approval.answer").length, 0, "old Enter is replaced");
	evidence.rawChunk("\x19"); evidence.beginDispatch(3); approval.handleInput("\x19"); evidence.endDispatch(); // ctrl+y
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(commands.filter((type) => type === "approval.answer").length, 1);
});

test("SUPPORTED no-default actions open their pages from the global route", () => {
	const app = new Application();
	const fields = app as unknown as { [key: string]: unknown };
	fields["keymap"] = new KeymapAdapter({ "app.permission.menu": ["ctrl+p"] });
	fields["pages"] = new PageStack();
	fields["host"] = { hasSelection: () => false, requestRender: () => {}, tui: () => ({}), mode: () => "fullscreen" };
	fields["editor"] = { draft: () => "", setDraft: () => {}, focus: () => {} };
	const route = (app as unknown as { onGlobalInput(input: string): unknown }).onGlobalInput.bind(app);
	const result = route("\x10");
	assert.deepEqual(result, { consume: true });
	const pages = fields["pages"] as PageStack;
	assert.equal(pages.current()?.title, "Permission");
});

test("overriding newLine blocks Pi's LF/Ctrl+J compatibility path", () => {
	const app = new Application();
	const fields = app as unknown as { [key: string]: unknown };
	fields["keymap"] = new KeymapAdapter({ "tui.input.newLine": ["ctrl+n"] });
	fields["pages"] = new PageStack();
	fields["host"] = { hasSelection: () => false, requestRender: () => {}, tui: () => ({}), mode: () => "fullscreen" };
	fields["editor"] = { draft: () => "", setDraft: () => {}, focus: () => {} };
	const route = (app as unknown as { onGlobalInput(input: string): unknown }).onGlobalInput.bind(app);
	assert.deepEqual(route("\n"), { consume: true });
	assert.equal(route("\x0e"), undefined, "the overridden newline key still reaches the editor");
});
