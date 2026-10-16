package com.jadecode.safety;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.agent.ExecutionContext;
import com.jadecode.util.Ansi;

/**
 * 权限闸门:三道串联——闸门 1 硬拒表(命中即拒,不询问)、闸门 2 问询表(命中 → 闸门 3 审批)、
 * 闸门 3 审批(由统一输入入口提供，不在这里读取 stdin)。
 *
 * <p>
 * fail-closed:没有可信交互来源、没有审批通道或用户拒绝都不执行。线程身份不再代表权限来源。
 * 闸门 1 拒绝时打印红色 [blocked] 行;审批拒绝时把命中理由带回,让模型知道边界在哪。
 * </p>
 */
public class PermissionGate {
    /** 命令策略(闸门 1/2 的 bash 规则来源) */
    private final CommandPolicy policy;
    /** 路径分类器(闸门 1/2 的文件规则来源) */
    private final PathGuard guard;
    /** 兼容直接调用时的默认审批；生产默认拒绝，由任务上下文显式携带终端通道。 */
    private final Approval approval;
    /** 闸门 1:命中即拒,不询问 */
    private final List<Rule> hardRules;
    /** 闸门 2:命中 → 交给闸门 3 审批 */
    private final List<Rule> askRules;

    /**
     * 装配用构造：无上下文调用默认拒绝，真实审批通道由任务携带。
     *
     * @param policy 命令策略(闸门 1/2 的 bash 规则来源)
     * @param guard  路径分类器(闸门 1/2 的文件规则来源)
     */
    public PermissionGate(CommandPolicy policy, PathGuard guard) {
        this(policy, guard, (name, input, reason) -> false);
    }

    /**
     * 测试用构造:审批 = 注入的假货,不碰真实控制台。
     *
     * @param policy   命令策略
     * @param guard    路径分类器
     * @param approval 审批实现(测试替身:直接返回 true/false)
     */
    public PermissionGate(CommandPolicy policy, PathGuard guard, Approval approval) {
        this.policy = policy;
        this.guard = guard;
        this.approval = approval;
        this.hardRules = buildHardRules();
        this.askRules = buildAskRules();
    }

    /** 闸门 1 硬拒表:命中即拒,不询问 */
    private List<Rule> buildHardRules() {
        return List.of(
                new Rule(List.of("bash"),
                        input -> policy.checkDenyList(input.path("command").asText(""))),
                new Rule(List.of("read_file", "write_file", "edit_file"),
                        input -> guard.highRiskReason(input.path("path").asText(""))));
    }

    /** 闸门 2 问询表:命中 → 交给闸门 3 审批 */
    private List<Rule> buildAskRules() {
        return List.of(
                new Rule(List.of("bash"),
                        input -> policy.checkRules(input.path("command").asText(""))),
                new Rule(List.of("bash"),
                        input -> policy.checkProtectedDirs(input.path("command").asText(""))),
                new Rule(List.of("read_file", "write_file", "edit_file"),
                        input -> guard.outsideReason(input.path("path").asText(""))));
    }

    /**
     * 三道闸门串联。
     *
     * @param toolName 工具名(规则表按它筛选适用规则)
     * @param input    模型传来的参数(规则从中取 command / path)
     * @return null = 放行;非 null = 拒绝理由(调用方回灌给模型)
     */
    public String check(String toolName, ObjectNode input) {
        return checkWith(toolName, input, approval);
    }

    /**
     * 生产执行入口：只允许人工来源使用自身审批通道；定时任务即使携带通道也不能询问。
     * 硬拒与路径规则仍先运行，绝不会因来源是人工而自动放行。
     */
    public String check(String toolName, ObjectNode input, ExecutionContext context) {
        Approval channel = context != null && !context.isCancelled() && context.source() == ExecutionContext.Source.USER
                && context.approval() != null ? context.approval() : (name, args, reason) -> false;
        return checkWith(toolName, input, channel);
    }

    /** 串联规则和选定审批通道；不依据命令输出或模型自报身份决定权限。 */
    private String checkWith(String toolName, ObjectNode input, Approval channel) {
        // 闸门 1:查硬拒表;命中 → 打印 + 拒绝(不询问)
        String denied = findReason(hardRules, toolName, input);
        if (denied != null) {
            System.out.println(Ansi.red("[blocked] " + denied));
            return denied;
        }

        // 闸门 2:查询问表;没命中 → 放行
        String reason = findReason(askRules, toolName, input);
        if (reason == null) {
            return null;
        }

        // 闸门 3:审批;拒绝时把命中理由带回去,让模型知道边界
        return channel.ask(toolName, input, reason) ? null : reason;
    }

    /** 遍历规则表:返回适用该工具且命中的第一条规则的理由;无命中返回 null */
    private static String findReason(List<Rule> rules, String toolName, ObjectNode input) {
        for (Rule rule : rules) {
            if (rule.tools().contains(toolName)) {
                String reason = rule.reason(input);
                if (reason != null) {
                    return reason;
                }
            }
        }
        return null;
    }

    /** 一条权限规则:适用哪些工具 + 检查函数(返回命中理由,null = 放行) */
    private record Rule(List<String> tools, Function<ObjectNode, String> check) {
        String reason(ObjectNode input) {
            return check.apply(input);
        }
    }

    /** 审批接口:闸门 3 的可替换点——生产实现是控制台,测试注入假货 */
    @FunctionalInterface
    public interface Approval {
        /**
         * @param toolName 待审批的工具名
         * @param input    工具入参
         * @param reason   命中闸门 2 的理由(展示给用户)
         * @return true 放行,false 拒绝
         */
        boolean ask(String toolName, ObjectNode input, String reason);
    }
}
