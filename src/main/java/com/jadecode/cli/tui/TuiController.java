package com.jadecode.cli.tui;

import java.util.List;
import java.util.function.Consumer;
import com.jadecode.cli.tui.bridge.ApprovalBroker;
import com.jadecode.cli.SessionHost;
import com.jadecode.tui.components.SelectList;
import com.jadecode.tui.core.InlineRenderer;
import com.jadecode.tui.terminal.InputDecoder.Input;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.safety.approval.ApprovalAnswer;

/**
 * 在界面线程处理输入与显示事件。只通过现有 submitUser／promoteUserBuffer 发出用户意图，
 * 不执行工具、不改模型历史。磁盘偏好保存和关闭业务资源都交给其它线程。
 */
public final class TuiController {
    private final TuiState state;
    private final SessionHost host;
    private final ApprovalBroker approvals;
    private final InlineRenderer renderer;
    private final TuiPreferences preferences;
    private final Consumer<UiEvent> events;
    private final Runnable shutdown;
    private long ctrlC;
    private String originalTheme;
    private boolean savePermissionDefault;
    /** 元数据选择按用户输入顺序保存，避免较早的 low 写入反而盖过后来确认的 high。 */
    private java.util.concurrent.CompletableFuture<Void> localWrites = java.util.concurrent.CompletableFuture.completedFuture(null);
    /** 新运行时装配通知可能先于 SessionOpened 到达，暂存而不是丢掉模型参数通知。 */
    private final java.util.Map<Long, List<UiEvent>> awaiting = new java.util.LinkedHashMap<>();
    public TuiController(TuiState state, SessionHost host, ApprovalBroker approvals,
            InlineRenderer renderer, TuiPreferences preferences, Consumer<UiEvent> events, Runnable shutdown) {
        this.state = state; this.host = host; this.approvals = approvals; this.renderer = renderer;
        this.preferences = preferences; this.events = events; this.shutdown = shutdown;
    }

    /** 应用事件；返回 false 表示清理完成，可以结束界面循环。 */
    public boolean accept(UiEvent event) {
        switch (event) {
            case UiEvent.Scoped scoped -> {
                if (scoped.generation() == state.generation) return accept(scoped.event());
                if (scoped.generation() > state.generation)
                    awaiting.computeIfAbsent(scoped.generation(), ignored -> new java.util.ArrayList<>()).add(scoped.event());
                // 旧代次事件直接丢弃，不能把旧回复、关闭通知或工具卡接到新会话。
            }
            case UiEvent.SessionOpened opened -> {
                var session = opened.opened();
                state.generation = session.generation(); state.loadingResume = false;
                state.selector = null; state.sessionPicker = null;
                state.sessionId = session.restored().metadata().id();
                HistoryViewMapper.restore(state, session.restored());
                for (UiEvent initial : awaiting.getOrDefault(state.generation, List.of())) accept(initial);
                awaiting.clear();
                refreshSession();
                if (!session.restored().metadata().model().equals(session.currentModel()))
                    state.add("System", "Warning", "Model", "原会话模型: " + session.restored().metadata().model()
                            + "；当前使用: " + session.currentModel());
                state.hint = "会话已打开，等待新输入 · /resume 切换 · /help";
                host.start(state.generation);
            }
            case UiEvent.SessionList list -> {
                state.loadingResume = false; state.selectorKind = "Resume";
                state.sessionPicker = new SessionPicker(list.sessions()); state.selector = state.sessionPicker.choices();
                state.hint = list.sessions().isEmpty() ? "当前工作区没有其它可恢复会话；Esc 返回" : "选择会话后恢复；不会重跑旧工具";
            }
            case UiEvent.ResumeFailed failed -> {
                state.loadingResume = false; awaiting.clear();
                state.hint = failed.message(); state.add("System", "Error", "Resume", failed.message());
            }
            case UiEvent.Input input -> input(input.input());
            case UiEvent.Execution execution -> state.execution(execution.notice());
            case UiEvent.Delta delta -> state.delta(delta);
            case UiEvent.StreamEnd end -> state.end(end);
            case UiEvent.Message message -> state.message(message);
            case UiEvent.ApprovalRequested request -> {
                if (state.closing) approvals.answer(request.id(), ApprovalAnswer.DENY);
                else { if (state.approvals.isEmpty()) state.resetApprovalView(); state.approvals.addLast(request); }
            }
            case UiEvent.ApprovalRemoved removed -> removeApproval(removed.id());
            case UiEvent.Stopped stopped -> { return false; }
        }
        return true;
    }

    /**
     * 从当前会话读取已发布的统计和设置，不读盘、不等模型，也不访问可修改的 history。
     * 以真实会话 ID 核对，避免 Host 已切换而 SessionOpened 尚未到达时采用新旧混合快照。
     * 返回 true 才要求刷新屏幕，空闲时不会为了查询统计不断重画终端。
     */
    public boolean refreshSession() {
        boolean changed = false;
        var permission = host.permissionSnapshot();
        if (permission != null) {
            String profile = permission.profile().label();
            changed = !profile.equals(state.permissionProfile); state.permissionProfile = profile;
        }
        var next = host.display();
        if (next == null || !next.id().equals(state.sessionId) || next.equals(state.session)) return changed;
        state.session = next; state.model = next.model(); state.modelInfo = next.metadata().describe();
        return true;
    }

    /**
     * 处理一个已解码的输入。先处理整会话退出，再只让当前可操作的区域接收按键。
     * 同一个 Enter 不能既批准操作又提交草稿；PASTE 是一整块正文，不拆成许多 Enter。
     * 本方法只修改界面状态或调用现有 Session 方法，不等待模型和工具返回。
     */
    private void input(Input input) {
        String key = input.key();
        if (state.closing) return;
        // 尺寸变化到新帧绘制之间，旧按钮可能已经不在屏幕上；本批后续按键不能继续批准它。
        if (key.equals("RESIZE")) { renderer.invalidateActions(); return; }
        if (key.equals("EOF")) { close(); return; }
        if (key.equals("CTRL_C")) {
            long now = System.nanoTime();
            if (ctrlC != 0 && now - ctrlC < 500_000_000L) { close(); return; }
            ctrlC = now;
            if (state.approvals.isEmpty() && state.selector == null) {
                state.editor.clear(); state.commands = null; state.commandsDismissed = false;
            }
            state.hint = "再次 Ctrl+C（500ms 内）退出整个会话；Esc 不取消 Agent"; return;
        }
        // 审批盖住选择器，选择器盖住编辑器：被盖住的草稿仍保存，但不会收到这一按键。
        if (!state.approvals.isEmpty()) { approval(input); return; }
        if (state.selector != null) { selector(input); return; }
        // 候选出现时，文字仍输入原草稿；只有选择／补全按键被候选接收。
        if (state.commands != null && commandInput(input)) return;
        switch (key) {
            case "TEXT" -> state.editor.insert(input.text(), false);
            case "PASTE" -> state.editor.insert(input.text(), true);
            case "ENTER" -> submit();
            case "NEWLINE" -> state.editor.insert("\n", false);
            case "BACKSPACE" -> state.editor.delete(true);
            case "DELETE" -> state.editor.delete(false);
            case "CTRL_D" -> { if (state.editor.empty()) close(); else state.editor.delete(false); }
            case "LEFT" -> state.editor.move(-1);
            case "RIGHT" -> state.editor.move(1);
            case "WORD_LEFT" -> state.editor.moveWord(-1);
            case "WORD_RIGHT" -> state.editor.moveWord(1);
            case "UP" -> state.editor.vertical(-1);
            case "DOWN" -> state.editor.vertical(1);
            case "HOME" -> state.editor.home();
            case "END" -> state.editor.end();
            case "CLEAR" -> state.editor.clear();
            case "KILL_END" -> state.editor.killEnd();
            case "DELETE_WORD" -> state.editor.deleteWord();
            case "UNDO" -> state.editor.undo();
            case "CTRL_O" -> {
                state.toolsExpanded = !state.toolsExpanded;
                state.entries.stream().filter(e -> e.kind.equals("Tool"))
                        .forEach(e -> { e.expanded = state.toolsExpanded; e.version++; });
            }
            case "CTRL_T" -> { state.thinkingVisible = !state.thinkingVisible; state.entries.stream().filter(e -> e.kind.equals("Thinking")).forEach(e -> e.expanded = false); }
            case "CLICK" -> click(renderer.hit(input.x(), input.y()));
            case "TAB" -> { }
            case "ESC" -> state.hint = "Esc 仅关闭选择界面；本版不支持单轮取消";
            default -> { }
        }
        if (List.of("TEXT", "PASTE", "BACKSPACE", "DELETE", "CTRL_D", "CLEAR", "KILL_END", "DELETE_WORD", "UNDO").contains(key))
            state.commandsDismissed = false;
        refreshCommands();
    }

    /** 只对单行、无空格的斜杠命令提供候选；普通任务或多行粘贴不会误入命令选择。 */
    private void refreshCommands() {
        String draft = state.editor.text();
        if (!state.commandSuggestions || state.commandsDismissed || !draft.startsWith("/") || draft.codePoints().anyMatch(Character::isWhitespace)) {
            state.commands = null;
            return;
        }
        if (state.commands == null) state.commands = new SelectList(List.of("/help", "/theme", "/model", "/thinking", "/permission",
                "/usage", "/resume", "/rename", "/quit", "/exit"));
        if (!state.commands.filter().equals(draft)) state.commands.filter(draft);
    }

    /**
     * 候选列表不保存另一份正文。Tab 只把选中命令补回编辑器；Enter 才清掉命令草稿并执行。
     * 返回 true 表示本按键已经处理，不再让编辑器浏览历史或把 `/` 发给模型。
     */
    private boolean commandInput(Input input) {
        switch (input.key()) {
            case "UP" -> state.commands.move(-1);
            case "DOWN" -> state.commands.move(1);
            case "ESC" -> { state.commands = null; state.commandsDismissed = true; }
            case "TAB", "ENTER" -> {
                String command = state.commands.selected();
                if (command == null) return false;
                if (input.key().equals("TAB")) {
                    state.editor.clear(); state.editor.insert(command, false);
                    // Tab 只补回文字，不执行命令；补完后收起候选，避免同一完整命令显示两次。
                    // 下一次真正编辑仍会重新启用候选，Enter 则沿普通命令提交路径执行。
                    state.commands = null; state.commandsDismissed = true;
                } else {
                    state.editor.submit(); state.commands = null; open(command);
                }
            }
            default -> { return false; }
        }
        return true;
    }

    /**
     * 将编辑器原文交给会话，并立即返回。忙时合并、排队和插入升级由 Session 决定。
     * 空提交只升级同一业务缓冲；前端命令不发给模型；q／exit 则是普通提示词。
     * UI 先保留用户输入供查阅；若业务队列拒收，再另显示拒收原因，不伪称已执行。
     */
    private void submit() {
        // 退出是整个 CLI 的本地操作，即使会话正在加载也要能响应，不能交给模型或排到任务队尾。
        String command = state.editor.text().strip();
        if (command.equals("/quit") || command.equals("/exit")) { state.editor.submit(); close(); return; }
        if (state.loadingResume) { state.hint = "正在打开会话，草稿已保留，请稍后提交"; return; }
        String prompt = state.editor.submit();
        if (prompt.isBlank()) {
            state.hint = host.hasCurrent() && host.current().promoteUserBuffer() ? "待发送内容已升级为插入式" : "没有可插入的待发送内容"; return;
        }
        if (command.equals("/resume") || command.startsWith("/resume ") || command.startsWith("/resume\n") || command.startsWith("/resume\t")) {
            String id = command.substring(7).strip();
            if (id.isEmpty()) open("/resume"); else resume(id);
            return;
        }
        if (command.startsWith("/rename ") || command.startsWith("/rename\n") || command.startsWith("/rename\t")) {
            saveLocal("Rename", id -> host.rename(id, command.substring(7).strip()), "会话已命名：" + command.substring(7).strip()); return;
        }
        if (command.startsWith("/thinking ")) {
            try { setThinking(com.jadecode.llm.ThinkingSettings.parse(command.substring(10))); }
            catch (IllegalArgumentException invalid) { state.hint = invalid.getMessage(); }
            return;
        }
        if (command.startsWith("/permission ")) { permissionCommand(command.substring(12).strip()); return; }
        if (List.of("/theme", "/help", "/model", "/thinking", "/usage", "/rename", "/permission").contains(command)) { open(command); return; }
        if (!host.hasCurrent()) { state.editor.insert(prompt, false); state.hint = "请先 /resume 选择会话，或重启 jade repl 新建会话"; return; }
        state.add("You", "User", "Request", prompt); state.hint = "";
        // future 在业务线程结束，回调仍只投递显示事件，不能在该线程直接改 TuiState。
        long generation = state.generation;
        host.current().submitUser(prompt, approvals).whenComplete((answer, failure) -> {
            if (failure instanceof java.util.concurrent.RejectedExecutionException)
                events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", "Rejected", failure.getMessage(), true)));
        });
    }
    /** 前端命令不交给模型：帮助/主题/参数在本地处理，resume 则让 Host 异步装配目标运行时。 */
    private void open(String command) {
        if (command.equals("/quit") || command.equals("/exit")) { close(); return; }
        if (state.loadingResume) { state.hint = "正在打开会话，请稍后"; return; }
        if (command.equals("/resume")) {
            state.loadingResume = true; state.hint = "正在读取当前工作区的会话列表…";
            Thread.ofVirtual().name("jade-session-list").start(() -> {
                try { events.accept(new UiEvent.SessionList(host.list())); }
                catch (Exception failure) { events.accept(new UiEvent.ResumeFailed(failure.getMessage())); }
            });
        } else if (command.equals("/theme")) {
            originalTheme = state.theme; state.selectorKind = "Theme"; state.selector = new SelectList(List.of("dark", "light"));
            if (state.theme.equals("light")) state.selector.move(1);
        } else if (command.equals("/permission")) {
            if (!host.hasCurrent()) { state.hint = "请先打开会话"; return; }
            savePermissionDefault = false;
            state.selectorKind = "Permission";
            state.selector = PermissionMenu.choices(host.current().permissionState().snapshot().profile());
            state.hint = "仅切换本次运行；/permission default <档位> 保存启动默认";
        } else if (command.equals("/model")) {
            // 只展示启动时已取得的快照，不把命令发给模型，也不在 UI 线程重新请求网络。
            refreshSession();
            state.add("", "Model", state.model + " · " + (state.session == null ? "默认" : state.session.thinkingLabel()), state.modelInfo);
        } else if (command.equals("/thinking")) {
            refreshSession();
            if (state.session == null || state.session.thinkingChoices().size() < 2) {
                state.hint = "当前端点或模型尚未适配思考强度切换"; return;
            }
            state.selectorKind = "Thinking";
            state.selector = new SelectList(state.session.thinkingChoices().stream().map(com.jadecode.llm.ThinkingSettings::value).toList());
            state.selector.move(state.session.thinkingChoices().indexOf(state.session.thinking()));
        } else if (command.equals("/rename")) {
            if (state.editor.empty()) state.editor.insert("/rename ", false);
            state.hint = "输入 /rename 新名称；名称只用于查找，不改变会话 UUID 或目录";
        } else if (command.equals("/usage")) {
            refreshSession();
            if (state.session == null) { state.hint = "请先打开会话"; return; }
            var usage = state.session.usage(); var total = usage.total();
            StringBuilder body = new StringBuilder("输入：" + ConversationView.tokens(total.inputTokens())
                    + "\n输出：" + ConversationView.tokens(total.outputTokens())
                    + "\n已报告请求：" + total.reportedRequests());
            if (total.missingRequests() > 0) body.append("\n未取得用量的请求：").append(total.missingRequests());
            usage.byActor().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry ->
                    body.append("\n").append(entry.getKey()).append("：↑").append(ConversationView.tokens(entry.getValue().inputTokens()))
                            .append(" ↓").append(ConversationView.tokens(entry.getValue().outputTokens())));
            state.add("System", "Model", "Usage", body.toString());
        } else {
            state.selectorKind = "Help";
            state.selector = new SelectList(List.of("Enter 提交 · Alt+Enter 换行 · 粘贴后仍需 Enter", "忙时空 Enter：插入待发送缓冲",
                    "Ctrl+T 显示思考 · Ctrl+O 展开工具 · 鼠标滚动／复制由终端处理", "Ctrl+C 清草稿，500ms 内再按退出 · 空编辑器 Ctrl+D 退出",
                    "Esc 关闭选择界面，不取消 Agent · /quit 或 /exit 退出整个会话", "窗口／主题变化可能清空 scrollback 并重绘",
                    "/model", "/thinking", "/permission", "/usage", "/theme", "/resume", "/rename", "/quit", "/exit"));
        }
    }

    @FunctionalInterface private interface LocalAction { void run(String expectedId) throws Exception; }

    /** 本地元数据写盘在工作线程做；完成后只投递文字，不能从该线程改界面组件。 */
    private void saveLocal(String label, LocalAction action, String success) {
        if (!host.hasCurrent() || state.loadingResume) { state.hint = "请先打开会话"; return; }
        long generation = state.generation;
        String id = state.sessionId;
        localWrites = localWrites.handle((value, failure) -> null).thenRunAsync(() -> {
            try { action.run(id); events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", label, success, false))); }
            catch (Exception failure) { events.accept(new UiEvent.Scoped(generation, new UiEvent.Message("System", "Error", failure.getMessage(), true))); }
        }, command -> Thread.ofVirtual().name("jade-session-setting").start(command));
    }

    /** 固定选择交给会话检查和保存，界面不直接改 SDK 或全局配置。 */
    private void setThinking(com.jadecode.llm.ThinkingSettings value) {
        saveLocal("Thinking", id -> host.thinking(id, value), "思考设置已保存：" + value.value() + "，从下一次请求生效");
    }
    /** 解析本地命令，不发给模型；错误档位不改变当前设置。 */
    private void permissionCommand(String arguments) {
        savePermissionDefault = arguments.startsWith("default ");
        try { choosePermission(PermissionProfile.parse(savePermissionDefault ? arguments.substring(8).strip() : arguments)); }
        catch (IllegalArgumentException invalid) { state.hint = invalid.getMessage(); }
    }
    /** Full Access 要先显示风险并再确认，其余档位直接采用，不中断已经执行的命令。 */
    private void choosePermission(PermissionProfile profile) {
        if (!host.hasCurrent()) { state.hint = "请先打开会话"; return; }
        if (profile == PermissionProfile.FULL_ACCESS && host.current().permissionState().snapshot().profile() != profile) {
            state.selectorKind = "PermissionConfirm"; state.selector = PermissionMenu.confirm();
            state.hint = "普通文件和命令可直接执行，可能修改工作区外文件；显式拒绝和默认硬保护仍有效";
        } else applyPermission(profile);
    }
    /**
     * 按选择顺序切换；仅明确 default 的命令才保存启动默认。
     * 后台可能正在保存“允许此类”规则，切换也交工作线程，界面不等配置锁或磁盘。
     * 失败如实报告，当前快照由 refreshSession 采用；草稿、主任务和队友不被重建。
     */
    private void applyPermission(PermissionProfile profile) {
        var permissions = host.current().permissionState();
        boolean save = savePermissionDefault;
        state.hint = "";
        localWrites = localWrites.handle((value, failure) -> null).thenRunAsync(() -> {
            permissions.select(profile);
            events.accept(new UiEvent.Message("System", "Permission", "当前权限：" + profile.label(), false));
            if (save) {
                try { preferences.savePermission(profile); events.accept(new UiEvent.Message("System", "Permission", "启动默认已保存：" + profile.label(), false)); }
                catch (java.io.IOException failed) { events.accept(new UiEvent.Message("System", "Error", "本次已切换，但默认保存失败：" + failed.getMessage(), true)); }
            }
        }, command -> Thread.ofVirtual().name("jade-permission-setting").start(command));
        savePermissionDefault = false;
    }
    /**
     * 选择期间，文字只用于筛选，不进入原草稿。主题上下移动只作预览，Esc 恢复原主题；
     * 只有 Enter 确认才排队保存。保存回调发事件回到界面线程，不跨线程改组件。
     */
    private void selector(Input input) {
        switch (input.key()) {
            case "ESC" -> {
                if (state.selectorKind.equals("Theme")) state.theme = originalTheme;
                boolean startupPicker = state.selectorKind.equals("Resume") && !host.hasCurrent();
                state.selector = null; state.sessionPicker = null;
                if (startupPicker) close();
            }
            case "UP", "BACKTAB" -> { state.selector.move(-1); previewTheme(); }
            case "DOWN", "TAB" -> { state.selector.move(1); previewTheme(); }
            case "TEXT" -> state.selector.filter(state.selector.filter() + input.text());
            case "BACKSPACE" -> { String filter = state.selector.filter(); if (!filter.isEmpty()) state.selector.filter(filter.substring(0, filter.offsetByCodePoints(filter.length(), -1))); }
            case "ENTER" -> {
                String choice = state.selector.selected(); if (choice == null) return;
                String kind = state.selectorKind; state.selector = null;
                if (kind.equals("Resume")) {
                    String id = state.sessionPicker.id(choice); state.sessionPicker = null;
                    resume(id);
                } else if (kind.equals("Theme")) {
                    state.theme = choice;
                    preferences.saveLater(choice, failed -> {
                        if (failed == null) events.accept(new UiEvent.Message("System", "Theme", "主题已保存：" + choice, false));
                        else events.accept(new UiEvent.Message("System", "Warning", "本次主题已生效，但保存失败：" + failed.getMessage(), true));
                    });
                } else if (kind.equals("Thinking")) setThinking(com.jadecode.llm.ThinkingSettings.parse(choice));
                else if (kind.equals("Permission")) choosePermission(PermissionProfile.parse(choice));
                else if (kind.equals("PermissionConfirm")) {
                    if (choice.equals("启用 Full Access")) applyPermission(PermissionProfile.FULL_ACCESS);
                    else state.hint = "权限未改变";
                }
                else if (choice.startsWith("/")) open(choice);
            }
            default -> { }
        }
    }
    private void previewTheme() { if (state.selectorKind.equals("Theme") && state.selector.selected() != null) state.theme = state.selector.selected(); }

    /**
     * 异步打开会话。加载与关闭不占 UI 线程，只有 SessionOpened 回来后才清理旧卡片并启动新会话。
     * id 为 null 新建，空串打开启动选择器；名称先查清单，重名展示候选，不默认选择第一项。
     */
    public void resume(String id) {
        if (id != null && id.isEmpty()) { open("/resume"); return; }
        if (state.loadingResume) { state.hint = "会话正在切换"; return; }
        state.loadingResume = true; state.hint = id == null ? "正在创建会话…" : "正在恢复会话…";
        Thread.ofVirtual().name("jade-session-open").start(() -> {
            try {
                String target = id;
                if (target != null) {
                    boolean uuid;
                    try { com.jadecode.session.history.SessionMetadata.requireId(target); uuid = true; }
                    catch (IllegalArgumentException invalid) { uuid = false; }
                    if (!uuid) {
                        var matches = host.find(target);
                        if (matches.isEmpty()) throw new java.io.IOException("没有匹配该名称的会话");
                        if (matches.size() > 1) { events.accept(new UiEvent.SessionList(matches)); return; }
                        target = matches.getFirst().id();
                    }
                }
                events.accept(new UiEvent.SessionOpened(host.open(target)));
            }
            catch (Exception failure) { events.accept(new UiEvent.ResumeFailed(failure.getMessage())); }
        });
    }
    /**
     * 审批独占输入焦点：上下键/翻页/首尾键只改变阅读位置，左右键或 Tab 才改变按钮选择。
     * Enter 必须有明确选中项，且同一申请的按钮已经在最后画面上可见，才向 Broker 交回答复。
     * 小窗口没有确认动作时不允许盲批；整个过程不编辑草稿、不执行工具。
     * 排队中的另一份申请由 removeApproval 重置，不能继承本申请的高亮或阅读位置。
     */
    private void approval(Input input) {
        // 阅读按键不作审批答复。即使当前窗口太小而按钮不可见，也允许滚动；
        // 真正的批准仍须通过下面基于最后已显示画面的检查。
        int step = Math.max(1, state.approvalPageSize);
        int last = Math.max(0, state.approvalTotalLines - step);
        switch (input.key()) {
            case "UP" -> { state.approvalOffset = Math.max(0, state.approvalOffset - 1); return; }
            case "DOWN" -> { state.approvalOffset = Math.min(last, state.approvalOffset + 1); return; }
            case "PAGE_UP" -> { state.approvalOffset = Math.max(0, state.approvalOffset - step); return; }
            case "PAGE_DOWN" -> { state.approvalOffset = (int) Math.min(last, (long) state.approvalOffset + step); return; }
            case "HOME" -> { state.approvalOffset = 0; return; }
            case "END" -> { state.approvalOffset = last; return; }
            default -> { }
        }
        // 新队首必须先真正显示，再允许选择。仅重置 choice 不足以防住上一份申请的连按。
        if (!renderer.hasAction("approve:" + state.approvals.getFirst().id())) return;
        switch (input.key()) {
            case "LEFT", "BACKTAB", "RIGHT", "TAB" -> {
                int direction = input.key().equals("LEFT") || input.key().equals("BACKTAB") ? -1 : 1;
                int choice = state.approvalChoice < 0 ? (direction > 0 ? 0 : 2) : Math.floorMod(state.approvalChoice + direction, 3);
                if (choice == 1 && state.approvals.getFirst().suggestion().isEmpty()) choice += direction;
                state.approvalChoice = choice;
            }
            case "ENTER" -> { if (state.approvalChoice >= 0) answer(state.approvals.getFirst().id(), ApprovalAnswer.values()[state.approvalChoice]); }
            case "CLICK" -> click(renderer.hit(input.x(), input.y()));
            default -> { /* 无默认选择，普通文字、粘贴、空 Enter 和 Esc 都不能批准或拒绝。 */ }
        }
    }
    /** 点击动作来自最后显示帧；不是模型正文提供的命令，仍需核对当前审批编号。 */
    private void click(String action) {
        if (action == null) return;
        if (action.startsWith("approve:")) answer(action.substring(8), ApprovalAnswer.ALLOW_ONCE);
        else if (action.startsWith("similar:")) answer(action.substring(8), ApprovalAnswer.ALLOW_SIMILAR);
        else if (action.startsWith("deny:")) answer(action.substring(5), ApprovalAnswer.DENY);
        else if (action.startsWith("entry:") && state.approvals.isEmpty()) state.entries.stream()
                .filter(e -> e.id.equals(action.substring(6))).findFirst().ifPresent(e -> { e.expanded = !e.expanded; e.version++; });
    }
    private void answer(String id, ApprovalAnswer allowed) {
        if (state.approvals.isEmpty() || !state.approvals.getFirst().id().equals(id)) return;
        if (allowed == ApprovalAnswer.ALLOW_SIMILAR && state.approvals.getFirst().suggestion().isEmpty()) return;
        approvals.answer(id, allowed); removeApproval(id);
    }
    private void removeApproval(String id) {
        boolean first = !state.approvals.isEmpty() && state.approvals.getFirst().id().equals(id);
        state.approvals.removeIf(request -> request.id().equals(id)); if (first) state.resetApprovalView();
    }
    /** 已提交的本地改名/设置先完成，再关闭日志；不等待业务任务自然做完。 */
    private void close() {
        if (state.closing) return;
        state.closing = true; approvals.close();
        localWrites.whenComplete((value, failure) -> shutdown.run());
    }
}
