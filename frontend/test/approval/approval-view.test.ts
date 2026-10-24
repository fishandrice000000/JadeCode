import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { ApprovalView, type ApprovalChoice } from "../../src/approval/approval-view.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const theme = resolveTheme(parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8")), "dark", {
	mode: "256color",
	noColor: true,
});

function choices(similar: boolean): ApprovalChoice[] {
	return [
		{ index: 0, label: "Allow once", enabled: true },
		{ index: 1, label: "Allow similar", enabled: similar },
		{ index: 2, label: "Deny", enabled: true },
	];
}

function input(parameters: string) {
	return { title: "Approval required", tool: "bash", scope: "this request", parameters, actor: "leader", teamActive: false, actorName: (a: string) => a, loading: false };
}

test("button rectangles point at the rendered option lines, not at shifted rows", () => {
	const view = new ApprovalView(theme, choices(false));
	const lines = view.render(input("{}"), 80, 0, 24, 80);
	const allowLine = lines.findIndex((line) => line.includes("Allow once"));
	const denyLine = lines.findIndex((line) => line.includes("Deny"));
	assert.ok(allowLine >= 0 && denyLine >= allowLine);
	const rects = view.lastLayout()!.rects;
	assert.deepEqual(rects.map((rect) => [rect.choice, rect.y]), [[0, allowLine], [2, denyLine]]);
	// 禁用的 similar 仍占一行，但没有可批准矩形。
	assert.equal(lines.filter((line) => line.includes("Allow similar")).length, 1);
	assert.equal(rects.some((rect) => rect.choice === 1), false);
});

test("long parameters are windowed; buttons stay visible and scroll changes the window", () => {
	const view = new ApprovalView(theme, choices(true));
	const parameters = Array.from({ length: 60 }, (_, index) => `line-${index}`).join("\n");
	const lines = view.render(input(parameters), 80, 0, 24, 80);
	assert.ok(lines.length <= 24, `approval must fit the viewport, got ${lines.length}`);
	assert.ok(lines.some((line) => line.includes("PgUp/PgDn")));
	const firstRect = view.lastLayout()!.rects.find((rect) => rect.choice === 0)!;
	assert.ok(firstRect.y >= 0 && firstRect.y < 24);
	view.scrollBy(20);
	const scrolled = view.render(input(parameters), 80, 0, 24, 80);
	assert.ok(scrolled.some((line) => line.includes("line-20")), "PgDn window should move to the newer lines");
});

test("no approval rectangles when the card cannot be placed inside the viewport", () => {
	const view = new ApprovalView(theme, choices(true));
	const lines = view.render(input("{}"), 80, 30, 24, 80);
	assert.ok(lines.length > 0);
	assert.deepEqual(view.lastLayout()!.rects, [], "out-of-viewport buttons must not be answerable");
});
