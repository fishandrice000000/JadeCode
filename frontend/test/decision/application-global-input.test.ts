import assert from "node:assert/strict";
import { test } from "node:test";
import { Application } from "../../src/app/Application.ts";
import { KeymapAdapter } from "../../src/editor/keymap-adapter.ts";
import { SessionStore } from "../../src/state/session-store.ts";
import { Editor, KeybindingsManager, TUI_KEYBINDINGS, setKeybindings, type TUI } from "@earendil-works/pi-tui";
import { JadeCompletionProvider } from "../../src/editor/completion-provider.ts";

/**
 * 直接驱动生产 Application.onGlobalInput 的全局路由。
 *
 * 这是白盒回归：只替换编辑器/宿主/客户端的最小替身，验证真实调用方确实使用了
 * isGlobalActionPress；避免“helper 测试通过但 Application 漏接”的盲区。
 */
interface Harness {
	app: Application;
	state: { draft: string; renders: number; quits: number; focus: number; suspends: number; resumes: number;
		commands: Array<{ type: string; identity: unknown }> };
}

function harness(): Harness {
	const state: Harness["state"] = { draft: "draft", renders: 0, quits: 0, focus: 0, suspends: 0, resumes: 0, commands: [] };
	const app = new Application();
	const fields = app as unknown as { [key: string]: unknown };
	fields["editor"] = {
		editor: { isShowingAutocomplete: () => false },
		draft: () => state.draft,
		setDraft: (value: string) => { state.draft = value; },
		focus: () => { state.focus += 1; },
	};
	fields["host"] = {
		hasSelection: () => false,
		requestRender: () => { state.renders += 1; },
		tui: () => ({}),
		mode: () => "fullscreen",
		terminal: { flushOutput: async () => {} },
		suspendTerminal: () => { state.suspends += 1; },
		resumeTerminal: () => { state.resumes += 1; },
	};
	fields["theme"] = { reload: () => {} };
	fields["keymap"] = new KeymapAdapter({});
	fields["client"] = {
		command: async (type: string, _payload: unknown, identity: unknown) => {
			state.commands.push({ type, identity });
			if (type === "client.shutdown") state.quits += 1;
			return { ok: true, type: "command.result", payload: {} };
		},
	};
	return { app, state };
}

/** 用后端快照建立忙闲与身份，避免用流式文字或前端动画推测任务状态。 */
function leaderState(app: Application, state: string): void {
	const session = (app as unknown as { session: SessionStore }).session;
	session.applySnapshot({ snapshot: { sessionId: "session-fixture", generation: "7", actors: [{ id: "leader", state }] } });
}

function route(app: Application, data: string): unknown {
	return (app as unknown as { onGlobalInput(input: string): unknown }).onGlobalInput(data);
}

test("a fresh Esc stops running or waiting turns without clearing the draft or closing the session", () => {
	for (const status of ["RUNNING", "WAITING_BACKGROUND", "WAITING_TEAM"]) {
		const { app, state } = harness();
		leaderState(app, status);
		assert.deepEqual(route(app, "\x1b"), { consume: true });
		assert.deepEqual(state.commands, [{ type: "session.stop", identity: { sessionId: "session-fixture", generation: "7" } }]);
		assert.equal(state.draft, "draft");
		assert.equal(state.quits, 0);
		assert.equal((app as unknown as { session: SessionStore }).session.actors()[0]?.state, status, "the stop acknowledgement must not invent IDLE");
	}
});

test("idle Esc and repeat/release events never send stop requests", () => {
	const { app, state } = harness();
	leaderState(app, "IDLE");
	route(app, "\x1b");
	leaderState(app, "RUNNING");
	for (const key of ["\x1b[27;1:2u", "\x1b[27;1:3u"]) route(app, key);
	assert.deepEqual(state.commands, []);
	assert.equal(state.draft, "draft");
});

test("an open page or pending approval consumes Esc before global cancellation", () => {
	const { app, state } = harness();
	leaderState(app, "RUNNING");
	const fields = app as unknown as Record<string, unknown>;
	let pageOpen = true;
	fields["pages"] = { current: () => pageOpen ? {} : undefined, handleInput: () => { pageOpen = false; return true; } };
	route(app, "\x1b");
	assert.equal(pageOpen, false);
	fields["approval"] = { hasPending: () => true };
	route(app, "\x1b");
	assert.deepEqual(state.commands, [], "approval keys belong to the existing priority listener, not session.stop");
});

test("Esc closes real Editor completion first and only a later press stops the turn", async () => {
	const { app, state } = harness();
	leaderState(app, "RUNNING");
	setKeybindings(new KeybindingsManager(TUI_KEYBINDINGS));
	const plain = (value: string) => value;
	const editor = new Editor({ requestRender: () => {}, terminal: { rows: 24, columns: 80 } } as unknown as TUI,
		{ borderColor: plain, selectList: { selectedPrefix: plain, selectedText: plain, description: plain, scrollInfo: plain, noMatch: plain } });
	editor.setAutocompleteProvider(new JadeCompletionProvider({ workspace: "/tmp", commandSuggestions: () => true,
		sessions: async () => [], thinking: () => [], permissions: () => [] }));
	(app as unknown as { editor: { editor: Editor } }).editor.editor = editor;
	editor.handleInput("/");
	// 等实际异步候选完成；有界轮询比假造 autocompleteState 更能检查应用与 Pi 的接线。
	for (let attempt = 0; attempt < 50 && !editor.isShowingAutocomplete(); attempt++)
		await new Promise((resolve) => setTimeout(resolve, 10));
	assert.equal(editor.isShowingAutocomplete(), true);
	assert.equal(route(app, "\x1b"), undefined);
	editor.handleInput("\x1b");
	assert.equal(editor.isShowingAutocomplete(), false);
	assert.equal(editor.getText(), "/");
	assert.equal(state.commands.length, 0);
	route(app, "\x1b");
	assert.equal(state.commands[0]?.type, "session.stop");
});

test("the interrupt override replaces Esc while slash stop uses the same service command", () => {
	const { app, state } = harness();
	leaderState(app, "RUNNING");
	(app as unknown as { keymap: KeymapAdapter }).keymap = new KeymapAdapter({ "app.interrupt": ["ctrl+w"] });
	assert.equal(route(app, "\x1b"), undefined);
	route(app, "\x17");
	(app as unknown as { runSlash(name: string, argument: string, raw: string): boolean }).runSlash("/stop", "", "/stop");
	assert.deepEqual(state.commands.map((entry) => entry.type), ["session.stop", "session.stop"]);
});

test("a new Ctrl+C PRESS clears the draft without quitting", () => {
	const { app, state } = harness();
	route(app, "\x1b[99;5u");
	assert.equal(state.draft, "");
	assert.equal(state.quits, 0);
	assert.equal(state.renders, 1, "clearing a consumed Ctrl+C must schedule a frame immediately");
});

test("page press/release pairs dispatch a printable character and navigation only once", () => {
	const { app } = harness();
	const inputs: string[] = [];
	(app as unknown as Record<string, unknown>)["pages"] = { current: () => ({}), handleInput: (data: string) => { inputs.push(data); return true; } };
	for (const data of ["\x1b[1;1B", "\x1b[1;1:3B", "\x1b[97u", "\x1b[97;1:3u"]) route(app, data);
	assert.deepEqual(inputs, ["\x1b[1;1B", "\x1b[97u"]);
});

test("Ctrl+C repeat and release never reach the global quit branch", () => {
	for (const data of ["\x1b[99;5:2u", "\x1b[99;5:3u"]) {
		const { app, state } = harness();
		const result = route(app, data);
		assert.equal(result, undefined, "repeat/release must be left to the editor");
		assert.equal(state.draft, "draft", "the editor draft must be untouched");
		assert.equal(state.quits, 0, "repeat/release must not quit");
	}
	// 空草稿时 repeat 也不能触发退出。
	const { app, state } = harness();
	state.draft = "";
	route(app, "\x1b[99;5:2u");
	assert.equal(state.quits, 0);
});

test("repeated submit stays blocked while repeated editor navigation remains available", () => {
	const { app, state } = harness();
	assert.deepEqual(route(app, "\x1b[13;1:2u"), { consume: true });
	assert.equal(route(app, "\x1b[1;1:2A"), undefined);
	assert.equal(state.draft, "draft");
});

test("a later fresh Ctrl+C PRESS still follows the normal clear-then-exit behavior", async () => {
	const { app, state } = harness();
	route(app, "\x1b[99;5u"); // 清空草稿
	route(app, "\x1b[99;5u"); // 空草稿再次 PRESS：进入退出
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(state.quits, 1);
});

test("an expired Ctrl+C window cannot turn a later isolated press into an exit", async () => {
	const { app, state } = harness();
	state.draft = "";
	(app as unknown as { lastClearAt: number }).lastClearAt = Date.now() - 1000;
	route(app, "\x1b[99;5u");
	assert.equal(state.quits, 0);
	route(app, "\x1b[99;5u");
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(state.quits, 1);
});


test("Ctrl+Z routes through the suspend helper and resumes once on SIGCONT", async () => {
	const { app, state } = harness();
	const originalKill = process.kill;
	(process as unknown as { kill: typeof process.kill }).kill = (() => true) as typeof process.kill;
	try {
		assert.deepEqual(route(app, "\x1a"), { consume: true });
		await new Promise((resolve) => setTimeout(resolve, 0));
		assert.equal(state.suspends, 1, "the host must pause rendering before SIGTSTP");
		process.emit("SIGCONT");
		await new Promise((resolve) => setTimeout(resolve, 0));
		assert.equal(state.resumes, 1, "SIGCONT resumes the host exactly once");
	} finally {
		(process as unknown as { kill: typeof process.kill }).kill = originalKill;
	}
});
