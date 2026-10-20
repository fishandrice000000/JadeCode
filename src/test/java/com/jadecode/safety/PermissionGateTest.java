package com.jadecode.safety;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.safety.approval.Approval;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.safety.approval.ApprovalRequest;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.util.Json;
import com.jadecode.util.PathResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionGateTest {

    private static ObjectNode input(String key, String value) {
        return Json.mapper().createObjectNode().put(key, value);
    }

    /** cwd 基准的闸门(注入假审批):bash 用例与文件用例共用 */
    private static PermissionGate gateWith(RecordingApproval approval) {
        return new PermissionGate(new PathResolver(Path.of("").toAbsolutePath()), PermissionState.defaults(), approval);
    }

    /** 记录审批调用:放行/拒绝开关 + 捕获透传参数 */
    private static class RecordingApproval implements Approval {
        boolean allow;
        final List<String[]> calls = new ArrayList<>();

        @Override
        public ApprovalAnswer ask(ApprovalRequest request) {
            calls.add(new String[] { request.tool(), request.reason() });
            return allow ? ApprovalAnswer.ALLOW_ONCE : ApprovalAnswer.DENY;
        }
    }

    /** 根目录删除仍是硬拒绝；普通文件名包含 sudo 不再误判。 */
    @Test
    void rootDeleteBlocksWithoutAsking() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        assertNotNull(gate.check("bash", input("command", "rm -rf /")));
        assertNull(gate.check("bash", input("command", "cat sudo-guide.md")));
        assertTrue(approval.calls.isEmpty());
    }

    /** 普通删除走审批；放行返回 null，拒绝保留规则原因。 */
    @Test
    void destructiveCommandAsksApproval() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        approval.allow = true;
        assertNull(gate.check("bash", input("command", "rm foo.txt")));

        approval.allow = false;
        assertTrue(gate.check("bash", input("command", "rm foo.txt")).contains("删除目标"));
    }

    /** ③ 审批收到的参数透传:工具名与命中理由 */
    @Test
    void approvalReceivesToolNameAndReason() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        gate.check("bash", input("command", "rm foo.txt"));

        assertEquals(1, approval.calls.size());
        assertEquals("bash", approval.calls.get(0)[0]);
        assertTrue(approval.calls.get(0)[1].contains("builtin.delete"));
    }

    /** ④ 无规则工具(glob):直接放行且不问 */
    @Test
    void nonBashToolsPassWithoutAsking() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        assertNull(gate.check("glob", input("pattern", "*.txt")));
        assertTrue(approval.calls.isEmpty());
    }

    /** ⑤ 定时来源即使有可放行通道也不能交互；不再拿线程身份代替来源。 */
    @Test
    void scheduledSourceRejectsWithoutAsking() {
        RecordingApproval approval = new RecordingApproval();
        approval.allow = true;
        PermissionGate gate = gateWith(approval);
        ExecutionContext context = ExecutionContext.create(ExecutionContext.Source.SCHEDULED, approval);
        AtomicReference<String> result = new AtomicReference<>();

        // 预置超时:若实现错误地阻塞在读输入上,测试超时失败而不是永久挂起
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Thread t = new Thread(() -> result.set(gate.check("bash", input("command", "rm foo.txt"), context)));
            t.setDaemon(true); // 兜底:即使线程卡死也不阻止 JVM 退出
            t.start();
            t.join();
        });
        assertTrue(result.get().contains("删除目标"));
        assertTrue(approval.calls.isEmpty());
    }

    /** /etc 读取现在按工作区外访问询问，不再永久硬拒整个目录。 */
    @Test
    void outsideSystemReadRequiresApproval() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        assertNotNull(gate.check("read_file", input("path", "/etc/passwd")));
        assertEquals(1, approval.calls.size());
    }

    /** ⑦ 文件工具工作区外路径(/tmp):走审批;放行 → null,拒绝 → 命中理由 */
    @Test
    void outsideWorkspaceFileAsksApproval() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        approval.allow = true;
        assertNull(gate.check("write_file", input("path", "/tmp/jadecode-v2-test.txt")));

        approval.allow = false;
        assertTrue(gate.check("write_file", input("path", "/tmp/jadecode-v2-test.txt")).contains("工作区外"));
    }

    /** ⑧ bash 触碰高危目录(cat /etc/hostname):走审批,堵旁路洞 */
    @Test
    void bashTouchingProtectedDirAsksApproval() {
        RecordingApproval approval = new RecordingApproval();
        PermissionGate gate = gateWith(approval);

        approval.allow = false;
        assertNotNull(gate.check("bash", input("command", "cat /etc/hostname")));
        assertEquals(1, approval.calls.size());
    }
}
