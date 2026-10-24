import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const builtin = parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8"));

test("v2 builtin resolves both variants and every role colour", () => {
	for (const variant of ["dark", "light"]) {
		const theme = resolveTheme(builtin, variant, { mode: "truecolor" });
		assert.equal(theme.appearance, variant);
		for (const role of ["text", "accent", "approvalOnceBg", "toolOutput", "mdHeading", "brandPrimary", "panelBg"] as const) {
			assert.ok(theme.colors.has(role), `${variant}.${role} must resolve`);
		}
	}
});

test("v1 single-appearance themes still parse and use semantic fallbacks", () => {
	const document = parseThemeDocument(JSON.stringify({
		name: "legacy", colors: { accent: "#00ff00", text: "#ffffff", background: "#000000" },
	}));
	const theme = resolveTheme(document, "single", { mode: "256color" });
	assert.equal(theme.appearance, "dark");
	assert.ok(theme.colors.has("mdHeading"), "missing newer roles fall back to accent/text");
});

test("system theme keeps terminal fg/bg and NO_COLOR emits no styling", () => {
	const dark = resolveTheme(builtin, "dark", { mode: "256color", noColor: true,
		system: { foreground: undefined, background: undefined, dark: true } });
	assert.equal(dark.noColor, true);
	assert.equal(dark.style("accent", "plain"), "plain");
	assert.equal(dark.bold("plain"), "plain");
	const colored = resolveTheme(builtin, "dark", { mode: "truecolor", noColor: false });
	assert.notEqual(colored.style("accent", "plain"), "plain");
});

test("NO_COLOR is honoured unless the host explicitly overrides it", () => {
	const previous = process.env.NO_COLOR;
	process.env.NO_COLOR = "1";
	try {
		const withoutOption = resolveTheme(builtin, "dark", { mode: "truecolor" });
		assert.equal(withoutOption.noColor, true);
		assert.equal(withoutOption.style("accent", "plain"), "plain");
		const overridden = resolveTheme(builtin, "dark", { mode: "truecolor", noColor: false });
		assert.equal(overridden.noColor, false);
		assert.notEqual(overridden.style("accent", "plain"), "plain");
	} finally {
		if (previous === undefined) delete process.env.NO_COLOR;
		else process.env.NO_COLOR = previous;
	}
});

test("bad documents fail loudly", () => {
	assert.throws(() => parseThemeDocument("{"));
	assert.throws(() => parseThemeDocument(JSON.stringify({ variants: { dark: {}, light: {} } })));
	assert.throws(() => parseThemeDocument(JSON.stringify({ schemaVersion: 2, variants: { dark: { colors: {} } } })));
});
