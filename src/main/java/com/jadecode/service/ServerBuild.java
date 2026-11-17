package com.jadecode.service;

import java.io.IOException;
import java.util.Properties;

/**
 * 服务端只读构建身份，不定位或启动 Node。Node CLI 在创建会话前用它检查配对版本。
 * 资源缺失时返回 unpaired，不能把普通 Maven 构建冒充成套发行。
 */
public record ServerBuild(String version, String buildId, int protocolVersion, boolean paired) {
    /** 从当前 JAR 的非秘密资源读取；不访问用户配置或网络。 */
    public static ServerBuild load() {
        try (var input = ServerBuild.class.getResourceAsStream("/META-INF/jade-build.properties")) {
            if (input == null)
                return new ServerBuild("1.1.0", "unpaired", 1, false);
            var properties = new Properties();
            properties.load(input);
            return new ServerBuild(properties.getProperty("version", "1.1.0"),
                    properties.getProperty("buildId", "unpaired"),
                    Integer.parseInt(properties.getProperty("protocolVersion", "1")),
                    Boolean.parseBoolean(properties.getProperty("paired", "false")));
        } catch (IOException | NumberFormatException invalid) {
            return new ServerBuild("1.1.0", "unpaired", 1, false);
        }
    }
}
