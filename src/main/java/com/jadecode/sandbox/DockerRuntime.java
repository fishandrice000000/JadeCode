package com.jadecode.sandbox;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * 管理工具执行需要的 Docker 容器及其生命周期。
 *
 * <p>按 Agent、轮次、挂载、读写和网络策略复用实例；acquire 为排队和执行领取引用，release 归还引用并更新时间。
 * 首次初始化后启动闲置扫描；引用归零的实例按数量和时长淘汰。故障或主动关闭先退休实例，
 * 确认删除后释放可写范围占用，删除失败的登记留给后续重试。</p>
 *
 * @see ExecutionPlan
 * @see SandboxWorker
 */
public final class DockerRuntime implements AutoCloseable {
    /**
     * 受管工作区的执行屏障；租约覆盖实际后台执行，不只覆盖提交工具的那一刻。
     */
    public interface Operations {
        /**
         * 按本次执行计划领取工作区执行租约，返回供调用结束时关闭的句柄；协调失败以 IOException 报告。
         *
         * @param plan 已经固定操作、身份、权限版本和挂载策略的执行计划
         * @return 执行期间持有、完成后关闭的工作区租约
         * @throws IOException 工作区执行屏障无法领取
         */
        AutoCloseable enter(ExecutionPlan plan) throws IOException;

        /**
         * 在工作区执行租约内记录本次计划的实际结果，供环境和工作区服务更新操作状态；记录失败以 IOException 报告。
         *
         * @param plan 已经固定操作、身份、权限版本和挂载策略的执行计划
         * @param outcome 本次容器实际执行结果，供工作区协调器记录完成状态
         * @throws IOException 执行结果无法记录到工作区操作状态
         */
        void completed(ExecutionPlan plan, Outcome outcome) throws IOException;
    }

    private Operations operations;

    /**
     * 首次执行前绑定共享工作区执行协调器，供 execute 在调用期间持有工作区租约并记录结果；
     * 已关闭或已有容器时抛出 IllegalStateException。
     *
     * @param value 首次执行前绑定的共享工作区协调器
     * @throws IllegalStateException 运行时已关闭或已有容器，协调入口已固定
     */
    public synchronized void operations(Operations value) {
        if (closed || !instances.isEmpty())
            throw new IllegalStateException("Execution coordination must be bound before use");
        operations = value;
    }

    /**
     * 返回已经解析的镜像 ID，尚未初始化时为 null，供状态显示使用。
     *
     * @return 已经固定的镜像 ID；尚未初始化时为 null
     */
    public synchronized String resolvedImage() {
        return image;
    }

    /**
     * 初始化 Docker 与本地镜像后返回固定镜像 ID，供环境准备比较保留的 Python 环境所用工具链。
     * 初始化失败抛出 IOException。
     *
     * @return 本地镜像检查通过后固定的镜像 ID
     * @throws IOException 运行时初始化失败
     */
    public synchronized String imageIdentity() throws IOException {
        initialize();
        return image;
    }

    /**
     * 将容器 Worker 的执行结果带回宿主，包含展示文本、错误标记、可选退出码、截断状态及原始输出。
     *
     * @param content 给模型与界面读取的完整工具结果文本
     * @param error 本次执行或条目是否失败
     * @param exitCode 实际进程退出码；null 表示未取得正常退出状态
     * @param truncated 返回的输出是否因保留预算而截断
     * @param rawOutput 命令的原始有界输出，供 Shell 结果直接采用
     */
    public record Outcome(String content, boolean error, Integer exitCode, boolean truncated, String rawOutput) {
        /**
         * 启动失败没有程序原始输出，保留同一条失败说明。
         *
         * @param content 给模型与界面读取的完整工具结果文本
         * @param error 本次执行或条目是否失败
         * @param exitCode 实际进程退出码；null 表示未取得正常退出状态
         * @param truncated 返回的输出是否因保留预算而截断
         */
        public Outcome(String content, boolean error, Integer exitCode, boolean truncated) {
            this(content, error, exitCode, truncated, content);
        }
    }

    /**
     * 为容器的可写挂载领取宿主路径占用的服务接口，返回的句柄由容器删除确认流程释放。
     */
    @FunctionalInterface
    public interface WriteScopes {
        /**
         * 为容器将要写入的宿主路径领取范围占用，返回删除容器后释放的句柄；范围冲突或领取失败抛出 IOException。
         *
         * @param paths 本次计划领取或核对的宿主路径列表
         * @return 容器删除确认后释放可写范围的句柄
         * @throws IOException 可写范围与其他会话冲突或目标路径无法解析
         */
        AutoCloseable acquire(List<Path> paths) throws IOException;
    }

    private final WriteScopes writeScopes;
    private final SandboxSettings settings;
    private final Path cache;
    private final Consumer<String> diagnostics;
    private final DockerCli cli = new DockerCli();
    private final Map<String, Instance> instances = new LinkedHashMap<>();
    private final Set<String> ended = new HashSet<>();
    /** 已交给后台队列但尚未 acquire 的确切对象；身份集合防止等值重建后重复领取。 */
    private final Set<ExecutionPlan> acceptedBackground = Collections.newSetFromMap(new IdentityHashMap<>());
    private final String session = UUID.randomUUID().toString();
    private final String owner = ProcessHandle.current().pid() + "@"
            + ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
    private final ScheduledExecutorService sweeper;
    private String image;
    private String uid;
    private Path masks;
    private volatile boolean closed;

    /**
     * 绑定沙箱设置、工作区专属缓存和诊断出口，使用本运行时自身的范围管理；创建回收调度器，首次 initialize 后安排扫描。
     *
     * @param settings 本次容器的镜像、资源上限与闲置回收设置
     * @param cache 此工作区专用的容器工具链缓存目录
     * @param diagnostics 接收容器、连接或会话诊断信息的出口
     */
    public DockerRuntime(SandboxSettings settings, Path cache, Consumer<String> diagnostics) {
        this(settings, cache, diagnostics, null);
    }

    /**
     * 绑定沙箱设置、工作区专属缓存、诊断出口和服务注入的可写范围领取器，并创建回收调度器。容器使用 scopes 领取范围，
     * 删除确认后归还。
     *
     * @param settings 本次容器的镜像、资源上限与闲置回收设置
     * @param cache 此工作区专用的容器工具链缓存目录
     * @param diagnostics 接收容器、连接或会话诊断信息的出口
     * @param scopes 为容器可写挂载领取范围占用的服务接口
     */
    public DockerRuntime(SandboxSettings settings, Path cache, Consumer<String> diagnostics, WriteScopes scopes) {
        this.writeScopes = scopes;
        this.settings = settings;
        this.cache = cache.toAbsolutePath().normalize();
        this.diagnostics = diagnostics;
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jade-sandbox-reaper");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 根据执行计划领取一个容器，并建立本次调用的引用。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>消费该计划已接纳的后台资格，检查运行时与轮次状态，初始化 Docker 并扫描实际挂载保护点。</li>
     * <li>用 Agent、归属轮次、读写、网络、业务挂载、保护点和显式限制组成复用键；命中健康实例时增加引用并返回。</li>
     * <li>新建前运行闲置回收；总数达到上限时让最老无引用实例让位，仍满则报告容量不足。</li>
     * <li>领取可写范围占用，先登记确定容器名，再按固定镜像 ID、资源限制和层叠挂载创建启动。</li>
     * <li>成功后建立首个引用；创建或启动 IO 失败时按确定名称清理并向上传递。</li>
     * </ol>
     *
     * <p>调用方领取引用后才等待实例执行锁，排队者也计入 references。单工作区基线可使用空归属轮次跨任务复用。</p>
     *
     * @param plan 已经固定操作、身份、权限版本和挂载策略的执行计划
     * @return 已经领取一份引用的容器实例，调用方使用结束后 release
     * @throws IOException 运行时或轮次已结束，镜像与挂载检查失败、容量不足或容器创建启动失败
     */
    private synchronized Instance acquire(ExecutionPlan plan) throws IOException {
        boolean accepted = acceptedBackground.remove(plan);
        if (closed || ended.contains(plan.actor() + "/" + plan.runId()) && !accepted)
            throw new IOException("Sandbox execution scope has ended");
        initialize();
        List<PathProtection.Guard> guards = PathProtection.scan(plan.mounts());
        // 仅有基线工作区的实例按会话身份复用；额外挂载与一次性执行仍绑定原 run。
        String ownerRun = plan.reusable() && plan.mounts().size() == 1
                && plan.mounts().getFirst().source().equals(plan.workspace())
                        ? ""
                        : plan.runId();
        String key = plan.actor() + "/" + ownerRun + "/" + plan.readOnly() + "/" + plan.network()
                + "/" + plan.mounts() + "/" + guards + "/" + plan.restrictions();
        if (plan.reusable())
            for (var value : instances.values())
                if (value.reusable && value.key.equals(key) && !value.retired) {
                    value.references++;
                    return value;
                }
        evictIdle();
        // 容量已满时先让闲置实例让位；活跃/排队/服务持有租约，不能被容量淘汰。
        if (instances.size() >= settings.maxContainers())
            instances.values().stream()
                    .filter(value -> value.references == 0).min(Comparator.comparingLong(value -> value.lastUsed))
                    .ifPresent(this::remove);
        if (instances.size() >= settings.maxContainers())
            throw new IOException("Sandbox container limit reached; wait for active work to finish");
        String name = "jade-sbx-" + UUID.randomUUID().toString().replace("-", "");
        AutoCloseable permit = writeScopes == null ? () -> {
        }
                : writeScopes.acquire(plan.mounts().stream()
                        .filter(ExecutionPlan.Mount::writable).map(ExecutionPlan.Mount::source).toList());
        var instance = new Instance(name, key, plan.actor(), ownerRun, plan.reusable(), permit);
        instances.put(name, instance); // 先登记，create/start 超时也有可清理的确定名称。
        try {
            // 直接使用已 inspect 的 sha256 镜像 ID，create 不会按 tag 隐式寻找新版本。
            var args = new ArrayList<>(List.of("create", "--name", name,
                    "--label", "io.jadecode.sandbox=1", "--label", "io.jadecode.owner=" + owner,
                    "--label", "io.jadecode.session=" + session, "--label", "io.jadecode.uid=" + uid.split(":")[0],
                    "--user", uid,
                    "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
                    "--memory=" + settings.memoryMiB() + "m", "--memory-swap=" + settings.memoryMiB() + "m",
                    "--cpus=" + settings.cpus(), "--pids-limit=" + settings.pids(),
                    "--network=" + (plan.network() ? "bridge" : "none"),
                    "--log-driver=local", "--log-opt=max-size=1m", "--log-opt=max-file=1", "--log-opt=compress=false",
                    "--tmpfs", "/tmp:rw,nosuid,nodev,size=" + settings.tmpMiB() + "m,mode=1777",
                    "--tmpfs", "/run:rw,nosuid,nodev,size=16m,mode=755", "--workdir", plan.workspace().toString(),
                    "--env", "HOME=/home/jade", "--env", "MAVEN_CONFIG=/home/jade/.m2"));
            var finalMounts = new LinkedHashMap<Path, String>();
            finalMounts.put(Path.of("/home/jade"), mount(cache, Path.of("/home/jade"), true));
            // 不读取宿主 passwd；为实际 UID 构造容器身份，让 Java/Maven 与 HOME 使用同一私有目录。
            finalMounts.put(Path.of("/etc/passwd"), mount(masks.resolve("passwd"), Path.of("/etc/passwd"), false));
            finalMounts.put(Path.of("/etc/group"), mount(masks.resolve("group"), Path.of("/etc/group"), false));
            for (var root : plan.mounts())
                finalMounts.put(root.target(), mount(root.source(), root.target(), root.writable()));
            // 最后叠保护挂载，避免更宽的工作区挂载重新暴露敏感子路径。
            for (var guard : guards) {
                Path source = guard.hidden() ? masks.resolve(guard.directory() ? "directory" : "file") : guard.target();
                finalMounts.put(guard.target(), mount(source, guard.target(), false));
            }
            // 精确敏感目标只能在已获批的一次性挂载中重开；父目录授权不会重开内部保护点。
            for (var root : plan.mounts())
                if (!root.source().equals(plan.workspace()) && PathProtection.sensitive(root.source()))
                    finalMounts.put(root.target(), mount(root.source(), root.target(), root.writable()));
            // 用户显式限制最后合并，精确敏感目标的临时挂载也不能覆盖 deny。
            for (var restriction : plan.restrictions()) {
                Path target = restriction.path();
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !target.toRealPath().equals(target))
                    throw new IOException("Protected target changed before container creation");
                Path source = restriction.hidden() ? masks.resolve(Files.isDirectory(target) ? "directory" : "file")
                        : target;
                finalMounts.put(target, mount(source, target, false));
            }
            for (String value : finalMounts.values())
                args.addAll(List.of("--mount", value));
            args.add(image);
            require(cli.call(args, "", 30, () -> closed), "create");
            require(cli.call(List.of("start", name), "", 15, () -> closed), "start");
            instance.references = 1;
            return instance;
        } catch (IOException failure) {
            remove(instance);
            throw failure;
        }
    }

    /**
     * 在工作区执行协调器的租约内执行一次计划，取消时返回错误结果，否则进入容器执行流程；取得结果后通知协调器记录完成。
     * 协调或关闭租约失败转成错误 Outcome。
     *
     * @param plan 已经固定操作、身份、权限版本和挂载策略的执行计划
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     * @param cancelled 执行过程中读取的取消检查函数
     * @return 包含正文、错误、退出码与原始输出的执行结果
     */
    public Outcome execute(ExecutionPlan plan, long timeoutSeconds, BooleanSupplier cancelled) {
        try (var lease = operations == null ? (AutoCloseable) () -> {
        } : operations.enter(plan)) {
            Outcome outcome = cancelled.getAsBoolean()
                    ? new Outcome("Error: Execution cancelled before acquiring workspace", true, null, false)
                    : executeIsolated(plan, timeoutSeconds, cancelled);
            if (operations != null)
                operations.completed(plan, outcome);
            return outcome;
        } catch (Exception failure) {
            return new Outcome("Error: Workspace execution coordination failed: " + failure.getMessage(), true, null,
                    false);
        }
    }

    /**
     * 在一个已授权容器内执行本次操作，并把 Worker 结果转换为宿主 Outcome。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>领取实例引用，限时等待该容器的串行执行锁，检查退休与取消状态。</li>
     * <li>把工作目录、工具、参数和超时编码为 JSON，用 docker exec 启动 SandboxWorker 并写入标准输入。</li>
     * <li>Worker 异常退出时读取 cgroup OOM 计数；正常退出时校验 protocol、content、isError 和 clean 等结构。</li>
     * <li>清理未完成或 OOM 次数增长时退休实例，解析退出码、截断和原始输出。</li>
     * <li>异常转换为失败结果；finally 归还引用，已经取得的执行锁在内部 finally 释放。</li>
     * </ol>
     *
     * @param plan 已经固定操作、身份、权限版本和挂载策略的执行计划
     * @param timeoutSeconds 每次命令或容器操作的正数等待上限，单位秒
     * @param cancelled 执行过程中读取的取消检查函数
     * @return Worker 正常结果或异常转成的错误结果
     */
    private Outcome executeIsolated(ExecutionPlan plan, long timeoutSeconds, BooleanSupplier cancelled) {
        Instance instance = null;
        boolean entered = false;
        try {
            instance = acquire(plan);
            if (!instance.lock.tryLock(timeoutSeconds, TimeUnit.SECONDS))
                throw new IOException("Timed out waiting for sandbox execution slot");
            try {
                entered = true;
                if (instance.retired || cancelled.getAsBoolean())
                    throw new IOException("Sandbox execution cancelled before start");
                ObjectNode request = Json.mapper().createObjectNode().put("cwd", plan.workspace().toString())
                        .put("tool", plan.tool()).put("timeout", timeoutSeconds);
                request.set("input", plan.input());
                var reply = cli.call(
                        List.of("exec", "--user", uid, "--workdir", plan.workspace().toString(), "-i", instance.name,
                                "java", "-XX:+DisableAttachMechanism", "-Xmx256m", "-cp", "/opt/jadecode/worker.jar",
                                SandboxWorker.class.getName()),
                        request.toString(), timeoutSeconds + 5, cancelled);
                if (reply.exitCode() != 0) {
                    // 执行端本身被杀时没有 JSON；只有本次计数增长才确定报告内核 OOM。
                    var events = cli.call(List.of("exec", instance.name, "cat", "/sys/fs/cgroup/memory.events"), "", 3,
                            () -> false);
                    long oom = oomKills(events.output());
                    if (oom > instance.oomCount || reply.output().contains("OutOfMemoryError"))
                        throw new IOException("Sandbox OOM: worker could not return a result");
                    throw new IOException("Sandbox worker exited without a result (status " + reply.exitCode() + ")");
                }
                var json = Json.mapper().readTree(reply.output());
                if (json == null || json.path("protocol").asInt() != 1 || !json.path("content").isTextual()
                        || !json.path("isError").isBoolean() || !json.path("clean").isBoolean())
                    throw new IOException("Sandbox worker returned an invalid result");
                long finalOom = json.path("oomCount").asLong(instance.oomCount);
                if (!json.path("clean").asBoolean() || finalOom > instance.oomCount)
                    retire(instance);
                instance.oomCount = finalOom;
                return new Outcome(json.path("content").asText(), json.path("isError").asBoolean(),
                        json.has("exitCode") ? json.path("exitCode").intValue() : null,
                        json.path("truncated").asBoolean(),
                        json.has("rawOutput") ? json.path("rawOutput").asText() : json.path("content").asText());
            } finally {
                instance.lock.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (instance != null && entered)
                retire(instance);
            return new Outcome("Error: Sandbox execution interrupted; operation may have side effects", true, null,
                    false);
        } catch (Exception failure) {
            if (instance != null && entered)
                retire(instance);
            return new Outcome(
                    "Error: " + failure.getMessage() + ". No host fallback or automatic replay was performed.", true,
                    null, false);
        } finally {
            if (instance != null)
                release(instance);
        }
    }

    /**
     * 在向模型返回后台启动回执前，按计划对象登记一次已接纳资格。实际 acquire 时消费该资格，
     * 使已结束的父轮次仍可兑现已接纳命令；会话关闭或非后台 bash 计划抛出 IOException。
     *
     * @param plan 已经固定操作、身份、权限版本和挂载策略的执行计划
     * @throws IOException 运行时或轮次已结束，或计划不是已批准的后台 bash
     */
    public synchronized void acceptBackground(ExecutionPlan plan) throws IOException {
        if (closed || ended.contains(plan.actor() + "/" + plan.runId()))
            throw new IOException("Sandbox task has already ended");
        if (!plan.tool().equals("bash") || !plan.input().path("run_in_background").asBoolean())
            throw new IOException("Only an approved background command may reserve its execution");
        acceptedBackground.add(plan);
    }

    /**
     * 移除尚未由 acquire 消费的后台计划资格，供后台入队或启动失败时撤销接纳记录。
     *
     * @param plan 已经固定操作、身份、权限版本和挂载策略的执行计划
     */
    public synchronized void discardBackground(ExecutionPlan plan) {
        acceptedBackground.remove(plan);
    }

    /**
     * 首次使用时核对本地 Docker、镜像协议和非 root UID/GID，准备私有缓存、遮蔽路径及容器身份文件。
     * 恢复已失去所有者的容器，固定镜像 ID，再登记首次延迟三十秒、之后每次结束再延迟三十秒的回收任务。
     * 初始化失败抛出 IOException。
     *
     * @throws IOException 本地 Docker、镜像协议、UID、缓存目录或孤儿恢复检查失败
     */
    private void initialize() throws IOException {
        if (image != null)
            return;
        cli.initialize();
        var inspected = cli.call(
                List.of("image", "inspect", "--format",
                        "{{.Id}} {{index .Config.Labels \"io.jadecode.worker-protocol\"}}", settings.image()),
                "", 15, () -> false);
        if (inspected.exitCode() != 0 || !inspected.output().strip().matches("sha256:[0-9a-f]{64} 1"))
            throw new IOException("Sandbox image is unavailable; run mvn -q package and sh scripts/build-sandbox.sh");
        // 镜像 ABI 与业务协议一同固定，不在模型任务中安装或修补执行器。
        String inspectedImage = inspected.output().strip().split(" ")[0];
        uid = localId("-u") + ":" + localId("-g");
        if (uid.startsWith("0:"))
            throw new IOException("Sandbox requires a non-root host UID for workspace ownership");
        // 缓存由模型写入，只检查根目录和现有 .m2 的布局，不向其内部写配置。
        // toolchains 固化于只读镜像，避免容器并发替换缓存链接时诱导宿主写出数据根。
        var cachePaths = new com.jadecode.config.JadePaths(cache);
        cachePaths.directory(cache, true);
        cachePaths.directory(cache.resolve(".m2"), false);
        if (masks == null) {
            masks = Files.createTempDirectory("jade-sandbox-masks-");
            Files.createDirectory(masks.resolve("directory"));
            Files.createFile(masks.resolve("file"));
            Files.setPosixFilePermissions(masks.resolve("directory"), PosixFilePermissions.fromString("---------"));
            Files.setPosixFilePermissions(masks.resolve("file"), PosixFilePermissions.fromString("---------"));
            String[] ids = uid.split(":");
            Files.writeString(masks.resolve("passwd"), "root:x:0:0:root:/root:/bin/sh\njade:x:" + ids[0] + ":" + ids[1]
                    + ":Jade sandbox:/home/jade:/bin/sh\nnobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n");
            Files.writeString(masks.resolve("group"), "root:x:0:\njade:x:" + ids[1] + ":\nnogroup:x:65534:\n");
            Files.setPosixFilePermissions(masks.resolve("passwd"), PosixFilePermissions.fromString("r--r--r--"));
            Files.setPosixFilePermissions(masks.resolve("group"), PosixFilePermissions.fromString("r--r--r--"));
        }
        recoverOrphans();
        image = inspectedImage;
        sweeper.scheduleWithFixedDelay(this::reapQuietly, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 调用系统 id 获取指定的 UID 或 GID，最多等待三秒，校验成功的数字输出后返回。
     * 超时、非法输出或中断抛出 IOException，最终结束该查询进程。
     *
     * @param flag 传给系统 id 的选项，-u 读取 UID，-g 读取 GID
     * @return 系统 id 返回的十进制 UID 或 GID
     * @throws IOException 系统 id 超时、输出不是数字、退出失败或线程中断
     */
    private static String localId(String flag) throws IOException {
        Process process = new ProcessBuilder("/usr/bin/id", flag).start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS))
                throw new IOException("Cannot determine sandbox UID/GID");
            String id = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII)
                    .strip();
            if (process.exitValue() != 0 || !id.matches("[0-9]+"))
                throw new IOException("Invalid sandbox UID/GID");
            return id;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("UID/GID lookup interrupted", failure);
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * 把来源、目标和可写标记编码为 Docker bind mount 参数，设置私有传播及递归挂载策略；
     * 路径含逗号或换行时抛出 IOException。
     *
     * @param source 容器挂载对应的宿主绝对路径
     * @param target 容器内的目标绝对路径
     * @param writable 该挂载是否允许写入宿主来源
     * @return 编码完成的 Docker --mount 参数值
     * @throws IOException 宿主或容器路径包含逗号或换行
     */
    private static String mount(Path source, Path target, boolean writable) throws IOException {
        if (source.toString().contains(",") || target.toString().contains(",") || source.toString().contains("\n")
                || target.toString().contains("\n"))
            throw new IOException("Sandbox mount paths containing comma/newline are unsupported");
        return "type=bind,src=" + source + ",dst=" + target + (writable
                ? ",bind-propagation=rprivate,bind-recursive=disabled"
                : ",readonly,bind-propagation=rprivate,bind-recursive=readonly");
    }

    /**
     * 检查 Docker 命令退出码；非零时把操作名称和输出组成 IOException，交给当前创建、启动或维护流程处理。
     *
     * @param reply Docker CLI 返回的退出码和有界合并输出
     * @param operation 本次 Docker 操作名，供失败说明定位创建、启动或维护步骤
     * @throws IOException Docker 命令返回非零退出码
     */
    private static void require(DockerCli.Reply reply, String operation) throws IOException {
        if (reply.exitCode() != 0)
            throw new IOException("Docker " + operation + " failed: " + reply.output().strip());
    }

    /**
     * 在调用方持有工作区独占屏障且确认所属执行已停止后，清理私有缓存中的 Docker 空挂载占位。扫描 root 所属子目录，
     * 用固定镜像的 UID 0 断网维护容器运行 find，只删除空目录和 root 零字节文件；最后按确定名称删除维护容器。
     * 目录、镜像、执行或清理异常以 IOException 返回。
     *
     * @param cache 此工作区专用的容器工具链缓存目录
     * @param settings 本次容器的镜像、资源上限与闲置回收设置
     * @throws IOException 缓存来源、镜像、清理命令或维护容器回收未确认
     */
    public static void cleanEmptyCacheMountpoints(Path cache, SandboxSettings settings) throws IOException {
        if (Files.notExists(cache, LinkOption.NOFOLLOW_LINKS))
            return;
        if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS)
                || !cache.toRealPath().equals(cache.toAbsolutePath().normalize()))
            throw new IOException("Invalid private cache directory");
        var roots = new ArrayList<Path>();
        try (var children = Files.list(cache)) {
            for (Path child : children.toList()) {
                // 只处理 Docker 创建的 root 目录；普通缓存以及指向其它位置的链接留给非特权删除。
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                        || ((Number) Files.getAttribute(child, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != 0)
                    continue;
                try (var entries = Files.list(child)) {
                    if (entries.findAny().isPresent())
                        roots.add(child);
                }
            }
        }
        if (roots.isEmpty())
            return; // 没有遗留占位时无需连接 Docker，未使用工作区也能直接删除。
        var cli = new DockerCli();
        cli.initialize();
        var inspected = cli.call(
                List.of("image", "inspect", "--format",
                        "{{.Id}} {{index .Config.Labels \"io.jadecode.worker-protocol\"}}", settings.image()),
                "", 15, () -> false);
        if (inspected.exitCode() != 0 || !inspected.output().strip().matches("sha256:[0-9a-f]{64} 1"))
            throw new IOException("Cache mountpoint cleanup requires an existing sandbox image");
        String image = inspected.output().strip().split(" ")[0];
        for (Path child : roots) {
            if (!child.toRealPath().equals(child) || !child.getParent().equals(cache))
                throw new IOException("Cache mountpoint changed before cleanup");
            String name = "jade-cache-clean-" + UUID.randomUUID().toString().replace("-", "");
            try {
                // find 默认不跟随链接；固定条件只删除空占位，不执行缓存中的任何脚本或程序。
                require(cli.call(
                        List.of("run", "--rm", "--name", name, "--label", "io.jadecode.maintenance=cache-mountpoints",
                                "--network=none", "--read-only", "--user", "0:0", "--cap-drop=ALL",
                                "--security-opt=no-new-privileges:true",
                                "--memory=64m", "--memory-swap=64m", "--cpus=1", "--pids-limit=32",
                                "--mount", mount(child, Path.of("/cache-mountpoint"), true), "--entrypoint",
                                "/usr/bin/find", image,
                                "/cache-mountpoint", "-mindepth", "1", "-depth", "(", "-type", "d", "-empty", "-o",
                                "-type", "f", "-uid", "0", "-size", "0c",
                                ")", "-delete"),
                        "", 30, () -> false), "empty cache mountpoint cleanup");
            } finally {
                // 客户端超时不等于容器结束；按本次确切名称回收，不能遗留一个维护实例。
                var removed = cli.call(List.of("rm", "--force", name), "", 15, () -> false);
                if (removed.exitCode() != 0 && !removed.output().contains("No such container"))
                    throw new IOException("Cache maintenance container cleanup unconfirmed: " + name);
            }
        }
    }

    /**
     * 从 cgroup memory.events 文本读取 oom_kill 计数，字段缺失或数值格式错误时返回 -1，
     * 供 Worker 异常退出后判断本次 OOM。
     *
     * @param events 从 cgroup memory.events 读取的文本
     * @return cgroup 的 oom_kill 次数；缺失或无法解析时为 -1
     */
    private static long oomKills(String events) {
        for (String line : events.split("\n"))
            if (line.startsWith("oom_kill "))
                try {
                    return Long.parseLong(line.substring(9).strip());
                } catch (NumberFormatException unknown) {
                    return -1;
                }
        return -1;
    }

    /**
     * 释放一次容器引用，并用单调时间更新最后使用时刻。一次性、已退休或关闭中的实例交给 remove 删除；
     * 可复用实例进入闲置淘汰检查。调用方在执行或服务使用结束后调用。
     *
     * @param instance 本运行时登记的容器实例，包含引用数、退休状态和可写范围句柄
     */
    private synchronized void release(Instance instance) {
        instance.references--;
        instance.lastUsed = System.nanoTime();
        if (!instance.reusable || instance.retired || closed)
            remove(instance);
        else
            evictIdle();
    }

    /**
     * 把实例标为退休，立即调用 remove 终止并删除容器，供执行异常、OOM 或后代清理失败时收尾。
     * 删除未确认的实例由登记表保留。
     *
     * @param instance 本运行时登记的容器实例，包含引用数、退休状态和可写范围句柄
     */
    private synchronized void retire(Instance instance) {
        instance.retired = true;
        remove(instance);
    }

    /**
     * 登记 actor/run 已结束，将该身份及子身份中绑定此轮次的实例标为退休，无引用时删除。
     * 已接纳后台资格由 acquire 单独消费；跨轮次基线实例的 run 为空，继续参与复用。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param run 任务轮次 ID；基线实例归属使用空串
     */
    public synchronized void endTask(String actor, String run) {
        ended.add(actor + "/" + run);
        for (var instance : List.copyOf(instances.values()))
            if ((instance.actor.equals(actor) || instance.actor.startsWith(actor + "/")) && instance.run.equals(run)) {
                ended.add(instance.actor + "/" + run);
                instance.retired = true;
                if (instance.references == 0)
                    remove(instance);
            }
    }

    /**
     * 撤销指定 Agent 及子身份尚未消费的后台资格，并退休删除其全部实例，供会话或队友关闭时主动终止执行。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     */
    public synchronized void endActor(String actor) {
        acceptedBackground.removeIf(plan -> plan.actor().equals(actor) || plan.actor().startsWith(actor + "/"));
        for (var instance : List.copyOf(instances.values()))
            if (instance.actor.equals(actor) || instance.actor.startsWith(actor + "/")) {
                instance.retired = true;
                remove(instance);
            }
    }

    /**
     * 按引用、数量和最后释放时间回收当前运行时的闲置容器。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>先重试 references 为零的退休登记，使先前删除失败的容器重新获得清理机会。</li>
     * <li>选择无引用且尚未退休的实例，按 lastUsed 从旧到新排列。</li>
     * <li>剩余候选数超过 maxIdle，或当前实例闲置达到 idleSeconds 时调用 remove；其他实例继续供复用。</li>
     * </ol>
     *
     * <p>此方法与领取、释放共用运行时监视器。主动取消和关闭走各自入口，空闲淘汰只选 references 为零的实例。</p>
     */
    private synchronized void evictIdle() {
        for (var failed : List.copyOf(instances.values()))
            if (failed.retired && failed.references == 0)
                remove(failed);
        var idle = instances.values().stream().filter(i -> i.references == 0 && !i.retired)
                .sorted(Comparator.comparingLong(i -> i.lastUsed)).toList();
        long now = System.nanoTime();
        for (int n = 0; n < idle.size(); n++)
            if (idle.size() - n > settings.maxIdle()
                    || now - idle.get(n).lastUsed >= TimeUnit.SECONDS.toNanos(settings.idleSeconds()))
                remove(idle.get(n));
    }

    /**
     * 执行一次定时闲置扫描；扫描发生运行时异常时发出诊断，让后续固定延迟的扫描继续获得执行机会。
     */
    private void reapQuietly() {
        try {
            evictIdle();
        } catch (RuntimeException failure) {
            diagnostics.accept("[Sandbox] idle cleanup failed");
        }
    }

    /**
     * 直接运行一次闲置淘汰，供同包验证驱动与周期任务相同的回收逻辑。
     */
    void reapIdle() {
        evictIdle();
    }

    /**
     * 先标记实例退休，再调用 docker rm --force。退出码为零或容器已不存在时，释放可写范围并移除登记；
     * 删除或范围释放未确认时发送诊断并保留登记，供后续扫描重试。
     *
     * @param instance 本运行时登记的容器实例，包含引用数、退休状态和可写范围句柄
     */
    private synchronized void remove(Instance instance) {
        instance.retired = true;
        try {
            var reply = cli.call(List.of("rm", "--force", instance.name), "", 10, () -> false);
            if (reply.exitCode() == 0 || reply.output().contains("No such container")) {
                try {
                    instance.writePermit.close();
                } catch (Exception failure) {
                    throw new IOException("Writable scope release failed", failure);
                }
                instances.remove(instance.name);
            } else
                diagnostics.accept("[Sandbox] cleanup unconfirmed for " + instance.name);
        } catch (IOException failure) {
            diagnostics.accept("[Sandbox] cleanup unconfirmed for " + instance.name);
        }
    }

    /**
     * 查询带 JadeCode 标签且 UID 匹配的容器，解析所有者 PID 和启动时间。原进程已退出或 PID 被复用时删除容器；
     * 匹配的存活进程及启动时间未知的存活进程继续保留。查询失败抛出 IOException，删除未确认发送诊断。
     *
     * @throws IOException 无法查询本用户 JadeCode 容器的归属资料
     */
    private void recoverOrphans() throws IOException {
        var result = cli.call(List.of("ps", "-a", "--filter", "label=io.jadecode.sandbox=1", "--filter",
                "label=io.jadecode.uid=" + uid.split(":")[0], "--format", "{{.ID}} {{.Label \"io.jadecode.owner\"}}"),
                "", 10, () -> false);
        if (result.exitCode() != 0)
            throw new IOException("Cannot inspect sandbox orphan ownership");
        for (String line : result.output().split("\n")) {
            String[] value = line.strip().split(" ");
            if (value.length != 2 || !value[0].matches("[0-9a-f]+") || !value[1].matches("[0-9]+@[0-9]+"))
                continue;
            String[] identity = value[1].split("@");
            var process = ProcessHandle.of(Long.parseLong(identity[0]));
            // 存活但无法读取启动时间不等于孤儿；宁可报告残留，也不误杀别的会话。
            boolean live = process.filter(ProcessHandle::isAlive).map(p -> p.info().startInstant()
                    .map(time -> time.toEpochMilli() == Long.parseLong(identity[1])).orElse(true)).orElse(false);
            if (!live) {
                var removed = cli.call(List.of("rm", "--force", value[0]), "", 10, () -> false);
                if (removed.exitCode() != 0)
                    diagnostics.accept("[Sandbox] orphan cleanup unconfirmed for " + value[0]);
            }
        }
    }

    /**
     * 返回本运行时登记的容器数量，包括删除尚未确认的退休实例，供容量查询和验证使用。
     *
     * @return 当前登记的实例数，包含删除尚未确认的退休实例
     */
    public synchronized int containerCount() {
        return instances.size();
    }

    /**
     * 为可信 MCP 命令打开专用 stdio 容器，使用只读工作区、指定网络策略和空额外限制列表，返回持有容器引用的 Service。
     * 调用方启动其 ProcessBuilder，并在连接关闭时归还服务。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param name 可信配置中要启动的 MCP 服务名
     * @param workspace 会话绑定的真实工作区目录
     * @param command 可信 MCP 配置确定的程序名和参数列表
     * @param network 该次计划或服务是否需要容器桥接网络
     * @return 持有专用容器引用及 stdio 启动入口的服务句柄
     * @throws IOException 服务工作区不符合挂载规则、容器无法取得或启动参数构造失败
     */
    public Service openService(String actor, String name, Path workspace, List<String> command, boolean network)
            throws IOException {
        return openService(actor, name, workspace, command, network, List.of());
    }

    /**
     * 校验项目目录并建立 MCP 服务专用的一次性执行计划，固定只读工作区、网络和路径限制。
     * 领取容器后构造 docker exec 的 stdio 进程入口，返回 Service；构造失败时退休实例并释放引用。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param name 可信配置中要启动的 MCP 服务名
     * @param workspace 会话绑定的真实工作区目录
     * @param command 可信 MCP 配置确定的程序名和参数列表
     * @param network 该次计划或服务是否需要容器桥接网络
     * @param restrictions 需要覆盖为隐藏或只读的路径限制列表
     * @return 持有专用容器引用及 stdio 启动入口的服务句柄
     * @throws IOException 服务工作区不符合挂载规则、容器无法取得或启动参数构造失败
     */
    public Service openService(String actor, String name, Path workspace, List<String> command, boolean network,
            List<ExecutionPlan.Restriction> restrictions) throws IOException {
        workspace = workspace.toAbsolutePath().normalize();
        if (workspace.getNameCount() < 2 || workspace.equals(Path.of(System.getProperty("user.home")))
                || PathProtection.sensitive(workspace) || cache.startsWith(workspace))
            throw new IOException("MCP service requires a narrow project directory outside Harness data");
        var input = Json.mapper().createObjectNode().put("service", name);
        var plan = new ExecutionPlan(actor + "/mcp/" + name, "service_" + UUID.randomUUID(), 0, "service", input,
                workspace, true, network, false, List.of(new ExecutionPlan.Mount(workspace, workspace, false)),
                restrictions);
        Instance instance = acquire(plan);
        try {
            var args = new ArrayList<>(
                    List.of("exec", "-i", "--user", uid, "--workdir", workspace.toString(), instance.name));
            args.addAll(command);
            return new Service(instance, new ProcessBuilder(cli.command(args)));
        } catch (IOException failed) {
            retire(instance);
            release(instance);
            throw failed;
        }
    }

    /**
     * 持有 MCP stdio 服务专用容器的引用和启动入口，从打开到关闭期间保持引用，关闭时退休容器并归还引用。
     */
    public final class Service implements AutoCloseable {
        private final Instance instance;
        private final ProcessBuilder builder;
        private boolean released;

        /**
         * 绑定服务独占的容器引用与 docker exec 进程构造器，由 MCP 连接负责启动进程，并在关闭时归还引用。
         *
         * @param instance 本运行时登记的容器实例，包含引用数、退休状态和可写范围句柄
         * @param builder 已准备好 docker exec 参数的服务进程构造器
         */
        private Service(Instance instance, ProcessBuilder builder) {
            this.instance = instance;
            this.builder = builder;
        }

        /**
         * 返回启动本服务容器内 stdio 命令的 ProcessBuilder，供 MCP 客户端建立进程通道。
         *
         * @return 用于启动容器内 MCP stdio 命令的进程构造器
         */
        public ProcessBuilder builder() {
            return builder;
        }

        /**
         * 首次关闭时将服务容器退休并删除，再释放服务持有的引用；released 标记使重复关闭只释放一次。
         */
        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                retire(instance);
                release(instance);
            }
        }
    }

    /**
     * 标记运行时关闭，撤销后台资格并停止回收调度，逐个删除登记容器。全部删除后清理临时遮蔽文件；
     * 仍有未确认实例时抛出 IllegalStateException，并保留其可写范围占用。
     *
     * @throws IllegalStateException 仍有未确认删除的容器，可写范围占用继续保留
     */
    @Override
    public synchronized void close() {
        if (closed)
            return;
        closed = true;
        acceptedBackground.clear();
        sweeper.shutdownNow();
        for (var instance : List.copyOf(instances.values()))
            remove(instance);
        if (masks != null && instances.isEmpty())
            try {
                Files.setPosixFilePermissions(masks.resolve("directory"), PosixFilePermissions.fromString("rwx------"));
                Files.deleteIfExists(masks.resolve("directory"));
                Files.deleteIfExists(masks.resolve("file"));
                Files.deleteIfExists(masks.resolve("passwd"));
                Files.deleteIfExists(masks.resolve("group"));
                Files.deleteIfExists(masks);
            } catch (IOException failure) {
                diagnostics.accept("[Sandbox] temporary mask cleanup unconfirmed: " + masks);
            }
        if (!instances.isEmpty())
            throw new IllegalStateException("Sandbox cleanup is incomplete; writable scopes remain held");
    }

    /**
     * 返回运行时已关闭且容器登记为空的状态，供服务确认清理完成后释放工作区。
     *
     * @return 运行时已关闭且容器登记为空时为 true
     */
    public synchronized boolean cleanupConfirmed() {
        return closed && instances.isEmpty();
    }

    /**
     * 登记一个容器的归属、复用策略、串行执行锁、引用数和最近释放时间，同时持有可写范围占用；
     * 退休实例等待删除确认后才从登记表移除。
     */
    private static final class Instance {
        final String name, key, actor, run;
        final boolean reusable;
        final ReentrantLock lock = new ReentrantLock();
        final AutoCloseable writePermit;
        int references;
        long lastUsed = System.nanoTime();
        long oomCount;
        volatile boolean retired;

        /**
         * 登记确定的容器名、复用键、Agent 和轮次归属，并接管已经领取的可写范围占用。
         * 创建启动成功后由 acquire 建立首个引用。
         *
         * @param name 预先生成的确定容器名，用于启动失败后仍能按名清理
         * @param key 完整复用策略键，包含归属、挂载、保护点和网络策略
         * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
         * @param run 实例归属轮次；跨轮次基线实例使用空串
         * @param reusable 该实例是否可以按相同策略复用
         * @param permit 容器创建前取得的可写范围占用句柄
         */
        Instance(String name, String key, String actor, String run, boolean reusable, AutoCloseable permit) {
            this.writePermit = permit;
            this.name = name;
            this.key = key;
            this.actor = actor;
            this.run = run;
            this.reusable = reusable;
        }
    }
}
