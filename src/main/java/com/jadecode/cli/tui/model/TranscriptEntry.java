package com.jadecode.cli.tui.model;

/**
 * 一条显示条目：用户消息、助手回复、思考块、工具卡或通知。
 *
 * <p>保存源文与出现顺序；版本递增使排版缓存失效。它不是模型 history，压缩后仍保留已显示
 * 内容。HISTORICAL 只表示从日志恢复且没有实时结果，不用 DONE 假冒执行成功，也不补实时
 * 计时。C13=B 保留 Request/Reply/Thinking 卡片容器与状态可读性。</p>
 */
public final class TranscriptEntry {
    /** 条目种类；决定卡片标题与内部排版。 */
    public enum Kind { USER, REPLY, THINKING, TOOL, NOTICE }

    private final String id;
    private final String actor;
    private final Kind kind;
    private final String title;
    private final StringBuilder body = new StringBuilder();
    private long version;
    private String status = "";
    private long elapsedNanos;
    private boolean expanded;
    private String source = "live";

    public TranscriptEntry(String id, String actor, Kind kind, String title) {
        this.id = id; this.actor = actor == null ? "" : actor;
        this.kind = kind; this.title = title == null ? "" : title;
    }

    public String id() { return id; }
    public String actor() { return actor; }
    public Kind kind() { return kind; }
    public String title() { return title; }
    public String body() { return body.toString(); }
    public long version() { return version; }
    public String status() { return status; }
    public long elapsedNanos() { return elapsedNanos; }
    public boolean expanded() { return expanded; }
    public String source() { return source; }

    /** 追加流式正文；不修改历史/日志。 */
    public void append(String text) {
        if (text == null || text.isEmpty()) return;
        body.append(text); version++;
    }

    /** 用完整正文替换（非流式消息/压缩通知）。 */
    public void replace(String text) {
        body.setLength(0); body.append(text == null ? "" : text); version++;
    }

    /** 设置真实状态（STREAMING/DONE/INCOMPLETE/HISTORICAL…）。 */
    public void status(String value) { status = value == null ? "" : value; version++; }

    /** 设置整轮/工具真实耗时；0 表示未知，不显示伪造的 0.0s。 */
    public void elapsed(long nanos) { elapsedNanos = Math.max(0, nanos); version++; }

    /** 折叠/展开；只影响显示。 */
    public void toggleExpanded() { expanded = !expanded; version++; }

    /** 设置展开态（Ctrl+T/Ctrl+O）。 */
    public void setExpanded(boolean value) { if (expanded != value) { expanded = value; version++; } }

    /** 标记来源（live/historical/notice）。 */
    public void source(String value) { source = value == null ? "live" : value; }

    /** @return 是否为可折叠内容（工具/思考）。 */
    public boolean collapsible() { return kind == Kind.TOOL || kind == Kind.THINKING; }
}
