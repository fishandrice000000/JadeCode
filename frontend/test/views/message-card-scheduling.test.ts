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

function entry(body: string): Entry {
	return { id: "reply", kind: "REPLY", actor: "leader", title: "Reply", body, arguments: "", status: "STREAMING",
		requestId: "r", callId: null, blockIndex: null, elapsedMs: null, historical: false, tool: null, revision: 1, expanded: false };
}

function factory(): { value: { create: () => unknown }; creates: number; setTexts: number; invalidates: number; widths: number[] } {
	const state = { creates: 0, setTexts: 0, invalidates: 0, widths: [] as number[] };
	return {
		get creates() { return state.creates; },
		get setTexts() { return state.setTexts; },
		get invalidates() { return state.invalidates; },
		get widths() { return state.widths; },
		value: {
			create: () => {
				state.creates += 1;
				let body = "";
				const setText = (value: string): void => { body = value; state.setTexts += 1; };
				return {
					markdown: {
						render: (width: number) => { state.widths.push(width); return [`${body}@${width}`]; },
						setText,
						invalidate: () => { state.invalidates += 1; },
					},
					setText,
					invalidate: () => { state.invalidates += 1; },
				};
			},
		},
	};
}

test("one card reuses its Markdown instance and invalidates on width change", () => {
	const fake = factory();
	const card = new MessageCard(entry("hello"), theme, fake.value as never, "  ", true, false, (actor) => actor);
	assert.match(card.render(80).join("\n"), /hello@80/);
	card.render(80);
	assert.equal(fake.creates, 1, "same card/width must reuse the Markdown instance");
	assert.equal(fake.setTexts, 1, "same body must not be re-set");
	card.render(100);
	assert.equal(fake.creates, 1, "width change must reuse the instance");
	assert.ok(fake.invalidates >= 1, "width change must invalidate layout");
	assert.match(card.render(100).join("\n"), /hello@100/);
});
