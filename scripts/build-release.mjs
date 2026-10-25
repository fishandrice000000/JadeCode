#!/usr/bin/env node
/**
 * 成套构建：固定依赖 → 编译 Pi vendor 与 Jade 前端 → 计算 buildId → 构建 JAR → 装配 target/frontend
 * 与 dist/jadecode。Java-only `mvn package` 仍是 unpaired，交互启动会明确拒绝。
 *
 * 产物身份来自前端源/vendor 补丁/lock/tsconfig/协议/主题品牌资产的内容哈希；同一脚本同时写入
 * frontend-manifest.json 与 Maven 资源，因此 JAR 与前端必须来自同一次构建。
 *
 * 选项：--no-install 跳过 npm ci（离线复验用）；--skip-tests 只跳过 Maven 测试，不能当验收。
 */
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const frontend = path.join(root, "frontend");
const args = new Set(process.argv.slice(2));
const skipInstall = args.has("--no-install");
const skipTests = args.has("--skip-tests");
const protocolVersion = 1;

const run = (command, commandArgs, cwd = root) => {
	execFileSync(command, commandArgs, { cwd, stdio: "inherit" });
};
const read = (file) => fs.readFileSync(file);
const sha = (buffer) => createHash("sha256").update(buffer).digest("hex");

function walk(dir, base = dir, out = []) {
	if (!fs.existsSync(dir)) return out;
	for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
		const full = path.join(dir, entry.name);
		if (entry.name === "node_modules" || entry.name === "dist" || entry.name === "dist-test" || entry.name === "provenance.json") continue;
		if (entry.isDirectory()) walk(full, base, out);
		else out.push(path.relative(base, full));
	}
	return out;
}

function buildId() {
	const inputs = [
		...walk(path.join(frontend, "src")).map((file) => path.join("src", file)),
		...walk(path.join(frontend, "assets")).map((file) => path.join("assets", file)),
		...walk(path.join(frontend, "vendor")).map((file) => path.join("vendor", file)),
		"package.json", "package-lock.json", "tsconfig.json", "tsconfig.test.json",
		"../protocol/tui-bridge-v1.schema.json",
	];
	const digest = createHash("sha256");
	for (const relative of inputs.sort()) {
		const file = path.resolve(frontend, relative);
		if (!fs.existsSync(file)) continue;
		digest.update(relative).update("\0").update(sha(read(file))).update("\n");
	}
	return digest.digest("hex");
}

function copySelected(from, to, names) {
	fs.mkdirSync(to, { recursive: true });
	for (const name of names) {
		const source = path.join(from, name);
		if (!fs.existsSync(source)) continue;
		const target = path.join(to, name);
		if (fs.statSync(source).isDirectory()) copyDirectory(source, target);
		else fs.copyFileSync(source, target);
	}
}

function copyDirectory(from, to, filter = () => true) {
	fs.mkdirSync(to, { recursive: true });
	for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
		if (!filter(entry.name)) continue;
		const source = path.join(from, entry.name);
		const target = path.join(to, entry.name);
		if (entry.isDirectory()) copyDirectory(source, target, filter);
		else if (entry.isSymbolicLink()) fs.symlinkSync(fs.readlinkSync(source), target);
		else fs.copyFileSync(source, target);
	}
}

if (!skipInstall) run("npm", ["ci", "--no-audit", "--no-fund"], frontend);
run("npm", ["run", "build"], frontend);
const id = buildId();
const compiler = JSON.parse(read(path.join(frontend, "node_modules/typescript/package.json"))).version;
const nodeEngine = JSON.parse(read(path.join(frontend, "package.json"))).engines.node;
const builtAt = new Date().toISOString();

run("mvn", ["-q", "-o", ...(skipTests ? ["-DskipTests"] : []), "clean", "package",
	`-Djade.buildId=${id}`, "-Djade.paired=true", `-Djade.protocolVersion=${protocolVersion}`]);

// target/frontend：开发 JAR 与发行 JAR 共用同一套布局。
const targetFrontend = path.join(root, "target/frontend");
fs.rmSync(targetFrontend, { recursive: true, force: true });
fs.mkdirSync(targetFrontend, { recursive: true });
for (const file of ["package.json", "package-lock.json"]) fs.copyFileSync(path.join(frontend, file), path.join(targetFrontend, file));
copyDirectory(path.join(frontend, "dist"), path.join(targetFrontend, "dist"));
copyDirectory(path.join(frontend, "assets"), path.join(targetFrontend, "assets"));
const vendorDist = path.join(frontend, "vendor/pi-tui");
copySelected(vendorDist, path.join(targetFrontend, "vendor/pi-tui"),
	["dist", "package.json", "LICENSE", "README.md", "native"]);
const productionDependencies = ["marked", "get-east-asian-width", "grok-mermaid", "highlight.js"];
/** npm 包的许可文件大小写并不统一；缺少许可必须阻断装配，不能静默漏发。 */
function dependencyLicense(name) {
	const directory = path.join(frontend, "node_modules", name);
	const file = fs.readdirSync(directory).find((entry) => /^licen[cs]e(?:\.(?:md|txt))?$/i.test(entry));
	if (!file) throw new Error(`Missing license for production dependency: ${name}`);
	return file;
}
for (const dependency of productionDependencies) {
	copyDirectory(path.join(frontend, "node_modules", dependency), path.join(targetFrontend, "node_modules", dependency),
		(name) => name !== "node_modules");
}
fs.mkdirSync(path.join(targetFrontend, "node_modules/@earendil-works"), { recursive: true });
copySelected(vendorDist, path.join(targetFrontend, "node_modules/@earendil-works/pi-tui"),
	["dist", "package.json", "LICENSE", "native"]);

/** 成套输出指纹包含编译 JS 与生产依赖；校验器不能只比较两个 buildId 字符串。 */
function releaseFiles(directory, prefix = "") {
	return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
		const relative = path.join(prefix, entry.name);
		return entry.isDirectory() ? releaseFiles(path.join(directory, entry.name), relative)
			: [{ path: relative, sha256: sha(read(path.join(directory, entry.name))) }];
	});
}

const assetFiles = walk(path.join(frontend, "assets")).sort();
const manifest = {
	buildId: id,
	protocolVersion,
	entry: "dist/main.js",
	compiler: `typescript ${compiler}`,
	nodeEngine,
	builtAt,
	projectVersion: (read(path.join(root, "pom.xml")).toString().match(/<version>([^<]+)<\/version>/) ?? [])[1] ?? "unknown",
	dependencies: productionDependencies,
	native: [path.join(frontend, "vendor/pi-tui/native/linux/prebuilds/linux-x64/linux-platform-x11.node")]
		.map((file) => ({ path: path.relative(frontend, file), sha256: sha(read(file)) })),
	assets: assetFiles.map((file) => ({ path: file, sha256: sha(read(path.join(frontend, "assets", file))) })),
	licenses: ["vendor/pi-tui/LICENSE", ...productionDependencies.map((name) => `node_modules/${name}/${dependencyLicense(name)}`)],
	files: releaseFiles(targetFrontend).sort((a, b) => a.path.localeCompare(b.path)),
};
fs.writeFileSync(path.join(targetFrontend, "frontend-manifest.json"), JSON.stringify(manifest, null, 2) + "\n");

// dist/jadecode：wrapper + lib/jadecode.jar + lib/frontend + licenses。
const dist = path.join(root, "dist/jadecode");
fs.rmSync(dist, { recursive: true, force: true });
fs.mkdirSync(path.join(dist, "lib"), { recursive: true });
fs.copyFileSync(path.join(root, "target/jadecode.jar"), path.join(dist, "lib/jadecode.jar"));
copyDirectory(targetFrontend, path.join(dist, "lib/frontend"));
fs.mkdirSync(path.join(dist, "licenses"), { recursive: true });
fs.copyFileSync(path.join(frontend, "vendor/pi-tui/LICENSE"), path.join(dist, "licenses/pi-tui-MIT.txt"));
for (const dependency of productionDependencies) {
	const file = path.join(frontend, "node_modules", dependency, dependencyLicense(dependency));
	fs.copyFileSync(file, path.join(dist, "licenses", `${dependency}.txt`));
}
fs.writeFileSync(path.join(dist, "jade"), `#!/bin/sh
# JadeCode 成套发行入口：相对自身位置定位 JAR，不依赖 cwd 或源码目录。
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_HOME="\${JAVA21_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export JAVA_HOME
exec "$JAVA_HOME/bin/java" -jar "$DIR/lib/jadecode.jar" "$@"
`);
fs.chmodSync(path.join(dist, "jade"), 0o755);
console.log(JSON.stringify({ buildId: id, targetFrontend, dist, builtAt }, null, 2));
