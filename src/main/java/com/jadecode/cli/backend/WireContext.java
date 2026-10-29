package com.jadecode.cli.backend;

import com.jadecode.cli.backend.protocol.PayloadTransfer;
import com.jadecode.cli.backend.protocol.EventEndpoint;

/**
 * 会话展示的共享资料：可替换的事件出口、大资料分块器，以及当前会话身份。
 *
 * <p>为什么单独抽出来：会话切换会同时替换 approval/observer/stream/messages 这些“代次对象”，
 * 常驻服务的出口由会话持有；入站大资料组装仍跟连接走。把 sessionId/generation 放进一个可更新的上下文，能避免每个
 * 订阅者构造函数都捕获一份可能过期的会话身份；事件仍必须带上发送时的真实代次。</p>
 */
public final class WireContext {
    private final EventEndpoint session;
    private final PayloadTransfer payloads;
    private volatile String sessionId = "";
    private volatile long generation;

    public WireContext(EventEndpoint session, PayloadTransfer payloads) {
        this.session = session;
        this.payloads = payloads;
    }

    /** 协议出口；所有业务事件都经它的 outbox 发出。 */
    public EventEndpoint session() { return session; }

    /** 大资料分块器。 */
    public PayloadTransfer payloads() { return payloads; }

    /** @return 当前会话 UUID；尚未打开会话时为空串。 */
    public String sessionId() { return sessionId; }

    /** @return 当前会话代次；尚未打开会话时为 0。 */
    public long generation() { return generation; }

    /** 会话打开/切换成功后由串行服务更新；之后的事件与命令都引用这一代。 */
    public void update(String id, long value) {
        this.sessionId = id == null ? "" : id;
        this.generation = value;
    }
}
