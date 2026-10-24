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

test("bash collapses to the last five lines while read_file keeps the full body", () => {
	const body = Array.from({ length: 12 }, (_, index) => `line-${index}`).join("\n");
	const bash = new ToolCard(entry("bash", body), theme, 1, false).render(80).join("\n");
	assert.match(bash, /line-11/);
	assert.doesNotMatch(bash, /line-0\b/);
	assert.match(bash, /more lines/);
	const read = new ToolCard(entry("read_file", body), theme, 1, false).render(80).join("\n");
	assert.match(read, /line-0/);
	assert.match(read, /line-11/);
});

test("write_file shows the first ten lines when collapsed", () => {
	const body = Array.from({ length: 14 }, (_, index) => `w-${index}`).join("\n");
	const text = new ToolCard(entry("write_file", body), theme, 1, false).render(80).join("\n");
	assert.match(text, /w-9/);
	assert.doesNotMatch(text, /w-10/);
	assert.match(text, /more lines/);
});
