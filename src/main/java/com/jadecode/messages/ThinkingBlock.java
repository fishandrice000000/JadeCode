package com.jadecode.messages;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 接口返回的 thinking 文字和原签名，作为 assistant 消息的一部分保留。
 *
 * <p>它不是普通回复，也不参与 extractText；工具续跑时需连同工具调用一起回传。
 * signature 只保存不解释、不展示，不得重新生成或用摘要代替。某些兼容后端不返回签名，
 * 此时用 null 表示缺省，序列化也不补造签名字段。记录不可变，构造不产生 IO。</p>
 * @param thinking 接口返回的思考文字，可为空但不可为 null
 * @param signature 原签名；后端未提供时为 null
 */
@JsonTypeName("thinking")
public record ThinkingBlock(String thinking, @JsonInclude(JsonInclude.Include.NON_NULL) String signature)
        implements ContentBlock {
    /** 允许空思考块（例如接口只提供签名），不把空文字当作协议错误。 */
    public ThinkingBlock { Objects.requireNonNull(thinking, "thinking"); }
}
