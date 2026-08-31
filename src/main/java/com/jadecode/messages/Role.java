package com.jadecode.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Role 枚举
 * 
 * 消息 JSON 中的角色字段.
 * 
 */
public enum Role {
    USER("user"),
    ASSISTANT("assistant");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Role fromWire(String wire) {
        for (Role r : Role.values()) {
            if (r.wire.equals(wire)) {
                return r;
            }
        }

        throw new IllegalArgumentException("未知的 role 值:" + wire);
    }
}
