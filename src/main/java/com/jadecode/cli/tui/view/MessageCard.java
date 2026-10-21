package com.jadecode.cli.tui.view;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.cli.tui.UiMessages;
import com.jadecode.cli.tui.UsageFormatter;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.tui.components.Markdown;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * C13=B 批准的消息卡容器：Request/Reply/Thinking 保留标题横线与真实状态，内部按 Pi 规则排版。
 *
 * <p>标题包含 actor、状态（STREAMING/DONE/INCOMPLETE/HISTORICAL）和真实整轮耗时；不得用
 * DONE 假冒历史成功，也不补实时计时。Request 使用整宽背景与 padding，Reply/Thinking 使用
 * Markdown；Thinking 全局显隐只缩短显示，原文完整保留。工具卡委托专用 renderer，不套普通
 * Markdown 容器。此卡容器是明确批准差异，不能声称与 Pi 无标题消息完全相同。</p>
 */
public final class MessageCard {
    private final Markdown markdown = new Markdown();

    /** @return 排版一条转录条目；空条目返回空列表。 */
    public List<StyledLine> render(TranscriptEntry entry, RenderContext context, int width,
            boolean thinkingVisible, String actor) {
        var out = new ArrayList<StyledLine>();
        if (entry.kind() == TranscriptEntry.Kind.TOOL) {
            // 工具卡由 ConversationView 用 ToolRendererRegistry 按可信身份渲染；这里不作为
            // 普通 Markdown 卡处理，避免把统一 JSON 结果重新画成一张无专用语义的卡片。
            return List.of();
        }
        String status = UiMessages.status(entry.status());
        boolean thinking = entry.kind() == TranscriptEntry.Kind.THINKING;
        if (thinking && !thinkingVisible && !entry.expanded()) {
            out.add(cardHeader(entry, actor + prefix(actor) + "Thinking… (Ctrl+T)", "thinkingTitle", "", width, context));
            return out;
        }
        String title = switch (entry.kind()) {
            case USER -> UiMessages.REQUEST;
            case REPLY -> UiMessages.REPLY;
            case THINKING -> UiMessages.THINKING;
            case NOTICE -> entry.title();
            case TOOL -> entry.title();
        };
        String titleText = actor + prefix(actor) + title + (status.isBlank() ? "" : " · " + status)
                + (entry.elapsedNanos() > 0 ? " · " + UsageFormatter.turnDuration(entry.elapsedNanos()) : "");
        String token = switch (entry.kind()) {
            case USER -> "requestTitle";
            case REPLY -> entry.status().equals("INCOMPLETE") || entry.status().equals("ERROR") ? "error" : "replyTitle";
            case THINKING -> "thinkingTitle";
            case NOTICE -> entry.status().equals("ERROR") ? "error" : entry.status().equals("WARNING") ? "warning" : "muted";
            case TOOL -> "toolTitle";
        };
        out.add(cardHeader(entry, titleText, token, "", width, context));
        if (entry.kind() == TranscriptEntry.Kind.USER) {
            // Pi 用户消息：整宽背景 + paddingX1/Y1；保留 Markdown，但不用标题以外的额外边框。
            Style background = context.theme().style("userMessageText").withBg(context.theme().color("userMessageBg"));
            var body = new ArrayList<StyledLine>();
            body.add(StyledLine.of("", background).padTo(width, background));
            for (StyledLine line : markdown.render(entry.body(), Math.max(1, width - 2), context.theme(), false)) {
                var spans = new ArrayList<Span>();
                spans.add(new Span(" ", background, null));
                for (Span span : line.spans())
                    spans.add(new Span(span.text(), span.style().withBg(context.theme().color("userMessageBg")), span.link()));
                // 背景必须铺满整条卡宽（paddingX=1），不能只覆盖文字长度。
                body.add(new StyledLine(spans).padTo(width, background));
            }
            body.add(StyledLine.of("", background).padTo(width, background));
            out.addAll(body);
            return out;
        }
        if (thinking) {
            out.addAll(markdown.render(entry.body(), width, context.theme(), true));
            return out;
        }
        out.addAll(markdown.render(entry.body(), width, context.theme(), false));
        if (entry.status().equals("INCOMPLETE"))
            out.add(StyledLine.of(DisplayWidth.ellipsize("Output interrupted; this is not a complete reply", width),
                    context.theme().style("error")));
        if (entry.status().equals("TRUNCATED"))
            out.add(StyledLine.of(DisplayWidth.ellipsize("Stopped at max_tokens; reply is truncated", width),
                    context.theme().style("warning")));
        return out;
    }

    private static String prefix(String actor) {
        return actor == null || actor.isBlank() ? "" : " · ";
    }

    /**
     * 标题 + 细线；细线不占额外行。窄屏只留可读标题。样式只影响标题，不影响正文。
     */
    private StyledLine cardHeader(TranscriptEntry entry, String title, String token, String ignored,
            int width, RenderContext context) {
        String text = DisplayWidth.ellipsize(title, Math.max(1, width));
        int remaining = width - DisplayWidth.width(text);
        Style style = context.theme().style(token);
        if (remaining < 3) return StyledLine.of(text, style);
        return new StyledLine(List.of(new Span(text, style, null),
                new Span(" " + "─".repeat(remaining - 1), context.theme().style("dim"), null)));
    }

    /** 主题切换/会话切换时丢弃 Markdown 缓存。 */
    public void invalidate() { markdown.invalidate(); }
}
