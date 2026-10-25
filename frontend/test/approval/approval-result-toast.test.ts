import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { stripTerminalSequences, visibleWidth } from "@earendil-works/pi-tui";
import { ApprovalResultToast } from "../../src/approval/approval-result-toast.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";
import { Application } from "../../src/app/Application.ts";
import type { ApprovalDisplayData } from "../../src/approval/approval-controller.ts";

const document = parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8"));
const data: ApprovalDisplayData = { requestId: "a", tool: "write_file", scope: "Write under /tmp", actor: "leader",
	parameters: '{"path":"中文.txt","content":"hello"}', loading: false };

test("a result is replaced rather than queued, expires independently, and clears on lifecycle change", (t) => {
	t.mock.timers.enable({ apis: ["setTimeout"] });
	const theme = resolveTheme(document, "dark", { mode: "truecolor", noColor: true });
	const toast = new ApprovalResultToast(() => theme, () => {});
	toast.show(data, 0);
	t.mock.timers.tick(4000);
	toast.show({ ...data, tool: "bash" }, 2);
	t.mock.timers.tick(1000);
	let text = toast.render(76).join("\n");
	assert.match(text, /Denied/); assert.match(text, /bash/); assert.doesNotMatch(text, /write_file|Allowed once/);
	t.mock.timers.tick(4000);
	assert.equal(toast.hasResult(), false);
	assert.deepEqual(toast.render(76), []);
	toast.show(data, 1);
	text = toast.render(76).join("\n");
	assert.match(text, /Allowed similar/); assert.match(text, /Write under \/tmp/);
	toast.clear();
	t.mock.timers.tick(5000);
	assert.deepEqual(toast.render(76), []);
});

test("result cards have styled hierarchy, bounded safe previews and a plain narrow fallback", () => {
	for (const appearance of ["dark", "light"]) {
		for (const noColor of [false, true]) {
			const theme = resolveTheme(document, appearance, { mode: "truecolor", noColor });
			const toast = new ApprovalResultToast(() => theme, () => {});
			try {
				for (const choice of [0, 1, 2]) {
					toast.show(data, choice);
					const lines = toast.render(76);
					assert.equal(lines.length, 5);
					assert.match(stripTerminalSequences(lines[0]!), /╭─+╮/);
					assert.ok(lines[1]!.includes(theme.style("accent", "write_file")));
					assert.ok(lines[3]!.includes(theme.style("syntaxString", '"hello"')));
					for (const width of [1, 20, 31, 32, 40, 76]) {
						const rows = toast.render(width);
						assert.equal(rows.length, toast.height(width));
						for (const row of rows) assert.ok(visibleWidth(row) <= width);
						if (noColor) assert.doesNotMatch(rows.join("\n"), /\x1b/);
					}
				}
				toast.show({ ...data, tool: "bash\x1b]52;c;unsafe\x07", parameters: "x".repeat(20000) + "PRIVATE_TAIL" }, 0);
				const text = toast.render(76).join("\n");
				assert.doesNotMatch(text, /\x1b\]52|PRIVATE_TAIL/);
				assert.match(stripTerminalSequences(text), /\\x1b/);
			} finally { toast.clear(); }
		}
	}
});

test("Application anchors a compact noncapturing result at top right without adding transcript entries", (t) => {
	t.mock.timers.enable({ apis: ["setTimeout"] });
	const app = new Application();
	const fields = app as unknown as Record<string, any>;
	const overlays: Array<{ component: ApprovalResultToast; options: any; hidden: boolean }> = [];
	fields.theme = { snapshot: () => resolveTheme(document, "dark", { mode: "truecolor", noColor: true }) };
	fields.host = { terminal: { columns: 100, rows: 24 }, requestRender: () => {}, tui: () => ({
		showOverlay: (component: ApprovalResultToast, options: any) => {
			const entry = { component, options, hidden: false };
			overlays.push(entry);
			return { hide: () => { entry.hidden = true; } };
		},
	}) };
	fields.dockOriginY = 18;
	const answered = fields.approval.callbacks.onAnswered as (data: ApprovalDisplayData, choice: number) => void;
	try {
		answered(data, 0);
		assert.equal(fields.transcript.entries().length, 0);
		assert.equal(overlays[0]!.options.nonCapturing, true);
		assert.equal(overlays[0]!.options.anchor, "top-right");
		assert.equal(overlays[0]!.options.margin, 1);
		assert.equal(overlays[0]!.options.width, 56);
		assert.equal(overlays[0]!.options.row, undefined);
		assert.equal(overlays[0]!.options.visible(), true);
		fields.dockOriginY = 12;
		assert.equal(overlays[0]!.options.anchor, "top-right", "multiline drafts do not move the popup");
		fields.host.terminal.columns = 30;
		assert.equal(overlays[0]!.options.width, 28, "popup follows terminal width without overflowing");
		fields.dockOriginY = 1;
		assert.equal(overlays[0]!.options.visible(), false, "small screen cannot move the result over the editor");
		fields.dockOriginY = 18;
		fields.pages.push({} as never);
		assert.equal(overlays[0]!.options.visible(), false, "a page has priority");
		fields.pages.clear();
		answered({ ...data, tool: "bash" }, 2);
		assert.equal(overlays[0]!.hidden, true);
		assert.equal(fields.transcript.entries().length, 0);
		t.mock.timers.tick(5000);
		assert.equal(overlays[1]!.options.visible(), false);
		answered(data, 0);
		fields.clearApprovalResult();
		assert.equal(overlays[2]!.hidden, true);
		assert.equal(fields.approvalResult.hasResult(), false);
	} finally { fields.clearApprovalResult(); }
});
