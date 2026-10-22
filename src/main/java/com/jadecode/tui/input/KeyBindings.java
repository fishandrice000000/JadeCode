package com.jadecode.tui.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实际可执行动作、默认键、局部覆盖与冲突报告的单一来源。
 *
 * <p>控制器、编辑器、帮助页和 footer 都从这里读取动作，避免“帮助里显示一个键但实际没实现”。
 * 缺 Agent 后端的动作（取消、模型热切换、树/分叉、队列取回、删除会话等）标记为
 * {@link Support#HIDDEN}，不会出现在帮助或候选里；没有默认绑定的动作仍可通过配置显式绑定，
 * 但只在其宿主上下文生效。覆盖来自统一配置的 `tui.keybindings`，非法动作/冲突会报告而不静默吞掉。</p>
 */
public final class KeyBindings {
    /** 动作所在上下文；同一按键在不同上下文可以有不同含义。 */
    public enum Context { GLOBAL, EDITOR, SELECT, APPROVAL, ALT_SCREEN, SEARCH }

    /** 是否在 JadeCode 当前后端可执行。HIDDEN 动作不会出现在帮助中。 */
    public enum Support { SUPPORTED, HIDDEN }

    /**
     * 一个动作定义。
     * @param id 稳定动作 ID（与 Pi 默认键表同名）
     * @param label 英文说明；自有界面文案统一英文
     * @param context 生效上下文
     * @param support 当前是否有真实后端
     * @param defaultChords 默认键；空表表示无默认绑定
     */
    public record Action(String id, String label, Context context, Support support, List<KeyChord> defaultChords) {
        public Action { defaultChords = List.copyOf(defaultChords); }

        /** @return 是否应在帮助/设置中展示。 */
        public boolean visible() { return support == Support.SUPPORTED; }

        /** @return 人类可读的默认键描述，例如 `ctrl+o`；无绑定返回空。 */
        public String keys() {
            return defaultChords.stream().map(KeyChord::display).reduce((a, b) -> a + ", " + b).orElse("");
        }
    }

    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final Map<String, List<KeyChord>> bindings = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();

    /**
     * @param overrides 配置中的动作→键覆盖；键格式如 `ctrl+o`、`shift+enter`。只允许覆盖已存在动作。
     */
    public KeyBindings(Map<String, List<String>> overrides) {
        registerDefaults();
        if (overrides != null) overrides.forEach(this::override);
    }

    /** 默认键表；只登记 JadeCode 当前能真实执行的动作。 */
    private void registerDefaults() {
        action("tui.editor.cursorUp", "Move cursor up", Context.EDITOR, "up");
        action("tui.editor.cursorDown", "Move cursor down", Context.EDITOR, "down");
        action("tui.editor.historyPrevious", "Previous input history", Context.EDITOR, Support.HIDDEN);
        action("tui.editor.historyNext", "Next input history", Context.EDITOR, Support.HIDDEN);
        action("tui.editor.cursorLeft", "Move cursor left", Context.EDITOR, "left", "ctrl+b");
        action("tui.editor.cursorRight", "Move cursor right", Context.EDITOR, "right", "ctrl+f");
        action("tui.editor.cursorWordLeft", "Move word left", Context.EDITOR, "alt+left", "ctrl+left", "alt+b");
        action("tui.editor.cursorWordRight", "Move word right", Context.EDITOR, "alt+right", "ctrl+right", "alt+f");
        action("tui.editor.cursorLineStart", "Move to line start", Context.EDITOR, "home", "ctrl+a");
        action("tui.editor.cursorLineEnd", "Move to line end", Context.EDITOR, "end", "ctrl+e");
        action("tui.editor.jumpForward", "Jump to character", Context.EDITOR, Support.HIDDEN);
        action("tui.editor.jumpBackward", "Jump backward to character", Context.EDITOR, Support.HIDDEN);
        action("tui.editor.pageUp", "Editor page up", Context.EDITOR, "pageup", "ctrl+pageup");
        action("tui.editor.pageDown", "Editor page down", Context.EDITOR, "pagedown", "ctrl+pagedown");
        action("tui.editor.deleteCharBackward", "Delete character backward", Context.EDITOR, "backspace");
        action("tui.editor.deleteCharForward", "Delete character forward", Context.EDITOR, "delete", "ctrl+d");
        action("tui.editor.deleteWordBackward", "Delete word backward", Context.EDITOR, "ctrl+w", "alt+backspace");
        action("tui.editor.deleteWordForward", "Delete word forward", Context.EDITOR, "alt+d", "alt+delete");
        action("tui.editor.deleteToLineStart", "Delete to line start", Context.EDITOR, "ctrl+u");
        action("tui.editor.deleteToLineEnd", "Delete to line end", Context.EDITOR, "ctrl+k");
        action("tui.editor.yank", "Yank killed text", Context.EDITOR, "ctrl+y");
        action("tui.editor.yankPop", "Cycle yanked text", Context.EDITOR, "alt+y");
        action("tui.editor.undo", "Undo", Context.EDITOR, "ctrl+-", "ctrl+_");
        action("tui.input.newLine", "Insert newline", Context.EDITOR, "shift+enter", "ctrl+j");
        action("tui.input.submit", "Submit input", Context.EDITOR, "enter");
        action("tui.input.tab", "Tab completion", Context.EDITOR, "tab");
        action("tui.input.copy", "Copy selection", Context.EDITOR, Support.HIDDEN);
        // 审批是独立上下文；帮助与实际路由共享这份目录，避免竖排后仍提示左右选择/Esc无效。
        action("approval.previous", "Previous approval option", Context.APPROVAL, "up", "left", "shift+tab");
        action("approval.next", "Next approval option", Context.APPROVAL, "down", "right", "tab");
        action("approval.confirm", "Confirm selected option", Context.APPROVAL, "enter");
        action("approval.deny", "Deny current request", Context.APPROVAL, "escape");
        action("approval.pageUp", "Read previous details", Context.APPROVAL, "pageup");
        action("approval.pageDown", "Read next details", Context.APPROVAL, "pagedown");
        action("tui.select.up", "Previous item", Context.SELECT, "up");
        action("tui.select.down", "Next item", Context.SELECT, "down");
        action("tui.select.pageUp", "Previous page", Context.SELECT, "pageup");
        action("tui.select.pageDown", "Next page", Context.SELECT, "pagedown");
        action("tui.select.confirm", "Confirm selection", Context.SELECT, "enter");
        action("tui.select.cancel", "Cancel selection", Context.SELECT, "escape", "ctrl+c");
        action("tui.altScreen.pageUp", "Scroll page up", Context.ALT_SCREEN, "pageup");
        action("tui.altScreen.pageDown", "Scroll page down", Context.ALT_SCREEN, "pagedown");
        action("tui.altScreen.halfPageUp", "Scroll half page up", Context.ALT_SCREEN);
        action("tui.altScreen.halfPageDown", "Scroll half page down", Context.ALT_SCREEN);
        action("tui.altScreen.lineUp", "Scroll line up", Context.ALT_SCREEN);
        action("tui.altScreen.lineDown", "Scroll line down", Context.ALT_SCREEN);
        action("tui.altScreen.previousPrompt", "Previous prompt", Context.ALT_SCREEN, Support.HIDDEN);
        action("tui.altScreen.nextPrompt", "Next prompt", Context.ALT_SCREEN, Support.HIDDEN);
        action("tui.altScreen.search", "Search transcript", Context.ALT_SCREEN, "ctrl+shift+f");
        action("tui.altScreen.searchNext", "Next search match", Context.SEARCH, "enter", "ctrl+g");
        action("tui.altScreen.searchPrevious", "Previous search match", Context.SEARCH, "shift+enter", "ctrl+shift+g");
        action("tui.altScreen.searchClose", "Close search", Context.SEARCH, "escape");
        action("tui.altScreen.top", "Scroll to top", Context.ALT_SCREEN, "ctrl+home");
        action("tui.altScreen.bottom", "Scroll to bottom", Context.ALT_SCREEN, "ctrl+end");
        action("app.interrupt", "Close local overlay", Context.GLOBAL, "escape");
        action("app.clear", "Clear draft", Context.GLOBAL, "ctrl+c");
        action("app.exit", "Exit when editor is empty", Context.GLOBAL, "ctrl+d");
        action("app.suspend", "Suspend to shell", Context.GLOBAL, "ctrl+z");
        action("app.thinking.cycle", "Cycle thinking level", Context.GLOBAL, "shift+tab");
        action("app.thinking.save", "Save thinking default", Context.GLOBAL, Support.HIDDEN);
        action("app.model.cycleForward", "Next model", Context.GLOBAL, Support.HIDDEN);
        action("app.model.cycleBackward", "Previous model", Context.GLOBAL, Support.HIDDEN);
        action("app.model.select", "Select model", Context.GLOBAL, Support.HIDDEN);
        action("app.tools.expand", "Expand or collapse tools", Context.GLOBAL, "ctrl+o");
        action("app.thinking.toggle", "Show or hide thinking", Context.GLOBAL, "ctrl+t");
        action("app.session.toggleNamedFilter", "Toggle named sessions", Context.SELECT, "ctrl+n");
        action("app.editor.external", "Open external editor", Context.GLOBAL, "ctrl+g");
        action("app.message.copy", "Copy last reply or selection", Context.GLOBAL, "ctrl+x");
        action("app.message.followUp", "Queue follow-up", Context.GLOBAL, Support.HIDDEN);
        action("app.message.dequeue", "Retrieve queued message", Context.GLOBAL, Support.HIDDEN);
        action("app.clipboard.pasteImage", "Paste clipboard image or text", Context.GLOBAL, "ctrl+v");
        action("app.session.new", "New session", Context.GLOBAL);
        action("app.session.tree", "Session tree", Context.GLOBAL, Support.HIDDEN);
        action("app.session.fork", "Fork session", Context.GLOBAL, Support.HIDDEN);
        action("app.session.resume", "Resume session", Context.GLOBAL);
        action("app.tree.foldOrUp", "Fold tree or move up", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.unfoldOrDown", "Unfold tree or move down", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.editLabel", "Edit tree label", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.toggleLabelTimestamp", "Toggle label timestamp", Context.GLOBAL, Support.HIDDEN);
        action("app.session.togglePath", "Toggle session path", Context.SELECT, "ctrl+p");
        action("app.session.toggleSort", "Toggle session sort", Context.SELECT, "ctrl+s");
        action("app.session.rename", "Rename session", Context.GLOBAL, Support.HIDDEN);
        action("app.session.delete", "Delete session", Context.GLOBAL, Support.HIDDEN);
        action("app.session.deleteNoninvasive", "Delete session non-invasively", Context.GLOBAL, Support.HIDDEN);
        action("app.models.save", "Save model selection", Context.GLOBAL, Support.HIDDEN);
        action("app.models.enableAll", "Enable all models", Context.GLOBAL, Support.HIDDEN);
        action("app.models.clearAll", "Clear model selection", Context.GLOBAL, Support.HIDDEN);
        action("app.models.toggleProvider", "Toggle provider models", Context.GLOBAL, Support.HIDDEN);
        action("app.models.reorderUp", "Reorder model up", Context.GLOBAL, Support.HIDDEN);
        action("app.models.reorderDown", "Reorder model down", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.filter.default", "Tree default filter", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.filter.noTools", "Hide tool results in tree", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.filter.userOnly", "Show user messages only", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.filter.labeledOnly", "Show labeled tree items", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.filter.all", "Show all tree items", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.filter.cycleForward", "Cycle tree filter forward", Context.GLOBAL, Support.HIDDEN);
        action("app.tree.filter.cycleBackward", "Cycle tree filter backward", Context.GLOBAL, Support.HIDDEN);
        // Jade 批准扩展：权限/设置入口无默认键，通过 slash 命令访问，避免占用 Pi 键位。
        action("app.permission.menu", "Permission menu", Context.GLOBAL);
    }

    private void action(String id, String label, Context context, String... keys) {
        action(id, label, context, Support.SUPPORTED, keys);
    }

    private void action(String id, String label, Context context, Support support, String... keys) {
        var chords = new ArrayList<KeyChord>();
        for (String key : keys) chords.add(parseChord(key));
        Action action = new Action(id, label, context, support, chords);
        actions.put(id, action);
        bindings.put(id, chords);
    }

    /** 解析 `ctrl+shift+enter` 形式的键；未知修饰抛出明确异常。 */
    public static KeyChord parseChord(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Empty key binding");
        String[] parts = value.trim().toLowerCase(Locale.ROOT).split("\\+");
        var modifiers = java.util.EnumSet.noneOf(KeyChord.Modifier.class);
        for (int i = 0; i < parts.length - 1; i++) {
            String modifier = parts[i].trim();
            if (modifier.equals("ctrl") || modifier.equals("control")) modifiers.add(KeyChord.Modifier.CTRL);
            else if (modifier.equals("alt") || modifier.equals("meta") && parts.length == 2) modifiers.add(KeyChord.Modifier.ALT);
            else if (modifier.equals("shift")) modifiers.add(KeyChord.Modifier.SHIFT);
            else if (modifier.equals("super") || modifier.equals("cmd")) modifiers.add(KeyChord.Modifier.SUPER);
            else if (modifier.equals("meta")) modifiers.add(KeyChord.Modifier.META);
            else throw new IllegalArgumentException("Unknown modifier: " + modifier);
        }
        String key = parts[parts.length - 1].trim();
        if (key.isEmpty()) throw new IllegalArgumentException("Missing key: " + value);
        return new KeyChord(key, modifiers, KeyChord.Phase.PRESS);
    }

    /** 应用一个配置覆盖；未知动作或非法键只记录问题，不破坏其它默认键。 */
    private void override(String id, List<String> keys) {
        if (!actions.containsKey(id)) { problems.add("Unknown keybinding action: " + id); return; }
        try {
            var chords = new ArrayList<KeyChord>();
            if (keys != null) for (String key : keys) chords.add(parseChord(key));
            bindings.put(id, chords);
        } catch (IllegalArgumentException invalid) { problems.add(id + ": " + invalid.getMessage()); }
    }

    /**
     * @return 事件是否匹配该动作。释放事件永不匹配；上下文由调用方先判断。
     */
    public boolean matches(String actionId, InputEvent event) {
        if (event == null || !event.actionableKey()) return false;
        for (KeyChord binding : bindings.getOrDefault(actionId, List.of()))
            if (event.key().matches(binding)) return true;
        return false;
    }

    /** @return 动作默认/覆盖后的键；未知动作返回空。 */
    public List<KeyChord> chords(String actionId) { return bindings.getOrDefault(actionId, List.of()); }

    /** @return 动作定义；未知 id 为 null。 */
    public Action action(String actionId) { return actions.get(actionId); }

    /** @return 所有动作，顺序与注册一致。 */
    public List<Action> all() { return List.copyOf(actions.values()); }

    /** @return 当前可展示动作（隐藏后端缺失项）。 */
    public List<Action> visible() {
        return actions.values().stream().filter(Action::visible).toList();
    }

    /** @return 配置覆盖问题，供设置菜单/日志显示；正常为空。 */
    public List<String> problems() { return List.copyOf(problems); }

    /**
     * 检测同一上下文内两个有效动作绑定同一键；返回可读说明。
     * 结果只报告，不自动删除键，避免用户覆盖造成静默失效。
     */
    public List<String> conflicts() {
        var result = new ArrayList<String>();
        for (Action first : visible()) {
            for (Action second : visible()) {
                if (first.id().compareTo(second.id()) >= 0 || first.context() != second.context()) continue;
                for (KeyChord chord : chords(first.id()))
                    if (chords(second.id()).contains(chord))
                        result.add(first.id() + " and " + second.id() + " share " + chord.display());
            }
        }
        return result;
    }

    /** @return 只读的绑定表快照，用于测试/诊断。 */
    public Map<String, List<KeyChord>> snapshot() { return Map.copyOf(bindings); }

    /** @return 某上下文内可用的动作；帮助页按上下文分组。 */
    public List<Action> visibleIn(Context context) {
        return visible().stream().filter(action -> action.context() == context).toList();
    }

    /** @return 动作 ID 集合，供校验配置中的动作名。 */
    public Set<String> actionIds() { return Set.copyOf(actions.keySet()); }
}
