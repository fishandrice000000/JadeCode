package com.jadecode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    private final BashTool bash = new BashTool();

    /** 构造 input 并执行:测试期的主循环替代品 */
    private String run(String command) {
        return run(bash, command);
    }

    private String run(BashTool tool, String command) {
        ObjectNode input = Json.mapper().createObjectNode();
        input.put("command", command);
        return tool.execute(input);
    }

    /** ① 正常执行:echo 输出原文(无换行) */
    @Test
    void executesSimpleCommand() {
        assertEquals("hello", run("echo hello"));
    }

    /** ② 空输出:返回占位符 */
    @Test
    void emptyOutputBecomesPlaceholder() {
        assertEquals("(no output)", run("true"));
    }

    /** ③ stderr 并入 stdout */
    @Test
    void stderrIsMergedIntoOutput() {
        assertEquals("err", run("echo err 1>&2"));
    }

    /** ④ 未知命令:shell 存在、进程正常启动,stderr 报 not found(不是 "Error:") */
    @Test
    void unknownCommandReturnsShellError() {
        String out = run("jadecode-nonexistent-cmd-xyz");
        assertTrue(out.contains("not found"));
    }

    /** ⑤ 黑名单:子串匹配拦截 */
    @Test
    void dangerousCommandsAreBlocked() {
        assertEquals("Error: Dangerous command blocked", run("sudo ls"));
        assertEquals("Error: Dangerous command blocked", run("rm -rf /tmp/x"));
    }

    /** ⑥ 超时:注入 2 秒超时 + sleep 5 → 超时消息随配置 */
    @Test
    void longRunningCommandTimesOut() {
        BashTool impatient = new BashTool(2);
        assertEquals("Error: Timeout (2s)", run(impatient, "sleep 5"));
    }

    /** ⑦ 超长输出截断到 50000 字符(60000 < 64KB 管道缓冲,进程能正常退出) */
    @Test
    void longOutputIsTruncated() {
        String out = run("awk 'BEGIN{for(i=0;i<60000;i++)printf \"x\"}'");
        assertEquals("x".repeat(50000), out);
    }

    /** ⑧ 缺 command 参数:返回错误字符串,不抛 NPE */
    @Test
    void missingCommandParameterReturnsError() {
        assertEquals("Error: Missing required parameter: command",
                bash.execute(Json.mapper().createObjectNode()));
    }
}
