package com.jadecode.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Json 工具类:全局唯一的 Json 转换入口。
 *
 * <p>共享一个 ObjectMapper,配置两项:忽略未知字段(transcript 反序列化的命门)、
 * 不输出 null 字段。全项目读写 JSON 都从这里取 mapper,保证多态往返一致。</p>
 */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** 纯静态工具类,禁止实例化 */
    private Json() {
    }

    /** @return 全局共享的 ObjectMapper(不要另行 new,配置会不一致) */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
