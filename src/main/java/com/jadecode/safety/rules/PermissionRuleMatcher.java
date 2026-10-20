package com.jadecode.safety.rules;

import java.nio.file.Path;
import java.util.List;
import com.jadecode.safety.checks.ToolPermissionCheck.*;

/** 规则只匹配解析结果；多段 shell 的允许规则必须覆盖所有命令，不能只批准第一段。 */
public final class PermissionRuleMatcher {
    /** deny/ask 任一部分命中即生效，allow 则另由 covers 检查整次调用。 */
    public PermissionRule first(List<PermissionRule> rules, PermissionRule.Effect effect, Analysis action) {
        return rules.stream().filter(r -> r.effect() == effect && applies(r, action)).findFirst().orElse(null);
    }
    /**
     * 判断用户 allow 是否授权整个调用；不修改规则或解释资料。
     * deny/ask 只要一部分命中就成立，但 allow 不能只覆盖第一段。
     * 命令前缀不自动包含重定向权限；多个资源也必须各自获准，避免越界目标漏查。
     */
    public boolean covers(List<PermissionRule> rules, Analysis action) {
        var allows = rules.stream().filter(r -> r.effect() == PermissionRule.Effect.ALLOW && scope(r, action)).toList();
        if (allows.stream().anyMatch(r -> r.kind() == PermissionRule.Kind.TOOL)) return true;
        if (!action.commands().isEmpty()) return !action.uncertain() && action.commands().stream()
                .allMatch(command -> !command.redirected() && allows.stream().anyMatch(rule -> command(rule, command)));
        return !action.resources().isEmpty() && action.resources().stream()
                .allMatch(resource -> allows.stream().anyMatch(rule -> resource(rule, resource)));
    }
    private boolean applies(PermissionRule rule, Analysis action) {
        if (!scope(rule, action)) return false;
        return switch (rule.kind()) {
            case TOOL -> true;
            case COMMAND -> action.commands().stream().anyMatch(c -> command(rule, c));
            case PATH -> action.resources().stream().anyMatch(r -> resource(rule, r));
        };
    }
    private boolean scope(PermissionRule r, Analysis a) {
        return (r.tool().equals("*") || r.tool().equals(a.tool()))
                && (r.workspace().isEmpty() || Path.of(r.workspace()).normalize().equals(a.cwd()));
    }
    private boolean command(PermissionRule rule, Command command) {
        if (rule.kind() != PermissionRule.Kind.COMMAND || command.argv().size() < rule.argvPrefix().size()) return false;
        // 前缀规则同时绑定实际 cwd，不能把 cd 到另一项目后的同名命令当作原授权。
        return (rule.workspace().isEmpty() || Path.of(rule.workspace()).normalize().equals(command.cwd()))
                && command.argv().subList(0, rule.argvPrefix().size()).equals(rule.argvPrefix());
    }
    private boolean resource(PermissionRule rule, Resource target) {
        if (rule.kind() != PermissionRule.Kind.PATH || !rule.operation().equals(target.operation())) return false;
        Path path = Path.of(rule.path()).normalize();
        return rule.recursive() ? target.path().startsWith(path) : target.path().equals(path);
    }
}
