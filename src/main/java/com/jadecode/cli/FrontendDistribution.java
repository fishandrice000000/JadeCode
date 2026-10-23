package com.jadecode.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.util.Json;

/**
 * 定位“成套产物”中的 Node 前端，并核对构建身份与运行时要求。
 *
 * <p>为什么必须相对 JAR 实际位置：发行包是 {@code dist/jadecode/jade + lib/jadecode.jar +
 * lib/frontend/...}，开发完整构建是 {@code target/jadecode.jar + target/frontend/...}。两者都不
 * 依赖当前工作目录，也不能回退到源码目录里的 {@code frontend/dist}，否则“本地能跑、发行包
 * 缺文件”会被掩盖。</p>
 *
 * <p>这里只做只读检查：不联网安装、不执行 npm、不修改任何文件。开发期只 {@code mvn package}
 * 的 JAR 会标记为 {@code unpaired}，交互启动明确失败并给出构建命令；headless/help/version 不
 * 经过这里，因此不需要 Node。</p>
 */
public final class FrontendDistribution {
    /** 缺 Node/缺前端/身份不匹配；调用方返回退出码 2 并打印清楚原因。 */
    public static final class Missing extends Exception {
        public Missing(String message) { super(message); }
    }

    /** 前端清单（由 build-release 生成）中的启动必需字段。 */
    public record Manifest(String buildId, int protocolVersion, String entry, String compiler,
            String nodeEngine, String builtAt) { }

    private static final String RESOURCE = "/META-INF/jade-build.properties";
    private final Path frontendDirectory;
    private final Manifest manifest;
    private final String backendBuildId;
    private final int backendProtocolVersion;
    private final String projectVersion;

    private FrontendDistribution(Path frontendDirectory, Manifest manifest, String backendBuildId,
            int backendProtocolVersion, String projectVersion) {
        this.frontendDirectory = frontendDirectory;
        this.manifest = manifest;
        this.backendBuildId = backendBuildId;
        this.backendProtocolVersion = backendProtocolVersion;
        this.projectVersion = projectVersion;
    }

    /** @return 前端根目录（含 dist/assets/node_modules）。 */
    public Path frontendDirectory() { return frontendDirectory; }

    /** @return Node 入口脚本的绝对路径。 */
    public Path entry() { return frontendDirectory.resolve(manifest.entry()).normalize(); }

    /** @return 关系配对后的构建身份。 */
    public String buildId() { return manifest.buildId(); }

    /** @return 协议版本。 */
    public int protocolVersion() { return manifest.protocolVersion(); }

    /** @return 项目版本（来自 JAR 内过滤资源）。 */
    public String projectVersion() { return projectVersion; }

    /**
     * 根据 JAR/class 目录位置定位前端并核对身份。
     *
     * @throws Missing 前端未配对、清单缺失、入口缺失或 buildId/协议版本不一致
     */
    public static FrontendDistribution locate() throws Missing {
        BuildInfo backend = BuildInfo.load();
        Path base = installationDirectory();
        Path frontend = base.resolve("frontend");
        Path manifestPath = frontend.resolve("frontend-manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new Missing("No paired Node frontend was found at " + frontend
                    + ".\nBuild the complete distribution with: node scripts/build-release.mjs"
                    + "\n(headless, --help and --version do not need the frontend.)");
        }
        JsonNode node;
        try {
            node = Json.mapper().readTree(Files.readString(manifestPath));
        } catch (IOException | RuntimeException failure) {
            throw new Missing("The frontend manifest cannot be read: " + failure.getMessage());
        }
        Manifest manifest = new Manifest(
                node.path("buildId").asText(""),
                node.path("protocolVersion").asInt(-1),
                node.path("entry").asText("dist/main.js"),
                node.path("compiler").asText(""),
                node.path("nodeEngine").asText(""),
                node.path("builtAt").asText(""));
        if (manifest.buildId().isBlank())
            throw new Missing("The frontend manifest has no buildId; rebuild with scripts/build-release.mjs");
        if (!backend.paired() || backend.buildId().isBlank() || backend.buildId().equals("unpaired"))
            throw new Missing("This JAR is not paired with a Node frontend (buildId=unpaired)."
                    + "\nBuild the complete distribution with: node scripts/build-release.mjs");
        if (!backend.buildId().equals(manifest.buildId()))
            throw new Missing("The JAR and the Node frontend are from different builds (" + backend.buildId()
                    + " != " + manifest.buildId() + "). Rebuild the complete distribution.");
        if (manifest.protocolVersion() != backend.protocolVersion())
            throw new Missing("The frontend speaks protocol " + manifest.protocolVersion() + " but this backend speaks "
                    + backend.protocolVersion() + ".");
        FrontendDistribution distribution = new FrontendDistribution(frontend, manifest, backend.buildId(),
                backend.protocolVersion(), backend.version());
        if (!Files.isRegularFile(distribution.entry()))
            throw new Missing("The frontend entry is missing: " + distribution.entry());
        return distribution;
    }

    /** @return JAR（或开发 class 目录）所在的实际安装根目录。 */
    static Path installationDirectory() throws Missing {
        try {
            var source = FrontendDistribution.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null)
                throw new Missing("The JadeCode installation directory cannot be determined");
            Path location = Path.of(source.getLocation().toURI());
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (URISyntaxException | RuntimeException failure) {
            throw new Missing("The JadeCode installation directory cannot be determined: " + failure.getMessage());
        }
    }

    /**
     * 找到 Node 可执行文件并核对最低版本。
     *
     * <p>允许 {@code JADE_NODE} 显式指定解释器；否则使用 PATH 中的 {@code node}。版本解析失败
     * 或低于 22.19.0 时明确失败，不在运行时联网安装。</p>
     */
    public String nodeExecutable() throws Missing {
        String candidate = System.getenv("JADE_NODE");
        if (candidate == null || candidate.isBlank()) candidate = "node";
        try {
            Process process = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new Missing("Timed out while checking the Node runtime: " + candidate);
            }
            if (process.exitValue() != 0)
                throw new Missing("Node is required for the interactive UI but `" + candidate + " --version` failed");
            if (!atLeast(output, 22, 19))
                throw new Missing("Node " + output + " is too old; the pinned Pi TUI needs Node >= 22.19.0");
            return candidate;
        } catch (IOException failure) {
            throw new Missing("Node is required for the interactive UI but was not found ("
                    + failure.getMessage() + "). Install Node >= 22.19.0; headless mode does not need it.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Missing("Interrupted while checking the Node runtime");
        }
    }

    private static boolean atLeast(String version, int major, int minor) {
        String value = version.startsWith("v") ? version.substring(1) : version;
        String[] parts = value.split("\\.");
        try {
            int actualMajor = Integer.parseInt(parts[0]);
            int actualMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return actualMajor > major || (actualMajor == major && actualMinor >= minor);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /** JAR 内 {@code /META-INF/jade-build.properties} 的只读快照。 */
    record BuildInfo(String version, String buildId, int protocolVersion, boolean paired) {
        static BuildInfo load() {
            try (InputStream stream = FrontendDistribution.class.getResourceAsStream(RESOURCE)) {
                if (stream == null) return new BuildInfo("0.1.0-SNAPSHOT", "unpaired", 1, false);
                Properties properties = new Properties();
                properties.load(stream);
                return new BuildInfo(properties.getProperty("version", "0.1.0-SNAPSHOT"),
                        properties.getProperty("buildId", "unpaired"),
                        Integer.parseInt(properties.getProperty("protocolVersion", "1")),
                        Boolean.parseBoolean(properties.getProperty("paired", "false")));
            } catch (IOException | NumberFormatException invalid) {
                return new BuildInfo("0.1.0-SNAPSHOT", "unpaired", 1, false);
            }
        }
    }

    /**
     * 过滤转发给 Node 的环境变量。
     *
     * <p>只传终端/locale/HOME/PATH/编辑器/剪贴板等必要变量，绝不批量转发模型与 MCP 认证
     * 环境（例如 API key）。这不是同用户进程之间的操作系统级沙箱：Node 仍能读 HOME；它的
     * 目的是避免把认证资料“顺手”交给一个只需要画界面的进程。</p>
     */
    static List<String> forwardedEnvironment(Map<String, String> parent) {
        List<String> names = new ArrayList<>();
        List<String> exact = List.of("HOME", "PATH", "SHELL", "USER", "LOGNAME", "TMPDIR", "TERM", "COLORTERM",
                "DISPLAY", "WAYLAND_DISPLAY", "EDITOR", "VISUAL", "PAGER", "TZ", "SystemRoot", "SYSTEMROOT",
                "COMSPEC", "PATHEXT", "WINDIR", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP",
                "JADE_NODE", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS", "VSCODE_INJECTION");
        for (String name : exact) if (parent.containsKey(name)) names.add(name);
        for (String name : parent.keySet()) {
            if (name.startsWith("LC_") || name.startsWith("TERM") || name.startsWith("SSH_")
                    || name.startsWith("TMUX") || name.startsWith("XDG_") || name.startsWith("WT_")
                    || name.startsWith("WSL") || name.startsWith("LANG")) {
                if (!names.contains(name)) names.add(name);
            }
        }
        return names;
    }
}
