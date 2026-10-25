import assert from "node:assert/strict";
import { test } from "node:test";
import { Application } from "../../src/app/Application.ts";
import { OSC52_SENT_MESSAGE } from "../../src/services/clipboard-controller.ts";

function harness(result: { status: string; error?: string }): {
	app: Application; notices: Array<[string, boolean]>;
} {
	const app = new Application();
	const notices: Array<[string, boolean]> = [];
	const fields = app as unknown as { [key: string]: unknown };
	fields["clipboard"] = { copy: async () => result };
	fields["flash"] = (message: string, error = false) => { notices.push([message, error]); };
	return { app, notices };
}

async function copy(app: Application): Promise<void> {
	await (app as unknown as { copy(value: string): Promise<void> }).copy("assistant reply");
}

test("copy feedback distinguishes desktop/OSC success from a failed copy", async () => {
	const desktop = harness({ status: "DESKTOP" });
	await copy(desktop.app);
	assert.deepEqual(desktop.notices, [["Copied!", false]]);
	const osc = harness({ status: "OSC52_SENT" });
	await copy(osc.app);
	assert.deepEqual(osc.notices, [[OSC52_SENT_MESSAGE, true]], "sending OSC52 must not claim the client clipboard changed");
	const failed = harness({ status: "FAILED", error: "clipboard unavailable" });
	await copy(failed.app);
	assert.deepEqual(failed.notices, [["clipboard unavailable", true]]);
	const noReason = harness({ status: "FAILED" });
	await copy(noReason.app);
	assert.deepEqual(noReason.notices, [["Copy failed", true]]);
});
