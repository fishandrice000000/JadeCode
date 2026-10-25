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
	// 文本下标不是终端列号：右上角弹窗前面可能保留中文等双列字符。记录真实格坐标用于定位断言。
	const corners = [];
	for (let row = 0; row < terminal.rows; row++) {
		const line = buffer.getLine(buffer.baseY + row);
		for (let column = 0; column < terminal.cols; column++) {
			const glyph = line?.getCell(column)?.getChars();
			if (glyph === "╭" || glyph === "╮" || glyph === "╰" || glyph === "╯") corners.push({ row, column, glyph });
		}
	}
	frames.push({ name: stage.name, columns: terminal.cols, rows: terminal.rows, text: lines.join("\n"), corners });
}
terminal.dispose();
process.stdout.write(JSON.stringify(frames));
