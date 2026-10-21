package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * 提示/通知排版：普通短状态、持久警告与真实辅助失败分开级别，不把失败改成成功。
 *
 * <p>普通 flash 可复用状态行；恢复/持久化/Memory 等真实问题进入可查转录。原诊断作为引用
 * 保留，外层英文标签由 {@link UiMessages} 提供；正常恢复不增加固定警告。本类只排版已有
 * 事实，不触发通知服务或网络副作用。</p>
 */
public final class NoticeView {
    /** @return 带级别样式的通知行。 */
    public List<StyledLine> render(String text, boolean error, RenderContext context, int width) {
        var out = new ArrayList<StyledLine>();
        String label = error ? UiMessages.ERROR : UiMessages.WARNING;
        out.add(StyledLine.of(DisplayWidth.ellipsize(label, width),
                context.theme().style(error ? "error" : "warning")));
        out.addAll(StyledLine.wrap(List.of(new Span(text == null ? "" : text,
                context.theme().style("text"), null)), Math.max(1, width)));
        return out;
    }

    /** @return 状态行 flash（不进入转录）；空文本返回空。 */
    public List<StyledLine> flash(String text, TuiState state, RenderContext context, int width) {
        if (text == null || text.isBlank()) return List.of();
        return List.of(StyledLine.of(DisplayWidth.ellipsize(text, width), context.theme().style("muted")));
    }
}
