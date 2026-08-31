package com.jadecode.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Json 工具类/
 * 
 * 全局唯一 Json 转换入口.
 * 
 * 配置:
 * 1. 忽略未知字段.
 * 2. 不输出 null 字段.
 * 
 */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
