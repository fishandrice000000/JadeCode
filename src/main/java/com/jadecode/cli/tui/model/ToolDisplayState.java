package com.jadecode.cli.tui.model;

import com.jadecode.execution.ToolIdentity;

/**
 * 一个工具调用的完整显示状态，从参数预览到排队/运行/结果始终复用同一张卡。
 *
 * <p>预览键为 generation+actor+requestId+callId；执行 notice 的 opaque ID 是
 * requestId/callId，因此同一请求的预览与实际执行能稳定关联，不靠“最近同名工具”猜配。
 * 模型参数只用于预览，不能赋予执行权；RETURNED 保持中性，不等于业务成功。错误/被阻止/
 * 未执行/中断分别保留，不能统一涂成绿色。</p>
 */
public final class ToolDisplayState {
    /** 显示状态；括号内为来源。 */
    public enum Status { PREVIEW, QUEUED, RUNNING, RETURNED, ERROR, BLOCKED, NOT_RUN, INTERRUPTED, HISTORICAL }

    private final String actor;
    private final String requestId;
    private final String callId;
    private final String name;
    private final ToolIdentity identity;
    private String arguments = "";
    private String result = "";
    private Status status = Status.PREVIEW;
    private boolean argumentsComplete;
    private long elapsedNanos;
    private long version;
    private boolean expanded;

    /**
     * @param actor 真实 Agent 身份，不取自模型参数
     * @param requestId 请求观察 ID；旧调用可能为空
     * @param callId 模型 tool_use ID（真实协议字段）
     * @param name 工具名，仅用于显示
     * @param identity 宿主可信身份；未知/旧来源为 null，使用 fallback
     */
    public ToolDisplayState(String actor, String requestId, String callId, String name, ToolIdentity identity) {
        this.actor = actor == null ? "" : actor;
        this.requestId = requestId == null ? "" : requestId;
        this.callId = callId == null ? "" : callId;
        this.name = name == null ? "" : name;
        this.identity = identity;
    }

    /** @return 显示关联键：actor/request/call。 */
    public String key() { return actor + "/" + requestId + "/" + callId; }

    public String actor() { return actor; }
    public String requestId() { return requestId; }
    public String callId() { return callId; }
    public String name() { return name; }
    public ToolIdentity identity() { return identity; }
    public String arguments() { return arguments; }
    public String result() { return result; }
    public Status status() { return status; }
    public boolean argumentsComplete() { return argumentsComplete; }
    public long elapsedNanos() { return elapsedNanos; }
    public long version() { return version; }
    public boolean expanded() { return expanded; }

    /** 设置/替换预览参数；不改变执行状态。 */
    public void previewArguments(String value) {
        arguments = value == null ? "" : value; version++;
    }

    /** 追加参数增量；只影响显示文本。 */
    public void appendArguments(String fragment) {
        if (fragment == null || fragment.isEmpty()) return;
        arguments += fragment; version++;
    }

    /** 参数块通过 JSON 校验：预览完整，但整响应仍可能失败。 */
    public void closeArguments() { argumentsComplete = true; version++; }

    /** 进入排队/运行；detail 是原始参数 JSON。 */
    public void executing(Status next, String value) {
        if (next != Status.QUEUED && next != Status.RUNNING) throw new IllegalArgumentException("非执行状态");
        status = next;
        if (value != null && !value.isBlank()) arguments = value;
        version++;
    }

    /** 执行结束；result 是真实工具输出，RETURNED 不表示成功。 */
    public void finished(Status next, String value, long nanos) {
        status = next; result = value == null ? "" : value; elapsedNanos = Math.max(0, nanos); version++;
    }

    /** 没有正常结果/中断；保留已有结果，不伪造成功。 */
    public void interrupted(Status next, String message) {
        status = next;
        if (message != null && !message.isBlank()) result = result.isEmpty() ? message : result + "\n" + message;
        version++;
    }

    /** 点击/Ctrl+O 切换展开；原文完整保留。 */
    public void toggleExpanded() { expanded = !expanded; version++; }

    /** 设置展开态（全局 Ctrl+O）。 */
    public void setExpanded(boolean value) { if (expanded != value) { expanded = value; version++; } }

    /** 从历史恢复：没有真实执行结果时标 HISTORICAL，不补用时。 */
    public void historical(String value, boolean error) {
        status = error ? Status.ERROR : Status.HISTORICAL; result = value == null ? "" : value; version++;
    }

    /** @return 是否处于执行中（用于 spinner/关闭收尾）。 */
    public boolean active() { return status == Status.PREVIEW || status == Status.QUEUED || status == Status.RUNNING; }
}
