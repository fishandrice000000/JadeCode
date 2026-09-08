package com.jadecode.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandPolicyTest {

    private final CommandPolicy policy = new CommandPolicy();

    /** ① 闸门 1:子串匹配硬拒绝列表;不命中返回 null */
    @Test
    void denyListBlocksAndPasses() {
        assertEquals("Blocked: 'sudo' is on the deny list", policy.checkDenyList("sudo ls"));
        assertEquals("Blocked: 'rm -rf /' is on the deny list", policy.checkDenyList("rm -rf /"));
        assertNull(policy.checkDenyList("echo hi"));
    }

    /** ② 闸门 2 正则:词边界——rmdir/echo del 不触发,del 大小写不敏感 */
    @Test
    void destructiveRegexRespectsWordBoundaries() {
        assertEquals("Potentially destructive command", policy.checkRules("rm foo.txt"));
        assertEquals("Potentially destructive command", policy.checkRules("rm -rf /tmp/x"));
        assertEquals("Potentially destructive command", policy.checkRules("echo a; rm b"));
        assertEquals("Potentially destructive command", policy.checkRules("DEL x.txt"));
        assertNull(policy.checkRules("rmdir"));
        assertNull(policy.checkRules("echo del test.txt"));
    }

    /** ③ 闸门 2 关键词:chmod 777 与 > /etc/ 触发;普通命令放行 */
    @Test
    void destructiveKeywordsHit() {
        assertEquals("Potentially destructive command", policy.checkRules("chmod 777 x"));
        assertEquals("Potentially destructive command", policy.checkRules("> /etc/x"));
        assertNull(policy.checkRules("echo hi"));
    }
}
