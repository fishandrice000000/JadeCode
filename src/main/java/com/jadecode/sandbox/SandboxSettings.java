package com.jadecode.sandbox;

/**
 * 一次 CLI 启动固定的 Docker 配置，不含凭据，也不接受模型覆盖。
 * 镜像必须由用户或安装脚本准备，执行时只使用已存在镜像，不隐式联网下载。
 * 内存/CPU/PID 是容器上限；idleSeconds 与 maxIdle 只影响没有执行引用的实例。
 */
public record SandboxSettings(boolean enabled, String image, int memoryMiB, int cpus, int pids,
        int tmpMiB, int idleSeconds, int maxIdle, int maxContainers) {
    /** 检查配置边界，错误不回显任意模型输入或认证信息。 */
    public SandboxSettings {
        if (image == null || image.isBlank() || image.startsWith("-") || image.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("sandbox.image must be an image name or digest");
        if (memoryMiB < 128 || cpus < 1 || pids < 32 || tmpMiB < 16 || idleSeconds < 1
                || maxIdle < 0 || maxContainers < 1 || maxIdle > maxContainers)
            throw new IllegalArgumentException("Invalid sandbox resource or lifecycle limit");
    }
    /** 正式配置缺省启用，首次使用前需构建镜像；不会因此启动 Docker。 */
    public static SandboxSettings defaults() {
        return new SandboxSettings(true, "jadecode-sandbox:1", 2048, 2, 256, 256, 600, 2, 8);
    }
    /** 显式离线装配保留原测试契约，不偷偷访问本机 Docker。 */
    public static SandboxSettings offline() {
        var d = defaults();
        return new SandboxSettings(false, d.image, d.memoryMiB, d.cpus, d.pids, d.tmpMiB, d.idleSeconds, d.maxIdle, d.maxContainers);
    }
}
