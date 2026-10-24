import { readFileSync } from "node:fs";
import { visibleWidth } from "@earendil-works/pi-tui";
import type { ResolvedTheme, Role } from "../theme/theme-resolver.ts";

/**
 * 欢迎区品牌：用户原始 mascot + 六行 JADE CODE 字标 + 真实版本。
 *
 * 档位沿用旧 BrandingView 的产品契约：
 * - FULL：mascot 与六行字标并排（宽 ≥ 89，高 ≥ 22）；
 * - COMPACT：mascot + 单行 JADE CODE（宽 ≥ 29，高 ≥ 22）；
 * - LINE：只保留版本与 tagline（窄屏/低高度，优先给聊天与输入区）。
 *
 * 颜色全部来自同一主题：mascot 边框 brandFrame、字身 brandPrimary、圆点/下划线 brandAccent；
 * 字标 █ 使用 brandBannerBody，轮廓使用 brandBannerShadow。NO_COLOR 只去掉属性，不删字符。
 */
const ASSETS = new URL("../../assets/branding/", import.meta.url);
const MASCOT_WIDTH = 17;
const BANNER_WIDTH = 69;
const FULL_WIDTH = MASCOT_WIDTH + 3 + BANNER_WIDTH;
const COMPACT_WIDTH = 29; // mascot 17 + 间隔 + 单行字标，保持旧 BrandingView 的测量值

function loadAsset(name: string): string[] {
	try {
		return readFileSync(new URL(name, ASSETS), "utf8").replace(/\n+$/, "").split("\n");
	} catch {
		return [];
	}
}

export class BrandingView {
	private readonly mascot = loadAsset("mascot.txt");
	private readonly banner = loadAsset("banner.txt");

	/** @returns 当前终端尺寸对应的档位。 */
	mode(width: number, rows: number): "FULL" | "COMPACT" | "LINE" {
		if (rows < 22) return "LINE";
		if (width >= FULL_WIDTH) return "FULL";
		if (width >= COMPACT_WIDTH) return "COMPACT";
		return "LINE";
	}

	/**
	 * @param width 可用列宽
	 * @param rows 终端行数；不足 22 行时只显示两行品牌，避免挤占聊天/输入区
	 * @param version 后端真实版本（不是写死的文案）
	 */
	render(theme: ResolvedTheme, width: number, rows: number, version: string): string[] {
		const mode = this.mode(width, rows);
		if (mode === "LINE") return this.line(theme, width, version);
		if (mode === "COMPACT") {
			const lines = this.mascot.map((line, index) => {
				const styled = this.styleMascotLine(theme, line);
				if (index !== 0) return styled;
				// 旧紧凑档保留 3 列间隔，使首行落在 29 列预算内。
				const wordmark = theme.bold(theme.style("brandBannerBody", "JADE CODE"));
				return `${styled}   ${wordmark}`;
			});
			lines.push(theme.style("text", `JadeCode v${version}`));
			lines.push(theme.style("dim", this.fit("coding agent · JadeCode", width)));
			return lines;
		}
		const lines: string[] = [];
		for (let index = 0; index < 6; index++) {
			const mascot = this.styleMascotLine(theme, this.mascot[index] ?? "");
			const banner = this.styleBannerLine(theme, this.banner[index] ?? "");
			lines.push(`${mascot}${" ".repeat(3)}${banner}`);
		}
		lines.push(theme.style("text", `JadeCode v${version}`));
		lines.push(theme.style("dim", this.fit(`coding agent · JadeCode`, width)));
		return lines;
	}

	private line(theme: ResolvedTheme, width: number, version: string): string[] {
		return [
			theme.style("text", this.fit(`JadeCode v${version}`, width)),
			theme.style("dim", this.fit("coding agent · JadeCode", width)),
		];
	}

	private fit(text: string, width: number): string {
		if (visibleWidth(text) <= width) return text;
		let result = "";
		for (const char of text) {
			if (visibleWidth(result + char) > width) break;
			result += char;
		}
		return result;
	}

	/** mascot 边框/字身/橙色点缀按字符分角色，保持原字符与位置。 */
	private styleMascotLine(theme: ResolvedTheme, line: string): string {
		if (!line) return "";
		let out = "";
		for (const char of line) {
			if ("┏━┓┃┣┫┗┛".includes(char)) out += theme.style("brandFrame", char);
			else if (char === "●" || char === "▸" || char === "_" || char === "$") out += theme.style("brandAccent", char);
			else if ("ready".includes(char) && line.includes("ready")) out += theme.style("text", char);
			else if ("jade".includes(char) && line.includes("jade")) out += theme.style("text", char);
			else if ("JADE".includes(char) && line.includes("JADE")) out += theme.style("brandPrimary", char);
			else out += theme.style("brandPrimary", char);
		}
		return out;
	}

	/** 字标：█ 为字身，其余框线字符为阴影；保留原始空格与列宽。 */
	private styleBannerLine(theme: ResolvedTheme, line: string): string {
		const padded = line.padEnd(BANNER_WIDTH, " ");
		let out = "";
		for (const char of padded) {
			if (char === "█") out += theme.style("brandBannerBody", char);
			else if ("╗╔╚╝═║╠╣╦╩╬─│".includes(char)) out += theme.style("brandBannerShadow", char);
			else out += char;
		}
		return out;
	}
}
