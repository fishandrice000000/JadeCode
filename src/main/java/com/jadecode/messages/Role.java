package com.jadecode.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Role -- 消息发送者角色。
 *
 * <p>线上 JSON 里是字符串("user" / "assistant"),本枚举是它的类型化表示;
 * {@link #wire()} / {@link #fromWire(String)} 负责两侧互转。</p>
 */
public enum Role {
    USER("user"),
    ASSISTANT("assistant");

    /** 线上 JSON 里的角色字符串 */
    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    /** @return 线上 JSON 的角色字符串("user" / "assistant") */
    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * 从线上 JSON 的角色字符串反解。
     *
     * @param wire "user" / "assistant"
     * @return 对应枚举
     * @throws IllegalArgumentException 无法识别的值
     */
    @JsonCreator
    public static Role fromWire(String wire) {
        for (Role r : Role.values()) {
            if (r.wire.equals(wire)) {
                return r;
            }
        }

        throw new IllegalArgumentException("Unknown role value:" + wire);
    }
}
