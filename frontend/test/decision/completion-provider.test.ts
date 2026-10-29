import assert from "node:assert/strict";
import { test } from "node:test";
import { JadeCompletionProvider } from "../../src/editor/completion-provider.ts";
import { parseCommand } from "../../src/editor/commands.ts";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Editor, type TUI } from "@earendil-works/pi-tui";

function provider(): JadeCompletionProvider {
	return new JadeCompletionProvider({
		workspace: "/tmp",
		commandSuggestions: () => true,
		sessions: async () => [],
		thinking: () => ["low", "high"],
		permissions: () => ["workspace", "full-access"],
	});
}

const options = () => ({ signal: new AbortController().signal });

test("real Editor repeatedly completes relative, absolute and @ directory levels without fd", async () => {
	const root = mkdtempSync(join(tmpdir(), "jade-completion-"));
	try {
		mkdirSync(join(root, "alpha/beta"), { recursive: true });
		writeFileSync(join(root, "alpha/beta/note.txt"), "fixture");
		const value = new JadeCompletionProvider({ workspace: root, commandSuggestions: () => true,
			sessions: async () => [], thinking: () => [], permissions: () => [] });
		const theme = { borderColor: (v: string) => v, selectList: { selectedPrefix: (v: string) => v,
			selectedText: (v: string) => v, description: (v: string) => v, scrollInfo: (v: string) => v, noMatch: (v: string) => v } };
		for (const prefix of ["./a", `${root}/a`, "@./a"]) {
			const editor = new Editor({ requestRender: () => {}, terminal: { rows: 30, columns: 100 } } as unknown as TUI, theme);
			editor.setAutocompleteProvider(value); editor.setText(prefix);
			for (const suffix of ["alpha/", "alpha/beta/", "alpha/beta/note.txt"]) {
				editor.handleInput("\t");
				await new Promise((resolve) => setTimeout(resolve, 10));
				assert.ok(editor.getText().trimEnd().endsWith(suffix), `${prefix} expected ${suffix}, got ${editor.getText()}`);
			}
		}
	} finally { rmSync(root, { recursive: true, force: true }); }
});

test("slash suggestions apply to a single slash and parse as a real command", async () => {
	const value = provider();
	const lines = ["/sett"];
	const suggestions = await value.getSuggestions(lines, 0, lines[0]!.length, options());
	assert.ok(suggestions, "expected slash suggestions");
	const item = suggestions!.items.find((candidate) => candidate.value === "settings");
	assert.ok(item, `expected settings candidate, got ${JSON.stringify(suggestions!.items)}`);
	const applied = value.applyCompletion(lines, 0, lines[0]!.length, item!, suggestions!.prefix);
	const line = applied.lines[0]!;
	assert.equal(line.startsWith("//"), false, `applyCompletion produced ${line}`);
	assert.equal(parseCommand(line)?.command.name, "/settings");
});

test("argument completions are reached for /permission and /thinking", async () => {
	const value = provider();
	const permission = ["/permission "];
	const permissionSuggestions = await value.getSuggestions(permission, 0, permission[0]!.length, options());
	assert.ok(permissionSuggestions, "permission argument candidates must be requested");
	assert.deepEqual(permissionSuggestions!.items.map((item) => item.value), ["workspace", "full-access"]);
	const thinking = ["/thinking "];
	const thinkingSuggestions = await value.getSuggestions(thinking, 0, thinking[0]!.length, options());
	assert.deepEqual(thinkingSuggestions!.items.map((item) => item.value), ["low", "high"]);
});

// 远程模式从后端取得候选，禁止到客户端同名工作区枚举文件。
test("service path completion uses the execution-machine callback", async () => {
    const requested: string[] = [];
    const value = new JadeCompletionProvider({ workspace: "/missing-on-client", commandSuggestions: () => true,
        sessions: async () => [], thinking: () => [], permissions: () => [],
        paths: async (prefix) => { requested.push(prefix); return { prefix, items: [{ value: "@src/", label: "src" }] }; } });
    const result = await value.getSuggestions(["read @s"], 0, 7, options());
    assert.deepEqual(requested, ["@s"]); assert.equal(result?.items[0]?.value, "@src/");
});
