package com.jadecode.cli.backend;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 把“程序自己打印的整条消息”翻译成可信的界面事件。
 *
 * <p>会话运行时会输出一些非流式整条文字：最终回答、Team 摘要、Memory 说明、错误等。旧 TUI
 * 在这里做一次带标签前缀的适配，例如 {@code [Leader] ...}、{@code [Teammate id] ...}，
 * 并且只从**程序前缀**得到 actor/label，绝不扫描模型回复正文来授予权限或身份。</p>
 *
 * <p>本类只负责这三件事：解析受控前缀、发送 {@code message} 事件、把错误标成 error。
 * 它不写转录、不改变模型消息、不把诊断当最终回答。</p>
 */
public final class BackendMessages {
    /** 一条已解析的程序消息。 */
    public record Parsed(String actor, String label, String body, boolean error) { }

    private final WireContext wire;

    public BackendMessages(WireContext wire) { this.wire = wire; }

    /** 解析并发送一条程序消息。 */
    public void message(String value, boolean error) {
        Parsed parsed = parse(value, error);
        emit(parsed);
    }

    /** 只发送已经解析好的消息（用于会话服务自己生成的、没有旧前缀的文字）。 */
    public void emit(Parsed parsed) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("actor", parsed.actor());
        payload.put("label", parsed.label());
        payload.put("text", parsed.body());
        payload.put("error", parsed.error());
        wire.session().sendEvent("message", payload, wire.sessionId(), wire.generation());
    }

    /**
     * 解析旧标签。规则与旧实现一致，避免最终回答被显示成 System：
     * 没有 Leader 前缀的 {@code [Reply] ...} 仍属于 leader。
     */
    public static Parsed parse(String value, boolean error) {
        String actor = "System";
        String body = value == null ? "" : value;
        String label = error ? "Error" : "Notice";
        if (body.startsWith("[Leader] ")) { actor = "leader"; body = body.substring(9); }
        else if (body.startsWith("[Leader]")) { actor = "leader"; body = body.substring(8); }
        else if (body.startsWith("[Teammate ")) {
            int end = body.indexOf("] ");
            if (end > 0) { actor = body.substring(10, end); body = body.substring(end + 2); }
        }
        if (body.startsWith("[")) {
            int end = body.indexOf(']');
            if (end > 0) {
                label = body.substring(1, end);
                body = body.substring(end + 1);
                if (body.startsWith(" ")) body = body.substring(1);
            }
        }
        if (actor.equals("System") && label.equals("Reply")) actor = "leader";
        return new Parsed(actor, label, body, error);
    }
}
