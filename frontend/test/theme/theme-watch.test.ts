import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ThemeController } from "../../src/theme/theme-controller.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";

const document = parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8"));

test("a late watch callback from the previous selection cannot replace the current theme", () => {
	const callbacks: Array<(result: unknown) => void> = [];
	const repository = {
		list: () => [{ name: "jadecode", path: "builtin", builtin: true }],
		load: () => ({ name: "jadecode", document, source: "", digest: "d" }),
		watch: (_name: string, callback: (result: unknown) => void) => { callbacks.push(callback); return () => {}; },
	};
	let changes = 0;
	const errors: string[] = [];
	const value = new ThemeController({
		repository: repository as never,
		settings: () => ({ theme: "system", lightTheme: "light", darkTheme: "dark" }),
		save: () => {}, onError: (message) => { errors.push(message); }, onChange: () => { changes += 1; },
	});
	const stale = callbacks[0]!;
	value.preview("dark"); // installs a newer watch ticket
	const before = changes;
	stale(new Error("late failure"));
	assert.equal(errors.length, 0, "a stale watch error must be ignored");
	assert.equal(changes, before, "a stale watch result must not rebuild the UI");
	value.close();
});
