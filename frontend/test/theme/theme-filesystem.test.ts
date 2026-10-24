import assert from "node:assert/strict";
import { test } from "node:test";
import { mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ThemeRepository } from "../../src/theme/theme-repository.ts";

/** 真实 fs.watch 必须跨越编辑器的原子替换保存，内存回调夹具无法发现旧 inode 失联。 */
test("a custom theme remains watched across two atomic file replacements", async () => {
	const directory = mkdtempSync(join(tmpdir(), "jade-theme-watch-"));
	let stop: (() => void) | undefined;
	try {
		const source = JSON.parse(readFileSync(new URL("../../../assets/themes/jadecode.json", import.meta.url), "utf8"));
		writeFileSync(join(directory, "a.json"), JSON.stringify(source));
		const repo = new ThemeRepository(directory, directory);
		const seen: string[] = [];
		stop = repo.watch("a", (change) => {
			assert.ok(!(change instanceof Error));
			seen.push(change.document.variants.dark!.vars.primary!.text);
		});
		for (const color of ["#123456", "#abcdef"]) {
			source.variants.dark.vars.primary = color;
			writeFileSync(join(directory, "a.next"), JSON.stringify(source));
			renameSync(join(directory, "a.next"), join(directory, "a.json"));
			const deadline = Date.now() + 1500;
			while (!seen.includes(color) && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 5));
			assert.ok(seen.includes(color), `file replacement ${color} must update the loaded document`);
		}
	} finally { stop?.(); rmSync(directory, { recursive: true, force: true }); }
});
