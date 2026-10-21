package com.jadecode.cli.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * 自有 TUI 文案的唯一英文来源：命令、菜单、通知标签、相对时间与状态码。
 *
 * <p>用户输入、模型回复、工具结果、文件名和底层诊断保持原文，不经过这里翻译。这不是多语言
 * 框架；集中常量是为了防止中英文混排、旧别名复活，或同一状态在不同 view 出现不同拼写。
 * C07 要求自有界面全部英文（设计/代码注释仍中文）。</p>
 */
public final class UiMessages {
    private UiMessages() { }

    public static final String BRAND = "JadeCode";
    public static final String TAGLINE = "coding agent";
    public static final String READY = "Ready";
    public static final String WORKING = "Working";
    public static final String WAITING = "Waiting for background";
    public static final String IDLE = "Idle";
    public static final String CLOSING = "Closing";
    public static final String APPROVAL = "Approval required";
    public static final String REQUEST = "Request";
    public static final String REPLY = "Reply";
    public static final String THINKING = "Thinking";
    public static final String TOOL = "Tool";
    public static final String CONTEXT = "Context";
    public static final String WARNING = "Warning";
    public static final String ERROR = "Error";
    public static final String NO_MATCHES = "No matches";
    public static final String BUSY = "Session is busy; draft kept";
    public static final String SAVED = "Saved";
    public static final String NOT_SAVED = "This change is active but could not be saved: ";
    public static final String NO_AGENT_CANCEL = "Esc closes local UI only; this agent has no per-turn cancel";
    public static final String SESSION_OPENING = "Opening session…";
    public static final String SESSION_LIST = "Loading sessions…";
    public static final String NO_SESSIONS = "No other sessions in this workspace; Esc to return";
    public static final String RESUME_HINT = "Resume with ./jade repl --resume ";
    public static final String SESSION_LABEL = "Session";
    public static final String ID_LABEL = "ID";
    public static final String NO_MODEL_SWITCH = "Model switching is unavailable in this build";
    public static final String NO_THINKING = "This endpoint/model has no selectable thinking levels";
    public static final String MCP_READ_ONLY = "Configured MCP servers (read-only)";
    public static final String NO_MCP = "No MCP servers configured";
    public static final String COPY_EMPTY = "No assistant reply to copy";
    public static final String COPIED = "Copied";
    public static final String COPY_SENT = "Copy request sent; the terminal client did not confirm receipt";
    public static final String COPY_FAILED = "Copy failed: ";
    public static final String IMAGE_UNAVAILABLE = "No image clipboard backend available";
    public static final String EXTERNAL_EDITOR_FAILED = "External editor failed; draft kept: ";
    public static final String SESSION_CLOSED = "Session closed";
    public static final String PERMISSION = "Permission";
    public static final String SETTINGS = "Settings";
    public static final String HOTKEYS = "Keys and commands";
    public static final String USAGE = "Usage";
    public static final String PENDING = "Queued";
    public static final String BUFFER = "Buffered input";
    public static final String BACKGROUND = "background";
    public static final String TOOL_EXPAND = "expand/collapse";
    public static final String SMART = "smart";

    /** 状态码到英文标签；未知状态保留原值（它来自真实执行事实，不猜）。 */
    public static String status(String value) {
        if (value == null || value.isBlank()) return "";
        return switch (value) {
            case "STREAMING" -> "Streaming";
            case "DONE" -> "Done";
            case "INCOMPLETE" -> "Incomplete";
            case "TRUNCATED" -> "Truncated";
            case "HISTORICAL" -> "Historical";
            case "HISTORICAL ERROR" -> "Historical error";
            case "PREVIEW" -> "Preview";
            case "QUEUED" -> "Queued";
            case "RUNNING" -> "Running";
            case "RETURNED" -> "Returned";
            case "ERROR" -> "Error";
            case "BLOCKED" -> "Blocked";
            case "NOT_RUN" -> "Not run";
            case "INTERRUPTED" -> "Interrupted";
            case "STOP_REQUESTED" -> "Stop requested";
            default -> value;
        };
    }

    /** 相对时间（会话列表）：英文、无小数秒；未来时间/刚刚显示 just now。 */
    public static String relativeTime(java.time.Instant updatedAt, java.time.Instant now) {
        long seconds = Math.max(0, java.time.Duration.between(updatedAt, now).getSeconds());
        if (seconds < 60) return "just now";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        if (seconds < 86_400) return (seconds / 3600) + "h ago";
        return (seconds / 86_400) + "d ago";
    }

    /** 权限档位英文标签；未知值保留原文。 */
    public static String permission(String label) {
        if (label == null || label.isBlank()) return "";
        return switch (label) {
            case "只读", "Read only", "READ_ONLY" -> "read-only";
            case "工作区", "Workspace", "WORKSPACE" -> "workspace";
            case "自动审查", "Auto review", "AUTO_REVIEW" -> "auto-review";
            case "完全访问", "Full Access", "FULL_ACCESS" -> "full-access";
            default -> label;
        };
    }

    /** 键位说明的固定行；HotkeysView 逐项与实际 KeyBindings 校验。 */
    public static List<String> quickHelp() {
        var lines = new ArrayList<String>();
        lines.add("Enter submit · Shift+Enter/Ctrl+J newline · Tab complete");
        lines.add("Esc closes local UI (no agent cancel) · Ctrl+C clear, twice to exit");
        lines.add("Ctrl+T thinking · Ctrl+O tools · Ctrl+G editor · Ctrl+V clipboard");
        lines.add("/settings · /session · /hotkeys · /copy · /new · /resume · /quit");
        return List.copyOf(lines);
    }
}
