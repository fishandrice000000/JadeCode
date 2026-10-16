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
 * fail-closed:需要询问的操作若没有可信人工来源、没有审批通道或用户拒绝，都不执行。
 * 未命中问询规则的普通操作不要求交互批准，不能误解为定时任务一律禁用工具。
 * 线程身份不再代表权限来源。
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
     * 装配用构造：无上下文调用的问询默认拒绝，真实审批通道由任务携带。
     *
     * @param policy 命令策略(闸门 1/2 的 bash 规则来源)
     * @param guard  路径分类器(闸门 1/2 的文件规则来源)
     */
    public PermissionGate(CommandPolicy policy, PathGuard guard) {
        this(policy, guard, (name, input, reason) -> false);
    }

    /**
     * 为旧直接调用或离线检查注入默认审批，不在构造时读取终端或执行任何规则。
     * 仅两参数 check 使用这个默认通道；生产的上下文重载始终按任务真实来源选通道。
     *
     * @param policy   命令策略
     * @param guard    路径分类器
     * @param approval 默认审批实现，例如离线替身；不应为 null
     */
    public PermissionGate(CommandPolicy policy, PathGuard guard, Approval approval) {
        this.policy = policy;
        this.guard = guard;
        this.approval = approval;
        this.hardRules = buildHardRules();
        this.askRules = buildAskRules();
    }

    /**
     * 构建硬拒表，不因人工同意而放行危险命令或高风险路径。
     * @return 有序规则：命令拒绝表、文件高风险路径；此处只绑定检查函数，不执行工具
     */
    private List<Rule> buildHardRules() {
        return List.of(
                new Rule(List.of("bash"),
                        input -> policy.checkDenyList(input.path("command").asText(""))),
                new Rule(List.of("read_file", "write_file", "edit_file"),
                        input -> guard.highRiskReason(input.path("path").asText(""))));
    }

    /**
     * 构建需要询问的规则表，与硬拒规则分开，避免批准绕开绝对禁止项。
     * @return 有序的命令、受保护目录及工作区外文件规则，首个命中理由用于展示
     */
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
     * 兼容不携带任务上下文的直接检查，使用构造时注入的默认审批通道。
     * 默认生产构造的通道只拒绝问询，不在这里读取 stdin。
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
     *
     * <p>同一个消费者会执行不同来源任务，因此不能再用“是否主线程”推断审批资格。
     * 子代理传递父身份，也不能通过委派升级定时任务权限。缺少/关闭上下文仅使问询拒绝，
     * 它不是整个执行器的取消机制；执行器仍须在实际工具步骤前复核关闭。</p>
     * @param toolName 工具分发表中的名称
     * @param input 非 null 的参数对象，只读检查 command/path，不在此执行工具
     * @param context Harness 创建的上下文；可为 null，不能从模型 input 中提取替代
     * @return null 表示规则放行；非 null 为拒绝原因，由上游包装成 tool_result
     */
    public String check(String toolName, ObjectNode input, ExecutionContext context) {
        Approval channel = context != null && !context.isCancelled() && context.source() == ExecutionContext.Source.USER
                && context.approval() != null ? context.approval() : (name, args, reason) -> false;
        return checkWith(toolName, input, channel);
    }

    /**
     * 依次执行硬拒、问询匹配、必要审批；不依据模型自报身份或命令输出决定权限。
     * @param toolName 待检查工具名
     * @param input 非 null 的只读参数
     * @param channel 已由可信上下文选定的非 null 通道，可能阻塞等用户，但本类不读终端
     * @return null 放行；硬拒或用户拒绝时返回命中理由，硬拒同时打印 blocked 诊断
     */
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

    /**
     * 按注册顺序查适用于此工具的首个命中规则，不运行其它工具的路径/命令判断。
     * @param rules 硬拒或问询规则表
     * @param toolName 待匹配工具名
     * @param input 只读参数对象
     * @return 第一个非 null 理由；无适用规则或均未命中时为 null
     */
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

    /**
     * 一条规则只产生匹配理由，不直接执行工具或向用户提问。
     * @param tools 适用工具名
     * @param check 参数到理由的检查函数；null 表示未命中
     */
    private record Rule(List<String> tools, Function<ObjectNode, String> check) {
        /**
         * @param input 待判断的只读参数对象
         * @return 检查函数给出的命中理由，未命中为 null
         */
        String reason(ObjectNode input) {
            return check.apply(input);
        }
    }

    /**
     * 人工审批通道，不是安全规则本身。生产实现由 ConsoleInput 统一读取终端，执行线程
     * 在此等待；离线检查可注入固定答复。接口对象仅由 Harness 传递，不是模型参数。
     */
    @FunctionalInterface
    public interface Approval {
        /**
         * 请求用户明确决定一项已命中问询规则的操作；实现可以阻塞，但不得绕开硬拒规则。
         * 无答复、关闭或中断应按拒绝处理，不以任意下一行输入充当批准。
         *
         * @param toolName 待审批的工具名
         * @param input    工具入参
         * @param reason   命中闸门 2 的理由(展示给用户)
         * @return true 放行,false 拒绝
         */
        boolean ask(String toolName, ObjectNode input, String reason);
    }
}
