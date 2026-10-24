import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ConversationView } from "../../src/views/conversation-view.ts";
import { SessionStore } from "../../src/state/session-store.ts";
import { TranscriptStore } from "../../src/state/transcript-store.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

function session(tui: Record<string, unknown> = {}): SessionStore {
	const store = new SessionStore();
	store.applyBootstrap({ buildId: "b", protocolVersion: 1, projectVersion: "test", workspace: "/tmp",
		themeDirectory: "/tmp", configRevision: "0", tui, startup: { mode: "new", autoSubmit: false, resumeTarget: "" } } as never);
	store.applySnapshot({ snapshot: { sessionId: "s", generation: "1", tui, configRevision: "0" } } as never);
	return store;
}

function view(tui: Record<string, unknown> = {}): { transcript: TranscriptStore; conversation: ConversationView; creates: () => number } {
	const transcript = new TranscriptStore();
	let creates = 0;
	const conversation = new ConversationView(transcript, session(tui), {
		create: () => {
			creates += 1;
			let body = "";
			const setText = (value: string): void => { body = value; };
			return {
				markdown: { render: (_width: number) => [`[reply] ${body}`], setText, invalidate: () => {} },
				setText,
				invalidate: () => {},
			};
		},
	} as never);
	conversation.setThemeAccessor(() => theme);
	return { transcript, conversation, creates: () => creates };
}

test("a reply updated after the first render becomes visible (revision-aware cache)", () => {
	const { transcript, conversation } = view();
	const entry = transcript.streamEntry("r1", "leader", "REPLY");
	conversation.render(80);
	transcript.update(entry.id, (current) => ({ ...current, body: "NEW_REPLY_BODY", status: "DONE" }));
	const lines = conversation.render(80).join("\n");
	assert.match(lines, /NEW_REPLY_BODY/);
	// 同宽重渲染应复用同一结果，且与重排路径间距一致。
	assert.deepEqual(conversation.render(80), conversation.render(80));
});

test("the empty welcome screen stays non-empty on repeated renders", () => {
	const { conversation } = view();
	const first = conversation.render(80);
	const second = conversation.render(80);
	assert.ok(first.length > 0);
	assert.deepEqual(second, first);
	assert.ok(second.some((line) => line.includes("JADE") || line.includes("JadeCode")), "branding must be present");
});


test("updating one entry rebuilds only that card; a stable neighbour is reused", () => {
	const { transcript, conversation, creates } = view();
	const first = transcript.streamEntry("r1", "leader", "REPLY");
	transcript.streamEntry("r2", "leader", "REPLY");
	conversation.render(80);
	assert.equal(creates(), 2);
	transcript.update(first.id, (current) => ({ ...current, body: "ONLY_R1_CHANGED", status: "DONE", revision: current.revision + 1 }));
	const lines = conversation.render(80).join("\n");
	assert.match(lines, /ONLY_R1_CHANGED/);
	assert.equal(creates(), 3, "only the changed entry creates a new card");
});


test("the welcome screen follows real viewport rows and quietStartup", () => {
	const tall = view();
	tall.conversation.setViewportRows(22);
	const tallLines = tall.conversation.render(120);
	assert.ok(tallLines.length >= 8, "22 rows keeps the banner");
	assert.ok(tallLines.some((line) => line.includes("JADE")));
	assert.ok(tallLines.some((line) => line.includes("Workspace:")), "normal startup shows the workspace");

	const short = view();
	short.conversation.setViewportRows(21);
	const shortLines = short.conversation.render(120);
	assert.equal(shortLines.length, 4, "LINE branding, blank separator and workspace line");
	assert.ok(shortLines.every((line) => !line.includes("JADE CODE") && !line.includes("█")));
	assert.ok(shortLines.some((line) => line.includes("JadeCode vtest")));

	const quiet = view({ quietStartup: true });
	const quietLines = quiet.conversation.render(120).join("\n");
	assert.doesNotMatch(quietLines, /Workspace:/, "quiet startup suppresses the workspace line");
	assert.match(quietLines, /JadeCode/);
});
