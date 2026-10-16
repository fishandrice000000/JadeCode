package com.jadecode.safety;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 判断一条 bash 命令是不是被禁止、还是需要先问用户。
 *
 * <p>只判断不执行：返回 null 表示放行，非 null 是拒绝或询问的理由，会作为工具结果回给模型。
 * 三段检查互不调用，先后顺序由 {@link PermissionGate} 决定。
 */
public class CommandPolicy {
    private static final List<String> DENY_LIST = List.of("rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=",
            "> /dev/sda");
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "(?i)(?:^|[;&|()\\n])\\s*(?:rm|del)(?=\\s|$|[;&|()])");
    private static final List<String> DESTRUCTIVE_KEYWORDS = List.of("rm ", "> /etc/", "chmod 777");

    /**
     * 闸门 1:子串匹配黑名单(课程做法,不是正则)。
     *
     * @param command 模型传来的完整命令字符串
     * @return 命中:"Blocked: '...' is on the deny list";全过:null(放行,不是错误)
     */
    public String checkDenyList(String command) {
        for (String kw : DENY_LIST) {
            if (command.contains(kw)) {
                return "Blocked: \'" + kw + "\' is on the deny list";
            }
        }
        return null;
    }

    /**
     * 闸门 2 的 bash 部分:破坏性正则命中 **或** 任一关键词子串命中。
     *
     * @param command 完整命令字符串
     * @return 命中:"Potentially destructive command";否则 null
     */
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

    /**
     * 闸门 2 的 bash 部分追加:命令中出现高危目录路径片段 → 询问。
     *
     * <p>
     * 堵上 bash 绕过文件高危防线的漏洞（比如 {@code cat /etc/hostname}）：命令里出现高危目录片段就询问。
     * 片段由 {@link PathGuard#highRiskDirs()} 生成（目录后面加 "/"），两边共用同一份清单。
     * </p>
     *
     * @param command 完整命令字符串
     * @return 命中:"Potentially touches protected directory";否则 null
     */
    public String checkProtectedDirs(String command) {
        for (String dir : PathGuard.highRiskDirs()) {
            if (command.contains(dir + "/")) {
                return "Potentially touches protected directory";
            }
        }
        return null;
    }
}
