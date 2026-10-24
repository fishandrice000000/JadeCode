import assert from "node:assert/strict";
import { test } from "node:test";
import { InputEvidence } from "../../src/terminal/input-evidence.ts";
import { PresentedEvidence } from "../../src/terminal/presented-evidence.ts";

test("a frame generated before invalidation is not published with a new epoch", () => {
	const evidence = new InputEvidence();
	const published: number[] = [];
	const deliver = (frame: { frameId: number }): void => { published.push(frame.frameId); };
	const presented = new PresentedEvidence(evidence, () => {});
	const epoch = presented.currentEpoch();
	presented.invalidate();
	const delivered = presented.publishedAt(80, 24, epoch, deliver);
	assert.equal(delivered, false);
	assert.deepEqual(published, []);
	assert.equal(presented.publishedAt(80, 24, presented.currentEpoch(), deliver), true);
});
