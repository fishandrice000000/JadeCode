package com.jadecode.safety;

import java.util.List;
import java.util.regex.Pattern;

public class CommandPolicy {
    private static final List<String> DENY_LIST = List.of("rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=",
            "> /dev/sda");
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "(?i)(?:^|[;&|()\\n])\\s*(?:rm|del)(?=\\s|$|[;&|()])");
    private static final List<String> DESTRUCTIVE_KEYWORDS = List.of("rm ", "> /etc/", "chmod 777");

    // 闸门 1:子串匹配 DENY_LIST(课程做法,不是正则)
    // 命中 → 返回 "Blocked: '{pattern}' is on the deny list"(课程消息,含单引号)
    // 全过 → 返回 null(null = 放行,不是错误)
    public String checkDenyList(String command) {
        for (String kw : DENY_LIST) {
            if (command.contains(kw)) {
                return "Blocked: \'" + kw + "\' is on the deny list";
            }
        }
        return null;
    }

    // 闸门 2 的 bash 部分:DESTRUCTIVE 正则命中 **或** 任一关键词子串命中
    // → 返回 "Potentially destructive command";否则 null
    public String checkRules(String command) {
        if (DESTRUCTIVE.matcher(command).find()) {
            return "Potentially destructive command";
        }

        for (String kw : DESTRUCTIVE_KEYWORDS) {
            if (command.contains(kw)) {
                return "Potentially destructive command";
            }
        }

        return null;
    }

    // 闸门 2 的 bash 部分追加:命令中出现高危目录路径片段 → 询问。
    // 堵 bash 旁路文件高危防线的洞(cat /etc/hostname 之类);
    // 片段由 PathGuard 高危清单派生(目录 + "/"),单一真相源。
    public String checkProtectedDirs(String command) {
        for (String dir : PathGuard.highRiskDirs()) {
            if (command.contains(dir + "/")) {
                return "Potentially touches protected directory";
            }
        }
        return null;
    }
}
