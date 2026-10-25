import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import xterm from "@xterm/headless";
import { stripTerminalSequences } from "@earendil-works/pi-tui";
import { highlightCode } from "../../src/markdown/syntax-highlight.ts";
import { MarkdownFactory } from "../../src/markdown/markdown-factory.ts";
import { parseThemeDocument } from "../../src/theme/theme-document.ts";
import { resolveTheme } from "../../src/theme/theme-resolver.ts";

const document = parseThemeDocument(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8"));

test("code glyphs receive distinct colours in the terminal, including through Markdown", async () => {
	for (const variant of ["dark", "light"]) {
		for (const mode of ["truecolor", "256color"] as const) {
			const theme = resolveTheme(document, variant, { mode, noColor: false });
			const markdown = new MarkdownFactory().create(theme, "  ");
			markdown.setText('```java\nreturn "hello";\n```');
			const terminal = new xterm.Terminal({ cols: 80, rows: 12, allowProposedApi: true });
			try {
				await new Promise<void>((resolve) => terminal.write(markdown.markdown.render(80).join("\r\n"), resolve));
				const row = Array.from({ length: 12 }, (_, index) => terminal.buffer.active.getLine(index)!)
					.find((line) => line.translateToString().includes("return"))!;
				const text = row.translateToString();
				const keyword = row.getCell(text.indexOf("return"))!;
				const string = row.getCell(text.indexOf("hello"))!;
				assert.ok(!keyword.isFgDefault(), `${variant}/${mode}: keyword must actually be coloured`);
				assert.notEqual(keyword.getFgColor(), string.getFgColor(), `${variant}/${mode}: strings and keywords differ`);
			} finally { terminal.dispose(); }
		}
	}
});

test("nested spans, multiline comments, entities and plain fallbacks preserve code", () => {
	const source = '/* 中文\ncomment */\nint sum(int x) { return x < 2 ? 1 : 0; }\n';
	for (const noColor of [false, true]) {
		const theme = resolveTheme(document, "dark", { mode: "truecolor", noColor });
		const lines = highlightCode(source, "java", theme);
		assert.equal(lines.map(stripTerminalSequences).join("\n"), source);
		if (noColor) assert.doesNotMatch(lines.join("\n"), /\x1b/);
		else {
			assert.ok(lines[1]!.includes(theme.style("syntaxComment", "comment */")), "multiline comment keeps its role");
			assert.ok(lines[2]!.includes(theme.style("syntaxFunction", "sum")), "nested function name receives its own role");
		}
		assert.equal(highlightCode(source, "unknown-language", theme).join("\n"), source);
	}
});
