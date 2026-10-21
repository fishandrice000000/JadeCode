package com.jadecode.tui.components;

import java.util.List;
import java.util.function.Consumer;
import com.jadecode.tui.core.Component;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.Style;
import com.jadecode.tui.text.StyledLine;

/**
 * 单行输入组件：水平滚动、placeholder、字素删除与基于同一编辑核心的按键语义。
 *
 * <p>用于选择器过滤、搜索框和设置输入；内部复用 {@link EditorBuffer}(singleLine=true)，
 * 因此不会出现“这里按 char 删除、主编辑器按字素删除”的两套行为。内容超过宽度时按光标
 * 水平滚动，placeholder 只在空内容时显示；Enter/上下键由宿主处理（确认/导航），组件不
 * 自行提交或执行命令。</p>
 */
public final class Input implements Component {
    private final EditorBuffer buffer = new EditorBuffer(true);
    private final String placeholder;
    private final Consumer<String> onChange;
    private int viewportColumn;

    /**
     * @param placeholder 空内容时的提示；自有界面传英文
     * @param onChange 文本变化回调；只用于宿主请求补全/重绘，不执行命令
     */
    public Input(String placeholder, Consumer<String> onChange) {
        this.placeholder = placeholder == null ? "" : placeholder;
        this.onChange = onChange == null ? value -> { } : onChange;
    }

    /** @return 当前文字。 */
    public String text() { return buffer.text(); }

    /** 用新文字替换并重置光标到末尾。 */
    public void setText(String value) {
        buffer.setText(value); viewportColumn = 0; onChange.accept(buffer.text());
    }

    /** 清空输入。 */
    public void clear() { buffer.clear(); viewportColumn = 0; onChange.accept(""); }

    /** @return 单行编辑核心，供宿主检查光标。 */
    public EditorBuffer buffer() { return buffer; }

    /**
     * 处理输入；Enter/上/下不消费，返回 false 让宿主导航/确认。
     */
    @Override public boolean handle(InputEvent event) {
        if (event.kind() == InputEvent.Kind.TEXT) {
            buffer.insert(event.text(), false); viewportColumn = 0; onChange.accept(buffer.text()); return true;
        }
        if (event.kind() == InputEvent.Kind.PASTE) {
            buffer.insert(event.text(), true); viewportColumn = 0; onChange.accept(buffer.text()); return true;
        }
        if (event.kind() != InputEvent.Kind.KEY || !event.actionableKey()) return false;
        KeyChord key = event.key();
        String name = key.key();
        if (name.equals("enter") || name.equals("up") || name.equals("down")
                || name.equals("tab") || name.equals("escape")) return false;
        if (key.has(KeyChord.Modifier.CTRL)) {
            switch (name) {
                case "a" -> { buffer.lineStart(); return true; }
                case "e" -> { buffer.lineEnd(); return true; }
                case "u" -> { buffer.killToLineStart(); changed(); return true; }
                case "k" -> { buffer.killToLineEnd(); changed(); return true; }
                case "w" -> { buffer.deleteWord(EditorBuffer.Direction.BACKWARD); changed(); return true; }
                case "y" -> { buffer.yank(); changed(); return true; }
                case "v" -> { return false; }
                default -> { return false; }
            }
        }
        switch (name) {
            case "backspace" -> { buffer.delete(EditorBuffer.Direction.BACKWARD); changed(); return true; }
            case "delete" -> { buffer.delete(EditorBuffer.Direction.FORWARD); changed(); return true; }
            case "left" -> { buffer.moveHorizontal(-1); return true; }
            case "right" -> { buffer.moveHorizontal(1); return true; }
            case "home" -> { buffer.lineStart(); return true; }
            case "end" -> { buffer.lineEnd(); return true; }
            default -> { return false; }
        }
    }

    private void changed() { viewportColumn = 0; onChange.accept(buffer.text()); }

    @Override public List<StyledLine> render(RenderContext context, int width) {
        String text = buffer.text();
        int caretColumn = DisplayWidth.columnAtOffset(text, buffer.caret());
        if (caretColumn < viewportColumn) viewportColumn = caretColumn;
        if (caretColumn >= viewportColumn + width) viewportColumn = caretColumn - width + 1;
        // 从 viewportColumn 对应的源文偏移开始裁列；placeholder 只显示在空输入。
        int startOffset = text.isEmpty() ? 0 : DisplayWidth.offsetAtColumn(text, viewportColumn);
        String cropped = DisplayWidth.take(text.substring(startOffset), Math.max(1, width));
        String shown = text.isEmpty() ? DisplayWidth.ellipsize(placeholder, width) : cropped;
        Style style = text.isEmpty() ? context.theme().style("dim") : context.theme().style("text");
        return List.of(new StyledLine(List.of(new Span(shown, style, null))));
    }

    /** @return 光标在渲染行内的列（已扣除水平滚动）；宿主用于硬件光标。 */
    public int cursorColumn(int width) {
        return Math.max(0, Math.min(width - 1, DisplayWidth.columnAtOffset(buffer.text(), buffer.caret()) - viewportColumn));
    }

    /** @return 光标是否应显示（输入始终聚焦）。 */
    public boolean cursorVisible() { return true; }
}
