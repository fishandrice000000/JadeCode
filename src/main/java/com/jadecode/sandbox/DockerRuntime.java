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
 * 一份 CLI 的本地 Docker 资源所有者。镜像/缓存与容器分开，构造不启动 Docker。
 * 实例按完整策略和 actor/run 复用；租约计数保护排队及后台操作，不根据 CPU 判断空闲。
 * 任何不完整结果都销毁对应实例，不重放；清理失败保留登记并报告，不能假称释放。
 */
public final class DockerRuntime implements AutoCloseable {
    /** 受管工作区的执行屏障；租约覆盖实际后台执行，不只覆盖提交工具的那一刻。 */
    public interface Operations {
        AutoCloseable enter(ExecutionPlan plan) throws IOException;
        void completed(ExecutionPlan plan,Outcome outcome) throws IOException;
    }
    private Operations operations;
    /** 工厂在首次执行前绑定共享屏障，旧工作区保持原来的并发语义。 */
    public synchronized void operations(Operations value) {
        if (closed || !instances.isEmpty()) throw new IllegalStateException("Execution coordination must be bound before use");
        operations=value;
    }
    /** 成功执行后返回已固定的镜像摘要；未使用时为 null，不为状态查询启动 Docker。 */
    public synchronized String resolvedImage() { return image; }
    /** 环境准备先固定镜像身份，再判断保留的 .venv 是否属于同一工具链。 */
    public synchronized String imageIdentity() throws IOException { initialize(); return image; }
    public record Outcome(String content, boolean error, Integer exitCode, boolean truncated, String rawOutput) {
        /** 启动失败没有程序原始输出，保留同一条失败说明。 */
        public Outcome(String content, boolean error, Integer exitCode, boolean truncated) { this(content, error, exitCode, truncated, content); }
    }
    /** 返回的租约只在容器确认删除后释放；模型不能提供这个入口。 */
    @FunctionalInterface public interface WriteScopes { AutoCloseable acquire(List<Path> paths) throws IOException; }
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
    private final String owner = ProcessHandle.current().pid() + "@" + ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
    private final ScheduledExecutorService sweeper;
    private String image;
    private String uid;
    private Path masks;
    private volatile boolean closed;
    /** cache 必须由 WorkspacePaths 创建在本项目专属分区；绝不挂载整个宿主 HOME。 */
    public DockerRuntime(SandboxSettings settings, Path cache, Consumer<String> diagnostics) {
        this(settings, cache, diagnostics, null);
    }
    /** 服务注入顶层会话的范围领取器；离线旧调用保持不依赖服务的契约。 */
    public DockerRuntime(SandboxSettings settings, Path cache, Consumer<String> diagnostics, WriteScopes scopes) {
        this.writeScopes = scopes;
        this.settings = settings; this.cache = cache.toAbsolutePath().normalize(); this.diagnostics = diagnostics;
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "jade-sandbox-reaper"); t.setDaemon(true); return t; });
    }
    /** 按身份与完整策略复用，额外挂载另绑定任务；文件保护列表变化时不沿用旧视图。 */
    private synchronized Instance acquire(ExecutionPlan plan) throws IOException {
        boolean accepted = acceptedBackground.remove(plan);
        if (closed || ended.contains(plan.actor() + "/" + plan.runId()) && !accepted) throw new IOException("Sandbox execution scope has ended");
        initialize();
        List<PathProtection.Guard> guards = PathProtection.scan(plan.mounts());
        // 仅有基线工作区的实例按会话身份复用；额外挂载与一次性执行仍绑定原 run。
        String ownerRun = plan.reusable() && plan.mounts().size() == 1 && plan.mounts().getFirst().source().equals(plan.workspace())
                ? "" : plan.runId();
        String key = plan.actor() + "/" + ownerRun + "/" + plan.readOnly() + "/" + plan.network()
                + "/" + plan.mounts() + "/" + guards + "/" + plan.restrictions();
        if (plan.reusable()) for (var value : instances.values()) if (value.reusable && value.key.equals(key) && !value.retired) {
            value.references++; return value;
        }
        evictIdle();
        // 容量已满时先让闲置实例让位；活跃/排队/服务持有租约，不能被容量淘汰。
        if (instances.size() >= settings.maxContainers()) instances.values().stream()
                .filter(value -> value.references == 0).min(Comparator.comparingLong(value -> value.lastUsed)).ifPresent(this::remove);
        if (instances.size() >= settings.maxContainers()) throw new IOException("Sandbox container limit reached; wait for active work to finish");
        String name = "jade-sbx-" + UUID.randomUUID().toString().replace("-", "");
        AutoCloseable permit = writeScopes == null ? () -> { } : writeScopes.acquire(plan.mounts().stream()
                .filter(ExecutionPlan.Mount::writable).map(ExecutionPlan.Mount::source).toList());
        var instance = new Instance(name, key, plan.actor(), ownerRun, plan.reusable(), permit);
        instances.put(name, instance); // 先登记，create/start 超时也有可清理的确定名称。
        try {
            // 直接使用已 inspect 的 sha256 镜像 ID，create 不会按 tag 隐式寻找新版本。
            var args = new ArrayList<>(List.of("create", "--name", name,
                    "--label", "io.jadecode.sandbox=1", "--label", "io.jadecode.owner=" + owner,
                    "--label", "io.jadecode.session=" + session, "--label", "io.jadecode.uid=" + uid.split(":")[0], "--user", uid,
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
            for (var root : plan.mounts()) finalMounts.put(root.target(), mount(root.source(), root.target(), root.writable()));
            // 最后叠保护挂载，避免更宽的工作区挂载重新暴露敏感子路径。
            for (var guard : guards) {
                Path source = guard.hidden() ? masks.resolve(guard.directory() ? "directory" : "file") : guard.target();
                finalMounts.put(guard.target(), mount(source, guard.target(), false));
            }
            // 精确敏感目标只能在已获批的一次性挂载中重开；父目录授权不会重开内部保护点。
            for (var root : plan.mounts()) if (!root.source().equals(plan.workspace()) && PathProtection.sensitive(root.source()))
                finalMounts.put(root.target(), mount(root.source(), root.target(), root.writable()));
            // 用户显式限制最后合并，精确敏感目标的临时挂载也不能覆盖 deny。
            for (var restriction : plan.restrictions()) {
                Path target = restriction.path();
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !target.toRealPath().equals(target))
                    throw new IOException("Protected target changed before container creation");
                Path source = restriction.hidden() ? masks.resolve(Files.isDirectory(target) ? "directory" : "file") : target;
                finalMounts.put(target, mount(source, target, false));
            }
            for (String value : finalMounts.values()) args.addAll(List.of("--mount", value));
            args.add(image);
            require(cli.call(args, "", 30, () -> closed), "create");
            require(cli.call(List.of("start", name), "", 15, () -> closed), "start");
            instance.references = 1;
            return instance;
        } catch (IOException failure) { remove(instance); throw failure; }
    }
    /** 调用固定镜像里的执行端；模型不能指定 Docker 参数、entrypoint 或额外环境。 */
    public Outcome execute(ExecutionPlan plan, long timeoutSeconds, BooleanSupplier cancelled) {
        try (var lease=operations==null ? (AutoCloseable)()->{} : operations.enter(plan)) {
            Outcome outcome=cancelled.getAsBoolean() ? new Outcome("Error: Execution cancelled before acquiring workspace",true,null,false)
                    : executeIsolated(plan,timeoutSeconds,cancelled);
            if (operations!=null) operations.completed(plan,outcome);
            return outcome;
        } catch (Exception failure) {
            return new Outcome("Error: Workspace execution coordination failed: "+failure.getMessage(),true,null,false);
        }
    }
    /** 已领取工作区执行租约后的原有容器流程；清理与取消语义保持在同一个实现中。 */
    private Outcome executeIsolated(ExecutionPlan plan, long timeoutSeconds, BooleanSupplier cancelled) {
        Instance instance = null;
        boolean entered = false;
        try {
            instance = acquire(plan);
            if (!instance.lock.tryLock(timeoutSeconds, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for sandbox execution slot");
            try {
                entered = true;
                if (instance.retired || cancelled.getAsBoolean()) throw new IOException("Sandbox execution cancelled before start");
                ObjectNode request = Json.mapper().createObjectNode().put("cwd", plan.workspace().toString())
                        .put("tool", plan.tool()).put("timeout", timeoutSeconds);
                request.set("input", plan.input());
                var reply = cli.call(List.of("exec", "--user", uid, "--workdir", plan.workspace().toString(), "-i", instance.name,
                        "java", "-XX:+DisableAttachMechanism", "-Xmx256m", "-cp", "/opt/jadecode/worker.jar",
                        SandboxWorker.class.getName()), request.toString(), timeoutSeconds + 5, cancelled);
                if (reply.exitCode() != 0) {
                    // 执行端本身被杀时没有 JSON；只有本次计数增长才确定报告内核 OOM。
                    var events = cli.call(List.of("exec", instance.name, "cat", "/sys/fs/cgroup/memory.events"), "", 3, () -> false);
                    long oom = oomKills(events.output());
                    if (oom > instance.oomCount || reply.output().contains("OutOfMemoryError"))
                        throw new IOException("Sandbox OOM: worker could not return a result");
                    throw new IOException("Sandbox worker exited without a result (status " + reply.exitCode() + ")");
                }
                var json = Json.mapper().readTree(reply.output());
                if (json == null || json.path("protocol").asInt() != 1 || !json.path("content").isTextual()
                        || !json.path("isError").isBoolean() || !json.path("clean").isBoolean()) throw new IOException("Sandbox worker returned an invalid result");
                long finalOom = json.path("oomCount").asLong(instance.oomCount);
                if (!json.path("clean").asBoolean() || finalOom > instance.oomCount) retire(instance);
                instance.oomCount = finalOom;
                return new Outcome(json.path("content").asText(), json.path("isError").asBoolean(),
                        json.has("exitCode") ? json.path("exitCode").intValue() : null, json.path("truncated").asBoolean(),
                        json.has("rawOutput") ? json.path("rawOutput").asText() : json.path("content").asText());
            } finally { instance.lock.unlock(); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); if (instance != null && entered) retire(instance);
            return new Outcome("Error: Sandbox execution interrupted; operation may have side effects", true, null, false);
        } catch (Exception failure) {
            if (instance != null && entered) retire(instance);
            return new Outcome("Error: " + failure.getMessage() + ". No host fallback or automatic replay was performed.", true, null, false);
        } finally { if (instance != null) release(instance); }
    }
    /**
     * 在返回后台接收回执之前登记一次许可；任务结束不撤销已接收操作，会话关闭仍取消。
     * 这里只登记，不创建容器；真正执行时原子消费，失败不重放。调用方入队失败必须 discard。
     */
    public synchronized void acceptBackground(ExecutionPlan plan) throws IOException {
        if (closed || ended.contains(plan.actor() + "/" + plan.runId())) throw new IOException("Sandbox task has already ended");
        if (!plan.tool().equals("bash") || !plan.input().path("run_in_background").asBoolean())
            throw new IOException("Only an approved background command may reserve its execution");
        acceptedBackground.add(plan);
    }
    /** 入队失败、上下文取消或版本过期时撤销未消费许可，不触碰其他后台操作。 */
    public synchronized void discardBackground(ExecutionPlan plan) { acceptedBackground.remove(plan); }
    /** 首次实际执行才核对 daemon、镜像与 UID；固定镜像 ID 后不受 tag 后续变化影响。 */
    private void initialize() throws IOException {
        if (image != null) return;
        cli.initialize();
        var inspected = cli.call(List.of("image", "inspect", "--format", "{{.Id}} {{index .Config.Labels \"io.jadecode.worker-protocol\"}}", settings.image()), "", 15, () -> false);
        if (inspected.exitCode() != 0 || !inspected.output().strip().matches("sha256:[0-9a-f]{64} 1"))
            throw new IOException("Sandbox image is unavailable; run mvn -q package and sh scripts/build-sandbox.sh");
        // 镜像 ABI 与业务协议一同固定，不在模型任务中安装或修补执行器。
        String inspectedImage = inspected.output().strip().split(" ")[0];
        uid = localId("-u") + ":" + localId("-g");
        if (uid.startsWith("0:")) throw new IOException("Sandbox requires a non-root host UID for workspace ownership");
        // 缓存由模型写入，只检查根目录和现有 .m2 的布局，不向其内部写配置。
        // toolchains 固化于只读镜像，避免容器并发替换缓存链接时诱导宿主写出数据根。
        var cachePaths = new com.jadecode.config.JadePaths(cache);
        cachePaths.directory(cache, true);
        cachePaths.directory(cache.resolve(".m2"), false);
        if (masks == null) {
            masks = Files.createTempDirectory("jade-sandbox-masks-");
            Files.createDirectory(masks.resolve("directory")); Files.createFile(masks.resolve("file"));
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
    /** ID 来自系统命令而非模型；禁止为写入方便修改宿主工作区属主或权限。 */
    private static String localId(String flag) throws IOException {
        Process process = new ProcessBuilder("/usr/bin/id", flag).start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IOException("Cannot determine sandbox UID/GID");
            String id = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).strip();
            if (process.exitValue() != 0 || !id.matches("[0-9]+")) throw new IOException("Invalid sandbox UID/GID");
            return id;
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("UID/GID lookup interrupted", failure); }
        finally { process.destroyForcibly(); }
    }
    /** Docker --mount 的逗号有语法含义；首版明确拒绝，不能用 Shell 转义伪装为安全参数。 */
    private static String mount(Path source, Path target, boolean writable) throws IOException {
        if (source.toString().contains(",") || target.toString().contains(",") || source.toString().contains("\n") || target.toString().contains("\n"))
            throw new IOException("Sandbox mount paths containing comma/newline are unsupported");
        return "type=bind,src=" + source + ",dst=" + target + (writable
                ? ",bind-propagation=rprivate,bind-recursive=disabled"
                : ",readonly,bind-propagation=rprivate,bind-recursive=readonly");
    }
    private static void require(DockerCli.Reply reply, String operation) throws IOException {
        if (reply.exitCode() != 0) throw new IOException("Docker " + operation + " failed: " + reply.output().strip());
    }
    /** 只解释 cgroup 指标，不将程序输出关键字当成沙箱扩权依据。 */
    private static long oomKills(String events) {
        for (String line : events.split("\n")) if (line.startsWith("oom_kill ")) try { return Long.parseLong(line.substring(9).strip()); }
        catch (NumberFormatException unknown) { return -1; }
        return -1;
    }
    private synchronized void release(Instance instance) {
        instance.references--; instance.lastUsed = System.nanoTime();
        if (!instance.reusable || instance.retired || closed) remove(instance);
        else evictIdle();
    }
    private synchronized void retire(Instance instance) { instance.retired = true; remove(instance); }
    /** 任务结束撤销新入口；已启动后台持有租约直到完成，额外挂载随后删除，基线可继续闲置。 */
    public synchronized void endTask(String actor, String run) {
        ended.add(actor + "/" + run);
        for (var instance : List.copyOf(instances.values()))
            if ((instance.actor.equals(actor) || instance.actor.startsWith(actor + "/")) && instance.run.equals(run)) {
                ended.add(instance.actor + "/" + run); instance.retired = true;
                if (instance.references == 0) remove(instance);
            }
    }
    /** 关闭会话/队友是主动取消，也停止该身份仍在执行的实例。 */
    public synchronized void endActor(String actor) {
        acceptedBackground.removeIf(plan -> plan.actor().equals(actor) || plan.actor().startsWith(actor + "/"));
        for (var instance : List.copyOf(instances.values()))
            if (instance.actor.equals(actor) || instance.actor.startsWith(actor + "/")) { instance.retired = true; remove(instance); }
    }
    /** 只有 references=0 才做空闲淘汰；长时间测试或后台服务不会因没有新调用而被误杀。 */
    private synchronized void evictIdle() {
        for (var failed : List.copyOf(instances.values())) if (failed.retired && failed.references == 0) remove(failed);
        var idle = instances.values().stream().filter(i -> i.references == 0 && !i.retired)
                .sorted(Comparator.comparingLong(i -> i.lastUsed)).toList();
        long now = System.nanoTime();
        for (int n = 0; n < idle.size(); n++)
            if (idle.size() - n > settings.maxIdle() || now - idle.get(n).lastUsed >= TimeUnit.SECONDS.toNanos(settings.idleSeconds())) remove(idle.get(n));
    }
    private void reapQuietly() { try { evictIdle(); } catch (RuntimeException failure) { diagnostics.accept("[Sandbox] idle cleanup failed"); } }
    /** 同一回收入口用于定向验证；生产由固定周期调用，不能驱逐有租约的实例。 */
    void reapIdle() { evictIdle(); }
    /** rm -f 同时结束容器内进程；失败保留登记，下一次清理会重试。 */
    private synchronized void remove(Instance instance) {
        instance.retired = true;
        try {
            var reply = cli.call(List.of("rm", "--force", instance.name), "", 10, () -> false);
            if (reply.exitCode() == 0 || reply.output().contains("No such container")) {
                try { instance.writePermit.close(); }
                catch (Exception failure) { throw new IOException("Writable scope release failed", failure); }
                instances.remove(instance.name);
            }
            else diagnostics.accept("[Sandbox] cleanup unconfirmed for " + instance.name);
        } catch (IOException failure) { diagnostics.accept("[Sandbox] cleanup unconfirmed for " + instance.name); }
    }
    /** 只回收带 Jade 标记且所属进程已经结束的实例，PID 重用通过启动时间区分。 */
    private void recoverOrphans() throws IOException {
        var result = cli.call(List.of("ps", "-a", "--filter", "label=io.jadecode.sandbox=1", "--filter", "label=io.jadecode.uid=" + uid.split(":")[0], "--format", "{{.ID}} {{.Label \"io.jadecode.owner\"}}"), "", 10, () -> false);
        if (result.exitCode() != 0) throw new IOException("Cannot inspect sandbox orphan ownership");
        for (String line : result.output().split("\n")) {
            String[] value = line.strip().split(" ");
            if (value.length != 2 || !value[0].matches("[0-9a-f]+") || !value[1].matches("[0-9]+@[0-9]+")) continue;
            String[] identity = value[1].split("@");
            var process = ProcessHandle.of(Long.parseLong(identity[0]));
            // 存活但无法读取启动时间不等于孤儿；宁可报告残留，也不误杀别的会话。
            boolean live = process.filter(ProcessHandle::isAlive).map(p -> p.info().startInstant()
                    .map(time -> time.toEpochMilli() == Long.parseLong(identity[1])).orElse(true)).orElse(false);
            if (!live) {
                var removed = cli.call(List.of("rm", "--force", value[0]), "", 10, () -> false);
                if (removed.exitCode() != 0) diagnostics.accept("[Sandbox] orphan cleanup unconfirmed for " + value[0]);
            }
        }
    }
    /** 只读计数供验证，不把宿主路径和模型输入暴露给展示端。 */
    public synchronized int containerCount() { return instances.size(); }
    /**
     * 服务有自己的容器和引用，SDK 持有 stdio；关闭 SDK 后显式删除服务容器。
     * 不把服务所需网络授权带给普通工具。command/args 来自可信 MCP 配置。
     */
    public Service openService(String actor, String name, Path workspace, List<String> command, boolean network) throws IOException {
        return openService(actor, name, workspace, command, network, List.of());
    }
    /** 服务启动时固定路径保护；权限变化由所有者关闭服务后重新连接，不能修改已运行容器的视图。 */
    public Service openService(String actor, String name, Path workspace, List<String> command, boolean network,
            List<ExecutionPlan.Restriction> restrictions) throws IOException {
        workspace = workspace.toAbsolutePath().normalize();
        if (workspace.getNameCount() < 2 || workspace.equals(Path.of(System.getProperty("user.home")))
                || PathProtection.sensitive(workspace) || cache.startsWith(workspace))
            throw new IOException("MCP service requires a narrow project directory outside Harness data");
        var input = Json.mapper().createObjectNode().put("service", name);
        var plan = new ExecutionPlan(actor + "/mcp/" + name, "service_" + UUID.randomUUID(), 0, "service", input,
                workspace, true, network, false, List.of(new ExecutionPlan.Mount(workspace, workspace, false)), restrictions);
        Instance instance = acquire(plan);
        try {
            var args = new ArrayList<>(List.of("exec", "-i", "--user", uid, "--workdir", workspace.toString(), instance.name));
            args.addAll(command);
            return new Service(instance, new ProcessBuilder(cli.command(args)));
        } catch (IOException failed) { retire(instance); release(instance); throw failed; }
    }
    /** stdio 的租约不能被闲置回收；重复 close 只释放一次引用。 */
    public final class Service implements AutoCloseable {
        private final Instance instance; private final ProcessBuilder builder; private boolean released;
        private Service(Instance instance, ProcessBuilder builder) { this.instance = instance; this.builder = builder; }
        public ProcessBuilder builder() { return builder; }
        @Override public synchronized void close() { if (!released) { released = true; retire(instance); release(instance); } }
    }
    /** 关闭只删除自有容器；缓存/镜像/工作区不属于临时资源。 */
    @Override public synchronized void close() {
        if (closed) return; closed = true; acceptedBackground.clear(); sweeper.shutdownNow();
        for (var instance : List.copyOf(instances.values())) remove(instance);
        if (masks != null && instances.isEmpty()) try {
            Files.setPosixFilePermissions(masks.resolve("directory"), PosixFilePermissions.fromString("rwx------"));
            Files.deleteIfExists(masks.resolve("directory")); Files.deleteIfExists(masks.resolve("file"));
            Files.deleteIfExists(masks.resolve("passwd")); Files.deleteIfExists(masks.resolve("group")); Files.deleteIfExists(masks);
        } catch (IOException failure) { diagnostics.accept("[Sandbox] temporary mask cleanup unconfirmed: " + masks); }
        if (!instances.isEmpty()) throw new IllegalStateException("Sandbox cleanup is incomplete; writable scopes remain held");
    }
    /** 服务回收必须确认所有所属容器已删除；清理失败时不能释放工作区。 */
    public synchronized boolean cleanupConfirmed() { return closed && instances.isEmpty(); }
    private static final class Instance {
        final String name, key, actor, run; final boolean reusable; final ReentrantLock lock = new ReentrantLock();
        final AutoCloseable writePermit;
        int references; long lastUsed = System.nanoTime(); long oomCount; volatile boolean retired;
        Instance(String name, String key, String actor, String run, boolean reusable, AutoCloseable permit) {
            this.writePermit = permit;
            this.name = name; this.key = key; this.actor = actor; this.run = run; this.reusable = reusable;
        }
    }
}
