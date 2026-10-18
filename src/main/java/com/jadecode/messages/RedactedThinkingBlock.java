package com.jadecode.messages;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * 保存接口不允许展示的加密思考数据，工具续跑时原样回传，不打印或尝试解密。
 * @param data 原始不透明数据，可为空但不可为 null；不是给用户的自然语言正文
 */
@JsonTypeName("redacted_thinking")
public record RedactedThinkingBlock(String data) implements ContentBlock {
    /** 在消息构造时保证必要字段存在，不产生 IO 或复制到普通 TextBlock。 */
    public RedactedThinkingBlock { Objects.requireNonNull(data, "data"); }
}
