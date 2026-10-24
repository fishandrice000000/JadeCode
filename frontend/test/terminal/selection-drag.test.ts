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
	tui.stop();
});

test("copyOnSelect=false keeps the drag selection without copying", async () => {
	const { copied, tui, terminal } = await drag({ copyOnSelect: false });
	await selectAlphaBeta(terminal);
	assert.deepEqual(copied, []);
	assert.equal(tui.hasActiveSelection(), true);
	assert.ok(terminal.getViewport().every((line) => !line.includes("Copied!")));
	copied.length = 0;
	assert.equal(await tui.copyActiveSelectionToClipboard(), true, "programmatic copy still works");
	assert.deepEqual(copied, ["alpha\nbeta"]);
	tui.stop();
});
