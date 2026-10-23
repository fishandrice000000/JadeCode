package com.jadecode.cli.tui;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.jadecode.config.JadeSettings;
import com.jadecode.cli.SessionHost;
import com.jadecode.cli.tui.approval.ApprovalViewState;
import com.jadecode.cli.tui.bridge.ApprovalBroker;
import com.jadecode.cli.tui.model.TranscriptEntry;
import com.jadecode.cli.tui.view.ChatViewport;
import com.jadecode.cli.tui.view.HotkeysView;
import com.jadecode.cli.tui.view.McpStatusView;
import com.jadecode.cli.tui.view.SessionInfoView;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.tui.components.Editor;
import com.jadecode.tui.components.Input;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.core.ActionToken;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.input.KeyBindings;
import com.jadecode.tui.input.KeyChord;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.LinkTarget;
import com.jadecode.tui.text.Span;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.ThemeManager;
import com.jadecode.tui.viewport.SelectionModel;

/**
 * 语义输入与 UI 事件分派器：审批 > 菜单/选择器 > 编辑器/视口；调用真实 Host 操作。
 *
 * <p>本类不手写编辑器键表（使用 KeyBindings），不直接写终端（renderer 负责），不实现
 * 组件算法。用户意图通过既有 SessionHost/ApprovalBroker 进入业务；所有批准需当前队首、
 * revision、成功 PresentedFrame 与输入水位同时成立。未知 slash 返回本地英文错误并保留
 * 草稿，不发给模型；不提供 Agent 取消/树/模型热切换等无后端动作。</p>
 */
public final class TuiController {
    /** 终端生命周期/剪贴板/外部编辑器端口；由 Application 实现，测试可注入空实现。 */
    public interface TerminalActions {
        default void suspend() { }
        default void openExternalEditor(String draft, Consumer<String> success, Consumer<String> failure) { }
        default void pasteClipboard(Editor editor) { }
        default void copy(String text, boolean explicit) { }
        default void setMode(String mode) { }
        default void openLink(String uri) { }
    }

    private final TuiState state;
    private final SessionHost host;
    private final ApprovalBroker approvals;
    private final ChatViewport chat;
    private final Editor editor;
    private final TuiPreferences preferences;
    private final KeyBindings keys;
    private final CommandCatalog catalog;
    private final SettingsMenu settings;
    private final ThemeManager themes;
    private final Consumer<UiEvent> events;
    private final Runnable shutdown;
    private final TerminalActions terminal;
    private final SessionInfoView sessionInfo = new SessionInfoView();
    private final HotkeysView hotkeys = new HotkeysView();
    private final McpStatusView mcpView = new McpStatusView();

    private long ctrlC;
    private JadeSettings.Tui themeSnapshot;
    /** 最近一次主题文件监听错误；恢复有效文件后只清除这条真实提示。 */
    private String themeWatchError = "";
    /** 最近一次主题预览读取错误；只清除由它写入的提示。 */
    private String themePreviewError = "";
    private final java.util.ArrayDeque<SelectorParent> selectorParents = new java.util.ArrayDeque<>();
    private record SelectorParent(String kind, SelectList list, String filter, String selectedId, String hint,
            String title, String description, String footer, String status) { }
    private boolean savePermissionDefault;
    private java.util.concurrent.CompletableFuture<Void> localWrites =
            java.util.concurrent.CompletableFuture.completedFuture(null);
    private final java.util.Map<Long, List<UiEvent>> awaiting = new java.util.LinkedHashMap<>();
    private int viewportTop, viewportHeight, dockTop, columns, rows;
    private boolean fullscreen;
    /** 全屏屏幕级选区：聊天和编辑器文本都能拖选/复制，与 Pi 一致。 */
    private final SelectionModel screenSelection = new SelectionModel();
    private boolean selectionDragged;
    private boolean selectionPressActive;
    private long lastClickNanos;
    private int clickCount;
    private boolean searchOpen;
    private boolean welcomeExpanded;
    /** 最近成功呈现的原始帧，仅用于把点击坐标映射到同一布局的动作矩形。 */
    private Frame lastFrame;

    public TuiController(TuiState state, SessionHost host, ApprovalBroker approvals, ChatViewport chat,
            Editor editor, TuiPreferences preferences, KeyBindings keys, CommandCatalog catalog,
            SettingsMenu settings, ThemeManager themes, Consumer<UiEvent> events, Runnable shutdown,
            TerminalActions terminal) {
        this.state = state; this.host = host; this.approvals = approvals; this.chat = chat;
        this.editor = editor; this.preferences = preferences; this.keys = keys; this.catalog = catalog;
        this.settings = settings; this.themes = themes; this.events = events; this.shutdown = shutdown;
        this.terminal = terminal == null ? new TerminalActions() { } : terminal;
        state.editor = editor;
    }

    /** 每帧布局后由 Application 调用，供鼠标点击/滚轮坐标换算。 */
    public void layout(int columns, int rows, int viewportTop, int viewportHeight, int dockTop, boolean fullscreen) {
        this.columns = columns; this.rows = rows; this.viewportTop = viewportTop;
        this.viewportHeight = viewportHeight; this.dockTop = dockTop; this.fullscreen = fullscreen;
    }

    /** @return 当前是否有屏幕级选区。 */
    public boolean selectionActive() { return fullscreen && screenSelection.active(); }

    /**
     * 当前前台是否可安全复制选区：必须全屏、有非空选区，且没有审批/选择器/搜索等
     * 覆盖层。modal 下面的旧选区、resize 或 epoch 变化后的选区都不能被 Ctrl+C/右键复制。
     * @return 可用于显式复制的选区是否存在
     */
    public boolean copyableSelectionActive() {
        return selectionActive() && state.approvals.isEmpty() && !state.approval.active()
                && state.selector == null && !searchOpen && !selectedText().isBlank();
    }

    /** resize/模式/会话切换后清除选区与拖动手势，避免复制旧坐标文本。 */
    public void invalidateSelection() {
        screenSelection.clear();
        selectionDragged = false;
        selectionPressActive = false;
    }

    /** @return 当前屏幕级选区纯文字；无选区/无帧为空。 */
    public String selectedText() {
        if (!selectionActive() || lastFrame == null) return "";
        return screenSelection.text(lastFrame.lines());
    }

    /**
     * 把屏幕级选区高亮合并进帧行；只有全屏模式有效，regular 交给终端原生选择。
     * 文本、命中与选区使用同一帧坐标，resize 后由 Application 失效重建。
     */
    public List<StyledLine> applySelection(List<StyledLine> lines) {
        if (!selectionActive()) return lines;
        var result = new java.util.ArrayList<StyledLine>(lines.size());
        for (int row = 0; row < lines.size(); row++) {
            StyledLine line = lines.get(row);
            var range = screenSelection.rangeFor(row, line.text());
            if (range.empty()) { result.add(line); continue; }
            var spans = new java.util.ArrayList<Span>();
            int column = 0;
            for (Span span : line.spans()) {
                for (String cluster : com.jadecode.tui.text.DisplayWidth.clusters(span.text())) {
                    int cells = com.jadecode.tui.text.DisplayWidth.clusterWidth(cluster);
                    boolean selected = column >= range.fromColumn() && column < range.toColumn();
                    spans.add(new Span(cluster, selected
                            ? span.style().withBg(themes.preview().color("selectedBg")) : span.style(), span.link()));
                    column += cells;
                }
            }
            result.add(new StyledLine(spans, line.annotation()));
        }
        return result;
    }

    /** @return 搜索 Overlay 是否打开。 */
    public boolean searchOpen() { return searchOpen; }

    /** @return 欢迎资源区是否由 Ctrl+O 展开。 */
    public boolean welcomeExpanded() { return welcomeExpanded; }

    /** Application 成功呈现后调用，建立动作证据并让审批水位对齐。 */
    public void presented(PresentedFrame frame, Frame rawFrame) {
        // 兼容已有内存帧调用：首次呈现前 state 可能尚未收到任何终端事件。
        // 不能倒退已知 epoch；生产 Application 使用下方显式终端代次/生产水位入口。
        presented(frame, rawFrame, state.inputSequence,
                frame == null ? state.terminalEpoch : Math.max(state.terminalEpoch, frame.terminalEpoch()));
    }

    /** 成功 flush 后绑定当前代次及生产端水位；旧帧不因 UI 消费延迟而恢复资格。 */
    public void presented(PresentedFrame frame, Frame rawFrame, long producedWatermark, long epoch) {
        if (frame == null || frame.terminalEpoch() != epoch) { invalidateActions(); return; }
        state.terminalEpoch = epoch;
        state.presented = frame;
        lastFrame = rawFrame;
        if (state.approval.active()) state.approval.presented(frame, epoch, producedWatermark);
    }

    /** 模式切换/resize 立即失效旧动作。 */
    public void invalidateActions() { state.presented = null; lastFrame = null; state.approval.invalidate(); }

    /** 应用事件；返回 false 表示清理完成，可以结束 UI 循环。 */
    public boolean accept(UiEvent uiEvent) {
        switch (uiEvent) {
            case UiEvent.Scoped scoped -> {
                if (scoped.generation() == state.generation) return accept(scoped.event());
                if (scoped.generation() > state.generation)
                    awaiting.computeIfAbsent(scoped.generation(), ignored -> new java.util.ArrayList<>()).add(scoped.event());
                // 旧代次迟到事件直接丢弃，不能把旧回复/工具卡接到新会话。
            }
            case UiEvent.SessionOpened opened -> {
                var session = opened.opened();
                state.installGeneration(session.generation());
                invalidateSelection();
                state.sessionId = session.restored().metadata().id();
                HistoryViewMapper.restore(state, session.restored());
                editor.setGeneration(state.generation);
                for (UiEvent initial : awaiting.getOrDefault(state.generation, List.of())) accept(initial);
                awaiting.clear();
                refreshSession();
                state.hint = "Enter submit · / for commands · Esc closes local UI";
                host.start(state.generation);
            }
            case UiEvent.SessionList list -> {
                state.loadingResume = false;
                state.sessionPicker = new SessionPicker(list.sessions());
                openResumeSelector(state.sessionPicker.choices());
                if (list.sessions().isEmpty()) state.selectorStatus = UiMessages.NO_SESSIONS;
                refreshSelectorMeta();
            }
            case UiEvent.ResumeFailed failed -> {
                state.loadingResume = false; awaiting.clear();
                state.hint = failed.message();
                state.transcript.addNotice("System", "Resume", failed.message(), true);
            }
            case UiEvent.Input input -> {
                // resize/恢复之后迟到的旧鼠标与按键不能把当前 epoch 改回去。
                if (input.input().terminalEpoch() < state.terminalEpoch) return true;
                state.inputSequence = Math.max(state.inputSequence, input.input().sequence());
                state.terminalEpoch = input.input().terminalEpoch();
                input(input.input());
            }
            case UiEvent.Execution execution -> state.execution(execution.notice());
            case UiEvent.StreamStarted started -> state.transcript.requestStarted(started.request(), started.actor());
            case UiEvent.Delta delta -> state.delta(delta);
            case UiEvent.ToolPreviewStarted preview -> state.transcript.previewStarted(preview.request(), preview.actor(),
                    preview.blockIndex(), preview.callId(), preview.name(), preview.initialInput(), preview.identity());
            case UiEvent.ToolPreviewDelta preview -> state.transcript.previewDelta(preview.request(), preview.blockIndex(),
                    preview.fragment());
            case UiEvent.ToolPreviewClosed preview -> state.transcript.previewClosed(preview.request(), preview.blockIndex());
            case UiEvent.StreamEnd end -> state.end(end);
            case UiEvent.Message message -> state.message(message);
            case UiEvent.ApprovalRequested request -> {
                if (state.closing) approvals.answer(request.id(), ApprovalAnswer.DENY);
                else {
                    // 审批优先于信息弹窗/搜索/菜单：先撤下 modal，避免按键继续被它消费。
                    closeCommandPanel();
                    if (searchOpen) { search.close(); searchOpen = false; }
                    // 选择页也必须撤下；未确认的 Theme 预览回退，不能把预览当作用户选择保存。
                    if (state.selector != null) {
                        if (state.selectorKind.equals("Theme") && themes != null) themes.cancelPreview();
                        closeSelector();
                    }
                    if (state.approvals.isEmpty()) {
                        var snapshot = new UiEvent.ApprovalRequested(request.id(), request.actor(), request.tool(),
                                request.arguments(), request.reason(), request.suggestion(), request.revision());
                        state.approvals.addLast(snapshot);
                        state.approval.show(snapshot, state.inputSequence, state.terminalEpoch);
                    } else state.approvals.addLast(request);
                }
            }
            case UiEvent.ApprovalRemoved removed -> removeApproval(removed.id());
            case UiEvent.Branch branch -> state.branch = branch.branch();
            case UiEvent.ClipboardText text -> {
                if (text.text().isEmpty()) return true;
                // 用户显式 Ctrl+V 是插入，不是替换整份草稿；保留光标/撤销边界。
                editor.insert(text.text(), true);
            }
            case UiEvent.ExternalEditorResult text -> editor.replaceText(text.text());
            case UiEvent.Repaint ignored -> { /* Application 已消费并请求重绘。 */ }
            case UiEvent.ThemeLoaded loaded -> adoptThemePreview(loaded.result());
            case UiEvent.ThemeWatched watched -> adoptThemeWatch(watched.result());
            case UiEvent.Flash flash -> state.flash(flash.text(), flash.durationMillis(), flash.error());
            case UiEvent.TerminalFailure failure -> { state.hint = failure.message(); close(); }
            case UiEvent.Stopped stopped -> { return false; }
        }
        return true;
    }

    /** 从当前会话读取已发布统计/权限；不读盘、不等模型，也不访问可变 history。 */
    public boolean refreshSession() {
        boolean changed = false;
        var permission = host.permissionSnapshot();
        if (permission != null) {
            String profile = permission.profile().value();
            changed = !profile.equals(state.permissionProfile); state.permissionProfile = profile;
        }
        var next = host.display();
        if (next != null && (!next.id().equals(state.sessionId) || !next.equals(state.session))) {
            state.session = next; state.model = next.model(); state.modelInfo = next.metadata().describe();
            changed = true;
        }
        var servers = host.mcpServers();
        if (!servers.equals(state.mcpServers)) { state.mcpServers = servers; changed = true; }
        return changed;
    }

    /** 处理一个已解码输入。先全局退出/暂退，再按审批/菜单/编辑器/视口优先级路由。 */
    private void input(InputEvent event) {
        if (state.closing) return;
        switch (event.kind()) {
            case RESIZE -> { invalidateActions(); invalidateSelection(); editor.setGeneration(state.generation); return; }
            case EOF -> { close(); return; }
            case FAILURE -> { state.transcript.addNotice("System", "Terminal", event.text(), true); close(); return; }
            case REPLY -> { return; }
            case MOUSE -> { mouse(event); return; }
            default -> { }
        }
        if (event.kind() != InputEvent.Kind.KEY && event.kind() != InputEvent.Kind.TEXT
                && event.kind() != InputEvent.Kind.PASTE) return;
        // 信息弹窗是前台 modal：审批未到时先消费导航/滚动/关闭，不落到编辑器、旧选区或退出逻辑。
        if (state.commandPanel != null && state.approvals.isEmpty()) { commandPanelInput(event); return; }
        if (event.actionableKey()) {
            KeyChord key = event.key();
            // 选择器的 Ctrl+C 是取消选择，不是全局清草稿；与 KeyBindings 的 select.cancel 对齐。
            if (state.selector != null && key.key().equals("c") && key.has(KeyChord.Modifier.CTRL)) {
                cancelSelector(); return;
            }
            // 全局会话动作：关闭/退出/暂退/剪贴板/展开。审批期间也不允许 Ctrl+C 清掉参数草稿。
            if (matches("app.exit", event) && state.approvals.isEmpty() && state.selector == null && editor.text().isEmpty()) { close(); return; }
            if (key.key().equals("c") && key.has(KeyChord.Modifier.CTRL)) {
                // S31：有效选区优先复制，且不计入连续 Ctrl+C 退出窗口；无选区才走原清稿/退出。
                if (copySelectionIfPresent()) return;
                ctrlC(event); return;
            }
            if (matches("app.suspend", event)) { terminal.suspend(); return; }
            if (matches("app.editor.external", event)) {
                terminal.openExternalEditor(editor.expandedText(), value -> {
                    events.accept(new UiEvent.Message("System", "Editor", "External draft applied", false));
                    // 结果必须回到 UI 线程；Application 在事件循环中调用这里。
                    pendingExternalEditor = value;
                }, message -> events.accept(new UiEvent.Message("System", "Editor", message, true)));
                return;
            }
            if (matches("app.clipboard.pasteImage", event)) { terminal.pasteClipboard(editor); return; }
            if (matches("app.message.copy", event)) { copySelectionOrLastReply(); return; }
            if (matches("app.tools.expand", event) && state.approvals.isEmpty()) {
                state.toolsExpanded = !state.toolsExpanded;
                state.transcript.setToolsExpanded(state.toolsExpanded);
                welcomeExpanded = !welcomeExpanded;
                return;
            }
            if (matches("app.thinking.toggle", event) && state.approvals.isEmpty()) {
                state.thinkingVisible = !state.thinkingVisible; return;
            }
            if (matches("app.thinking.cycle", event) && state.approvals.isEmpty()) { cycleThinking(); return; }
            if (matches("app.session.new", event) && state.approvals.isEmpty()) { resume(null); return; }
            if (matches("app.session.resume", event) && state.approvals.isEmpty()) { openSessionList(); return; }
            if (matches("app.permission.menu", event) && state.approvals.isEmpty()) {
                if (host.hasCurrent()) {
                    state.selectorKind = "Permission";
                    state.selector = PermissionMenu.choices(host.current().permissionState().snapshot().profile());
                } else state.hint = "Open a session first";
                return;
            }
            if (matches("tui.altScreen.halfPageUp", event)) { chat.scrollLines(-Math.max(1, viewportHeight / 2)); return; }
            if (matches("tui.altScreen.halfPageDown", event)) { chat.scrollLines(Math.max(1, viewportHeight / 2)); return; }
            if (matches("tui.altScreen.lineUp", event)) { chat.scrollLines(-1); return; }
            if (matches("tui.altScreen.lineDown", event)) { chat.scrollLines(1); return; }
            if (key.key().equals("f") && key.has(KeyChord.Modifier.CTRL) && state.approvals.isEmpty() && state.selector == null) {
                openSearch(); return;
            }
        }
        // 搜索 Overlay 优先于编辑器；它消费文字/Esc/Enter。
        if (searchOpen) {
            if (search.handle(event)) updateSearchOpen();
            return;
        }
        if (!state.approvals.isEmpty()) { approvalInput(event); return; }
        if (state.selector != null) { selectorInput(event); return; }
        // 全屏 PageUp/PageDown 在编辑器为空时滚动转录；有草稿时仍由编辑器翻页。
        if (event.actionableKey() && (event.key().key().equals("pageup") || event.key().key().equals("pagedown"))
                && editor.text().isEmpty()) {
            chat.page(event.key().key().equals("pageup") ? -1 : 1); return;
        }
        if (event.actionableKey() && (matches("tui.altScreen.top", event) || matches("tui.altScreen.bottom", event))) {
            if (matches("tui.altScreen.top", event)) chat.top(); else chat.bottom();
            return;
        }
        if (editor.handle(event)) return;
        // 未消费的 Esc 是本地关闭提示，不是 Agent 取消。
        if (event.actionableKey() && event.key().key().equals("escape")) {
            state.hint = UiMessages.NO_AGENT_CANCEL;
        }
    }

    private String pendingExternalEditor;
    private final com.jadecode.tui.overlay.SearchOverlay search = new com.jadecode.tui.overlay.SearchOverlay();

    /** 由 Application 在每个事件循环调用；外部编辑器结果只在 UI 线程采用。 */
    public void applyPendingExternalEditor() {
        if (pendingExternalEditor == null) return;
        editor.replaceText(pendingExternalEditor);
        pendingExternalEditor = null;
    }

    /** @return 搜索 Overlay，Application 用于合成。 */
    public com.jadecode.tui.overlay.SearchOverlay searchOverlay() { return search; }

    private void openSearch() {
        search.open(chat); searchOpen = true;
        state.hint = "Search · Enter next · Shift+Enter previous · Esc close";
    }

    private void updateSearchOpen() { searchOpen = search.visible(); if (!searchOpen) state.hint = ""; }

    private void ctrlC(InputEvent event) {
        long now = System.nanoTime();
        if (ctrlC != 0 && now - ctrlC < 500_000_000L) { close(); return; }
        ctrlC = now;
        if (state.approvals.isEmpty() && state.selector == null) editor.clear();
        state.hint = "Press Ctrl+C again within 500ms to exit; Esc does not cancel the agent";
    }

    /**
     * 全屏鼠标：滚轮滚动；审批按钮只在显式按下且证据齐全时答复；
     * 其余按下/拖动/释放走屏幕级选区，单击合成编辑器光标/链接动作。
     * regular 不进入这里，保留终端原生选择。
     */
    private void mouse(InputEvent event) {
        if (!fullscreen) return;
        InputEvent.Mouse mouse = event.mouse();
        int row = mouse.y(), column = mouse.x();
        // 命令弹窗 modal：滚轮只滚动前台页面，点击/右键不穿透到底层聊天或审批。
        if (state.commandPanel != null && state.approvals.isEmpty()) {
            if (mouse.wheel() && mouse.wheelDirection() != 0) state.commandPanel.scrollBy(mouse.wheelDirection());
            return;
        }
        if (mouse.wheel()) {
            if (state.selector != null && mouse.wheelDirection() != 0) {
                state.selector.move(mouse.wheelDirection());
                if (state.selectorKind.equals("Theme")) previewSelector();
                refreshSelectorMeta();
                return;
            }
            // auto/固定行数与 Alt 倍速都由 ChatViewport 的同一策略计算，不能再乘一次。
            chat.wheel(mouse.wheelDirection(), mouse.alt());
            return;
        }
        PresentedFrame frame = state.presented;
        if (frame == null || lastFrame == null) return;
        // 右键：只在当前前台有效选区上复制一次；不重建选区、不激活链接、不答复审批，
        // 也不回退复制最后回复。必须在通用按下/审批命中之前处理。
        if (mouse.button() == 2) {
            if (mouse.action() == InputEvent.Mouse.Action.PRESS && copySelectionIfPresent()) {
                return;
            }
            return;
        }
        // 只有显式左键能进入选区/链接/审批；中键等不产生动作。
        if (mouse.button() != 0) return;
        ActionToken hit = lastFrame.actionAt(column, row);
        // 选择器打开时点击只作用于面板候选；点击搜索/说明区也不穿透成聊天选区。
        if (state.selector != null && !state.approval.active()) {
            if (mouse.action() == InputEvent.Mouse.Action.PRESS && hit != null
                    && hit.kind() == ActionToken.Kind.SELECT_ITEM && hit.validAt(frame.revision())) {
                state.selector.selectId(hit.objectId());
                if (state.selectorKind.equals("Theme")) previewSelector();
                refreshSelectorMeta();
            }
            return;
        }
        // 审批优先：按下按钮必须满足显式左键/命中/水位/已呈现；拖动与释放绝不答复。
        if (state.approval.active()) {
            if (mouse.action() == InputEvent.Mouse.Action.PRESS && hit != null && hit.isApproval()
                    && state.approval.canAnswer(event.sequence(), state.terminalEpoch, frame, hit.kind())) {
                approvals.answer(state.approval.request().id(),
                        hit.kind() == ActionToken.Kind.DENY ? ApprovalAnswer.DENY
                                : hit.kind() == ActionToken.Kind.ALLOW_SIMILAR ? ApprovalAnswer.ALLOW_SIMILAR
                                : ApprovalAnswer.ALLOW_ONCE);
                removeApproval(state.approval.request().id());
            }
            return;
        }
        switch (mouse.action()) {
            case PRESS -> {
                long now = System.nanoTime();
                clickCount = now - lastClickNanos < 400_000_000L ? Math.min(3, clickCount + 1) : 1;
                lastClickNanos = now;
                selectionDragged = false;
                selectionPressActive = true;
                screenSelection.start(new SelectionModel.Position(Math.max(0, row), Math.max(0, column)), clickCount);
            }
            case DRAG -> {
                if (!selectionPressActive) return;
                screenSelection.drag(new SelectionModel.Position(Math.max(0, row), Math.max(0, column)));
                selectionDragged = true;
                autoScrollSelection(row);
            }
            case RELEASE -> {
                // 释放必须属于一次真实按下；重复释放或没有按下的释放不得复制/点击。
                if (!selectionPressActive) return;
                selectionPressActive = false;
                boolean dragged = selectionDragged;
                selectionDragged = false;
                if (!dragged) {
                    LinkTarget link = linkAt(row, column);
                    if (link != null) { screenSelection.clear(); terminalsOpen(link); return; }
                    if (editorClick(row, column)) { screenSelection.clear(); return; }
                    screenSelection.clear();
                    return;
                }
                screenSelection.finish();
                // 一次拖选只复制一次；选区保留，重复释放不再触发。
                String text = screenSelection.text(lastFrame.lines());
                if (!text.isBlank() && chat.copyOnSelect()) terminal.copy(text, false);
            }
            default -> { }
        }
    }

    /** 拖出视口边缘时自动滚动聊天；焦点夹回可见边缘，避免坐标越界。 */
    private void autoScrollSelection(int row) {
        if (row < viewportTop) {
            chat.scrollLines(-2);
            screenSelection.drag(new SelectionModel.Position(viewportTop, 0));
        } else if (row >= viewportTop + viewportHeight) {
            chat.scrollLines(2);
            screenSelection.drag(new SelectionModel.Position(Math.max(viewportTop, viewportTop + viewportHeight - 1),
                    lastFrame.lines().isEmpty() ? 0
                            : Math.max(0, lastFrame.lines().get(Math.min(lastFrame.lines().size() - 1,
                            viewportTop + viewportHeight - 1)).width() - 1)));
        }
    }

    /** @return 帧内该位置是否带 OSC8 链接。 */
    private LinkTarget linkAt(int row, int column) {
        if (lastFrame == null || row < 0 || row >= lastFrame.lines().size()) return null;
        int used = 0;
        for (Span span : lastFrame.lines().get(row).spans()) {
            int width = com.jadecode.tui.text.DisplayWidth.width(span.text());
            if (column >= used && column < used + width) return span.link();
            used += width;
        }
        return null;
    }

    /** 单击编辑器：候选区选中对应项，内容区把光标放到对应字素；边框不处理。 */
    private boolean editorClick(int row, int column) {
        if (state.selector != null || state.editor == null) return false;
        int editorRow = row - dockTop;
        int candidateRow = editorRow - state.editor.completionStartRow();
        if (candidateRow >= 0 && candidateRow < state.editor.completionRows())
            return state.editor.clickCompletion(candidateRow);
        int contentRow = editorRow - 1;
        if (contentRow < 0 || contentRow >= state.editor.visibleRows()) return false;
        state.editor.caretForClick(contentRow, column, columns);
        return true;
    }

    private void terminalsOpen(LinkTarget link) {
        // 打开链接是外部副作用；只接受用户明确点击，并交给 Application 的受控端口。
        terminal.openLink(link.uri());
    }

    private void cycleThinking() {
        if (state.session == null || state.session.thinkingChoices().size() < 2) {
            state.hint = UiMessages.NO_THINKING; return;
        }
        var choices = state.session.thinkingChoices();
        int index = choices.indexOf(state.session.thinking());
        var next = choices.get((index + 1) % choices.size());
        saveLocal("Thinking", id -> host.thinking(id, next), "Thinking level: " + next.value());
    }

    /** 复制最近助手回复；/copy 动作固定走这里。 */
    private void copyLastReply() {
        String text = state.transcript.lastReplyText();
        if (text.isBlank()) { state.hint = UiMessages.COPY_EMPTY; return; }
        terminal.copy(text, true);
    }

    /**
     * 复制当前屏幕选区并保留选区/草稿/退出计时；没有可复制选区返回 false。
     * 一次调用只发起一次复制；自动 copy-on-select 与显式复制互不叠加。
     */
    private boolean copySelectionIfPresent() {
        if (!copyableSelectionActive()) return false;
        String text = selectedText();
        if (text.isBlank()) return false;
        terminal.copy(text, true);
        return true;
    }

    /**
     * Ctrl+X/菜单复制：有效屏幕选区优先；否则聊天组件选区；最后最近助手回复。
     * 有 modal 时不复制下面隐藏的选区，但无选区时仍允许复制最后回复。
     */
    public void copySelectionOrLastReply() {
        if (copySelectionIfPresent()) return;
        String text = chat != null && chat.selection().active() && state.approvals.isEmpty()
                && state.selector == null && !searchOpen ? chat.selection().text(chat.document())
                : state.transcript.lastReplyText();
        if (text.isBlank()) { state.hint = UiMessages.COPY_EMPTY; return; }
        terminal.copy(text, true);
    }

    /** 审批输入：上下/左右/Tab 选择，翻页只阅读；Enter 显式确认，Esc 安全拒绝当前项。 */
    private void approvalInput(InputEvent event) {
        if (event.kind() != InputEvent.Kind.KEY || !event.actionableKey()) return;
        KeyChord key = event.key();
        var approval = state.approval;
        if (matches("approval.previous", event) || matches("approval.next", event)) {
            approval.move(matches("approval.previous", event) ? -1 : 1, !approval.request().suggestion().isBlank());
            return;
        }
        if (matches("approval.pageUp", event) || matches("approval.pageDown", event)) {
            approval.scroll((matches("approval.pageUp", event) ? -1 : 1) * Math.max(1, approval.pageSize()));
            return;
        }
        switch (key.key()) {
            case "home" -> { approval.scroll(-approval.totalLines()); return; }
            case "end" -> { approval.scroll(approval.totalLines()); return; }
            default -> { }
        }
        // 导航可接收 repeat，确认/拒绝只接收 PRESS，避免长按连批或连拒下一请求。
        if (key.phase() != KeyChord.Phase.PRESS) return;
        if (matches("approval.confirm", event) || matches("approval.deny", event)) {
            var answer = matches("approval.deny", event) ? ApprovalAnswer.DENY : approval.selectedAnswer();
            if (answer == null) return;
            ActionToken.Kind kind = answer == ApprovalAnswer.DENY ? ActionToken.Kind.DENY
                    : answer == ApprovalAnswer.ALLOW_SIMILAR ? ActionToken.Kind.ALLOW_SIMILAR : ActionToken.Kind.APPROVE;
            if (!approval.canAnswer(event.sequence(), state.terminalEpoch, state.presented, kind)) return;
            approvals.answer(approval.request().id(), answer);
            removeApproval(approval.request().id());
            return;
        }
        // 普通文字/粘贴/空 Enter 不批准也不拒绝。
    }

    private void removeApproval(String id) {
        boolean first = !state.approvals.isEmpty() && state.approvals.getFirst().id().equals(id);
        state.approvals.removeIf(request -> request.id().equals(id));
        if (first) {
            state.approval.clear();
            if (!state.approvals.isEmpty()) {
                var next = state.approvals.getFirst();
                state.approval.show(next, state.inputSequence, state.terminalEpoch);
            } else state.hint = "";
        }
    }

    /** 打开命令信息弹窗；不写 Transcript/history，不创建第二套事件循环。 */
    private void openCommandPanel(com.jadecode.cli.tui.model.CommandPanelModel model) {
        state.commandPanel = new com.jadecode.cli.tui.view.CommandPanelView(model);
        state.hint = "";
    }

    /** 关闭命令信息弹窗；Esc/审批到达/会话切换/关闭路径复用。 */
    private void closeCommandPanel() {
        state.commandPanel = null;
    }

    /**
     * 信息弹窗输入：只处理前台页面的滚动/分组/服务选择与关闭；其它输入（含 Ctrl+C/Ctrl+X、
     * 字符、粘贴、旧选区复制）一律消费，不能穿透到 Editor/退出逻辑。
     */
    private void commandPanelInput(InputEvent event) {
        var panel = state.commandPanel;
        if (panel == null) return;
        if (event.kind() == InputEvent.Kind.TEXT || event.kind() == InputEvent.Kind.PASTE) return;
        if (event.kind() != InputEvent.Kind.KEY || !event.actionableKey()) return;
        KeyChord key = event.key();
        switch (key.key()) {
            case "escape" -> { closeCommandPanel(); return; }
            case "up" -> { panel.moveService(-1); return; }
            case "down" -> { panel.moveService(1); return; }
            case "pageup" -> { panel.page(-1); return; }
            case "pagedown" -> { panel.page(1); return; }
            case "home" -> { panel.top(); return; }
            case "end" -> { panel.end(); return; }
            case "tab" -> { panel.moveSection(key.has(KeyChord.Modifier.SHIFT) ? -1 : 1); return; }
            case "left" -> { if (panel.model().kind() == com.jadecode.cli.tui.model.CommandPanelModel.Kind.HOTKEYS)
                    panel.moveSection(-1); else panel.moveService(-1); return; }
            case "right" -> { if (panel.model().kind() == com.jadecode.cli.tui.model.CommandPanelModel.Kind.HOTKEYS)
                    panel.moveSection(1); else panel.moveService(1); return; }
            default -> { }
        }
    }

    /**
     * 选择器输入：搜索框使用 Input（字素/光标/水平滚动），上下/翻页/Enter/Esc 由这里处理。
     * 设置页查询为空时 Space 切值；非空查询里的空格属于搜索文本，不吞成切换。释放事件
     * 由 actionableKey 过滤，不会重复执行。
     */
    private void selectorInput(InputEvent event) {
        if (state.selector == null) return;
        // 测试/恢复路径可能只有候选而没有 Input；按当前 kind 延迟建立，保持同一语义。
        if (state.selectorInput == null) initSelectorInput(selectorPlaceholder(state.selectorKind), state.selector.filter());
        if (event.kind() == InputEvent.Kind.TEXT) {
            if (event.text().equals(" ") && state.selectorKind.equals("Settings")
                    && state.selectorInput.text().isEmpty()) {
                chooseSelector();
                return;
            }
            if (state.selectorInput.handle(event)) syncSelectorFilter();
            return;
        }
        if (event.kind() == InputEvent.Kind.PASTE) {
            if (state.selectorInput.handle(event)) syncSelectorFilter();
            return;
        }
        if (event.kind() != InputEvent.Kind.KEY || !event.actionableKey()) return;
        KeyChord key = event.key();
        switch (key.key()) {
            case "escape" -> { cancelSelector(); return; }
            case "up" -> { state.selector.move(-1); previewSelector(); refreshSelectorMeta(); return; }
            case "down" -> { state.selector.move(1); previewSelector(); refreshSelectorMeta(); return; }
            case "pageup" -> { state.selector.page(-1, 5); refreshSelectorMeta(); return; }
            case "pagedown" -> { state.selector.page(1, 5); refreshSelectorMeta(); return; }
            case "enter" -> { chooseSelector(); return; }
            default -> { }
        }
        if (key.key().equals("n") && key.has(KeyChord.Modifier.CTRL) && state.selectorKind.equals("Resume")) {
            state.sessionPicker.toggleNamedOnly(); rebuildSessionSelector(); return;
        }
        if (key.key().equals("p") && key.has(KeyChord.Modifier.CTRL) && state.selectorKind.equals("Resume")) {
            state.sessionPicker.togglePath(); rebuildSessionSelector(); return;
        }
        if (key.key().equals("s") && key.has(KeyChord.Modifier.CTRL) && state.selectorKind.equals("Resume")) {
            state.sessionPicker.toggleSort(); rebuildSessionSelector(); return;
        }
        // 左右/Home/End/退格/删除等编辑键交给 Input，保持字素边界与搜索光标。
        if (state.selectorInput.handle(event)) syncSelectorFilter();
    }

    /** 搜索框文字是唯一查询源；过滤后重新计算说明和窗口。 */
    private void syncSelectorFilter() {
        if (state.selector == null || state.selectorInput == null) return;
        state.selector.filter(state.selectorInput.text());
        refreshSelectorMeta();
    }

    private void initSelectorInput(String placeholder, String filter) {
        state.selectorInput = new Input(placeholder, text -> { });
        state.selectorInput.setText(filter == null ? "" : filter);
    }

    private String selectorPlaceholder(String kind) {
        return switch (kind) {
            case "Settings" -> "Search settings";
            case "Theme" -> "Search themes";
            case "Resume" -> "Search sessions";
            case "Thinking" -> "Search thinking levels";
            case "Permission" -> "Search permissions";
            case "PermissionConfirm" -> "Search";
            default -> "Search";
        };
    }

    private void openSettingsSelector() {
        selectorParents.clear();
        state.selectorKind = "Settings";
        state.selector = settings.list();
        state.selectorTitle = "Settings";
        state.selectorFooter = "Type to search · Enter/Space to change · Esc to cancel";
        state.selectorStatus = "";
        state.hint = "";
        initSelectorInput(selectorPlaceholder("Settings"), "");
        refreshSelectorMeta();
    }

    /** 打开 Theme 子菜单；父 Settings 的筛选/选中和逻辑设置快照在栈中保存。 */
    private void openThemeSelector() {
        pushSelectorParent();
        themeSnapshot = settings.current();
        themes.beginPreview();
        state.selectorKind = "Theme";
        var items = new java.util.ArrayList<SelectList.Item>();
        for (String choice : settings.themeChoices())
            items.add(new SelectList.Item(choice, choice, ""));
        state.selector = new SelectList(items, true);
        state.selector.selectId(settings.current().theme());
        state.selectorTitle = "Theme";
        state.selectorFooter = "Enter apply · Esc back";
        state.selectorStatus = "";
        state.hint = "";
        initSelectorInput(selectorPlaceholder("Theme"), "");
        refreshSelectorMeta();
    }

    private void openResumeSelector(SelectList choices) {
        selectorParents.clear();
        state.selectorKind = "Resume";
        state.selector = choices;
        state.selectorTitle = "Resume session";
        state.selectorFooter = "Enter resume · Ctrl+N named · Ctrl+P path · Ctrl+S sort · Esc cancel";
        state.hint = "";
        initSelectorInput(selectorPlaceholder("Resume"), "");
        refreshSelectorMeta();
    }

    private void openThinkingSelector() {
        selectorParents.clear();
        state.selectorKind = "Thinking";
        var items = new java.util.ArrayList<SelectList.Item>();
        var current = state.session == null ? null : state.session.thinking();
        for (var thinking : state.session.thinkingChoices())
            items.add(new SelectList.Item(thinking.value(),
                    (thinking.equals(current) ? "✓ " : "  ") + thinking.value(),
                    thinking.value()));
        state.selector = new SelectList(items, true);
        state.selector.selectId(current == null ? "" : current.value());
        state.selectorTitle = "Thinking level";
        state.selectorFooter = "Enter select · Esc back";
        state.selectorStatus = "";
        state.hint = "";
        initSelectorInput(selectorPlaceholder("Thinking"), "");
        refreshSelectorMeta();
    }

    private void openPermissionSelector() {
        selectorParents.clear();
        state.selectorKind = "Permission";
        state.selector = PermissionMenu.choices(host.current().permissionState().snapshot().profile());
        state.selectorTitle = "Permission";
        state.selectorFooter = "Enter select · Esc cancel";
        state.selectorStatus = "";
        state.hint = "";
        initSelectorInput(selectorPlaceholder("Permission"), "");
        refreshSelectorMeta();
    }

    /** Full Access 的二次确认是 Permission 子页；Esc 返回父页，不直接扩大授权。 */
    private void openPermissionConfirm() {
        pushSelectorParent();
        state.selectorKind = "PermissionConfirm";
        state.selector = PermissionMenu.confirm();
        state.selectorTitle = "Enable Full Access?";
        state.selectorFooter = "Enter confirm · Esc back";
        state.selectorStatus = "";
        state.hint = "";
        initSelectorInput(selectorPlaceholder("PermissionConfirm"), "");
        refreshSelectorMeta();
    }

    private void pushSelectorParent() {
        if (state.selector == null) return;
        selectorParents.push(new SelectorParent(state.selectorKind, state.selector, state.selector.filter(),
                state.selector.selected(), state.hint, state.selectorTitle, state.selectorDescription,
                state.selectorFooter, state.selectorStatus));
    }

    /**
     * 返回父选择器并保留筛选/选中；Settings 父页用当前设置快照重建，Theme 确认后行值立即更新。
     */
    private void restoreSelectorParent(boolean rebuildSettings) {
        SelectorParent parent = selectorParents.poll();
        if (parent == null) { closeSelector(); return; }
        state.selectorKind = parent.kind();
        state.selector = rebuildSettings && parent.kind().equals("Settings") ? settings.list() : parent.list();
        state.selector.filter(parent.filter());
        state.selector.selectId(parent.selectedId());
        state.selectorTitle = parent.title();
        state.selectorDescription = parent.description();
        state.selectorFooter = parent.footer();
        state.selectorStatus = parent.status();
        state.hint = parent.hint();
        initSelectorInput(selectorPlaceholder(parent.kind()), parent.filter());
        refreshSelectorMeta();
    }

    private void closeSelector() {
        state.selector = null; state.selectorInput = null; state.selectorKind = "";
        state.selectorTitle = ""; state.selectorDescription = ""; state.selectorFooter = ""; state.selectorStatus = "";
        state.hint = "";
        selectorParents.clear();
    }

    /** 设置/权限/主题/思考/会话的选中说明随当前项变化。 */
    private void refreshSelectorMeta() {
        if (state.selector == null) return;
        String selected = state.selector.selected();
        String kind = state.selectorKind;
        switch (kind) {
            case "Settings" -> {
                state.selectorDescription = selected == null ? "" : settings.description(selected);
                state.selectorStatus = "";
            }
            case "Theme" -> {
                String current = settings.current().theme();
                String effective = themes.preview().name();
                state.selectorDescription = "Current setting: " + current + " · effective palette: " + effective
                        + (selected == null || selected.equals(current) ? "" : " · preview: " + selected);
                state.selectorStatus = "";
            }
            case "Resume" -> {
                var item = state.selector.selectedItem();
                state.selectorDescription = item == null ? "" : "Resume: " + item.label();
                if (state.selector.size() == 0 && !state.selectorStatus.isBlank()) {
                    // 空列表/无匹配保留 Controller 设置的真实提示，不用排序状态覆盖。
                } else {
                    state.selectorStatus = (state.sessionPicker.namedOnly() ? "Named only" : "All sessions")
                            + " · " + (state.sessionPicker.pathsVisible() ? "Paths shown" : "Paths hidden")
                            + " · " + (state.sessionPicker.recentFirst() ? "Newest first" : "Title order");
                }
            }
            case "Thinking" -> {
                String current = state.session == null || state.session.thinking() == null ? "unknown"
                        : state.session.thinking().value();
                state.selectorDescription = "Current session level: " + current
                        + ". Changes the next request; no default pool is saved.";
                state.selectorStatus = "";
            }
            case "Permission" -> {
                state.selectorDescription = PermissionMenu.description(selected);
                state.selectorStatus = "";
            }
            case "PermissionConfirm" -> {
                state.selectorDescription = "Full Access skips routine prompts; explicit denies and hard protections remain.";
                state.selectorStatus = "";
            }
            default -> state.selectorDescription = "";
        }
    }

    /** 主题子页只预览；Esc 会恢复原逻辑 theme+配对快照。 */
    private void previewSelector() {
        if (!state.selectorKind.equals("Theme") || themes == null) return;
        String selected = state.selector.selected();
        if (selected == null) return;
        ThemeManager.LoadRequest request;
        try {
            request = themes.requestPreview(selected);
        } catch (RuntimeException failure) {
            state.hint = failure.getMessage();
            return;
        }
        // 文件读取/解析放 worker；结果只能经 UiEvent 回到 UI 线程，不在 worker 写主题状态。
        Thread.ofVirtual().name("jade-theme-preview").start(() -> {
            ThemeManager.LoadResult result;
            try {
                var document = request.builtin()
                        ? com.jadecode.tui.theme.ThemePalette.builtinDocument()
                        : com.jadecode.tui.theme.ThemeDocument.read(request.file());
                result = new ThemeManager.LoadResult(request, document, null);
            } catch (RuntimeException failure) {
                result = new ThemeManager.LoadResult(request, null, failure.getMessage());
            }
            events.accept(new UiEvent.ThemeLoaded(result));
        });
    }

    /** 采纳预览读取结果；迟到 ticket 由 Manager 丢弃，失败只作为真实英文提示显示。 */
    private void adoptThemePreview(ThemeManager.LoadResult result) {
        if (themes == null) return;
        String error = themes.acceptPreview(result);
        if (!state.selectorKind.equals("Theme")) return;
        if (!error.isEmpty()) {
            themePreviewError = error;
            state.hint = error;
            return;
        }
        // 成功预览只清除自己写过的错误，不覆盖其它来源的提示。
        if (!themePreviewError.isEmpty() && themePreviewError.equals(state.hint)) {
            themePreviewError = "";
            state.hint = "";
        }
        refreshSelectorMeta();
    }

    /** 采纳当前主题文件的修改；坏文件保留最后有效快照并显示真实错误。 */
    private void adoptThemeWatch(ThemeManager.WatchResult result) {
        if (themes == null) return;
        String error = themes.acceptWatch(result);
        if (!error.isEmpty()) {
            themeWatchError = error;
            state.hint = error;
        } else if (!themeWatchError.isEmpty() && themeWatchError.equals(state.hint)) {
            // 文件恢复有效后清掉这条已经失效的错误，不覆盖其它来源的提示。
            themeWatchError = "";
            state.hint = "";
        }
    }

    private void rebuildSessionSelector() {
        if (state.selector == null || state.sessionPicker == null) return;
        String filter = state.selector.filter();
        String selected = state.selector.selected();
        state.selector = state.sessionPicker.choices();
        state.selector.filter(filter);
        if (selected != null) state.selector.selectId(selected);
        state.selectorStatus = "";
        refreshSelectorMeta();
    }

    private void cancelSelector() {
        if (state.selectorKind.equals("Theme")) {
            themes.cancelPreview();
            if (themeSnapshot != null) settings.reload(themeSnapshot);
            themeSnapshot = null;
        }
        if (state.selectorKind.equals("PermissionConfirm") || !selectorParents.isEmpty()) {
            restoreSelectorParent(false);
            return;
        }
        String kind = state.selectorKind;
        closeSelector();
        if (kind.equals("Resume") && !host.hasCurrent()) close();
    }

    private void chooseSelector() {
        String selectedId = state.selector == null ? null : state.selector.selected();
        if (selectedId == null) return;
        String kind = state.selectorKind;
        switch (kind) {
            case "Resume" -> {
                String id = state.sessionPicker.id(selectedId);
                closeSelector();
                if (id != null) resume(id);
            }
            case "Theme" -> {
                // Enter 必须是“提交当前选中项”：若异步预览还没到/属于别的候选，就在此同步读一次
                // 小主题文件，保证确认色板与逻辑标识一致；在途旧 ticket 随后被丢弃。
                String error = themes.preview(selectedId);
                if (!error.isEmpty()) { state.hint = error; refreshSelectorMeta(); return; }
                themes.applyPreview();
                // 逻辑值来自用户选择，不从未解析调色板 name 反推；applyPreview 已同步内存标识。
                settings.reload(SettingsMenu.apply(settings.current(), Map.of("theme", selectedId)));
                preferences.saveLater(Map.of("theme", selectedId), failed -> {
                    if (failed != null) events.accept(new UiEvent.Message("System", "Warning",
                            UiMessages.NOT_SAVED + failed.getMessage(), true));
                });
                themeSnapshot = null;
                if (!selectorParents.isEmpty()) restoreSelectorParent(true); else closeSelector();
            }
            case "Thinking" -> {
                closeSelector();
                try { changeThinking(com.jadecode.llm.ThinkingSettings.parse(selectedId)); }
                catch (IllegalArgumentException invalid) { state.hint = invalid.getMessage(); }
            }
            case "Permission" -> choosePermission(PermissionProfile.parse(selectedId));
            case "PermissionConfirm" -> {
                if (selectedId.equals("confirm")) { closeSelector(); applyPermission(PermissionProfile.FULL_ACCESS); }
                else restoreSelectorParent(false);
            }
            case "Settings" -> {
                String filter = state.selector.filter();
                if (selectedId.equals("theme")) {
                    openThemeSelector();
                    return;
                }
                if (selectedId.equals("mode")) {
                    String mode = settings.current().mode().equals("fullscreen") ? "regular" : "fullscreen";
                    settings.reload(SettingsMenu.apply(settings.current(), Map.of("mode", mode)));
                    terminal.setMode(mode);
                    preferences.saveLater(Map.of("mode", mode), failed -> {
                        if (failed != null) events.accept(new UiEvent.Message("System", "Warning",
                                UiMessages.NOT_SAVED + failed.getMessage(), true));
                    });
                    rebuildSettingsSelector(filter, selectedId);
                    return;
                }
                Map<String, Object> patch = settings.cycle(selectedId);
                if (patch.isEmpty()) return;
                applySettingsPatch(patch);
                rebuildSettingsSelector(filter, selectedId);
            }
            default -> { }
        }
    }

    private void rebuildSettingsSelector(String filter, String selectedId) {
        state.selector = settings.list();
        state.selector.filter(filter);
        state.selector.selectId(selectedId);
        if (state.selectorInput != null) state.selectorInput.setText(filter);
        refreshSelectorMeta();
    }

    /**
     * 应用已由 SettingsMenu 计算好的白名单 patch：更新即时预览/行为，并异步保存。
     * 普通设置成功保持安静，不产生 Setting saved 转录；真实写盘失败才发 Warning。
     * 包内可见以便用临时配置验证成功/失败路径，不绕过保存队列。
     */
    void applySettingsPatch(Map<String, Object> patch) {
        var updated = settings.current();
        if (patch.containsKey("thinkingVisible")) state.thinkingVisible = updated.thinkingVisible();
        if (patch.containsKey("toolsExpanded")) { state.toolsExpanded = updated.toolsExpanded();
            state.transcript.setToolsExpanded(state.toolsExpanded); }
        if (patch.containsKey("quietStartup")) state.quietStartup = updated.quietStartup();
        if (patch.containsKey("fullscreenScrollbar")) {
            try { chat.setScrollbar(ChatViewport.Scrollbar.valueOf(updated.fullscreenScrollbar().toUpperCase(java.util.Locale.ROOT))); }
            catch (IllegalArgumentException ignored) { }
        }
        if (patch.containsKey("fullscreenCopyOnSelect")) chat.setCopyOnSelect(updated.fullscreenCopyOnSelect());
        if (patch.containsKey("fullscreenWheelScrollLines")) {
            // 0 是 auto；设置变化重置手势，不能映射成常量 3。
            chat.setWheelScrollLines(updated.fullscreenWheelScrollLines());
        }
        if (patch.containsKey("autocompleteMaxVisible")) editor.setCompletionMaxVisible(updated.autocompleteMaxVisible());
        if (patch.containsKey("lightTheme") || patch.containsKey("darkTheme"))
            themes.setPair(updated.lightTheme(), updated.darkTheme());
        // 普通设置成功保持安静；写盘结果只报告真实失败。
        preferences.saveLater(patch, failed -> {
            if (failed != null) events.accept(new UiEvent.Message("System", "Warning",
                    UiMessages.NOT_SAVED + failed.getMessage(), true));
        });
    }

    private void changeThinking(com.jadecode.llm.ThinkingSettings value) {
        saveLocal("Thinking", id -> host.thinking(id, value), "Thinking level: " + value.value());
    }

    /** 权限：Full Access 需显式确认；其余档位直接切换，真实 Gate 规则不变。 */
    private void choosePermission(PermissionProfile profile) {
        if (!host.hasCurrent()) { state.hint = "Open a session first"; return; }
        if (profile == PermissionProfile.FULL_ACCESS
                && host.current().permissionState().snapshot().profile() != profile) {
            openPermissionConfirm();
        } else {
            if (state.selector != null) closeSelector();
            applyPermission(profile);
        }
    }

    private void applyPermission(PermissionProfile profile) {
        var permissions = host.current().permissionState();
        boolean save = savePermissionDefault;
        savePermissionDefault = false;
        state.hint = "";
        localWrites = localWrites.handle((value, failure) -> null).thenRunAsync(() -> {
            permissions.select(profile);
            events.accept(new UiEvent.Message("System", "Permission", "Permission: " + profile.label(), false));
            if (save) {
                try {
                    preferences.savePermission(profile);
                    events.accept(new UiEvent.Message("System", "Permission", "Default permission saved: " + profile.label(), false));
                } catch (java.io.IOException failed) {
                    events.accept(new UiEvent.Message("System", "Warning",
                            "Active for this run, but saving the default failed: " + failed.getMessage(), true));
                }
            }
        }, command -> Thread.ofVirtual().name("jade-permission").start(command));
    }

    @FunctionalInterface private interface LocalAction { void run(String expectedId) throws Exception; }

    private void saveLocal(String label, LocalAction action, String success) {
        if (!host.hasCurrent() || state.loadingResume) { state.hint = "Open a session first"; return; }
        long generation = state.generation;
        String id = state.sessionId;
        localWrites = localWrites.handle((value, failure) -> null).thenRunAsync(() -> {
            try { action.run(id); events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", label, success, false))); }
            catch (Exception failure) { events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", "Error", failure.getMessage(), true))); }
        }, command -> Thread.ofVirtual().name("jade-session-setting").start(command));
    }

    /** @return 编辑器提交意图；返回 true 才清空草稿。 */
    public boolean submit(String expanded) {
        String text = expanded == null ? "" : expanded;
        String trimmed = text.strip();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("/")) return command(trimmed);
        if (state.loadingResume) { state.hint = "Session is opening; draft kept"; return false; }
        if (!host.hasCurrent()) { state.hint = "Open or create a session first"; return false; }
        state.transcript.addUser(text);
        state.hint = "";
        long generation = state.generation;
        host.current().submitUser(text, approvals).whenComplete((answer, failure) -> {
            if (failure instanceof java.util.concurrent.RejectedExecutionException)
                events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", "Rejected", failure.getMessage(), true)));
        });
        return true;
    }

    /** 本地命令；未知 slash 保留输入并返回 false。 */
    private boolean command(String text) {
        int space = text.indexOf(' ');
        String name = space < 0 ? text : text.substring(0, space);
        String arguments = space < 0 ? "" : text.substring(space + 1).strip();
        CommandCatalog.Command command = catalog.find(name);
        if (command == null) {
            state.hint = "Unknown command: " + name + " · /hotkeys";
            return false;
        }
        switch (name) {
            case "/quit" -> { close(); return true; }
            case "/new" -> { resume(null); return true; }
            case "/resume" -> {
                if (arguments.isEmpty()) openSessionList(); else resume(arguments); return true;
            }
            case "/name" -> {
                if (arguments.isEmpty()) { editor.replaceText("/name "); state.hint = "Type a title after /name"; return false; }
                saveLocal("Rename", id -> host.rename(id, arguments), "Session renamed: " + arguments); return true;
            }
            case "/settings" -> { openSettingsSelector(); return true; }
            case "/session" -> { openCommandPanel(sessionInfo.panelSession(state, state.generation,
                    state.transcript.revision())); return true; }
            case "/hotkeys" -> { openCommandPanel(hotkeys.panel(keys, catalog.commands(), state.generation,
                    state.transcript.revision())); return true; }
            case "/copy" -> { copyLastReply(); return true; }
            case "/model" -> {
                // UI 测试/无 Host 的只读展示仍可工作；生产装配中 Host 存在时刷新真实快照。
                if (host != null) refreshSession();
                openCommandPanel(sessionInfo.panelModel(state, state.generation, state.transcript.revision()));
                return true;
            }
            case "/thinking" -> {
                if (arguments.isEmpty()) {
                    if (state.session == null || state.session.thinkingChoices().size() < 2) {
                        state.hint = UiMessages.NO_THINKING; return true;
                    }
                    openThinkingSelector();
                } else {
                    try { changeThinking(com.jadecode.llm.ThinkingSettings.parse(arguments)); }
                    catch (IllegalArgumentException invalid) { state.hint = invalid.getMessage(); }
                }
                return true;
            }
            case "/permission" -> {
                savePermissionDefault = arguments.startsWith("default ");
                String value = savePermissionDefault ? arguments.substring(8).strip() : arguments;
                if (value.isEmpty()) {
                    if (!host.hasCurrent()) { state.hint = "Open a session first"; return true; }
                    openPermissionSelector();
                } else {
                    try { choosePermission(PermissionProfile.parse(value)); }
                    catch (IllegalArgumentException invalid) { state.hint = invalid.getMessage(); }
                }
                return true;
            }
            case "/mcp" -> { openCommandPanel(mcpView.panel(state.mcpServers, state.generation,
                    state.transcript.revision())); return true; }
            default -> { state.hint = "Unknown command: " + name; return false; }
        }
    }

    /** 打开会话清单；只读清单，不解析每份日志。 */
    private void openSessionList() {
        if (state.loadingResume) { state.hint = "Session is switching"; return; }
        state.loadingResume = true; state.hint = UiMessages.SESSION_LIST;
        Thread.ofVirtual().name("jade-session-list").start(() -> {
            try { events.accept(new UiEvent.SessionList(host.list())); }
            catch (Exception failure) { events.accept(new UiEvent.ResumeFailed(failure.getMessage())); }
        });
    }

    /** 异步打开会话；加载/关闭不占 UI 线程，只有 SessionOpened 回来后才清理旧卡片。 */
    public void resume(String id) {
        if (id != null && id.isEmpty()) { openSessionList(); return; }
        if (state.loadingResume) { state.hint = "Session is switching"; return; }
        state.loadingResume = true; state.hint = id == null ? "Creating session…" : UiMessages.SESSION_OPENING;
        Thread.ofVirtual().name("jade-session-open").start(() -> {
            try {
                String target = id;
                if (target != null) {
                    boolean uuid;
                    try { com.jadecode.session.history.SessionMetadata.requireId(target); uuid = true; }
                    catch (IllegalArgumentException invalid) { uuid = false; }
                    if (!uuid) {
                        var matches = host.find(target);
                        if (matches.isEmpty()) throw new java.io.IOException("No session matches that name");
                        if (matches.size() > 1) { events.accept(new UiEvent.SessionList(matches)); return; }
                        target = matches.getFirst().id();
                    }
                }
                events.accept(new UiEvent.SessionOpened(host.open(target)));
            } catch (Exception failure) { events.accept(new UiEvent.ResumeFailed(failure.getMessage())); }
        });
    }

    /** 已提交本地写入先完成，再关闭业务；审批先拒绝。 */
    private void close() {
        if (state.closing) return;
        state.closing = true; closeCommandPanel(); approvals.close();
        localWrites.whenComplete((value, failure) -> shutdown.run());
    }

    private boolean matches(String action, InputEvent event) { return keys.matches(action, event); }
}
