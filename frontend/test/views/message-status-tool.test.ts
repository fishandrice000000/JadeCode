import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { MessageCard } from "../../src/views/message-views.ts";
import { ToolCard } from "../../src/views/tool-views.ts";
import type { Entry } from "../../src/state/transcript-store.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});
const factory = { create: () => {
	let body = "";
	const setText = (value: string): void => { body = value; };
	return { markdown: { render: () => [body], setText, invalidate: () => {} }, setText, invalidate: () => {} };
} };

function reply(status: string, elapsedMs: number | null): Entry {
	return { id: "r", kind: "REPLY", actor: "leader", title: "Reply", body: "body", arguments: "", status,
		requestId: null, callId: null, blockIndex: null, elapsedMs, historical: status === "HISTORICAL",
		tool: null, revision: 1, expanded: false };
}

test("reply cards show real status and elapsed time, never a fake 0.0s", () => {
	const done = new MessageCard(reply("DONE", 1234), theme, factory as never, "  ", true, false, (a) => a).render(80).join("\n");
	assert.match(done, /Reply · Done · 1\.2s/);
	const historical = new MessageCard(reply("HISTORICAL", null), theme, factory as never, "  ", true, false, (a) => a).render(80).join("\n");
	assert.match(historical, /Historical/);
	assert.doesNotMatch(historical, /0\.0s/);
});

function tool(name: string, body: string, expanded: boolean, outputPad = 1): Entry {
	return { id: "t", kind: "TOOL", actor: "leader", title: name, body, arguments: "{}", status: "RETURNED",
		requestId: null, callId: null, blockIndex: null, elapsedMs: null, historical: false,
		tool: { kind: "BUILTIN", serverName: "", originalName: name, hostReadOnly: false }, revision: 1, expanded };
}

test("fallback tools show ten lines collapsed and all lines expanded; outputPad adds one margin", () => {
	const body = Array.from({ length: 14 }, (_, index) => `l-${index}`).join("\n");
	const collapsed = new ToolCard(tool("custom_tool", body, false), theme, 1, false).render(80).join("\n");
	assert.match(collapsed, /l-9/);
	assert.doesNotMatch(collapsed, /l-10/);
	assert.match(collapsed, /more lines/);
	const expanded = new ToolCard(tool("custom_tool", body, true), theme, 1, false).render(80).join("\n");
	assert.match(expanded, /l-13/);
	const padded = new ToolCard(tool("custom_tool", "one", false), theme, 1, false).render(80);
	assert.ok(padded.every((line) => line.startsWith(" ")), "outputPad must add a left margin");
});
