package com.jadecode.tui.components;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.completion.CompletionProvider;
import com.jadecode.tui.completion.CompletionRequest;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** 编辑器文本/光标/撤销/粘贴/历史/补全/提交行为测试。 */
class TuiEditorBehaviorTest {
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final KeyBindings keys = new KeyBindings(java.util.Map.of());
    private final List<String> submitted = new ArrayList<>();

    private Editor editor(boolean accept) {
        return new Editor(keys, new Editor.Listener() {
            @Override public boolean submit(String text) { submitted.add(text); return accept; }
        });
    }

    private static InputEvent key(String name, KeyChord.Modifier... modifiers) {
        return InputEvent.key(KeyChord.of(name, modifiers), 1, 1);
    }

    private static InputEvent text(String value) { return InputEvent.text(value, 1, 1); }

    @Test void unicodeEditingUsesGraphemeBoundaries() {
        var editor = editor(true);
        editor.handle(text("a👩‍👩‍👧‍👦e\u0301"));
        editor.handle(key("backspace"));
        assertEquals("a👩‍👩‍👧‍👦", editor.text());
        editor.handle(key("backspace"));
        assertEquals("a", editor.text());
    }

    @Test void newlineKeysFollowC08AndAltEnterIsUnbound() {
        var editor = editor(true);
        editor.handle(text("a"));
        editor.handle(key("enter", KeyChord.Modifier.SHIFT));
        editor.handle(text("b"));
        editor.handle(key("j", KeyChord.Modifier.CTRL));
        editor.handle(text("c"));
        assertEquals("a\nb\nc", editor.text());
        editor.handle(key("enter", KeyChord.Modifier.ALT));
        assertEquals("a\nb\nc", editor.text());
    }

    @Test void backslashEnterFallsBackToNewline() {
        var editor = editor(true);
        editor.handle(text("a\\"));
        editor.handle(key("enter"));
        assertEquals("a\n", editor.text());
        assertTrue(submitted.isEmpty());
    }

    @Test void rejectedSubmitKeepsDraftAndHistory() {
        var editor = editor(false);
        editor.handle(text("hello"));
        editor.handle(key("enter"));
        assertEquals("hello", editor.text());
        assertTrue(submitted.contains("hello"));
        var accepting = editor(true);
        accepting.handle(text("world"));
        accepting.handle(key("enter"));
        assertEquals("", accepting.text());
        assertEquals("world", submitted.getLast());
    }

    @Test void emptyEnterNeverSubmitsOrPromotes() {
        var editor = editor(true);
        editor.handle(key("enter"));
        assertTrue(submitted.isEmpty());
        editor.handle(text("   "));
        editor.handle(key("enter"));
        assertTrue(submitted.isEmpty());
    }

    @Test void undoCoalescesTypingAndRestoresDeletedPasteMarker() {
        var editor = editor(true);
        editor.handle(text("hello"));
        editor.handle(key("-", KeyChord.Modifier.CTRL));
        assertEquals("", editor.text());
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 12; i++) { big.append("line").append(i); if (i < 11) big.append('\n'); }
        editor.handle(InputEvent.paste(big.toString(), 2, 1));
        String marked = editor.text();
        assertTrue(marked.startsWith("[paste #1 +12 lines]"), marked);
        editor.handle(key("a", KeyChord.Modifier.CTRL)); // line start
        editor.handle(key("k", KeyChord.Modifier.CTRL)); // kill line
        assertEquals("", editor.text());
        editor.handle(key("-", KeyChord.Modifier.CTRL));
        assertEquals(marked, editor.text());
    }

    @Test void killRingYankAndYankPopWork() {
        var editor = editor(true);
        editor.handle(text("one two"));
        // 移到行尾并删除到行首（kill）
        editor.handle(key("e", KeyChord.Modifier.CTRL));
        editor.handle(key("u", KeyChord.Modifier.CTRL));
        assertEquals("", editor.text());
        editor.handle(key("y", KeyChord.Modifier.CTRL));
        assertEquals("one two", editor.text());
        editor.handle(key("a", KeyChord.Modifier.CTRL));
        // 产生第二个 kill 项后再 yank-pop
        editor.handle(key("e", KeyChord.Modifier.CTRL));
        editor.handle(key("u", KeyChord.Modifier.CTRL));
        editor.handle(text("third"));
        editor.handle(key("a", KeyChord.Modifier.CTRL));
        editor.handle(key("u", KeyChord.Modifier.CTRL));
        editor.handle(key("y", KeyChord.Modifier.CTRL));
        String yanked = editor.text();
        assertFalse(yanked.isEmpty());
        editor.handle(key("y", KeyChord.Modifier.ALT));
        assertTrue(editor.buffer().killRing().size() >= 1);
    }

    @Test void historyDedupesAdjacentAndRestoresDraft() {
        var history = new EditorHistory();
        history.add("a"); history.add("a"); history.add("b");
        assertEquals(List.of("a", "b"), history.entries());
        for (int i = 0; i < 120; i++) history.add("item" + i);
        assertEquals(100, history.entries().size());
        history.begin("draft", 5);
        assertEquals("item119", history.previous());
        assertEquals("draft", history.next());
        assertEquals(5, history.draftCaret());
    }

    @Test void tabAppliesSlashCandidateWithoutExecutingAndEnterExecutes() {
        var editor = editor(true);
        editor.setCompletionProvider(request -> java.util.concurrent.CompletableFuture.completedFuture(
                CompletionRequest.Result.of(request, List.of(new CompletionRequest.Candidate(
                        "/settings", "/settings ", "command", 0, request.text().length(), true)))));
        editor.handle(text("/set"));
        editor.requestCompletions();
        editor.handle(key("tab"));
        assertEquals("/settings ", editor.text());
        assertTrue(submitted.isEmpty());
        editor.handle(key("enter"));
        assertEquals("/settings ", submitted.getLast());
        assertEquals("", editor.text());
    }

    @Test void clickingCandidateRowSelectsItForTabApplication() {
        var editor = editor(true);
        editor.setCompletionProvider(request -> java.util.concurrent.CompletableFuture.completedFuture(
                CompletionRequest.Result.of(request, List.of(
                        new CompletionRequest.Candidate("/alpha", "/alpha ", "first", 0, request.text().length(), true),
                        new CompletionRequest.Candidate("/beta", "/beta ", "second", 0, request.text().length(), true)))));
        editor.handle(text("/a"));
        editor.requestCompletions();
        var context = new RenderContext(40, 20, theme);
        editor.render(context, 40);
        assertTrue(editor.clickCompletion(1));
        editor.handle(key("tab"));
        assertEquals("/beta ", editor.text());
    }

    @Test void renderingShowsBordersHiddenCountsAndCursor() {
        var editor = editor(true);
        for (int i = 0; i < 40; i++) editor.handle(text("line" + i + "\n"));
        var context = new RenderContext(30, 20, theme);
        var lines = editor.render(context, 30);
        assertTrue(lines.size() > 5);
        assertEquals(30, lines.getFirst().width());
        assertEquals(30, lines.getLast().width());
        var cursor = editor.cursor(context);
        assertTrue(cursor.row() >= 1 && cursor.row() < lines.size());
        assertTrue(cursor.visible());
    }

    @Test void caretForClickMapsScreenRowAndColumnToGrapheme() {
        var editor = editor(true);
        editor.handle(text("ab\n中文"));
        editor.caretForClick(0, 1, 20);
        assertEquals(1, editor.caret());
        editor.caretForClick(1, 2, 20);
        assertEquals(4, editor.caret(), "wide char column maps to grapheme start");
        editor.caretForClick(9, 0, 20);
        assertEquals(4, editor.caret(), "out-of-range row does not move the caret");
    }

    @Test void pastePathGetsLeadingSpaceAfterWord() {
        var editor = editor(true);
        editor.handle(text("word"));
        editor.handle(InputEvent.paste("/tmp/file", 2, 1));
        assertEquals("word /tmp/file", editor.text());
    }
}
