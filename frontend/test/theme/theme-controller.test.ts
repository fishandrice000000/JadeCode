import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { ThemeRepository } from "../../src/theme/theme-repository.ts";
import { ThemeController } from "../../src/theme/theme-controller.ts";

const builtin = new URL("../../../assets/themes/", import.meta.url).pathname;

function controller(): { value: ThemeController; errors: string[]; saved: Array<Record<string, unknown>>; directory: string } {
	const custom = mkdtempSync(join(tmpdir(), "jade-theme-"));
	writeFileSync(join(custom, "custom.json"), JSON.stringify({ name: "custom", colors: { accent: "#00ff00", text: "#ffffff", background: "#000000" } }));
	const errors: string[] = [];
	const saved: Array<Record<string, unknown>> = [];
	const value = new ThemeController({
		repository: new ThemeRepository(builtin, custom),
		settings: () => ({ theme: "system", lightTheme: "light", darkTheme: "dark" }),
		save: (patch) => { saved.push(patch); },
		onError: (message) => { errors.push(message); },
		onChange: () => {},
	});
	return { value, errors, saved, directory: custom };
}

test("custom themes preview/cancel/commit without writing through the controller", (t) => {
	const { value, saved, directory } = controller();
	t.after(() => { value.close(); rmSync(directory, { recursive: true, force: true }); });
	assert.ok(value.names().includes("custom"));
	value.preview("custom");
	assert.match(value.snapshot().name, /custom/);
	value.cancel();
	assert.equal(value.selectedName(), "system");
	assert.deepEqual(saved, []);
	value.preview("custom");
	value.commit();
	assert.deepEqual(saved, [{ theme: "custom" }]);
});

test("a bad theme file keeps the last valid snapshot and reports the real error", (t) => {
	const { value, errors, directory } = controller();
	t.after(() => { value.close(); rmSync(directory, { recursive: true, force: true }); });
	value.preview("custom");
	const before = value.snapshot();
	const repository = (value as unknown as { options: { repository: ThemeRepository } }).options.repository;
	const customDir = (repository as unknown as { customDirectory: string }).customDirectory;
	writeFileSync(join(customDir, "custom.json"), "{ not json");
	value.reload();
	assert.equal(value.snapshot(), before, "bad content must not replace the last valid colors");
	assert.ok(errors.length > 0);
	assert.ok(value.lastFailure().length > 0);
});
