package com.jadecode.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShellPermissionCheckTest {
    // 保留原有四个检查位置，按本轮新契约维护，不新增测试矩阵。
    private PermissionDecision check(String command) {
        var resolver = new com.jadecode.util.PathResolver(java.nio.file.Path.of("").toAbsolutePath());
        var action = new com.jadecode.safety.checks.ShellPermissionCheck(resolver).analyze("bash",
                com.jadecode.util.Json.mapper().createObjectNode().put("command", command));
        return new PermissionGate(resolver, PermissionState.defaults(), null).evaluate(action, PermissionState.defaults().snapshot());
    }

    /** 实际可执行名才触发系统保护；文件名中的 sudo 不触发。 */
    @Test
    void denyListBlocksAndPasses() {
        assertEquals(PermissionDecision.Kind.ASK, check("sudo ls").kind());
        assertEquals(PermissionDecision.Kind.DENY, check("rm -rf /").kind());
        assertEquals(PermissionDecision.Kind.ALLOW, check("cat sudo-guide.md").kind());
    }

    /** 复合命令逐段识别删除；只是打印 rm 文字不是删除。 */
    @Test
    void destructiveRegexRespectsWordBoundaries() {
        assertEquals(PermissionDecision.Kind.ASK, check("rm foo.txt").kind());
        assertEquals(PermissionDecision.Kind.ASK, check("rm 123").kind());
        assertEquals(PermissionDecision.Kind.ASK, check("echo a; rm b").kind());
        assertEquals(PermissionDecision.Kind.ALLOW, check("echo 'rm x.txt'").kind());
    }

    /** 重定向解释真实目标，/dev/null 不制造多余审批。 */
    @Test
    void destructiveKeywordsHit() {
        assertEquals(PermissionDecision.Kind.ALLOW, check("chmod 777 x").kind());
        assertEquals(PermissionDecision.Kind.ASK, check("> /etc/x").kind());
        assertEquals(PermissionDecision.Kind.ALLOW, check("echo hi > /dev/null").kind());
    }

    /** 越界读取需确认，打印路径文字和普通命令不需确认。 */
    @Test
    void protectedDirFragmentsHit() {
        assertEquals(PermissionDecision.Kind.ASK, check("cat /etc/hostname").kind());
        assertEquals(PermissionDecision.Kind.ALLOW, check("echo /etc/hostname").kind());
        assertEquals(PermissionDecision.Kind.ALLOW, check("echo hi").kind());
    }
}
