package com.jadecode.safety;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Ansi;

/**
 * 权限闸门:三道串联——闸门 1 硬拒表(命中即拒,不询问)、闸门 2 问询表(命中 → 闸门 3 审批)、
 * 闸门 3 审批(控制台 y/N)。
 *
 * <p>
 * fail-closed:EOF、读输入失败、非主线程(后台任务)一律拒绝——宁可不做,不可放开。
 * 闸门 1 拒绝时打印红色 [blocked] 行;审批拒绝时把命中理由带回,让模型知道边界在哪。
 * </p>
 */
public class PermissionGate {
    /** 命令策略(闸门 1/2 的 bash 规则来源) */
    private final CommandPolicy policy;
    /** 路径分类器(闸门 1/2 的文件规则来源) */
    private final PathGuard guard;
    /** 闸门 3:审批实现(生产 = 控制台,测试 = 注入假货) */
    private final Approval approval;
    /** 构造时的线程:非它请求审批一律拒绝(后台任务不能弹交互) */
    private final Thread mainThread;
    /** 闸门 1:命中即拒,不询问 */
    private final List<Rule> hardRules;
    /** 闸门 2:命中 → 交给闸门 3 审批 */
    private final List<Rule> askRules;
    /** 控制台输入只建一个,与 ReplCommand 同款(坑 6) */
    private final BufferedReader console = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));

    /**
     * 装配用构造:审批 = 真实控制台交互。
     *
     * @param policy 命令策略(闸门 1/2 的 bash 规则来源)
     * @param guard  路径分类器(闸门 1/2 的文件规则来源)
     */
    public PermissionGate(CommandPolicy policy, PathGuard guard) {
        this.policy = policy;
        this.guard = guard;
        this.mainThread = Thread.currentThread();
        this.approval = this::consoleApproval;
        this.hardRules = buildHardRules(); // 在构造器里建表:字段初始化器拿不到 policy/guard
        this.askRules = buildAskRules();
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
        this.mainThread = Thread.currentThread();
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

    /** 默认审批的实现,方法引用指向它:打印理由与入参,主线程等 y/N,其余一律拒绝 */
    private boolean consoleApproval(String toolName, ObjectNode input, String reason) {
        System.out.println("\n" + Ansi.yellow("[permission] " + reason));
        System.out.println("   Tool: " + toolName + "(" + input + ")");

        // 非主线程:不等输入,直接拒绝(总计划要求,后台任务用)
        if (Thread.currentThread() != mainThread) {
            System.out.println("   Denied: interactive approval is only available on the main thread");
            return false;
        }

        System.out.print("   Allow? [y/N] ");
        try {
            String line = console.readLine();
            if (line == null) {
                return false; // EOF:fail-closed
            }
            String answer = line.strip().toLowerCase();
            return answer.equals("y") || answer.equals("yes");
        } catch (IOException e) {
            return false; // 读不到输入也 fail-closed
        }
    }

    /**
     * 三道闸门串联。
     *
     * @param toolName 工具名(规则表按它筛选适用规则)
     * @param input    模型传来的参数(规则从中取 command / path)
     * @return null = 放行;非 null = 拒绝理由(调用方回灌给模型)
     */
    public String check(String toolName, ObjectNode input) {
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
        return approval.ask(toolName, input, reason) ? null : reason;
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
