package com.jadecode.cli.backend;

import com.jadecode.execution.RuntimeMessage;
import com.jadecode.util.Json;

/**
 * 将已带身份和标签的运行消息编码为界面事件；正文不参与元信息判定。
 */
public final class BackendMessages {
    private final WireContext wire;

    /**
     * 绑定当前代次的完整资料出口，不执行模型请求或修改会话。
     *
     * @param wire 当前会话代次的事件出口与身份上下文
     */
    public BackendMessages(WireContext wire) {
        this.wire = wire;
    }

    /**
     * 原样发送正文，错误标志由程序分支给定；不写模型历史。
     *
     * @param message 待编码、校验或投影的完整消息
     */
    public void emit(RuntimeMessage message) {
        var payload = Json.mapper().createObjectNode().put("actor", message.actor())
                .put("label", message.label()).put("text", message.text()).put("error", message.error());
        wire.session().sendEvent("message", payload, wire.sessionId(), wire.generation());
    }
}
