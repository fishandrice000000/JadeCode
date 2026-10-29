package com.jadecode.cli.backend.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 会话展示资料的出口；运行时只发送资料，不持有控制连接。
 * 本地旧入口由 ProtocolSession 实现；常驻服务由可重连出口实现。
 * 方法只入队，不等待终端消费；提交后的 JSON 不得由发送方再修改，失败不能授予执行权限。
 */
public interface EventEndpoint {
    void sendEvent(String type, ObjectNode payload, String sessionId, Long generation);
    void sendStreamEvent(String type, ObjectNode payload, String sessionId, Long generation, String mergeKey);
    void sendResult(String commandId, String type, ObjectNode payload, String sessionId, Long generation);
    void sendOk(String commandId, ObjectNode payload, String sessionId, Long generation);
    void sendError(String commandId, ProtocolError error, String message, String sessionId, Long generation);
    long sentSeq();
    void expectReady(String id, Long generation, Long sourceSeq);
    void flushAndClose();
}
