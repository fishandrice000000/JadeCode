package com.jadecode.context;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jadecode.messages.Message;
import com.jadecode.util.Json;

/**
 * ContextSerialization -- 压缩管线共用的 JSON 序列化与字符估算。
 *
 * <p>
 * 抽出来的唯一理由:让各策略类与 transcript 落盘都能用同一份序列化,
 * 而不必反向依赖总入口 {@link ContextCompactor},也不把这段代码复制多份。
 * 本类<b>无状态</b>,全部是 static helper。
 * </p>
 *
 * <p>
 * <b>单位是字符</b>(Java {@link String#length()}),不是字节,也不是精确 token。
 * 序列化统一走项目共享的 {@link Json#mapper()},不另起 {@code ObjectMapper}。
 * </p>
 */
final class ContextSerialization {

    /** 纯静态工具类,禁止实例化 */
    private ContextSerialization() {
    }

    /**
     * 估算上下文字符数:整段消息的 JSON 序列化长度。
     *
     * <p>
     * 单位是字符,不是字节,也不是精确 token。
     * </p>
     *
     * <p>
     * TODO(s08-user): 实现本方法。用项目共享的 {@code Json.mapper()},别另起 ObjectMapper。
     * </p>
     *
     * @param messages 消息列表
     * @return JSON 文本的字符数
     */
    static int estimateChars(List<Message> messages) {
        return toJson(messages).length();
    }

    /**
     * 项目共享 mapper 的序列化;失败属实现缺陷,包成非受检异常。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     * @throws IllegalStateException 序列化失败(带 cause)
     */
    static String toJson(Object value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化消息失败", e);
        }
    }
}
