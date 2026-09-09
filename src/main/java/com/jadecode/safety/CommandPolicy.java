package com.jadecode.safety;

import java.util.List;
import java.util.regex.Pattern;

/**
 * bash 命令策略:闸门 1(黑名单硬拒)与闸门 2(破坏性 / 高危目录 → 询问)的规则判定。
 *
 * <p>
 * 只判定不执行:返回 null = 放行,非 null = 拒绝或询问理由(文案经闸门回灌给模型)。
 * 三个检查互不调用,串联顺序由 {@link PermissionGate} 决定。
 * </p>
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
     * 堵 bash 旁路文件高危防线的洞(cat /etc/hostname 之类);
     * 片段由 {@link PathGuard#highRiskDirs()} 派生(目录 + "/"),单一真相源。
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
