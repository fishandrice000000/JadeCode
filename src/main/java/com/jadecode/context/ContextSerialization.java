package com.jadecode.context;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jadecode.messages.Message;
import com.jadecode.util.Json;

/**
 * 压缩各层共用的 JSON 序列化与字符数估算。
 *
 * <p>抽出来是为了让各个策略类和 transcript 落盘都用同一份序列化，
 * 不用反向依赖入口类 {@link ContextCompactor}，也不用把代码复制多份。
 * 类里全是静态方法，不保存状态。长度单位是 Java 字符数（{@link String#length()}），
 * 不是字节数，也不是精确 token 数。序列化统一用项目共享的 {@link Json#mapper()}。
 */
final class ContextSerialization {

    /** 纯静态工具类,禁止实例化 */
    private ContextSerialization() {
    }

    /**
     * 估算一段消息的字符数：把整段消息序列化成 JSON 后的长度。
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
            throw new IllegalStateException("Failed to serialize messages", e);
        }
    }
}
