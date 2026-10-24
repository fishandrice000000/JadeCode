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


test("auto watches the resolved pair file, re-subscribes on scheme change, and close drops queued callbacks", () => {
	const callbacks = new Map<string, (result: unknown) => void>();
	const watched: string[] = [];
	const docs = { a: document("a", "#ff0000"), b: document("b", "#0000ff") };
	const settings = { theme: "auto", lightTheme: "b", darkTheme: "a" };
	const repository = {
		list: () => [{ name: "a", path: "a", builtin: false }, { name: "b", path: "b", builtin: false }],
		load: (name: string) => ({ name, document: docs[name as keyof typeof docs], source: "", digest: name }),
		watch: (name: string, callback: (result: unknown) => void) => {
			watched.push(name);
			callbacks.set(name, callback);
			return () => { callbacks.delete(name); };
		},
	};
	let changes = 0;
	const errors: string[] = [];
	const value = new ThemeController({
		repository: repository as never,
		settings: () => settings,
		save: () => {}, onError: (message) => { errors.push(message); }, onChange: () => { changes += 1; },
	});
	assert.deepEqual(watched, ["a"], "auto must watch the dark pair file, not the logical name");
	const darkA = callbacks.get("a")!;
	darkA({ name: "a", document: docs.a, source: "", digest: "1" });
	darkA({ name: "a", document: docs.a, source: "", digest: "2" });
	assert.equal(changes, 2, "the auto subscription accepts repeated updates");
	value.setScheme(false);
	assert.equal(watched.includes("b"), true, "switching to light must subscribe to the light pair file");
	const oldA = darkA;
	const before = changes;
	oldA(new Error("late a"));
	oldA({ name: "a", document: docs.a, source: "", digest: "3" });
	assert.equal(changes, before);
	callbacks.get("b")!({ name: "b", document: docs.b, source: "", digest: "1" });
	assert.equal(changes, before + 1);
	assert.equal(value.selectedName(), "auto", "logical selection stays auto");
	// L02: queued callback delivered after close must not change state or report errors.
	const queued = callbacks.get("b")!;
	value.close();
	const afterClose = changes;
	const errorsBefore = errors.length;
	queued({ name: "b", document: docs.b, source: "", digest: "2" });
	queued(new Error("closed"));
	assert.equal(changes, afterClose);
	assert.equal(errors.length, errorsBefore);
});


test("commit watches the submitted theme while the async settings reply is still pending", () => {
	const callbacks = new Map<string, (result: unknown) => void>();
	const watched: string[] = [];
	const docs = { a: document("a", "#ff0000"), b: document("b", "#0000ff") };
	const settings = { theme: "a", lightTheme: "light", darkTheme: "dark" };
	const repository = {
		list: () => [{ name: "a", path: "a", builtin: false }, { name: "b", path: "b", builtin: false }],
		load: (name: string) => ({ name, document: docs[name as keyof typeof docs], source: "", digest: name }),
		watch: (name: string, callback: (result: unknown) => void) => {
			watched.push(name);
			callbacks.set(name, callback);
			return () => { callbacks.delete(name); };
		},
	};
	let changes = 0;
	const errors: string[] = [];
	const value = new ThemeController({
		repository: repository as never,
		settings: () => settings,
		save: () => { /* 异步：后端回执尚未到达，settings 仍是 a */ },
		onError: (message) => { errors.push(message); }, onChange: () => { changes += 1; },
	});
	const oldA = callbacks.get("a")!;
	value.preview("b");
	value.commit();
	assert.match(value.snapshot().name, /b/);
	assert.equal(watched.at(-1), "b", "the submitted theme must be watched before settings.saved");
	assert.equal(callbacks.has("a"), false);
	const before = changes;
	oldA(new Error("late a"));
	assert.equal(errors.length, 0, "the old file callback must be rejected during the save window");
	assert.equal(changes, before);
	// 回执后 settings 才更新；订阅仍应指向 b，并接受 b 的更新。
	settings.theme = "b";
	value.settingsChanged();
	assert.equal(watched.at(-1), "b");
	const afterReply = changes;
	callbacks.get("b")!({ name: "b", document: docs.b, source: "", digest: "2" });
	assert.equal(changes, afterReply + 1);
});


test("a failed theme save restores the retryable preview and keeps the new file watched", () => {
	const callbacks = new Map<string, (result: unknown) => void>();
	const docs = { a: document("a", "#ff0000"), b: document("b", "#0000ff") };
	const settings = { theme: "a", lightTheme: "light", darkTheme: "dark" };
	const repository = {
		list: () => [{ name: "a", path: "a", builtin: false }, { name: "b", path: "b", builtin: false }],
		load: (name: string) => ({ name, document: docs[name as keyof typeof docs], source: "", digest: name }),
		watch: (name: string, callback: (result: unknown) => void) => { callbacks.set(name, callback); return () => { callbacks.delete(name); }; },
	};
	const errors: string[] = [];
	const value = new ThemeController({
		repository: repository as never, settings: () => settings, save: () => {},
		onError: (message) => { errors.push(message); }, onChange: () => {},
	});
	value.preview("b");
	value.commit();
	assert.match(value.snapshot().name, /b/);
	value.commitFailed("write failed");
	assert.equal(value.selectedName(), "b", "failed save keeps the retryable preview");
	assert.equal(settings.theme, "a", "settings were not pretended saved");
	assert.equal(errors.at(-1), "write failed");
	assert.equal(callbacks.has("b"), true, "the previewed file stays watched for retry");
	value.commitSucceeded();
	value.close();
});
