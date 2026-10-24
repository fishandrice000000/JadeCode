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
	let patchCalls = 0;
	fields["client"] = {
		command: async (type: string, _payload: unknown, _identity: unknown,
			hooks?: { onCommandError?: (message: string) => void }) => {
			if (type === "settings.patch" && failCommand && ++patchCalls === 1) {
				hooks?.onCommandError?.("rejected");
				return { ok: false, type: "command.error", payload: { message: "rejected" } };
			}
			return { ok: true, type: "command.result", payload: {} };
		},
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


test("a synchronously rejected non-theme command is removed before a merged settings.failed", async () => {
	const { app, failed } = harness(true);
	invoke(app, { thinkingVisible: false });
	invoke(app, { theme: "b" });
	await new Promise((resolve) => setTimeout(resolve, 0));
	event(app, "settings.failed", { message: "B_WRITE_FAILED" });
	assert.deepEqual(failed, [["b", "B_WRITE_FAILED"]]);
});
