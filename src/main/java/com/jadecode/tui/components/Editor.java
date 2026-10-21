package com.jadecode.tui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.jadecode.tui.completion.CompletionProvider;
import com.jadecode.tui.completion.CompletionRequest;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * Pi 风格多行编辑器：上下横边、默认无左右竖边、隐藏行提示、状态 spinner 与候选区。
 *
 * <p>编辑状态在 {@link EditorBuffer}，布局在 {@link EditorLayout}；本类只负责组合显示、路由
 * 按键、异步补全和提交意图。内容高度为 `max(5, floor(rows*0.3))`（不含上下边框），父布局
 * 可再缩小。Enter 产生一次提交意图，只有宿主接受后草稿才清空；Shift+Enter/Ctrl+J/反斜杠
 * Enter 插入换行，Alt+Enter 在 C08 下不绑定。Tab 只应用候选，不执行命令；slash 候选的
 * Enter 应用后由宿主执行，路径候选的 Enter 只应用。</p>
 *
 * <p>本类不等待磁盘/补全 future、不写终端、不提交 Agent；候选结果必须在请求身份与草稿
 * 版本仍匹配时采用。</p>
 */
public final class Editor implements Component {
    /** 编辑器对宿主的意图端口；返回值表示业务是否接受提交。 */
    public interface Listener {
        /** @return true 表示已接受并应清空草稿；false 保留草稿与历史。 */
        boolean submit(String text);
        /** Esc 在无补全时的局部关闭/提示；不得冒充 Agent 取消。 */
        default void localEscape() { }
        /** Ctrl+G 外部编辑器；宿主异步处理。 */
        default void externalEditor() { }
        /** Ctrl+V 剪贴板文字/图片路径；宿主异步处理。 */
        default void pasteClipboard() { }
        /** Ctrl+X 复制最后回复/选区；宿主处理。 */
        default void copy() { }
    }

    private final EditorBuffer buffer = new EditorBuffer(false);
    private final EditorHistory history = new EditorHistory();
    private final KeyBindings bindings;
    private final Listener listener;
    private CompletionProvider completion = CompletionProvider.NONE;
    // 补全 future 在 worker 线程完成，UI 线程读取；这些字段用 volatile 保证可见性。
    private volatile List<CompletionRequest.Candidate> candidates = List.of();
    private volatile int candidateIndex;
    private volatile boolean completionVisible;
    private long requestCounter;
    private long draftVersion;
    private long generation;
    private long lastRequestId;
    private volatile String completionError = "";
    private String status = "";
    private int lastColumns = 78;
    private int lastVisibleRows = 5;
    private int scrollTop;
    private boolean focused = true;
    private int completionMaxVisible = 5;

    /**
     * @param bindings 动作/默认键的单一来源；编辑器按动作 ID 匹配，不硬编码另一份键表
     * @param listener 提交/局部动作端口
     */
    public Editor(KeyBindings bindings, Listener listener) {
        this.bindings = bindings;
        this.listener = listener == null ? new Listener() {
            @Override public boolean submit(String text) { return false; }
        } : listener;
    }

    /** 设置补全提供者；null 关闭补全。 */
    public void setCompletionProvider(CompletionProvider provider) {
        this.completion = provider == null ? CompletionProvider.NONE : provider;
    }

    /** 设置会话代次；变化时丢弃旧候选。 */
    public void setGeneration(long value) {
        if (generation == value) return;
        generation = value; cancelCompletion();
    }

    /** 设置上边框状态（Working/Approval…）；只影响显示。 */
    public void setStatus(String value) { status = value == null ? "" : DisplayWidth.ellipsize(value, 120); }

    /** 聚焦时发送硬件光标；失焦时仍保留草稿。 */
    public void setFocused(boolean value) { focused = value; }

    /** 设置候选窗口最大可见行数（3-20，配置层已校验）。 */
    public void setCompletionMaxVisible(int value) { completionMaxVisible = Math.max(1, value); }

    /** @return 是否聚焦。 */
    public boolean focused() { return focused; }

    /** @return 逻辑正文（含粘贴标记）。 */
    public String text() { return buffer.text(); }

    /** @return 提交时将展开的完整正文。 */
    public String expandedText() { return buffer.expandedText(); }

    /** @return 逻辑光标。 */
    public int caret() { return buffer.caret(); }

    /** 清空草稿（Ctrl+C）。 */
    public void clear() { buffer.clear(); draftVersion++; cancelCompletion(); }

    /** 在光标处插入用户显式粘贴的文字（如 Ctrl+V），保留撤销与光标。 */
    public void insert(String text, boolean paste) {
        buffer.insert(text, paste);
        afterEdit();
    }

    /** 外部编辑器返回：替换草稿、保留历史与撤销边界。 */
    public void replaceText(String value) {
        buffer.setText(value); draftVersion++; history.end(buffer.text()); cancelCompletion();
    }

    /** 恢复历史草稿（宿主安装历史后使用）。 */
    public void remember(String value) {
        if (value == null || value.isBlank()) return;
        history.add(value);
    }

    /** @return 输入历史只读快照。 */
    public List<String> historyEntries() { return history.entries(); }

    /** @return 当前草稿版本，供宿主补全/异步校验。 */
    public long draftVersion() { return draftVersion; }

    /** @return 当前补全候选；无则空。 */
    public List<CompletionRequest.Candidate> candidates() { return candidates; }

    /** @return 是否显示候选。 */
    public boolean completionVisible() { return completionVisible; }

    /** @return 补全失败原因；成功为空。 */
    public String completionError() { return completionError; }

    /**
     * 处理输入。返回 true 表示消费。
     * 释放/重复按键中只有 PRESS 会触发编辑；补全按键优先于普通编辑。
     */
    @Override public boolean handle(InputEvent event) {
        if (event.kind() == InputEvent.Kind.TEXT) {
            buffer.insert(event.text(), false); afterEdit(); return true;
        }
        if (event.kind() == InputEvent.Kind.PASTE) {
            buffer.insert(event.text(), true); afterEdit(); return true;
        }
        if (event.kind() != InputEvent.Kind.KEY || !event.actionableKey()) return false;
        KeyChord key = event.key();
        if (completionVisible && handleCompletion(key)) return true;
        if (key.has(KeyChord.Modifier.ALT) && key.key().equals("enter")) {
            // C08：无独立 follow-up 接口，Alt+Enter 整体不绑定（含闲时）。
            return true;
        }
        // 先按动作表匹配，保证帮助显示的快捷键真正生效。
        if (bindings.matches("tui.input.newLine", event)) { buffer.newline(); afterEdit(); return true; }
        if (bindings.matches("tui.input.submit", event)) { submit(); return true; }
        if (bindings.matches("tui.input.tab", event)) {
            if (!completionVisible) requestCompletions();
            return true;
        }
        if (bindings.matches("tui.editor.cursorLeft", event)) { buffer.moveHorizontal(-1); return true; }
        if (bindings.matches("tui.editor.cursorRight", event)) { buffer.moveHorizontal(1); return true; }
        if (bindings.matches("tui.editor.cursorWordLeft", event)) { buffer.moveWord(-1); return true; }
        if (bindings.matches("tui.editor.cursorWordRight", event)) { buffer.moveWord(1); return true; }
        if (bindings.matches("tui.editor.cursorUp", event)) { vertical(-1); return true; }
        if (bindings.matches("tui.editor.cursorDown", event)) { vertical(1); return true; }
        if (bindings.matches("tui.editor.pageUp", event)) { vertical(-lastVisibleRows); return true; }
        if (bindings.matches("tui.editor.pageDown", event)) { vertical(lastVisibleRows); return true; }
        if (bindings.matches("tui.editor.cursorLineStart", event)) { buffer.lineStart(); return true; }
        if (bindings.matches("tui.editor.cursorLineEnd", event)) { buffer.lineEnd(); return true; }
        if (bindings.matches("tui.editor.deleteCharBackward", event)) { buffer.delete(EditorBuffer.Direction.BACKWARD); afterEdit(); return true; }
        if (bindings.matches("tui.editor.deleteCharForward", event)) {
            if (buffer.empty()) return false; // 空编辑器 Ctrl+D 由宿主退出
            buffer.delete(EditorBuffer.Direction.FORWARD); afterEdit(); return true;
        }
        if (bindings.matches("tui.editor.deleteWordBackward", event)) { buffer.deleteWord(EditorBuffer.Direction.BACKWARD); afterEdit(); return true; }
        if (bindings.matches("tui.editor.deleteWordForward", event)) { buffer.deleteWord(EditorBuffer.Direction.FORWARD); afterEdit(); return true; }
        if (bindings.matches("tui.editor.deleteToLineStart", event)) { buffer.killToLineStart(); afterEdit(); return true; }
        if (bindings.matches("tui.editor.deleteToLineEnd", event)) { buffer.killToLineEnd(); afterEdit(); return true; }
        if (bindings.matches("tui.editor.yank", event)) { buffer.yank(); afterEdit(); return true; }
        if (bindings.matches("tui.editor.yankPop", event)) { buffer.yankPop(); afterEdit(); return true; }
        if (bindings.matches("tui.editor.undo", event)) { buffer.undo(); afterEdit(); return true; }
        if (bindings.matches("app.interrupt", event)) {
            if (completionVisible) cancelCompletion(); else listener.localEscape();
            return true;
        }
        if (bindings.matches("app.editor.external", event)) { listener.externalEditor(); return true; }
        if (bindings.matches("app.clipboard.pasteImage", event)) { listener.pasteClipboard(); return true; }
        if (bindings.matches("app.message.copy", event)) { listener.copy(); return true; }
        return false;
    }

    private void vertical(int delta) {
        if (buffer.vertical(delta < 0 ? -1 : 1, lastColumns, Math.abs(delta))) return;
        // 越过首/末视觉行才进入历史；历史项也用 setText 恢复光标。
        history.begin(buffer.text(), buffer.caret());
        String next = delta < 0 ? history.previous() : history.next();
        if (next != null) {
            buffer.setText(next);
            if (!history.browsing()) buffer.setCaret(history.draftCaret());
        }
    }

    /** 提交意图：反斜杠 Enter 先回退成换行；粘贴标记在这里展开。 */
    private void submit() {
        String text = buffer.text();
        if (text.endsWith("\\")) {
            buffer.delete(EditorBuffer.Direction.BACKWARD);
            buffer.newline();
            afterEdit();
            return;
        }
        String expanded = buffer.expandedText();
        if (expanded.isBlank()) return;
        if (listener.submit(expanded)) {
            buffer.submit();
            history.add(expanded);
            draftVersion++; cancelCompletion();
        }
    }

    /** 编辑后：版本递增、历史离开浏览、重新请求候选。 */
    private void afterEdit() {
        draftVersion++; history.end(buffer.text()); requestCompletions();
    }

    /** 请求补全；同一时刻只保留最新请求，结果身份不匹配即丢弃。 */
    public void requestCompletions() {
        String text = buffer.text();
        if (text.isBlank() || !shouldComplete(text)) { cancelCompletion(); return; }
        int from = replacementStart(text, buffer.caret());
        var request = new CompletionRequest.Request(++requestCounter, generation, draftVersion, text,
                buffer.caret(), from, buffer.caret(), text.startsWith("/") ? CompletionRequest.Kind.COMMAND : CompletionRequest.Kind.PATH);
        CompletableFuture<CompletionRequest.Result> future;
        try { future = completion.complete(request); }
        catch (RuntimeException failure) { completionError = failure.getMessage(); cancelCompletion(); return; }
        future.thenAccept(result -> {
            // 回调可能在 worker 线程；只校验并暂存，真正应用由 UI 线程按键触发。
            synchronized (this) {
                if (result.request().requestId() != requestCounter || result.request().generation() != generation
                        || result.request().draftVersion() != draftVersion || !result.request().text().equals(buffer.text()))
                    return;
                if (!result.error().isEmpty()) { completionError = result.error(); candidates = List.of(); completionVisible = true; return; }
                completionError = "";
                candidates = result.candidates();
                candidateIndex = 0;
                completionVisible = !candidates.isEmpty() || !completionError.isEmpty();
            }
        });
    }

    /** 只在斜杠命令/路径/@引用附近请求，普通自然语言不弹候选。 */
    private static boolean shouldComplete(String text) {
        String before = text.substring(0, Math.min(text.length(), text.length()));
        if (before.startsWith("/") && !before.contains("\n")) return true;
        int lastSpace = Math.max(before.lastIndexOf(' '), before.lastIndexOf('\n'));
        String tail = lastSpace < 0 ? before : before.substring(lastSpace + 1);
        return tail.startsWith("@") || tail.contains("/") || tail.startsWith("./") || tail.startsWith("../");
    }

    /** 替换范围从当前 token 开头到光标；命令名从 `/` 开始，路径从 token 开头开始。 */
    private static int replacementStart(String text, int caret) {
        int i = caret;
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
        return i;
    }

    /** 候选导航；返回 true 表示消费按键。 */
    private boolean handleCompletion(KeyChord key) {
        if (key.key().equals("escape") || bindings.matches("tui.select.cancel",
                InputEvent.key(key, 0, 0))) { cancelCompletion(); return true; }
        if (bindings.matches("tui.select.up", InputEvent.key(key, 0, 0)) || key.key().equals("up")) {
            if (!candidates.isEmpty()) candidateIndex = Math.floorMod(candidateIndex - 1, candidates.size());
            return true;
        }
        if (bindings.matches("tui.select.down", InputEvent.key(key, 0, 0)) || key.key().equals("down")) {
            if (!candidates.isEmpty()) candidateIndex = Math.floorMod(candidateIndex + 1, candidates.size());
            return true;
        }
        if (key.key().equals("pageup") || key.key().equals("pagedown")) {
            int step = 5;
            if (!candidates.isEmpty()) candidateIndex = Math.max(0, Math.min(candidates.size() - 1,
                    candidateIndex + (key.key().equals("pageup") ? -step : step)));
            return true;
        }
        if (bindings.matches("tui.input.tab", InputEvent.key(key, 0, 0))) { applyCompletion(false); return true; }
        if (bindings.matches("tui.input.submit", InputEvent.key(key, 0, 0))) { applyCompletion(true); return true; }
        return false;
    }

    /** 应用选中候选；slash 命令 Enter 应用后提交，文件候选只应用。 */
    public void applyCompletion(boolean execute) {
        if (candidates.isEmpty()) { cancelCompletion(); return; }
        var candidate = candidates.get(Math.max(0, Math.min(candidateIndex, candidates.size() - 1)));
        int from = candidate.replaceFrom(), to = candidate.replaceTo();
        String current = buffer.text();
        if (from < 0 || to > current.length() || from > to) { cancelCompletion(); return; }
        String replacement = candidate.label();
        buffer.replaceRange(from, to, replacement);
        draftVersion++;
        cancelCompletion();
        if (execute && candidate.executableOnEnter()) submit();
        else requestCompletions();
    }

    private void cancelCompletion() {
        completionVisible = false; candidates = List.of(); candidateIndex = 0; completionError = "";
    }

    /**
     * 排版与绘制。返回行顺序：上边框、内容行、下边框、候选。
     * 光标相对返回值由 {@link #cursor(RenderContext)} 提供，二者使用同一次布局。
     */
    @Override public List<StyledLine> render(RenderContext context, int width) {
        // 与 Pi 相同：无左右 padding 时保留 1 列给末尾假光标，避免光标超过内容宽后
        // 被终端自动折行或裁掉。
        int contentWidth = Math.max(1, width - 1);
        lastColumns = contentWidth;
        EditorLayout layout = EditorLayout.of(buffer.text(), contentWidth);
        int maxVisible = Math.max(5, (int) Math.floor(context.height() * 0.3));
        int caretRow = layout.rowOf(buffer.caret());
        int caretColumn = layout.columnOf(buffer.caret());
        scrollTop = layout.topFor(caretRow, maxVisible);
        lastVisibleRows = Math.min(maxVisible, layout.lines().size() - scrollTop);
        var result = new ArrayList<StyledLine>();
        Style border = context.theme().style("border");
        result.add(borderLine(width, status, scrollTop > 0 ? "↑ " + scrollTop : "", border));
        int end = Math.min(layout.lines().size(), scrollTop + maxVisible);
        for (int row = scrollTop; row < end; row++) {
            String text = layout.lines().get(row).text();
            StyledLine line = new StyledLine(List.of(new Span(text, context.theme().style("text"), null)));
            if (focused && row == caretRow) line = withFakeCursor(line, caretColumn, context);
            var spans = new ArrayList<>(line.spans());
            if (DisplayWidth.width(line.text()) < width) spans.add(new Span(" ".repeat(width - line.width()),
                    context.theme().style("text"), null));
            result.add(new StyledLine(spans));
        }
        int hiddenBelow = Math.max(0, layout.lines().size() - end);
        result.add(borderLine(width, "", hiddenBelow > 0 ? "↓ " + hiddenBelow : "", border));
        if (completionVisible) {
            int visible = Math.max(1, Math.min(completionMaxVisible, context.height() - result.size()));
            var list = new SelectList(candidates.stream().map(candidate -> new SelectList.Item(
                    candidate.id(), candidate.label(), candidate.description())).toList(), true);
            // 用当前选中索引构造窗口：先按同样顺序过滤为空（全部保留），再移动到索引。
            for (int i = 0; i < candidateIndex && i < list.size() - 1; i++) list.move(1);
            result.addAll(list.render(width, visible, context));
            if (!completionError.isEmpty())
                result.add(StyledLine.of(DisplayWidth.ellipsize(completionError, width), context.theme().style("warning")));
        }
        return result;
    }

    private StyledLine borderLine(int width, String status, String hidden, Style style) {
        String text;
        if (!status.isEmpty()) {
            String label = " " + status + " ";
            text = "──" + label + "─".repeat(Math.max(0, width - 2 - DisplayWidth.width(label) - (hidden.isEmpty() ? 0 : DisplayWidth.width(hidden) + 1)))
                    + (hidden.isEmpty() ? "" : " " + hidden);
        } else if (!hidden.isEmpty()) {
            text = "─── " + hidden + " " + "─".repeat(Math.max(0, width - 5 - DisplayWidth.width(hidden)));
        } else text = "─".repeat(Math.max(0, width));
        return StyledLine.of(DisplayWidth.take(text, width), style);
    }

    /** @return 当前光标在 render 返回行中的位置；与同一布局一致。 */
    public FrameCursor cursor(RenderContext context) {
        EditorLayout layout = EditorLayout.of(buffer.text(), Math.max(1, context.width() - 1));
        int caretRow = layout.rowOf(buffer.caret());
        int row = 1 + Math.max(0, caretRow - scrollTop);
        int column = Math.min(context.width() - 1, layout.columnOf(buffer.caret()));
        return new FrameCursor(row, column, focused);
    }

    /**
     * 在光标列绘制反色假光标；光标位于行末时插入一个反色空格。它不改变逻辑正文，
     * 也不进入提交/复制文本；硬件光标仍由 renderer 按设置定位到同一坐标。
     */
    private StyledLine withFakeCursor(StyledLine line, int caretColumn, RenderContext context) {
        Style cursorStyle = context.theme().style("accent").withInverse(true);
        if (context.theme().style("text").isPlain()) cursorStyle = Style.PLAIN.withInverse(false);
        var spans = new ArrayList<Span>();
        int column = 0;
        boolean placed = false;
        for (Span span : line.spans()) {
            for (String cluster : DisplayWidth.clusters(span.text())) {
                int cells = DisplayWidth.clusterWidth(cluster);
                if (!placed && column >= caretColumn) {
                    spans.add(new Span(cluster, cursorStyle, span.link()));
                    placed = true;
                } else spans.add(new Span(cluster, span.style(), span.link()));
                column += cells;
            }
        }
        if (!placed) spans.add(new Span(" ", cursorStyle, null));
        return new StyledLine(spans, line.annotation());
    }

    /** 光标反馈；只在组件内部使用，避免与 core.Frame.Cursor 混淆。 */
    public record FrameCursor(int row, int column, boolean visible) { }

    /** @return 当前可见内容行数，供宿主滚动/翻页。 */
    public int visibleRows() { return lastVisibleRows; }

    /** @return 编辑器布局（当前宽度）；测试/宿主定位使用。 */
    public EditorLayout layout() { return EditorLayout.of(buffer.text(), lastColumns); }

    /** @return 编辑核心，供宿主读取撤销/粘贴状态或测试。 */
    public EditorBuffer buffer() { return buffer; }
}
