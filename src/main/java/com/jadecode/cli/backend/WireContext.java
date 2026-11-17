package com.jadecode.cli.backend;

import com.jadecode.cli.backend.protocol.EventEndpoint;

/**
 * 会话展示的共享资料：可替换的完整资料出口，以及当前会话身份。
 *
 * <p>为什么单独抽出来：会话切换会同时替换 approval/observer/stream/messages 这些“代次对象”，
 * 常驻服务的出口由会话持有；入站大资料组装仍跟连接走。把 sessionId/generation 放进一个可更新的上下文，能避免每个
 * 订阅者构造函数都捕获一份可能过期的会话身份；事件仍必须带上发送时的真实代次。</p>
 */
public final class WireContext {
    private final EventEndpoint session;
    private volatile String sessionId = "";
    private volatile long generation;

    /**
     * 绑定会话事件出口；会话打开或切换时由 update 填入 sessionId 和 generation。
     *
     * @param session 当前会话的事件出口
     */
    public WireContext(EventEndpoint session) {
        this.session = session;
    }

    /**
     * 协议出口；所有业务事件都经它的 outbox 发出。
     *
     * @return 当前会话使用的事件与命令回执出口
     */
    public EventEndpoint session() {
        return session;
    }

    /**
     * 读取当前事件出口绑定的持久会话身份。
     *
     * @return 当前会话 UUID；尚未打开会话时为空串。
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * 读取当前事件出口绑定的会话代次，供所有事件携带一致身份。
     *
     * @return 当前会话代次；尚未打开会话时为 0。
     */
    public long generation() {
        return generation;
    }

    /**
     * 会话打开/切换成功后由串行服务更新；之后的事件与命令都引用这一代。
     *
     * @param id 当前 Agent 或会话的稳定身份标识
     * @param value 新打开会话的 generation 代次
     */
    public void update(String id, long value) {
        this.sessionId = id == null ? "" : id;
        this.generation = value;
    }
}
