import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { BrandingView } from "../../src/views/branding-view.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";
import { foregroundAnsi } from "@earendil-works/pi-tui";

const document = parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8"));
const theme = resolveTheme(document, "dark", { mode: "256color", noColor: true });
const colored = resolveTheme(document, "dark", { mode: "truecolor", noColor: false });

test("branding keeps the original mascot/banner, version and narrow fallback", () => {
	const wide = new BrandingView().render(theme, 120, 30, "9.9.9").join("\n");
	assert.match(wide, /JADE/);
	assert.match(wide, /████/);
	assert.match(wide, /9\.9\.9/);
	const narrow = new BrandingView().render(theme, 20, 24, "9.9.9").join("\n");
	assert.match(narrow, /JadeCode v9\.9\.9/);
	assert.doesNotMatch(narrow, /┏|█|JADE CODE/);
	assert.doesNotMatch(narrow, /\x1b\[/);
});


test("full/compact/line tiers follow measured width and the 22-row height floor", () => {
	const view = new BrandingView();
	assert.equal(view.mode(89, 40), "FULL");
	assert.equal(view.mode(88, 40), "COMPACT");
	assert.equal(view.mode(29, 30), "COMPACT");
	assert.equal(view.mode(28, 30), "LINE");
	assert.equal(view.mode(120, 21), "LINE");
	assert.equal(view.mode(80, 24), "COMPACT");
	const full = view.render(theme, 120, 40, "9.9.9");
	assert.equal(full.length, 8);
	assert.equal(full[6], "JadeCode v9.9.9");
	assert.match(full[7]!, /^coding agent/);
	const compact = view.render(theme, 80, 24, "9.9.9");
	assert.equal(compact.length, 6);
	assert.match(compact[0]!, /JADE CODE/);
	const short = view.render(theme, 120, 21, "9.9.9");
	assert.equal(short.length, 2);
	assert.doesNotMatch(short.join("\n"), /┏|█/);
});

test("branding uses frame/body/shadow/accent roles from the same theme", () => {
	const view = new BrandingView();
	const full = view.render(colored, 120, 40, "1.0.0").join("\n");
	const ansi = (role: "brandFrame" | "brandBannerBody" | "brandBannerShadow" | "brandAccent") => {
		const color = colored.colors.get(role)!;
		return foregroundAnsi(color, "truecolor");
	};
	assert.ok(full.includes(ansi("brandFrame")), "mascot frame uses brandFrame");
	assert.ok(full.includes(ansi("brandBannerBody")), "wordmark body uses brandBannerBody");
	assert.ok(full.includes(ansi("brandBannerShadow")), "wordmark outline uses brandBannerShadow");
	const compact = view.render(colored, 80, 24, "1.0.0").join("\n");
	assert.ok(compact.includes(ansi("brandAccent")), "mascot dots use brandAccent");
});
