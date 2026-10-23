import { readFileSync } from "node:fs";
import type { ResolvedTheme } from "../theme/theme-resolver.ts";

/**
 * 欢迎区品牌：用户原始 mascot + 六行 JADE CODE 字标 + 真实版本。
 *
 * 宽度/高度不足时按原 Java BrandingView 的档位降级（并排 → mascot+单行 → 紧凑），
 * quietStartup 只隐藏资源提示，不隐藏品牌。所有颜色来自当前主题角色。
 */

const ASSETS = new URL("../../assets/branding/", import.meta.url);

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

	/**
	 * @param width 可用列宽
	 * @param rows 终端行数；不够时降档
	 * @param version 后端真实版本（不是写死的文案）
	 */
	render(theme: ResolvedTheme, width: number, rows: number, version: string): string[] {
		const mascotWidth = Math.max(...this.mascot.map((line) => line.length), 0);
		const bannerWidth = Math.max(...this.banner.map((line) => line.length), 0);
		const wide = mascotWidth + 3 + bannerWidth;
		const compact = mascotWidth + 1 + "JADE CODE".length;
		const lines: string[] = [];
		if (rows < 14 || width < compact) {
			lines.push(theme.bold(theme.style("brandPrimary", "JADE CODE")) + "  " + theme.style("dim", version));
			return lines;
		}
		if (width >= wide && rows >= 16) {
			for (let i = 0; i < Math.max(this.mascot.length, this.banner.length); i++) {
				lines.push(`${theme.style("brandPrimary", this.mascot[i] ?? "")}${" ".repeat(3)}${theme.style("brandBannerBody", this.banner[i] ?? "")}`);
			}
			lines.push("");
			lines.push(this.tagline(theme, width, version));
			return lines;
		}
		for (const line of this.mascot) lines.push(theme.style("brandPrimary", line));
		lines.push("");
		lines.push(`${theme.bold(theme.style("brandPrimary", "JADE CODE"))}  ${theme.style("dim", version)}`);
		return lines;
	}

	private tagline(theme: ResolvedTheme, width: number, version: string): string {
		const text = `JadeCode ${version} · coding agent · Enter submits, Shift+Enter adds a line`;
		return theme.style("dim", text.slice(0, Math.max(0, width)));
	}
}
