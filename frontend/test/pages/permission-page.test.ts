import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { PermissionPage } from "../../src/pages/permission-page.ts";
import type { PageContext } from "../../src/pages/page-stack.ts";
import { SessionStore } from "../../src/state/session-store.ts";
import { KeymapAdapter } from "../../src/editor/keymap-adapter.ts";
import { InputEvidence } from "../../src/terminal/input-evidence.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";
import type { BackendClient, CommandResult } from "../../src/bridge/client.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

function setup(keymap = new KeymapAdapter({})): { page: (intent: "runtime" | "default") => PermissionPage; commands: Array<{ type: string; payload: Record<string, unknown> }> } {
	const commands: Array<{ type: string; payload: Record<string, unknown> }> = [];
	const client = {
		command: async (type: string, payload: Record<string, unknown>) => {
			commands.push({ type, payload });
			if (type === "permission.prepareFullAccess") {
				return { ok: true, type: "command.result", payload: { confirmationId: "fa-1" } };
			}
			return { ok: true, type: "command.result", payload: {} };
		},
	} as unknown as BackendClient;
	const state = new SessionStore();
	state.applyBootstrap({ buildId: "b", protocolVersion: 1, projectVersion: "t", workspace: "/tmp", themeDirectory: "/tmp",
		configRevision: "7", tui: {}, startup: { mode: "new", autoSubmit: false, resumeTarget: "" } } as never);
	state.applySnapshot({ snapshot: {
		sessionId: "s", generation: "1", configRevision: "7", tui: {},
		permission: { profile: "workspace", revision: "3", defaultProfile: "workspace", rules: [], disabledDefaultCount: 0 },
	} } as never);
	const evidence = new InputEvidence();
	const context: PageContext = {
		state, keymap, theme: () => theme, client: () => client, identity: () => ({ sessionId: "s", generation: "1" }),
		closePage: () => {}, pushPage: () => {}, requestRender: () => {}, notify: () => {}, saveSettings: () => {},
		themeNames: () => [], previewTheme: () => {}, commitTheme: () => {}, commitThemeTarget: () => {}, cancelTheme: () => {},
		sessionTheme: () => "system", setDraft: () => {}, jumpToMatch: () => {},
		lastFrame: () => ({ frameId: 9, epoch: 0, rawWatermark: 5, decodedWatermark: 5, columns: 80, rows: 40 }),
		dockOriginY: () => 0, evidence: () => { evidence.rawChunk("x"); return evidence.beginDispatch(6); },
		settings: () => state.settings(),
	};
	return { page: (intent) => new PermissionPage(context, intent), commands };
}

test("saving a normal default profile sends permission.default with the config revision", async () => {
	const { page, commands } = setup();
	const value = page("default");
	value.handleInput("\r");
	await new Promise((resolve) => setTimeout(resolve, 0));
	const saved = commands.find((c) => c.type === "permission.default")!;
	assert.equal(saved.payload.profile, "workspace");
	assert.equal(saved.payload.expectedConfigRevision, 7);
	assert.equal("expectedPermissionRevision" in saved.payload, false);
});

test("Full Access default confirmation uses the default intent and the config revision", async () => {
	const { page, commands } = setup();
	const value = page("default");
	value.handleInput("\x1b[B");
	value.handleInput("\x1b[B");
	value.handleInput("\r");
	await new Promise((resolve) => setTimeout(resolve, 0));
	value.render(80); // 确认按钮布局（真实帧由 Application 提供）
	value.handleInput("\x1b[C"); // 默认 Cancel → Confirm
	value.handleInput("\r");
	await new Promise((resolve) => setTimeout(resolve, 0));
	const presented = commands.find((c) => c.type === "permission.presented")!;
	assert.equal(presented.payload.intent, "default");
	assert.ok(Array.isArray(presented.payload.actionRects) && presented.payload.actionRects.length > 0);
	const saved = commands.filter((c) => c.type === "permission.default").at(-1)!;
	assert.equal(saved.payload.profile, "full-access");
	assert.equal(saved.payload.expectedConfigRevision, 7);
	assert.equal(saved.payload.fullAccessConfirmationId, "fa-1");
	assert.equal((saved.payload.evidence as { phase: string }).phase, "PRESS");
});


test("FullAccess second step uses the overridden select keys and ignores the replaced Down", async () => {
	const { page, commands } = setup(new KeymapAdapter({ "tui.select.down": ["ctrl+n"] }));
	const value = page("runtime");
	value.handleInput("\x0e"); // ctrl+n -> auto-review
	value.handleInput("\x0e"); // ctrl+n -> full-access
	value.handleInput("\r");    // select.confirm (default enter)
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.ok(commands.some((c) => c.type === "permission.prepareFullAccess"));
	value.render(80);
	value.handleInput("\x0e"); // ctrl+n toggles to Confirm
	assert.match(value.render(80).join("\n"), /▸ \[ Confirm Full Access \]/);
	value.handleInput("\x1b[B"); // replaced Down must not toggle
	assert.match(value.render(80).join("\n"), /▸ \[ Confirm Full Access \]/);
});

test("a delayed FullAccess presented ACK cannot authorize after a terminal epoch change", async () => {
	const { page, commands } = setup();
	const value = page("runtime");
	const context = (value as unknown as { context: PageContext }).context;
	let frame = context.lastFrame()!;
	context.lastFrame = () => frame;
	const original = context.client()!;
	let acknowledge!: (result: CommandResult) => void;
	context.client = () => ({ command: (type: string, payload: Record<string, unknown>, identity: unknown) => {
		if (type === "permission.presented") return new Promise((resolve) => { acknowledge = resolve; });
		return original.command(type, payload, identity as never);
	} }) as BackendClient;
	value.handleInput("\x1b[B"); value.handleInput("\x1b[B"); value.handleInput("\r");
	await new Promise((resolve) => setTimeout(resolve, 0));
	value.render(80);
	value.handleInput("\x1b[B"); value.handleInput("\r");
	frame = { ...frame, epoch: frame.epoch + 1 };
	acknowledge({ ok: true, type: "command.result", payload: {} });
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(commands.some((command) => command.type === "permission.select"), false);
});
