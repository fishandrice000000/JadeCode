package com.jadecode.safety;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionGateTest {

    private static ObjectNode input(String key, String value) {
        return Json.mapper().createObjectNode().put(key, value);
    }

    /** 记录审批调用:放行/拒绝开关 + 捕获透传参数 */
    private static class RecordingApproval implements PermissionGate.Approval {
        boolean allow;
        final List<String[]> calls = new ArrayList<>();

        @Override
        public boolean ask(String toolName, ObjectNode input, String reason) {
            calls.add(new String[] { toolName, reason });
            return allow;
        }
    }

    /** ① 闸门 1 短路:deny list 命中 → 直接拒绝,审批未被调用 */
    @Test
    void denyListBlocksWithoutAsking() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = new PermissionGate(new CommandPolicy(), approval);

        assertFalse(gate.check("bash", input("command", "sudo ls")));
        assertTrue(approval.calls.isEmpty());
    }

    /** ② 闸门 3:破坏性命令走审批,审批的裁决就是最终裁决 */
    @Test
    void destructiveCommandAsksApproval() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = new PermissionGate(new CommandPolicy(), approval);

        approval.allow = true;
        assertTrue(gate.check("bash", input("command", "rm foo.txt")));

        approval.allow = false;
        assertFalse(gate.check("bash", input("command", "rm foo.txt")));
    }

    /** ③ 审批收到的参数透传:工具名与命中理由 */
    @Test
    void approvalReceivesToolNameAndReason() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = new PermissionGate(new CommandPolicy(), approval);

        gate.check("bash", input("command", "rm foo.txt"));

        assertEquals(1, approval.calls.size());
        assertEquals("bash", approval.calls.get(0)[0]);
        assertEquals("Potentially destructive command", approval.calls.get(0)[1]);
    }

    /** ④ 非 bash 工具:规则表无命中 → 放行且不问 */
    @Test
    void nonBashToolsPassWithoutAsking() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = new PermissionGate(new CommandPolicy(), approval);

        assertTrue(gate.check("glob", input("pattern", "*.txt")));
        assertTrue(approval.calls.isEmpty());
    }

    /** ⑤ 默认审批 + 非主线程:直接拒绝,不等输入(总计划:非主线程禁交互审批) */
    @Test
    void defaultApprovalRejectsOnNonMainThread() {
        PermissionGate gate = new PermissionGate(new CommandPolicy()); // 默认审批
        AtomicBoolean result = new AtomicBoolean();

        // 预置超时:若实现错误地阻塞在读输入上,测试超时失败而不是永久挂起
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Thread t = new Thread(() -> result.set(gate.check("bash", input("command", "rm foo.txt"))));
            t.setDaemon(true); // 兜底:即使线程卡死也不阻止 JVM 退出
            t.start();
            t.join();
        });
        assertFalse(result.get());
    }
}
