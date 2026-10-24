import assert from "node:assert/strict";
import { test } from "node:test";
import { JadeCompletionProvider } from "../../src/editor/completion-provider.ts";
import { parseCommand } from "../../src/editor/commands.ts";

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
