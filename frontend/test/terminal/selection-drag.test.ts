import assert from "node:assert/strict";
import { test } from "node:test";
import { Text } from "@earendil-works/pi-tui";
import { TuiAltScreen } from "../../vendor/pi-tui/src/tui-alt-screen.ts";
import { VirtualTerminal } from "../../vendor/pi-tui/test/virtual-terminal.ts";

async function drag(options: { copyOnSelect?: boolean } = {}): Promise<{
	copied: string[]; tui: TuiAltScreen; terminal: VirtualTerminal;
}> {
	const terminal = new VirtualTerminal(20, 4);
	const copied: string[] = [];
	const tui = new TuiAltScreen(terminal, undefined, undefined, {
		copyOnSelect: options.copyOnSelect ?? true,
		copySelection: async (text) => { copied.push(text); return true; },
	});
	tui.addChild(new Text("alpha\nbeta\ngamma\ndelta", 0, 0));
	tui.start();
	await terminal.waitForRender();
	return { copied, tui, terminal };
}

function inverse(terminal: VirtualTerminal, x: number, y: number): boolean {
	const xterm = (terminal as unknown as { xterm: { buffer: { active: { baseY: number;
		getLine(row: number): { getCell(column: number): { isInverse(): boolean } | undefined } | undefined } } } }).xterm;
	const line = xterm.buffer.active.getLine(xterm.buffer.active.baseY + y);
	return Boolean(line?.getCell(x)?.isInverse());
}

async function selectAlphaBeta(terminal: VirtualTerminal): Promise<void> {
	terminal.sendInput("\x1b[<0;1;1M");
	terminal.sendInput("\x1b[<32;4;2M");
	terminal.sendInput("\x1b[<0;4;2m");
	await terminal.waitForRender();
}

test("dragging visible text copies the selection and shows Copied!", async () => {
	const { copied, tui, terminal } = await drag();
	await selectAlphaBeta(terminal);
	assert.deepEqual(copied, ["alpha\nbeta"]);
	assert.equal(tui.hasActiveSelection(), true);
	assert.ok(terminal.getViewport().some((line) => line.includes("Copied!")));
	assert.deepEqual([0, 1, 2, 3, 4].map((x) => inverse(terminal, x, 0)), [true, true, true, true, true],
		"alpha is fully highlighted");
	assert.deepEqual([0, 1, 2, 3].map((x) => inverse(terminal, x, 1)), [true, true, true, true],
		"beta is fully highlighted");
	assert.equal(inverse(terminal, 0, 2), false, "unselected gamma is not highlighted");
	tui.requestRender(true);
	await terminal.waitForRender();
	assert.equal(inverse(terminal, 4, 0), true, "a forced repaint keeps the selection highlight");
	tui.stop();
});

test("copyOnSelect=false keeps the drag selection without copying", async () => {
	const { copied, tui, terminal } = await drag({ copyOnSelect: false });
	await selectAlphaBeta(terminal);
	assert.deepEqual(copied, []);
	assert.equal(tui.hasActiveSelection(), true);
	assert.ok(terminal.getViewport().every((line) => !line.includes("Copied!")));
	assert.equal(inverse(terminal, 0, 0), true, "the selection stays visible without copying");
	copied.length = 0;
	assert.equal(await tui.copyActiveSelectionToClipboard(), true, "programmatic copy still works");
	assert.deepEqual(copied, ["alpha\nbeta"]);
	tui.stop();
});

test("flash messages expire after their duration", async () => {
	const { tui, terminal } = await drag();
	tui.flash("FLASH_TIMEOUT", 25);
	await terminal.waitForRender();
	assert.ok(terminal.getViewport().some((line) => line.includes("FLASH_TIMEOUT")));
	await new Promise((resolve) => setTimeout(resolve, 60));
	await terminal.waitForRender();
	assert.ok(terminal.getViewport().every((line) => !line.includes("FLASH_TIMEOUT")), "the flash must disappear");
	tui.stop();
});

test("a failed copySelection result is shown as the real error", async () => {
	const terminal = new VirtualTerminal(20, 4);
	const tui = new TuiAltScreen(terminal, undefined, undefined, {
		copySelection: async () => "CLIPBOARD_DENIED",
	});
	tui.addChild(new Text("alpha\nbeta\ngamma\ndelta", 0, 0));
	tui.start();
	await terminal.waitForRender();
	await selectAlphaBeta(terminal);
	assert.ok(terminal.getViewport().some((line) => line.includes("CLIPBOARD_DENIED")));
	assert.ok(terminal.getViewport().every((line) => !line.includes("Copied!")), "failure must not claim success");
	tui.stop();
});

test("copy success uses the default flash duration and failure uses the long error duration", async () => {
	async function run(copySelection: (text: string) => Promise<true | string>): Promise<Array<[string, number | undefined]>> {
		const terminal = new VirtualTerminal(20, 4);
		const calls: Array<[string, number | undefined]> = [];
		const tui = new TuiAltScreen(terminal, undefined, undefined, { copySelection });
		const originalFlash = tui.flash.bind(tui);
		tui.flash = (message: string, durationMs?: number) => {
			calls.push([message, durationMs]);
			originalFlash(message, 10);
		};
		tui.addChild(new Text("alpha\nbeta", 0, 0));
		tui.start();
		await terminal.waitForRender();
		terminal.sendInput("\x1b[<0;1;1M");
		terminal.sendInput("\x1b[<32;3;1M");
		terminal.sendInput("\x1b[<0;3;1m");
		await terminal.waitForRender();
		await new Promise((resolve) => setTimeout(resolve, 20));
		tui.stop();
		return calls;
	}
	assert.deepEqual(await run(async () => true), [["Copied!", undefined]],
		"success relies on the default duration");
	assert.deepEqual(await run(async () => "CLIPBOARD_DENIED"), [["CLIPBOARD_DENIED", 5000]],
		"copy errors stay visible longer");
});
