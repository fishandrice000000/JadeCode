/**
 * 主题文件的结构解析（v1 单外观 / v2 深+浅双变体）。
 *
 * 只保存“文件里写了什么”：不展开变量、不绑定终端、不生成 ANSI。这样坏主题可以在读取阶段
 * 明确失败，并保留上一份有效快照，而不会让半个主题进入渲染。
 *
 * v1: `{ name?, appearance?, vars?, colors }` → 唯一变体键 "single"
 * v2: `{ schemaVersion: 2, name?, variants: { dark: {...}, light: {...} } }`
 */

export type RawColor = { text: string; index: number | null };

export interface RawVariant {
	name: string;
	appearance: "dark" | "light";
	vars: Record<string, RawColor>;
	colors: Record<string, RawColor>;
	panelBgMix: number;
}

export interface ThemeDocument {
	schemaVersion: 1 | 2;
	name: string;
	variants: Record<string, RawVariant>;
}

export const SINGLE = "single";
export const DARK = "dark";
export const LIGHT = "light";
export const DEFAULT_PANEL_BG_MIX = 0.1;

export function parseThemeDocument(json: string): ThemeDocument {
	let root: unknown;
	try {
		root = JSON.parse(json);
	} catch (error) {
		throw new Error(`Cannot parse theme JSON: ${(error as Error).message}`);
	}
	if (!isRecord(root)) throw new Error("Cannot parse theme JSON: root must be an object");
	const name = typeof root.name === "string" && root.name.length > 0 ? root.name : "custom";
	const version = root.schemaVersion;
	if (version !== undefined && version !== null) {
		if (typeof version !== "number" || !Number.isInteger(version)) {
			throw new Error("Theme schemaVersion must be a number");
		}
		if (version !== 2) throw new Error(`Unsupported theme schemaVersion: ${version}`);
		return parseV2(name, root);
	}
	// 有 variants 却没有版本号几乎一定是漏写；不能静默降级成 v1 后只画一半。
	if ("variants" in root) throw new Error("Theme has variants but no schemaVersion: 2");
	return parseV1(name, root);
}

function parseV2(name: string, root: Record<string, unknown>): ThemeDocument {
	const variantsNode = root.variants;
	if (!isRecord(variantsNode)) throw new Error("Theme v2 requires a variants object");
	const variants: Record<string, RawVariant> = {};
	for (const key of [DARK, LIGHT]) {
		const node = variantsNode[key];
		if (!isRecord(node)) throw new Error(`Theme v2 requires both a dark and a light variant (missing ${key})`);
		const appearance = (key === "light" ? "light" : "dark") as "dark" | "light";
		variants[key] = parseVariant(key, appearance, node);
	}
	return { schemaVersion: 2, name, variants };
}

function parseV1(name: string, root: Record<string, unknown>): ThemeDocument {
	if (!isRecord(root.colors)) throw new Error("Theme requires a colors object");
	const appearance = root.appearance === "light" ? "light" : "dark";
	return { schemaVersion: 1, name, variants: { [SINGLE]: parseVariant(SINGLE, appearance, root) } };
}

function parseVariant(key: string, appearance: "dark" | "light", node: Record<string, unknown>): RawVariant {
	const vars = parseColors(node.vars, `variants.${key}.vars`, false);
	const colors = parseColors(node.colors, `variants.${key}.colors`, true);
	let panelBgMix = DEFAULT_PANEL_BG_MIX;
	const derivations = node.derivations;
	if (derivations !== undefined) {
		if (!isRecord(derivations)) throw new Error(`variants.${key}.derivations must be an object`);
		if (derivations.panelBgMix !== undefined) {
			const value = derivations.panelBgMix;
			if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || value > 1) {
				throw new Error(`variants.${key}.derivations.panelBgMix must be between 0 and 1`);
			}
			panelBgMix = value;
		}
	}
	return { name: key, appearance, vars, colors, panelBgMix };
}

function parseColors(node: unknown, path: string, required: boolean): Record<string, RawColor> {
	if (node === undefined) {
		if (required) throw new Error(`Theme requires ${path}`);
		return {};
	}
	if (!isRecord(node)) throw new Error(`Theme ${path} must be an object`);
	const result: Record<string, RawColor> = {};
	for (const [key, value] of Object.entries(node)) {
		if (typeof value === "number") {
			if (!Number.isInteger(value) || value < 0 || value > 255) {
				throw new Error(`${path}.${key} index must be an integer 0-255`);
			}
			result[key] = { text: "", index: value };
			continue;
		}
		if (typeof value !== "string") throw new Error(`${path}.${key} must be a string or an index color`);
		result[key] = { text: value.trim(), index: null };
	}
	return result;
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}
