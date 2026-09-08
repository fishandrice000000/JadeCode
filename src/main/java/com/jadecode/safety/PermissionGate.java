package com.jadecode.safety;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class PermissionGate {
    private final CommandPolicy policy;
    private final Approval approval;
    private final Thread mainThread;
    private final List<Rule> rules;
    // 控制台输入只建一个,与 ReplCommand 同款(坑 6)
    private final BufferedReader console = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));

    // 装配用:审批 = 真实控制台交互
    public PermissionGate(CommandPolicy policy) {
        this.policy = policy;
        this.mainThread = Thread.currentThread();
        this.approval = this::consoleApproval;
        this.rules = buildRules(); // 在构造器里建表:字段初始化器拿不到 policy
    }

    // 测试用:审批 = 注入的假货
    public PermissionGate(CommandPolicy policy, Approval approval) {
        this.policy = policy;
        this.approval = approval;
        this.mainThread = Thread.currentThread();
        this.rules = buildRules();
    }

    // 规则表(课程 PERMISSION_RULES 的 Java 形状):检查函数返回命中理由,null = 不命中
    private List<Rule> buildRules() {
        return List.of(
                new Rule(List.of("bash"),
                        input -> policy.checkRules(input.path("command").asText(""))));
    }

    // 默认审批的实现,方法引用指向它
    private boolean consoleApproval(String toolName, ObjectNode input, String reason) {
        System.out.println("\n[permission] " + reason);
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

    // 三道闸门串联;false = 拒绝(loop 回灌 "Permission denied."),true = 放行
    public boolean check(String toolName, ObjectNode input) {
        // 闸门 1:硬拒绝列表(仅 bash,课程同款);命中 → 打印 + 拒绝(不询问)
        if (toolName.equals("bash")) {
            String denied = policy.checkDenyList(input.path("command").asText(""));
            if (denied != null) {
                System.out.println("[blocked] " + denied);
                return false;
            }
        }

        // 闸门 2:按顺序遍历规则表,第一条命中即止(课程 check_rules 同款短路)
        String reason = null;
        for (Rule rule : rules) {
            if (rule.tools().contains(toolName)) {
                reason = rule.reason(input);
                if (reason != null) {
                    break;
                }
            }
        }
        if (reason == null) {
            return true; // 没有规则命中 → 放行(非 bash 工具自然走到这里)
        }

        // 闸门 3:审批
        return approval.ask(toolName, input, reason);
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
