import assert from "node:assert/strict";
import { test } from "node:test";
import { Application } from "../../src/app/Application.ts";
import { KeymapAdapter } from "../../src/editor/keymap-adapter.ts";

/**
 * 直接驱动生产 Application.onGlobalInput 的全局路由。
 *
 * 这是白盒回归：只替换编辑器/宿主/客户端的最小替身，验证真实调用方确实使用了
 * isGlobalActionPress；避免“helper 测试通过但 Application 漏接”的盲区。
 */
interface Harness {
	app: Application;
	state: { draft: string; renders: number; quits: number; focus: number };
}

function harness(): Harness {
	const state = { draft: "draft", renders: 0, quits: 0, focus: 0 };
	const app = new Application();
	const fields = app as unknown as { [key: string]: unknown };
	fields["editor"] = {
		draft: () => state.draft,
		setDraft: (value: string) => { state.draft = value; },
		focus: () => { state.focus += 1; },
	};
	fields["host"] = {
		hasSelection: () => false,
		requestRender: () => { state.renders += 1; },
		tui: () => ({}),
		mode: () => "fullscreen",
	};
	fields["keymap"] = new KeymapAdapter({});
	fields["client"] = {
		command: async (type: string) => {
			if (type === "client.shutdown") state.quits += 1;
			return { ok: true, type: "command.result", payload: {} };
		},
	};
	return { app, state };
}

function route(app: Application, data: string): unknown {
	return (app as unknown as { onGlobalInput(input: string): unknown }).onGlobalInput(data);
}

test("a new Ctrl+C PRESS clears the draft without quitting", () => {
	const { app, state } = harness();
	route(app, "\x1b[99;5u");
	assert.equal(state.draft, "");
	assert.equal(state.quits, 0);
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

test("a later fresh Ctrl+C PRESS still follows the normal clear-then-exit behavior", async () => {
	const { app, state } = harness();
	route(app, "\x1b[99;5u"); // 清空草稿
	route(app, "\x1b[99;5u"); // 空草稿再次 PRESS：进入退出
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.equal(state.quits, 1);
});
