package com.jadecode.safety.rules;

import java.nio.file.Path;
import java.util.*;
import com.jadecode.safety.*;
import com.jadecode.safety.checks.ToolPermissionCheck.Analysis;
import static com.jadecode.safety.checks.ShellPermissionCheck.executable;

/**
 * 少量有编号的初始保护。先检查硬保护/强制人工，再检查允许规则，最后处理普通风险。
 * 所有判断针对解析后的命令或资源，不搜索整段命令中的任意关键词。
 */
public final class DefaultPermissionRules {
    public static final Set<String> IDS = Set.of("builtin.root-delete", "builtin.raw-device-write", "builtin.system-control",
            "builtin.sensitive-access", "builtin.sensitive-write", "builtin.delete", "builtin.outside", "builtin.complex-shell");

    /** 普通 allow 不覆盖这里的保护；用户可在配置里明确禁用某个默认编号。 */
    public PermissionDecision protectedDecision(Analysis a, PermissionState.Snapshot state) {
        for (var r : a.resources()) {
            boolean write = !r.operation().equals("READ");
            if (r.operation().equals("DELETE") && r.path().getParent() == null && enabled(state, "builtin.root-delete"))
                return PermissionDecision.deny("builtin.root-delete", "拒绝删除文件系统根目录");
            if (write && r.path().startsWith("/dev") && !r.path().equals(Path.of("/dev/null")) && enabled(state, "builtin.raw-device-write"))
                return PermissionDecision.deny("builtin.raw-device-write", "拒绝覆盖原始设备目标：" + r.path());
            if (sensitive(r.path(), write) && enabled(state, write ? "builtin.sensitive-write" : "builtin.sensitive-access"))
                return PermissionDecision.ask(write ? "builtin.sensitive-write" : "builtin.sensitive-access", "需要人工确认敏感目标：" + r.path(), true);
        }
        for (var c : a.commands()) {
            String name = executable(c.argv().getFirst());
            if ((name.equals("mkfs") || name.startsWith("mkfs.")) && enabled(state, "builtin.raw-device-write"))
                return PermissionDecision.deny("builtin.raw-device-write", "拒绝磁盘格式化命令");
            if (Set.of("sudo", "su", "shutdown", "reboot", "poweroff", "halt").contains(name) && enabled(state, "builtin.system-control"))
                return PermissionDecision.ask("builtin.system-control", "需要人工确认系统管理命令：" + name, true);
        }
        if (state.profile().policy().readOnly() && (a.mutating() || !a.commands().isEmpty() && !a.readOnlyCommand()))
            return PermissionDecision.ask("profile.read-only", "Read Only 下写入或一般程序需要人工确认", true);
        return null;
    }
    /** 普通风险可以由精确用户 allow 消除；不确定并不意味着一定有害。 */
    public PermissionDecision ordinary(Analysis a, PermissionState.Snapshot state) {
        if (state.profile() != PermissionProfile.FULL_ACCESS && enabled(state, "builtin.delete")
                && a.resources().stream().anyMatch(r -> r.operation().equals("DELETE")))
            return PermissionDecision.ask("builtin.delete", "需要确认删除目标：" + a.resources().stream().filter(r -> r.operation().equals("DELETE")).map(r -> r.path().toString()).toList(), false);
        if (state.profile() != PermissionProfile.FULL_ACCESS && enabled(state, "builtin.outside")
                && a.resources().stream().anyMatch(r -> !r.path().startsWith(a.cwd())))
            return PermissionDecision.ask("builtin.outside", "访问工作区外的具体目标需要授权", false);
        if (a.uncertain() && state.profile() != PermissionProfile.FULL_ACCESS && enabled(state, "builtin.complex-shell"))
            return PermissionDecision.ask("builtin.complex-shell", "无法可靠静态分析本次工具调用", false);
        return PermissionDecision.allow();
    }
    private boolean enabled(PermissionState.Snapshot state, String id) { return !state.disabledDefaults().contains(id); }
    /** 只识别具体敏感目录/文件，不因为普通文件名中含 key/env 就拦截。 */
    private boolean sensitive(Path path, boolean write) {
        for (Path part : path) if (Set.of(".ssh", ".aws", ".gnupg").contains(part.toString())) return true;
        String file = path.getFileName() == null ? "" : path.getFileName().toString();
        if (file.equals(".env") || file.startsWith(".env.")) return true;
        if (!write) return false;
        if (Set.of(".bashrc", ".zshrc", ".profile").contains(file)) return true;
        // 权限配置和 Git 元数据可以改变后续执行；普通读取不受此写保护。
        for (Path part : path) if (part.toString().equals(".git")) return true;
        for (int i = 0; i + 1 < path.getNameCount(); i++)
            if (path.getName(i).toString().equals(".jadecode") && Set.of("config", "permissions").contains(path.getName(i + 1).toString())) return true;
        return Set.of("/etc", "/boot", "/sys", "/proc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/var").stream().anyMatch(path::startsWith);
    }
}
