package com.jadecode.cli.backend.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 统一会话资料的事件出口。ProtocolSession 为连接分配序号并发送帧，SessionChannel 先采用展示投影再转交当前连接。
 * 调用方提交完整载荷后交由出口控制发送生命周期。
 */
public interface EventEndpoint {
    /**
     * 按给定会话身份发送普通结构事件，保持与同出口其他事件的顺序。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     */
    void sendEvent(String type, ObjectNode payload, String sessionId, Long generation);

    /**
     * 发送正文或参数预览增量，并附相邻同键片段的合并标识。
     *
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     * @param mergeKey 增量合并键；null 表示结构事件单独发送
     */
    void sendStreamEvent(String type, ObjectNode payload, String sessionId, Long generation, String mergeKey);

    /**
     * 将完整业务结果关联到原命令，交给命令回执通道。
     *
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param type 当前协议事件或命令结果的类型名
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     */
    void sendResult(String commandId, String type, ObjectNode payload, String sessionId, Long generation);

    /**
     * 以 command.result 类型返回成功载荷，业务状态由载荷字段说明。
     *
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param payload 协议事件的完整 JSON 业务载荷
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     */
    void sendOk(String commandId, ObjectNode payload, String sessionId, Long generation);

    /**
     * 以 command.error 返回错误类别、说明与可重试状态。
     *
     * @param commandId 客户端命令 ID，供命令回执和重复请求查找使用
     * @param error 本次协议错误类别，决定错误码和可重试标记
     * @param message 需要返回给调用者的结果或错误说明
     * @param sessionId 持久会话 ID，用于关联本次事件与历史
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     */
    void sendError(String commandId, ProtocolError error, String message, String sessionId, Long generation);

    /**
     * 读取出口已采用或已发送的来源游标，供完整快照固定同步位置。
     *
     * @return 此出口当前的事件来源游标
     */
    long sentSeq();

    /**
     * 登记本次快照 ID、会话代次与来源序号，供 ready 确认核对。
     *
     * @param id 本次完整历史快照的身份标识
     * @param generation 客户端看到的会话代次，切换会话后随新代次更新
     * @param sourceSeq 完整快照 begin 固定的展示来源序号
     */
    void expectReady(String id, Long generation, Long sourceSeq);

    /**
     * 请求将已经接纳的事件与回执排空，再结束当前控制连接。
     */
    void flushAndClose();
}
