import assert from "node:assert/strict";
import { test } from "node:test";
import { SessionStore } from "../../src/state/session-store.ts";
import { TranscriptStore } from "../../src/state/transcript-store.ts";
import { ConversationView } from "../../src/views/conversation-view.ts";
import { StatusView } from "../../src/views/status-view.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

test("wire resource names appear only when expanded and CH comes from the reported request", () => {
	const theme = resolveTheme(parseThemeDocument(JSON.stringify({ name: "t", colors: {} })), "dark", { mode: "256color", noColor: true });
	const session = new SessionStore();
	session.applySnapshot({ snapshot: { sessionId: "s", generation: "1", tui: { quietStartup: true },
		resources: { Context: ["AGENTS.md"], Skills: ["local"] }, usage: { recentCacheHitPercent: 60 } } } as never);
	const conversation = new ConversationView(new TranscriptStore(), session, {} as never);
	conversation.setThemeAccessor(() => theme);
	assert.doesNotMatch(conversation.render(100).join("\n"), /AGENTS.md|Skills · local/);
	conversation.toggleWelcomeResources();
	assert.match(conversation.render(100).join("\n"), /Context · AGENTS.md/);
	assert.match(conversation.render(100).join("\n"), /Skills · local/);
	assert.match(new StatusView(session).render(theme, 200).join("\n"), /CH 60%/);
	session.applySnapshot({ snapshot: { sessionId: "s", generation: "1", tui: {}, usage: { recentCacheHitPercent: null } } } as never);
	assert.doesNotMatch(new StatusView(session).render(theme, 200).join("\n"), /CH /);
});

test("context marks local estimates and preserves reported occupancy without the estimate marker", () => {
	const theme = resolveTheme(parseThemeDocument(JSON.stringify({ name: "t", colors: {} })), "dark", { mode: "256color", noColor: true });
	const session = new SessionStore();
	for (const estimated of [true, false]) {
		session.applySnapshot({ snapshot: { sessionId: "s", generation: "1", tui: {}, usage: {},
			context: { occupiedTokens: 2000, windowTokens: 10000, remainingPercent: 80, estimated } } } as never);
		const rendered = new StatusView(session).render(theme, 200).join("\n");
		assert.match(rendered, estimated ? /ctx ~2/ : /ctx 2/);
	}
});
