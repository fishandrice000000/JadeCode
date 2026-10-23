package com.jadecode.cli.backend.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 内部 TUI 协议的一条消息（版本 1）。
 *
 * <p>这是 Java 后端与 Node 界面之间唯一允许的结构化资料。它只携带纯数据：没有
 * {@code UiEvent}、{@code Future}、组件、{@code AppConfig} 或认证。{@code payload} 是一个
 * 白名单类型的 JSON 对象，而不是任意 Java 类名，因此反序列化不会按类型字符串加载类。</p>
 *
 * <p>{@code seq} 是发送方向自己单调递增的序号；{@code commandId} 只在命令/命令答复中出现，
 * 用来把结果关联回发起者；{@code sessionId}/{@code generation} 标识命令或事件属于哪一代
 * 会话，代次不匹配的旧事件由前端丢弃、旧命令由后端拒绝。</p>
 *
 * @param version      协议版本，当前固定为 {@link WireCodec#PROTOCOL_VERSION}
 * @param connectionId 握手后由 Java 分配的连接身份；{@code client.hello} 之前为空串
 * @param seq          发送方向单调递增的序号，从 1 开始
 * @param type         白名单消息类型，例如 {@code stream.delta}
 * @param commandId    命令/结果关联 ID；事件为 null
 * @param sessionId    相关会话 UUID；与具体会话无关时可以为 null
 * @param generation   会话代次；与具体会话无关时可以为 null
 * @param payload      该类型的纯资料对象
 */
public record WireMessage(int version, String connectionId, long seq, String type,
        String commandId, String sessionId, Long generation, ObjectNode payload) {

    /** 创建一个事件；事件不回答任何命令，因此 {@code commandId} 为 null。 */
    public static WireMessage event(long seq, String connectionId, String type, String sessionId,
            Long generation, ObjectNode payload) {
        return new WireMessage(WireCodec.PROTOCOL_VERSION, connectionId, seq, type, null, sessionId, generation, payload);
    }

    /** 创建一个命令答复；{@code commandId} 让 Node 找回对应的请求。 */
    public static WireMessage result(long seq, String connectionId, String type, String commandId,
            String sessionId, Long generation, ObjectNode payload) {
        return new WireMessage(WireCodec.PROTOCOL_VERSION, connectionId, seq, type, commandId, sessionId, generation, payload);
    }
}
