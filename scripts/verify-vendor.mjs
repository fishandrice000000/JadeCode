#!/usr/bin/env node
/**
 * 只读核对 vendored Pi：来源 commit、原文件 hash、当前文件 hash、补丁登记。
 *
 * 任何未登记的漂移都必须失败；脚本不会“自动修正 hash”，避免把未解释的修改洗成合法来源。
 * Pi 工作树只读取（git show），不修改。
 */
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const vendor = path.join(root, "frontend/vendor/pi-tui");
const pi = process.env.PI_SOURCE ?? "/home/fishandrice/Backend/pi";
const provenance = JSON.parse(fs.readFileSync(path.join(root, "frontend/vendor/pi-tui/provenance.json"), "utf8"));
const patchesDoc = fs.readFileSync(path.join(root, "frontend/vendor/patches.md"), "utf8");
const sha = (buffer) => createHash("sha256").update(buffer).digest("hex");
let failed = 0;
const fail = (message) => { console.error(`verify-vendor: ${message}`); failed += 1; };

if (provenance.source?.commit !== "4954d760504c11df9b2cbdc040346107b6a8153d") {
	fail(`unexpected upstream commit ${provenance.source?.commit}`);
}
for (const id of provenance.patches ?? []) {
	if (!patchesDoc.includes(`## ${id}`)) fail(`patch ${id} is listed in provenance but not documented in patches.md`);
}
for (const entry of provenance.files) {
	const file = path.join(vendor, entry.path);
	if (!fs.existsSync(file)) { fail(`missing vendored file ${entry.path}`); continue; }
	const current = sha(fs.readFileSync(file));
	if (current !== entry.currentSha256) fail(`current hash drifted: ${entry.path}\n  recorded ${entry.currentSha256}\n  actual   ${current}`);
	if (entry.originalSha256) {
		try {
			const original = execFileSync("git", ["-C", pi, "show", `${provenance.source.commit}:${entry.upstreamPath}`], { maxBuffer: 64 * 1024 * 1024 });
			if (sha(original) !== entry.originalSha256) fail(`upstream hash mismatch for ${entry.path}`);
		} catch (error) {
			fail(`cannot read upstream ${entry.upstreamPath}: ${error.message}`);
		}
	}
}
const changed = provenance.files.filter((entry) => entry.originalSha256 && entry.originalSha256 !== entry.currentSha256);
for (const entry of changed) {
	if (!patchesDoc.includes(entry.path)) fail(`modified file ${entry.path} is not named in patches.md`);
}
if (failed > 0) process.exit(1);
console.log(`verify-vendor: ${provenance.files.length} vendored files verified, ${changed.length} documented modifications`);
