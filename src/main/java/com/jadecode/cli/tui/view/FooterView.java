package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.TuiState;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.cli.tui.UsageFormatter;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;

/**
 * 页脚：目录(分支)•会话名 + 统计左/模型右，含 C17 紧凑权限档位与 C20 token 规则。
 *
 * <p>统计只取 SessionUsage/ContextUsage 只读快照；100000→100K，缓存 R/W 与 CH 只在有真实
 * 分母时显示；无费用/订阅数据不画。窄屏按列裁切：优先保留会话/目录，模型右侧在空间不足时
 * 省略而不是挤掉统计。HOME 缩写只改显示，不改变实际路径。</p>
 */
public final class FooterView {
    /** @return 两行页脚；极窄窗口可只剩一行。 */
    public List<StyledLine> render(TuiState state, RenderContext context, int width) {
        int columns = Math.max(1, width);
        var result = new ArrayList<StyledLine>();
        String directory = abbreviateHome(state.directory);
        String sessionTitle = state.session == null || state.session.title().isBlank()
                || state.session.title().equals("新会话") ? "New session" : state.session.title();
        String first = directory
                + (state.branch == null || state.branch.isBlank() ? "" : " (" + state.branch + ")")
                + " • " + UiMessages.SESSION_LABEL + " " + sessionTitle;
        result.add(StyledLine.of(DisplayWidth.ellipsize(first, columns), context.theme().style("muted")));
        if (context.height() < 4) return result;
        String permission = UiMessages.permission(state.permissionProfile);
        var left = new ArrayList<String>();
        if (state.session != null) {
            var total = state.session.usage().total();
            String tokens = UsageFormatter.tokenCounts(total);
            if (!tokens.isBlank()) left.add(tokens);
            String cache = UsageFormatter.cacheCounts(total);
            if (!cache.isBlank()) left.add(cache);
            String hit = UsageFormatter.cacheHit(total);
            if (!hit.isBlank()) left.add(hit);
            String ctx = UsageFormatter.context(state.session.context());
            if (!ctx.isBlank()) left.add("ctx " + ctx);
            String missing = UsageFormatter.missing(total);
            if (!missing.isBlank()) left.add(missing);
        }
        if (!permission.isBlank()) left.add(permission);
        String leftText = String.join(" · ", left);
        String right = state.model == null ? "" : state.model;
        String second;
        if (right.isBlank()) second = DisplayWidth.ellipsize(leftText, columns);
        else {
            int rightWidth = Math.min(DisplayWidth.width(right), Math.max(1, columns / 2));
            String rightCropped = DisplayWidth.ellipsize(right, rightWidth);
            int leftSpace = columns - DisplayWidth.width(rightCropped) - 1;
            if (leftSpace < 8) second = DisplayWidth.ellipsize(rightCropped, columns);
            else {
                String leftCropped = DisplayWidth.ellipsize(leftText, leftSpace);
                int gap = Math.max(1, columns - DisplayWidth.width(leftCropped) - DisplayWidth.width(rightCropped));
                second = leftCropped + " ".repeat(gap) + rightCropped;
            }
        }
        result.add(StyledLine.of(DisplayWidth.take(second, columns), context.theme().style("dim")));
        return result;
    }

    /** 仅显示层缩写 HOME；不改变真实工作目录。 */
    static String abbreviateHome(String path) {
        if (path == null || path.isBlank()) return "";
        String home = System.getProperty("user.home", "");
        if (!home.isBlank() && path.startsWith(home)) return "~" + path.substring(home.length());
        return path;
    }
}
