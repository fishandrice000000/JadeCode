package com.jadecode.safety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PathGuard 路径分类器(v2)
 *
 * 从 s02 的"硬拦器"转型为"分类器":解析路径后分三类——
 * 工作区内(放行)/ 高危区(闸门 1 硬拒)/ 工作区外(闸门 2 询问)。
 * 只做判断,不抛异常;拒绝与否由 PermissionGate 决定。
 *
 * 注意:裸工具路线下,工具本身不再调用本类——分类结果经闸门生效,
 * 单点执法是已接受的取舍(见 docs/jobs/v2/path-fence.md)。
 */
public class PathGuard {
    // 学习项目清单:不完整,不是完整安全边界(课程对 deny list 同定位)
    private static final List<String> HIGH_RISK = List.of(
            "/etc", "/boot", "/dev", "/sys", "/proc", "/bin", "/sbin", "/lib", "/lib64", "/usr", "/var");

    /** 路径解析器:所有判定都基于它解析后的路径 */
    private final PathResolver resolver;

    /**
     * @param resolver 路径解析器
     */
    public PathGuard(PathResolver resolver) {
        this.resolver = resolver;
    }

    /** 闸门 1 用:解析后路径落在高危区内 → 命中理由,否则 null */
    public String highRiskReason(String raw) {
        Path p = resolver.resolve(raw);
        return isHighRisk(p) ? "Access to protected directory: " + p : null;
    }

    /** 闸门 2 用:解析后路径在工作区外且非高危 → 命中理由,否则 null */
    public String outsideReason(String raw) {
        Path p = resolver.resolve(raw);
        if (isHighRisk(p) || p.startsWith(resolver.workspaceReal())) {
            return null;
        }
        return "Access outside workspace";
    }

    /** 高危目录清单(CommandPolicy 构造 bash 路径片段规则用,单一真相源) */
    static List<String> highRiskDirs() {
        return HIGH_RISK;
    }

    /** 词法前缀比对;目录本身是符号链接时再比对其真实路径(如 macOS 的 /tmp) */
    private static boolean isHighRisk(Path p) {
        for (String dir : HIGH_RISK) {
            Path d = Path.of(dir);
            if (p.startsWith(d)) {
                return true;
            }
            if (Files.isDirectory(d) && p.startsWith(realOf(d))) {
                return true;
            }
        }
        return false;
    }

    /** toRealPath 的容错版:不存在/不可读时退回词法路径 */
    private static Path realOf(Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            return p; // 不存在/不可读:保持词法,词法比对已在上一步做过
        }
    }
}
