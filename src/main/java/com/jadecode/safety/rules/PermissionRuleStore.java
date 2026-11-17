package com.jadecode.safety.rules;

import java.io.IOException;

/**
 * 保存明确的长期授权。实现由 config 注入，权限判断代码不依赖具体 JSON 文件。
 */
@FunctionalInterface
public interface PermissionRuleStore {
    /**
     * 保存一条用户确认的长期权限规则。写入完成后调用方再更新运行时权限快照；持久化失败以 IOException 返回。
     *
     * @param rule 本次检查、匹配或持久保存的一条权限规则
     * @throws IOException 长期规则持久保存失败，运行时继续保留原规则快照
     */
    void save(PermissionRule rule) throws IOException;
}
