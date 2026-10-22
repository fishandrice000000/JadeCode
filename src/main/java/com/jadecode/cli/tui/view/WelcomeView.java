package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * JadeCode 欢迎区：Pi 式紧凑帮助 + 真实资源展开，身份/版本/模型均为真实值。
 *
 * <p>只展示当前实际存在的品牌/版本/资源；不复制 Pi 更新公告、彩蛋或不存在扩展。
 * 默认也不再铺四行固定快捷帮助（S43 批准显示差异）；帮助仍由 /hotkeys 与 / 提供。
 * `quietStartup` 进一步隐藏资源提示，只保留品牌与 coding agent。Ctrl+O 展开真实资源
 * （技能/工作区/模型/权限），不会伪造加载项。</p>
 */
public final class WelcomeView {
    /**
     * @param version JadeCode 版本
     * @param model 当前模型（真实 metadata；未知为空）
     * @param directory 工作区
     * @param resources 已加载资源名（技能/项目说明等），为空则省略
     * @param quiet 是否安静启动
     */
    public List<StyledLine> render(String version, String model, String directory, List<String> resources,
            boolean quiet, RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        String versionLabel = " " + (version == null || version.isBlank() ? "" : "v" + version);
        out.add(StyledLine.of(DisplayWidth.ellipsize("███  JadeCode" + versionLabel, width),
                context.theme().style("accent")));
        out.add(StyledLine.of(DisplayWidth.ellipsize("█ █  " + UiMessages.TAGLINE, width),
                context.theme().style("muted")));
        if (quiet) return out;
        // S43：默认标题不再输出 fixed quickHelp 四行；帮助保留在 /hotkeys 和 / 命令候选。
        out.add(StyledLine.empty());
        boolean hasResources = model != null && !model.isBlank() || directory != null && !directory.isBlank()
                || resources != null && !resources.isEmpty();
        out.add(StyledLine.of(DisplayWidth.ellipsize(hasResources
                ? "Press Ctrl+O to show loaded resources." : "Type a request or / for commands.", width),
                context.theme().style("dim")));
        return out;
    }

    /** 展开的资源区；只列出实际值。 */
    public List<StyledLine> expanded(String model, String directory, List<String> resources,
            RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        if (model != null && !model.isBlank())
            out.add(StyledLine.of(DisplayWidth.ellipsize("Model · " + model, width), context.theme().style("muted")));
        if (directory != null && !directory.isBlank())
            out.add(StyledLine.of(DisplayWidth.ellipsize("Workspace · " + directory, width), context.theme().style("muted")));
        if (resources != null) for (String resource : resources)
            out.add(StyledLine.of(DisplayWidth.ellipsize("Resource · " + resource, width), context.theme().style("dim")));
        return out;
    }
}
