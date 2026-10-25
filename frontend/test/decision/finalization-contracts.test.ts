import assert from "node:assert/strict";
import { test } from "node:test";
import { Application } from "../../src/app/Application.ts";
import { readFileSync } from "node:fs";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

test("large prompt chunks preserve UTF-8 bytes when emoji straddles the chunk boundary", () => {
	const app = new Application();
	const chunks: string[] = [];
	const client = { send: (type: string, payload: { text?: string }) => {
		if (type === "payload.chunk") chunks.push(payload.text!);
	}, command: async () => ({ ok: true }) };
	const value = "a".repeat(8191) + "😀" + "中".repeat(33000);
	(app as unknown as { sendSubmit(c: unknown, id: string, text: string): void }).sendSubmit(client, "s", value);
	assert.ok(chunks.length > 1);
	assert.deepEqual(Buffer.concat(chunks.map((chunk) => Buffer.from(chunk))), Buffer.from(value));
});

test("Application theme changes invalidate cached cards and update editor and approval consumers", () => {
	const app = new Application();
	const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "light", { mode: "truecolor", noColor: false });
	const calls: unknown[] = [];
	const fields = app as unknown as Record<string, unknown>;
	fields.theme = { snapshot: () => theme };
	fields.conversation = { invalidate: () => calls.push("cards") };
	fields.editor = { setTheme: (value: unknown) => calls.push(["editor", value]) };
	fields.approval = { setTheme: (value: unknown) => calls.push(["approval", value]) };
	fields.onTerminalInvalidate = (reason: string) => calls.push(reason);
	fields.host = { requestRender: (force: boolean) => calls.push(force) };
	(app as unknown as { onThemeChanged(): void }).onThemeChanged();
	assert.deepEqual(calls, ["cards", ["editor", theme], ["approval", theme], "theme", true]);
});

test("production terminal color wiring delivers OSC colors and rejects replies from a replaced screen", async () => {
	const app = new Application();
	const fields = app as unknown as Record<string, unknown>;
	const replies: Array<(colors: unknown) => void> = [];
	const colors: unknown[] = [];
	fields.theme = { setScheme: () => {}, setTerminalColors: (fg: unknown, bg: unknown) => colors.push([fg, bg]) };
	const screen = { onTerminalColorSchemeChange: () => () => {}, setTerminalColorSchemeNotifications: () => {},
		queryTerminalColors: (options: { onLateReply: (colors: unknown) => void }) => { replies.push(options.onLateReply); return new Promise(() => {}); } };
	fields.host = { tui: () => screen };
	const install = () => (app as unknown as { installTerminalColors(): void }).installTerminalColors();
	install();
	replies[0]!({ foreground: { r: 18, g: 52, b: 86 } });
	assert.deepEqual(colors, [[{ kind: "rgb", r: 18, g: 52, b: 86 }, undefined]]);
	install();
	replies[0]!({ foreground: { r: 1, g: 1, b: 1 } });
	assert.equal(colors.length, 1, "an old query cannot overwrite colors after screen reconstruction");
});
