import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { MessageCard } from "../../src/views/message-views.ts";
import { ToolCard } from "../../src/views/tool-views.ts";
import type { Entry } from "../../src/state/transcript-store.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";
import { stripTerminalSequences, visibleWidth } from "@earendil-works/pi-tui";

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

test("card titles separate status, time and errors without changing safe text or width", () => {
	const document = parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8"));
	for (const variant of ["dark", "light"]) {
		for (const noColor of [false, true]) {
			const palette = resolveTheme(document, variant, { mode: "truecolor", noColor });
			for (const [status, role, label] of [["RUNNING", "accent", "Running"], ["RETURNED", "success", "Returned"],
				["BLOCKED", "warning", "Blocked"], ["ERROR", "error", "Error"], ["HISTORICAL", "dim", "Historical"]] as const) {
				const entry = { ...tool("Workflow", "正文", false), status, elapsedMs: 13400 };
				const card = new ToolCard(entry, palette, 1, false);
				const title = card.render(100)[0]!;
				assert.ok(title.includes(palette.style(role, label)));
				assert.ok(stripTerminalSequences(title).includes(`Workflow · ${label} · 13.4s`));
				for (const width of [1, 8, 24, 80]) {
					for (const line of card.render(width)) assert.ok(visibleWidth(line) <= width);
				}
				if (noColor) assert.doesNotMatch(card.render(80).join("\n"), /\x1b/);
			}
			const error = { ...reply("ERROR", null), kind: "NOTICE" as const, title: "Error\x1b[32m", body: "保存失败\x1b[32m" };
			const lines = new MessageCard(error, palette, factory as never, "  ", true, false, (a) => a).render(80);
			assert.ok(lines[1]!.includes(palette.style("error", "保存失败")));
			assert.equal(lines.map(stripTerminalSequences).join("\n"), "◆ Error · Error\n  保存失败");
		}
	}
});
