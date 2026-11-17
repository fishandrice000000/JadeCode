package com.jadecode.safety.rules;

import java.nio.file.Path;
import java.util.List;
import com.jadecode.safety.checks.ToolPermissionCheck.*;

/**
 * 规则只匹配解析结果；多段 shell 的允许规则必须覆盖所有命令，不能只批准第一段。
 */
public final class PermissionRuleMatcher {
    /**
     * deny/ask 任一部分命中即生效，allow 则另由 covers 检查整次调用。
     *
     * @param rules 用户配置的有序权限规则列表
     * @param effect 本次要查找的 ALLOW、DENY 或 ASK 规则类型
     * @param action 从工具参数解析出的命令、资源及读写属性
     * @return 指定 effect 中首条部分命中的规则；未命中为 null
     */
    public PermissionRule first(List<PermissionRule> rules, PermissionRule.Effect effect, Analysis action) {
        return rules.stream().filter(r -> r.effect() == effect && applies(r, action)).findFirst().orElse(null);
    }

    /**
     * 判断用户 allow 是否授权整个调用；不修改规则或解释资料。
     * deny/ask 只要一部分命中就成立，但 allow 不能只覆盖第一段。
     * 命令前缀不自动包含重定向权限；多个资源也必须各自获准，避免越界目标漏查。
     *
     * @param rules 用户配置的有序权限规则列表
     * @param action 从工具参数解析出的命令、资源及读写属性
     * @return 用户 allow 覆盖本次全部命令或资源时为 true
     */
    public boolean covers(List<PermissionRule> rules, Analysis action) {
        var allows = rules.stream().filter(r -> r.effect() == PermissionRule.Effect.ALLOW && scope(r, action)).toList();
        if (allows.stream().anyMatch(r -> r.kind() == PermissionRule.Kind.TOOL))
            return true;
        if (!action.commands().isEmpty())
            return !action.uncertain() && action.commands().stream()
                    .allMatch(command -> !command.redirected()
                            && allows.stream().anyMatch(rule -> command(rule, command)));
        return !action.resources().isEmpty() && action.resources().stream()
                .allMatch(resource -> allows.stream().anyMatch(rule -> resource(rule, resource)));
    }

    /**
     * 先核对规则的工具与工作区范围，再按规则类型匹配整把工具、任一命令段或任一资源；用于 deny 和 ask 的部分命中判断。
     *
     * @param rule 本次检查、匹配或持久保存的一条权限规则
     * @param action 从工具参数解析出的命令、资源及读写属性
     * @return 规则范围匹配且工具、命令或任一资源命中时为 true
     */
    private boolean applies(PermissionRule rule, Analysis action) {
        if (!scope(rule, action))
            return false;
        return switch (rule.kind()) {
            case TOOL -> true;
            case COMMAND -> action.commands().stream().anyMatch(c -> command(rule, c));
            case PATH -> action.resources().stream().anyMatch(r -> resource(rule, r));
        };
    }

    /**
     * 匹配规则的工具名与起始工作目录；工具星号匹配任意工具，空工作区字段匹配任意目录。
     *
     * @param r 需要匹配工具和工作区范围的权限规则
     * @param a 工具分析得到的资源与命令资料
     * @return 工具名与起始工作目录均符合规则范围时为 true
     */
    private boolean scope(PermissionRule r, Analysis a) {
        return (r.tool().equals("*") || r.tool().equals(a.tool()))
                && (r.workspace().isEmpty() || Path.of(r.workspace()).normalize().equals(a.cwd()));
    }

    /**
     * 核对 COMMAND 规则的参数前缀和命令段实际工作目录；参数逐项相同且目录匹配时返回 true。
     *
     * @param rule 本次检查、匹配或持久保存的一条权限规则
     * @param command Shell 分析中的一个命令段，含 argv、实际目录和重定向标记
     * @return 命令类型、argv 前缀及实际工作目录均匹配时为 true
     */
    private boolean command(PermissionRule rule, Command command) {
        if (rule.kind() != PermissionRule.Kind.COMMAND || command.argv().size() < rule.argvPrefix().size())
            return false;
        // 前缀规则同时绑定实际 cwd，不能把 cd 到另一项目后的同名命令当作原授权。
        return (rule.workspace().isEmpty() || Path.of(rule.workspace()).normalize().equals(command.cwd()))
                && command.argv().subList(0, rule.argvPrefix().size()).equals(rule.argvPrefix());
    }

    /**
     * 核对 PATH 规则的读写操作，再按 recursive 选择路径相等或目录包含关系，返回目标资源是否命中。
     *
     * @param rule 本次检查、匹配或持久保存的一条权限规则
     * @param target 带真实路径和 READ、WRITE 或 DELETE 操作的目标资源
     * @return 路径规则的操作种类和精确或递归范围均匹配时为 true
     */
    private boolean resource(PermissionRule rule, Resource target) {
        if (rule.kind() != PermissionRule.Kind.PATH || !rule.operation().equals(target.operation()))
            return false;
        Path path = Path.of(rule.path()).normalize();
        return rule.recursive() ? target.path().startsWith(path) : target.path().equals(path);
    }
}
