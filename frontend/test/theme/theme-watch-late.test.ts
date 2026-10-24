import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ThemeController } from "../../src/theme/theme-controller.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";

const docs = {
	a: parseThemeDocument(JSON.stringify({ name: "a", colors: { accent: "#ff0000", text: "#ffffff", background: "#000000" } })),
	b: parseThemeDocument(JSON.stringify({ name: "b", colors: { accent: "#00ff00", text: "#ffffff", background: "#000000" } })),
	c: parseThemeDocument(JSON.stringify({ name: "c", colors: { accent: "#0000ff", text: "#ffffff", background: "#000000" } })),
	a2: parseThemeDocument(JSON.stringify({ name: "a", colors: { accent: "#123456", text: "#ffffff", background: "#000000" } })),
};

test("a late failure for an older commit must not overwrite a newer preview", () => {
	const repository = {
		list: () => Object.keys(docs).map((name) => ({ name, path: name, builtin: false })),
		load: (name: string) => ({ name, document: docs[name as keyof typeof docs], source: "", digest: name }),
		watch: () => () => {},
	};
	const settings = { theme: "a", lightTheme: "light", darkTheme: "dark" };
	const errors: string[] = [];
	const value = new ThemeController({
		repository: repository as never, settings: () => settings, save: () => {},
		onError: (message) => { errors.push(message); }, onChange: () => {},
	});
	value.preview("b");
	value.commit();
	value.preview("c");
	value.commitFailedFor("b", "old save failed");
	assert.equal(value.selectedName(), "c", "the newer preview must win");
	assert.match(value.snapshot().name, /c/);
	assert.equal(errors.at(-1), "old save failed");
	value.close();
});


test("a late failure after cancel must not revive the abandoned preview", () => {
	const repository = {
		list: () => Object.keys(docs).map((name) => ({ name, path: name, builtin: false })),
		load: (name: string) => ({ name, document: docs[name as keyof typeof docs], source: "", digest: name }),
		watch: () => () => {},
	};
	const settings = { theme: "a", lightTheme: "light", darkTheme: "dark" };
	const errors: string[] = [];
	const value = new ThemeController({
		repository: repository as never, settings: () => settings, save: () => {},
		onError: (message) => { errors.push(message); }, onChange: () => {},
	});
	value.preview("b"); value.commit();
	value.preview("c"); value.cancel();
	value.commitFailedFor("b", "old save failed");
	assert.equal(value.selectedName(), "a");
	assert.match(value.snapshot().name, /a/);
	assert.equal(errors.at(-1), "old save failed");
	value.close();
});


test("clearPreview restores the saved theme subscription and rejects the preview file", () => {
	const callbacks = new Map<string, (result: unknown) => void>();
	const watched: string[] = [];
	let currentA = docs.a;
	const repository = {
		list: () => Object.keys(docs).map((name) => ({ name, path: name, builtin: false })),
		// watch 回调会重新 load；让 a 的真实加载结果可更新，才能证明新颜色生效。
		load: (name: string) => ({ name, document: name === "a" ? currentA : docs[name as keyof typeof docs], source: "", digest: name }),
		watch: (name: string, callback: (result: unknown) => void) => {
			watched.push(name); callbacks.set(name, callback); return () => { callbacks.delete(name); };
		},
	};
	const settings = { theme: "a", lightTheme: "a", darkTheme: "a" };
	const errors: string[] = [];
	const value = new ThemeController({
		repository: repository as never, settings: () => settings, save: () => {},
		onError: (message) => { errors.push(message); }, onChange: () => {},
	});
	value.preview("b");
	const previewB = callbacks.get("b")!;
	value.clearPreview();
	assert.equal(value.selectedName(), "a");
	assert.match(value.snapshot().name, /a/);
	assert.equal(watched.at(-1), "a", "the saved theme must be watched again");
	assert.equal(callbacks.has("b"), false, "the preview subscription must be cancelled");
	const beforeAccent = value.snapshot().colors.get("accent");
	previewB({ name: "b", document: docs.b, source: "", digest: "late" });
	previewB(new Error("late b error"));
	assert.match(value.snapshot().name, /a/, "a late preview update must not change the saved snapshot");
	assert.equal(errors.length, 0, "a late preview error must not be reported");
	currentA = docs.a2;
	callbacks.get("a")!({ name: "a", document: docs.a2, source: "", digest: "2" });
	assert.deepEqual(value.snapshot().colors.get("accent"), { kind: "rgb", r: 18, g: 52, b: 86 },
		"the current a subscription must reload and apply the new accent");
	value.close();
});
