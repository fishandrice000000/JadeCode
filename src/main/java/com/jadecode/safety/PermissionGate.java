package com.jadecode.safety;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Ansi;

public class PermissionGate {
    private final CommandPolicy policy;
    private final PathGuard guard;
    private final Approval approval;
    private final Thread mainThread;
    private final List<Rule> hardRules; // 闸门 1:命中即拒,不询问
    private final List<Rule> askRules; // 闸门 2:命中 → 交给闸门 3 审批
    // 控制台输入只建一个,与 ReplCommand 同款(坑 6)
    private final BufferedReader console = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));

    // 装配用:审批 = 真实控制台交互
    public PermissionGate(CommandPolicy policy, PathGuard guard) {
        this.policy = policy;
        this.guard = guard;
        this.mainThread = Thread.currentThread();
        this.approval = this::consoleApproval;
        this.hardRules = buildHardRules(); // 在构造器里建表:字段初始化器拿不到 policy/guard
        this.askRules = buildAskRules();
    }

    // 测试用:审批 = 注入的假货
    public PermissionGate(CommandPolicy policy, PathGuard guard, Approval approval) {
        this.policy = policy;
        this.guard = guard;
        this.approval = approval;
        this.mainThread = Thread.currentThread();
        this.hardRules = buildHardRules();
        this.askRules = buildAskRules();
    }

    // 闸门 1 硬拒表:命中即拒,不询问
    private List<Rule> buildHardRules() {
        return List.of(
                new Rule(List.of("bash"),
                        input -> policy.checkDenyList(input.path("command").asText(""))),
                new Rule(List.of("read_file", "write_file", "edit_file"),
                        input -> guard.highRiskReason(input.path("path").asText(""))));
    }

    // 闸门 2 问询表:命中 → 交给闸门 3 审批
    private List<Rule> buildAskRules() {
        return List.of(
                new Rule(List.of("bash"),
                        input -> policy.checkRules(input.path("command").asText(""))),
                new Rule(List.of("bash"),
                        input -> policy.checkProtectedDirs(input.path("command").asText(""))),
                new Rule(List.of("read_file", "write_file", "edit_file"),
                        input -> guard.outsideReason(input.path("path").asText(""))));
    }

    // 默认审批的实现,方法引用指向它
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

    // 三道闸门串联;返回 null = 放行,非 null = 拒绝理由(loop 据此回灌带原因的消息)
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

    @FunctionalInterface
    public interface Approval {
        boolean ask(String toolName, ObjectNode input, String reason);
    }
}
