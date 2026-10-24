import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { MessageCard } from "../../src/views/message-views.ts";
import type { Entry } from "../../src/state/transcript-store.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

function entry(revision: number, body: string): Entry {
	return { id: "reply", kind: "REPLY", actor: "leader", title: "Reply", body, arguments: "", status: "STREAMING",
		requestId: "r", callId: null, blockIndex: null, elapsedMs: null, historical: false, tool: null, revision, expanded: false };
}

function factory(): { value: { create: (theme: unknown, indent: string) => unknown }; creates: number; setTexts: number } {
	const state = { creates: 0, setTexts: 0 };
	return {
		get creates() { return state.creates; },
		get setTexts() { return state.setTexts; },
		value: {
			create: () => {
				state.creates += 1;
				let body = "";
				const setText = (value: string): void => { body = value; state.setTexts += 1; };
				return {
					markdown: { render: () => [body], setText, invalidate: () => {} },
					setText,
					invalidate: () => {},
				};
			},
		},
	};
}

test("a stable reply reuses its Markdown instance; body revision and width invalidate it", () => {
	const fake = factory();
	const card = new MessageCard(entry(1, "hello"), theme, fake.value as never, "  ", true, false, (actor) => actor);
	assert.match(card.render(80).join("\n"), /hello/);
	card.render(80);
	assert.equal(fake.creates, 1, "same entry/width must not recreate the Markdown instance");
	assert.equal(fake.setTexts, 1, "same body must not be re-set");
	const changed = new MessageCard(entry(2, "hello world"), theme, fake.value as never, "  ", true, false, (actor) => actor);
	assert.match(changed.render(80).join("\n"), /hello world/);
	assert.equal(fake.creates, 2);
	const wider = new MessageCard(entry(2, "hello world"), theme, fake.value as never, "  ", true, false, (actor) => actor);
	wider.render(100);
	assert.equal(fake.creates, 3, "width changes rebuild the Markdown instance");
});
