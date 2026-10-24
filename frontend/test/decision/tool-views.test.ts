import { TranscriptStore } from "../../src/state/transcript-store.ts";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ToolCard } from "../../src/views/tool-views.ts";
import type { Entry } from "../../src/state/transcript-store.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

function entry(name: string, body: string): Entry {
	return {
		id: `tool-${name}`, kind: "TOOL", actor: "leader", title: name, body, arguments: "{\"path\":\"x\"}",
		status: "RETURNED", requestId: null, callId: null, blockIndex: null, elapsedMs: null,
		historical: false, tool: { kind: "BUILTIN", serverName: "", originalName: name, hostReadOnly: false },
		revision: 0, expanded: false,
	};
}

test("bash shows its tail; collapsed read_file hides body and expansion restores it", () => {
	const body = Array.from({ length: 12 }, (_, index) => `line-${index}`).join("\n");
	const bash = new ToolCard(entry("bash", body), theme, 1, false).render(80).join("\n");
	assert.match(bash, /line-11/);
	assert.doesNotMatch(bash, /line-0\b/);
	assert.match(bash, /more lines/);
	const read = new ToolCard(entry("read_file", body), theme, 1, false).render(80).join("\n");
	// 批准的 N07/F046 要求 read 收起无正文；旧断言把错误行为写成了契约。
	assert.doesNotMatch(read, /line-0/);
	const expanded = new ToolCard({ ...entry("read_file", body), expanded: true }, theme, 1, false).render(80).join("\n");
	assert.match(expanded, /line-0/);
	assert.match(expanded, /line-11/);
});

test("write_file shows the first ten lines when collapsed", () => {
	const body = Array.from({ length: 14 }, (_, index) => `w-${index}`).join("\n");
	const text = new ToolCard(entry("write_file", body), theme, 1, false).render(80).join("\n");
	assert.match(text, /w-9/);
	assert.doesNotMatch(text, /w-10/);
	assert.match(text, /more lines/);
});

test("preview → execution → result preserves one card and its original arguments", () => {
	const store = new TranscriptStore();
	const args = JSON.stringify({ path: "result.txt", content: "requested" });
	const preview = store.previewStarted("request", "leader", 0, "call", "write_file", args);
	const identity = { kind: "BUILTIN", originalName: "write_file", serverName: "", hostReadOnly: false };
	store.toolNotice("leader", "request/call", "write_file", args, "RUNNING", 0, identity);
	const result = store.toolNotice("leader", "request/call", "write_file", "Wrote result.txt", "RETURNED", 25, identity);
	assert.equal(result.id, preview.id);
	assert.equal(result.arguments, args);
	assert.equal(result.body, "Wrote result.txt");
	assert.equal(store.entries().length, 1);
});



test("MCP names never select builtin collapse rules; edit/write previews remain requested input", () => {
	const mcp = { ...entry("read_file", "MCP_RESULT"), tool: { kind: "MCP", serverName: "fixture", originalName: "read_file", hostReadOnly: false } };
	assert.match(new ToolCard(mcp, theme, 1, false).render(80).join("\n"), /MCP_RESULT/);
	const edit = { ...entry("edit_file", ""), status: "QUEUED", arguments: JSON.stringify({ old_text: "old", new_text: "new" }) };
	const output = new ToolCard(edit, theme, 1, false).render(80).join("\n");
	assert.match(output, /requested fragments/); assert.match(output, /- old/); assert.match(output, /\+ new/);
	assert.match(output, /Queued/); assert.doesNotMatch(output, /Done|Returned/);
});
