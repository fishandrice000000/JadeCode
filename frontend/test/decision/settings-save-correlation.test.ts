import assert from "node:assert/strict";
import { test } from "node:test";
import { Application } from "../../src/app/Application.ts";

function harness(failCommand = false): {
	app: Application; failed: Array<[string, string]>; succeeded: string[];
} {
	const app = new Application();
	const fields = app as unknown as { [key: string]: unknown };
	const failed: Array<[string, string]> = [];
	const succeeded: string[] = [];
	fields["theme"] = {
		commitFailedFor: (name: string, message: string) => { failed.push([name, message]); },
		commitSucceededFor: (name: string) => { succeeded.push(name); },
		settingsChanged: () => {},
	};
	fields["client"] = {
		command: async (type: string) => type === "settings.patch" && failCommand
			? { ok: false, type: "command.error", payload: { message: "rejected" } }
			: { ok: true, type: "command.result", payload: {} },
	};
	fields["host"] = {
		setHardwareCursor: () => {}, setClearOnShrink: () => {}, setWheelScrollLines: () => {},
		setScrollbar: () => {}, setCopyOnSelect: () => {}, setMode: () => {},
		setTerminalProgress: () => {}, requestRender: () => {},
	};
	fields["editor"] = { applySettings: () => {} };
	return { app, failed, succeeded };
}

function invoke(app: Application, patch: Record<string, unknown>): void {
	(app as unknown as { saveSettings(patch: Record<string, unknown>): void }).saveSettings(patch);
}
function event(app: Application, type: string, payload: Record<string, unknown>): void {
	(app as unknown as { handleEvent(message: unknown): void }).handleEvent({
		version: 1, connectionId: "c", seq: "1", type, commandId: null, sessionId: null, generation: null, payload,
	});
}

test("a non-theme settings reply cannot consume or fail a pending theme commit", async () => {
	const { app, failed, succeeded } = harness();
	invoke(app, { thinkingVisible: false });
	invoke(app, { theme: "b" });
	await new Promise((resolve) => setTimeout(resolve, 0));
	event(app, "settings.saved", { configRevision: "1", tui: {} });
	assert.deepEqual(succeeded, [], "the non-theme save must not mark the theme saved");
	event(app, "settings.failed", { message: "The settings changed" });
	assert.deepEqual(failed, [["b", "The settings changed"]]);
});

test("a command-level non-theme failure does not fail the pending theme", async () => {
	const { app, failed } = harness(true);
	invoke(app, { thinkingVisible: false });
	await new Promise((resolve) => setTimeout(resolve, 0));
	assert.deepEqual(failed, []);
});
