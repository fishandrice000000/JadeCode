import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { BrandingView } from "../../src/views/branding-view.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const document = parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8"));
const theme = resolveTheme(document, "dark", { mode: "256color", noColor: true });

test("branding keeps the original mascot/banner, version and narrow fallback", () => {
	const wide = new BrandingView().render(theme, 120, 30, "9.9.9").join("\n");
	assert.match(wide, /JADE/);
	assert.match(wide, /████/);
	assert.match(wide, /9\.9\.9/);
	const narrow = new BrandingView().render(theme, 20, 24, "9.9.9").join("\n");
	assert.match(narrow, /JADE CODE/);
	assert.match(narrow, /9\.9\.9/);
	assert.doesNotMatch(narrow, /\x1b\[/);
});
