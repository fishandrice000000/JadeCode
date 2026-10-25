#!/usr/bin/env node
/** 直接复用固定 Pi 的可移植契约语料；不运行原生剪贴板/图片客户端或性能基准。 */
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
// 显式白名单：这些文件只检查输入、布局、文本和虚拟终端，不碰用户桌面或联网服务。
const names = ["visible-width", "keys", "stdin-buffer", "wrap-ansi", "truncate-to-width", "layout",
	"terminal-colors", "terminal", "markdown", "editor", "input", "select-list", "settings-list",
	"wheel-scroll", "tui-alt-screen", "tui-render", "mouse-components", "latex", "overlay-options", "autocomplete"];
const result = spawnSync(process.execPath, ["--experimental-transform-types", "--test", "--test-reporter=spec",
	...names.map((name) => `frontend/vendor/pi-tui/test/${name}.test.ts`)], {
	cwd: root, encoding: "utf8", maxBuffer: 32 * 1024 * 1024,
});
const output = (result.stdout ?? "") + (result.stderr ?? "");
const count = (key) => Number(new RegExp(`ℹ ${key} (\\d+)`).exec(output)?.[1] ?? -1);
const report = { sourceCommit: "4954d760504c11df9b2cbdc040346107b6a8153d", node: process.version,
	files: names, tests: count("tests"), pass: count("pass"), fail: count("fail"),
	skipped: count("skipped"), exit: result.status };
fs.mkdirSync(path.join(root, "target"), { recursive: true });
fs.writeFileSync(path.join(root, "target/pi-contract-verification.log"), output);
fs.writeFileSync(path.join(root, "target/pi-contract-verification.json"), JSON.stringify(report, null, 2) + "\n");
process.stdout.write(JSON.stringify(report) + "\n");
if (result.status !== 0 || report.fail !== 0 || report.skipped !== 0 || report.tests <= 0) process.exitCode = 1;
