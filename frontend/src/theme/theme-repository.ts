import * as fs from "node:fs";
import * as path from "node:path";
import { parseThemeDocument, type ThemeDocument } from "./theme-document.ts";

/**
 * 主题文件的读取与监听。
 *
 * 内置主题来自 `frontend/assets/themes/jadecode.json`（唯一运行资产）；自定义主题来自用户
 * 配置目录下的 `themes/*.json`。这里不写主配置、不改用户文件；坏文件不覆盖上一份有效快照，
 * 只把错误交给调用方显示。
 */

export interface ThemeEntry {
	name: string;
	path: string;
	builtin: boolean;
}

export interface ThemeLoadResult {
	name: string;
	document: ThemeDocument;
	source: string;
	/** 文件内容指纹；用于判断 watch 事件是否带来新内容。 */
	digest: string;
}

export class ThemeRepository {
	private readonly builtinDirectory: string;
	private readonly customDirectory: string;
	private readonly cache = new Map<string, ThemeLoadResult>();

	constructor(builtinDirectory: string, customDirectory: string) {
		this.builtinDirectory = builtinDirectory;
		this.customDirectory = customDirectory;
	}

	/** @returns 可选主题名：内置 jadecode + 用户目录中的 json 文件（不含扩展名）。 */
	list(): ThemeEntry[] {
		const entries: ThemeEntry[] = [{ name: "jadecode", path: path.join(this.builtinDirectory, "jadecode.json"), builtin: true }];
		let names: string[] = [];
		try {
			names = fs.readdirSync(this.customDirectory).filter((name) => name.endsWith(".json")).sort();
		} catch {
			// 自定义目录不存在是合法状态：只有内置主题。
		}
		for (const file of names) {
			const name = file.slice(0, -".json".length);
			if (name === "jadecode") continue;
			entries.push({ name, path: path.join(this.customDirectory, file), builtin: false });
		}
		return entries;
	}

	/**
	 * 读取并解析主题；同一文件内容未变时复用缓存（主题预览/重绘不重复解析）。
	 */
	load(name: string): ThemeLoadResult {
		const entry = this.list().find((item) => item.name === name);
		if (!entry) throw new Error(`Unknown theme: ${name}`);
		const source = fs.readFileSync(entry.path, "utf8");
		const digest = simpleDigest(source);
		const cached = this.cache.get(entry.path);
		if (cached && cached.digest === digest) return cached;
		const document = parseThemeDocument(source);
		const result: ThemeLoadResult = { name, document, source, digest };
		this.cache.set(entry.path, result);
		return result;
	}

	/**
	 * 监听当前主题文件（仅自定义文件）。回调收到新文档或错误；调用方用票据丢弃迟到结果。
	 * @returns 取消监听函数
	 */
	watch(name: string, onChange: (result: ThemeLoadResult | Error) => void): () => void {
		const entry = this.list().find((item) => item.name === name);
		if (!entry || entry.builtin) return () => {};
		try {
			// 编辑器常用 rename 原子替换文件；监听目录而非旧 inode，连续保存才不会失联。
			const watcher = fs.watch(path.dirname(entry.path), { persistent: false }, (_event, filename) => {
				if (filename !== null && filename.toString() !== path.basename(entry.path)) return;
				try {
					onChange(this.load(name));
				} catch (error) {
					onChange(error as Error);
				}
			});
			watcher.on("error", (error) => onChange(error));
			return () => watcher.close();
		} catch (error) {
			// 订阅建立失败也要报告；延到下一微任务，让 Controller 先安装本次票据。
			queueMicrotask(() => onChange(error as Error));
			return () => {};
		}
	}
}

function simpleDigest(value: string): string {
	// 只用于判断内容是否变化；不是安全哈希。
	let hash = 2166136261;
	for (let i = 0; i < value.length; i++) {
		hash ^= value.charCodeAt(i);
		hash = Math.imul(hash, 16777619);
	}
	return (hash >>> 0).toString(16);
}
