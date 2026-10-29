import assert from "node:assert/strict";
import { test } from "node:test";
import { ACTION_COUNT, HIDDEN_COUNT, SUPPORTED_COUNT } from "../../src/editor/keymap-adapter.ts";
import { COMMANDS } from "../../src/editor/commands.ts";

test("the approved 97-action catalogue keeps 62 supported / 35 hidden", () => {
	assert.equal(ACTION_COUNT, 97);
	assert.equal(SUPPORTED_COUNT, 62);
	assert.equal(HIDDEN_COUNT, 35);
});

test("the session command catalogue includes the goal lifecycle command", () => {
	assert.deepEqual(COMMANDS.map((command) => command.name), [
		"/settings", "/name", "/team", "/session", "/hotkeys", "/copy", "/new",
		"/resume", "/stop", "/close", "/quit", "/model", "/thinking", "/permission", "/mcp", "/goal",
	]);
});
