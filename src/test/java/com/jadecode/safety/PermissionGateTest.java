package com.jadecode.safety;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionGateTest {

    private static ObjectNode input(String key, String value) {
        return Json.mapper().createObjectNode().put(key, value);
    }

    /** cwd 基准的闸门(注入假审批):bash 用例与文件用例共用 */
    private static PermissionGate gateWith(RecordingApproval approval) {
        PathGuard guard = new PathGuard(new PathResolver(Path.of("").toAbsolutePath()));
        return new PermissionGate(new CommandPolicy(), guard, approval);
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

    /** ① 闸门 1 短路(bash):deny list 命中 → 直接拒绝并带回理由,审批未被调用 */
    @Test
    void denyListBlocksWithoutAsking() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        assertEquals("Blocked: 'sudo' is on the deny list", gate.check("bash", input("command", "sudo ls")));
        assertTrue(approval.calls.isEmpty());
    }

    /** ② 闸门 3(bash):破坏性命令走审批;放行 → null,拒绝 → 命中理由 */
    @Test
    void destructiveCommandAsksApproval() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        approval.allow = true;
        assertNull(gate.check("bash", input("command", "rm foo.txt")));

        approval.allow = false;
        assertEquals("Potentially destructive command", gate.check("bash", input("command", "rm foo.txt")));
    }

    /** ③ 审批收到的参数透传:工具名与命中理由 */
    @Test
    void approvalReceivesToolNameAndReason() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        gate.check("bash", input("command", "rm foo.txt"));

        assertEquals(1, approval.calls.size());
        assertEquals("bash", approval.calls.get(0)[0]);
        assertEquals("Potentially destructive command", approval.calls.get(0)[1]);
    }

    /** ④ 无规则工具(glob):直接放行且不问 */
    @Test
    void nonBashToolsPassWithoutAsking() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        assertNull(gate.check("glob", input("pattern", "*.txt")));
        assertTrue(approval.calls.isEmpty());
    }

    /** ⑤ 默认审批 + 非主线程:直接拒绝并带回理由,不等输入(总计划:非主线程禁交互审批) */
    @Test
    void defaultApprovalRejectsOnNonMainThread() {
        PermissionGate gate = new PermissionGate(new CommandPolicy(),
                new PathGuard(new PathResolver(Path.of("").toAbsolutePath()))); // 默认审批
        AtomicReference<String> result = new AtomicReference<>();

        // 预置超时:若实现错误地阻塞在读输入上,测试超时失败而不是永久挂起
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Thread t = new Thread(() -> result.set(gate.check("bash", input("command", "rm foo.txt"))));
            t.setDaemon(true); // 兜底:即使线程卡死也不阻止 JVM 退出
            t.start();
            t.join();
        });
        assertEquals("Potentially destructive command", result.get());
    }

    /** ⑥ 文件工具高危路径(/etc):硬拒并带回理由,审批未被调用 */
    @Test
    void highRiskFilePathIsDeniedWithoutAsking() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        assertEquals("Access to protected directory: /etc/passwd",
                gate.check("read_file", input("path", "/etc/passwd")));
        assertTrue(approval.calls.isEmpty());
    }

    /** ⑦ 文件工具工作区外路径(/tmp):走审批;放行 → null,拒绝 → 命中理由 */
    @Test
    void outsideWorkspaceFileAsksApproval() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        approval.allow = true;
        assertNull(gate.check("write_file", input("path", "/tmp/jadecode-v2-test.txt")));

        approval.allow = false;
        assertEquals("Access outside workspace", gate.check("write_file", input("path", "/tmp/jadecode-v2-test.txt")));
    }

    /** ⑧ bash 触碰高危目录(cat /etc/hostname):走审批,堵旁路洞 */
    @Test
    void bashTouchingProtectedDirAsksApproval() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        approval.allow = false;
        assertEquals("Potentially touches protected directory",
                gate.check("bash", input("command", "cat /etc/hostname")));
        assertEquals(1, approval.calls.size());
    }
}
