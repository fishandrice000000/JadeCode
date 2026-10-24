import assert from "node:assert/strict";
import { test } from "node:test";
import { UiEventBarrier } from "../../src/app/ui-barrier.ts";

test("events arriving with bootstrap in one batch are consumed after UI init, in order", () => {
	const barrier = new UiEventBarrier<string>();
	const consumed = new Array<string>();
	// 模拟一次 socket 写入里的 bootstrap + 完整快照。
	// bootstrap 不走屏障（Application 会立即应用设置）；它只为说明同一批次还有 UI 事件。
	consumed.push("bootstrap");
	barrier.offer("snapshot.begin", (event: string) => { consumed.push(event); });
	barrier.offer("snapshot.chunk", (event: string) => { consumed.push(event); });
	barrier.offer("snapshot.end", (event: string) => { consumed.push(event); });
	assert.deepEqual(consumed, ["bootstrap"], "UI-dependent events must not run before the editor/host exist");
	assert.equal(barrier.pendingCount(), 3);
	barrier.activate(function (event: string) { consumed[consumed.length] = event; });
	assert.deepEqual(consumed, ["bootstrap", "snapshot.begin", "snapshot.chunk", "snapshot.end"]);
	// 之后就绪的事件直接消费，不重复、不丢失。
	barrier.offer("stream.delta", (event: string) => { consumed.push(event); });
	assert.deepEqual(consumed.slice(-1), ["stream.delta"]);
});
