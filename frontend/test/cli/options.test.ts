import test from "node:test";
import assert from "node:assert/strict";
import { parseOptions, UsageError } from "../../src/cli/options.ts";
import { sessionRequest } from "../../src/cli/run.ts";

test("blank UI, positional prompts and literal arguments retain their meaning", () => {
	assert.equal(parseOptions([]).prompt, "");
	assert.equal(parseOptions(["run", "hello world"]).prompt, "run hello world");
	assert.equal(parseOptions(["--", "--headless", "a\n b"]).prompt, "--headless a\n b");
	assert.equal(parseOptions(["--", "--model-config=x"]).prompt, "--model-config=x");
});

test("headless, resume and model path contracts migrate from Java", () => {
	assert.equal(parseOptions(["--headless", "--permission=read-only", "hi"]).permission, "read-only");
	assert.equal(parseOptions(["--resume"]).resume, "");
	assert.equal(parseOptions(["--resume=abc"]).resume, "abc");
	for (const args of [["--headless"], ["--headless", "   "], ["--resume", "abc"], ["--resume=abc", "prompt"], ["--resume", "--headless", "x"], ["--resume="], ["--unknown"], ["--permission=no"], ["--model-config"], ["--model-config="], ["--model-config", "--headless", "x"], ["--model-config=a", "--model-config=b"]]) assert.throws(() => parseOptions(args), UsageError);
	for (const args of [["--model-config", "profiles/model.json", "--headless", "hi"], ["--model-config=profiles/model.json", "--headless", "hi"]]) assert.equal(parseOptions(args).modelConfig, "profiles/model.json");
	assert.equal(parseOptions(["--model-config=missing", "--help"]).help, true);
	assert.equal(parseOptions(["--model-config=missing", "--version"]).version, true);
});

test("management and workspace options reject conflicting side effects", () => {
	for (const args of [["--sessions", "--service-stop"], ["--isolated", "--workspace", "/x"], ["--attach", "x", "prompt"], ["--remote", "host"], ["--upload-snapshot", "/x"], ["--download-artifact", "x", "--workspace-id", "w"], ["--output", "x"], ["--create-workspace", "--headless", "x"]]) assert.throws(() => parseOptions(args), UsageError);
	assert.equal(parseOptions(["--remote", "host", "--sessions"]).list, true);
});

test("remote paths remain execution-machine paths and are never uploaded", () => {
	const request = sessionRequest(parseOptions(["--remote", "host", "--workspace", "relative/project", "--model-config", "profiles/model.json", "hello"]));
	assert.equal(request.workspace, "relative/project");
	assert.equal(request.modelConfig, "profiles/model.json");
	assert.equal(request.prompt, "hello");
	assert.ok(typeof request.submissionId === "string");
});
