package com.jadecode.safety.rules;

import java.io.IOException;

/** 保存明确的长期授权。实现由 config 注入，权限判断代码不依赖具体 JSON 文件。 */
@FunctionalInterface
public interface PermissionRuleStore { void save(PermissionRule rule) throws IOException; }
