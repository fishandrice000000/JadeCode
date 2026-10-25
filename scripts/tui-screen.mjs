/** 按真实 PTY 阶段解析屏幕；仅供发行集成驱动使用，不进入生产包。 */
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(new URL("../frontend/package.json", import.meta.url));
const { Terminal } = require("@xterm/headless");
const directory = process.argv[2];
const stages = JSON.parse(fs.readFileSync(path.join(directory, "stages.json"), "utf8"));
const terminal = new Terminal({ cols: stages[0].columns, rows: stages[0].rows, allowProposedApi: true, scrollback: 0 });
const frames = [];
for (const stage of stages) {
	terminal.resize(stage.columns, stage.rows);
	await new Promise((resolve) => terminal.write(fs.readFileSync(path.join(directory, `${stage.name}.bin`)), resolve));
	const buffer = terminal.buffer.active;
	const lines = Array.from({ length: terminal.rows }, (_, y) => buffer.getLine(buffer.baseY + y)?.translateToString(true) ?? "");
	frames.push({ name: stage.name, columns: terminal.cols, rows: terminal.rows, text: lines.join("\n") });
}
terminal.dispose();
process.stdout.write(JSON.stringify(frames));
