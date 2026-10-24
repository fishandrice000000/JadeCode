import assert from "node:assert/strict";
import { test } from "node:test";
import { ThemeController } from "../../src/theme/theme-controller.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";

function document(name: string, accent: string) {
	return parseThemeDocument(JSON.stringify({ name, colors: { accent, text: "#ffffff", background: "#000000" } }));
}

test("the current subscription survives repeated updates, and switching themes moves it", () => {
	const callbacks = new Map<string, (result: unknown) => void>();
	let cancels = 0;
	const docs = { a: document("a", "#ff0000"), b: document("b", "#0000ff") };
	const repository = {
		list: () => [{ name: "a", path: "a", builtin: false }, { name: "b", path: "b", builtin: false }],
		load: (name: string) => ({ name, document: docs[name as keyof typeof docs], source: "", digest: name }),
		watch: (name: string, callback: (result: unknown) => void) => {
			callbacks.set(name, callback);
			return () => { cancels += 1; callbacks.delete(name); };
		},
	};
	let changes = 0;
	const errors: string[] = [];
	const value = new ThemeController({
		repository: repository as never,
		settings: () => ({ theme: "a", lightTheme: "light", darkTheme: "dark" }),
		save: () => {}, onError: (message) => { errors.push(message); }, onChange: () => { changes += 1; },
	});
	const first = callbacks.get("a")!;
	assert.ok(first, "the initial custom theme must be watched");
	first({ name: "a", document: docs.a, source: "", digest: "1" });
	assert.equal(changes, 1);
	first({ name: "a", document: docs.a, source: "", digest: "2" });
	assert.equal(changes, 2, "the same subscription must accept a second valid update");
	assert.equal(cancels, 0);

	value.preview("b");
	assert.equal(callbacks.has("b"), true, "preview must subscribe to the new theme file");
	assert.equal(callbacks.has("a"), false, "the old subscription must be cancelled");
	const before = changes;
	first(new Error("late a"));
	first({ name: "a", document: docs.a, source: "", digest: "3" });
	assert.equal(changes, before, "late results from the old theme must be ignored");
	callbacks.get("b")!({ name: "b", document: docs.b, source: "", digest: "1" });
	assert.equal(changes, before + 1);
	assert.match(value.snapshot().name, /b/);

	const errorsBefore = errors.length;
	callbacks.get("b")!(new Error("bad b"));
	assert.equal(value.snapshot().name.includes("b"), true, "a bad file keeps the last valid snapshot");
	assert.ok(errors.length > errorsBefore);
	value.close();
	assert.ok(cancels >= 2);
});
