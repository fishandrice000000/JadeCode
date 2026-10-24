import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { Application } from "../../src/app/Application.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";
import { PageStack } from "../../src/pages/page-stack.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color", noColor: true,
});

function app(rows: number): { app: Application; setRows: (rows: number) => void } {
	const value = new Application();
	const fields = value as unknown as { [key: string]: unknown };
	const session = fields["session"] as { applyBootstrap: (payload: unknown) => void; applySnapshot: (payload: unknown) => void };
	session.applyBootstrap({ buildId: "b", protocolVersion: 1, projectVersion: "9.9.9", workspace: "/tmp",
		themeDirectory: "/tmp", configRevision: "0", tui: {}, startup: { mode: "new", autoSubmit: false, resumeTarget: "" } });
	session.applySnapshot({ snapshot: { sessionId: "s", generation: "1", tui: {}, configRevision: "0" } });
	const conversation = fields["conversation"] as { setThemeAccessor: (accessor: () => unknown) => void };
	conversation.setThemeAccessor(() => theme);
	fields["theme"] = { snapshot: () => theme, reload: () => {}, settingsChanged: () => {} };
	const state = { rows };
	fields["host"] = {
		terminal: { get rows() { return state.rows; } },
		mode: () => "regular",
		viewportTop: () => 0,
		requestRender: () => {},
		hasSelection: () => false,
		tui: () => ({}),
	};
	fields["editor"] = { editor: { render: () => ["EDITOR"] } };
	fields["pages"] = new PageStack();
	return { app: value, setRows: (next) => { state.rows = next; } };
}

function render(value: Application): string {
	return (value as unknown as { renderRoot(width: number): string[] }).renderRoot(120).join("\n");
}

test("the real welcome path uses terminal rows: 21 lines downgrades, 22 restores the banner, resize invalidates", () => {
	const { app: value, setRows } = app(21);
	const low = render(value);
	assert.match(low, /JadeCode v9\.9\.9/);
	assert.doesNotMatch(low, /█/, "21 rows must not render the six-line wordmark");
	setRows(22);
	const tall = render(value);
	assert.match(tall, /█/, "22 rows restores the full banner on the same width");
	assert.match(tall, /JadeCode v9\.9\.9/);
	setRows(21);
	assert.doesNotMatch(render(value), /█/, "resize below the threshold invalidates the cached welcome");
});
