import assert from "node:assert/strict";
import { test } from "node:test";
import { isGlobalActionPress } from "../../src/terminal/global-keys.ts";

test("global actions accept a new PRESS but not Kitty repeat/release", () => {
	// 生产 Pi 的 Kitty 编码：Ctrl+C press / repeat / release。
	assert.equal(isGlobalActionPress("\x1b[99;5u"), true);
	assert.equal(isGlobalActionPress("\x1b[99;5:2u"), false);
	assert.equal(isGlobalActionPress("\x1b[99;5:3u"), false);
	// 普通字符仍可作为新的全局按键（编辑器/页面决定是否消费）。
	assert.equal(isGlobalActionPress("a"), true);
	assert.equal(isGlobalActionPress("\r"), true);
});
