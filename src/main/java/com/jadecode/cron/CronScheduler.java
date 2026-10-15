package com.jadecode.cron;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

/**
 * s12 时间与交付状态管理：到期先落盘 pending，再交给运行时串行发送 prompt。
 *
 * <p>不创建计时线程、不调用模型或 shell；CronRuntime 负责何时调用 pollDue 和消费。
 * 同一实例的方法串行保护内存/文件，但不是多进程任务库锁；同一工作区只由一个 CLI 管理。
 * 文件用临时文件原子替换，未 fsync，不承诺断电耐久性或恰好一次交付。</p>
 */
public final class CronScheduler {
    private static final int MAX_JOBS = 100;
    private final Path workspace;
    private final Path file;
    private final Clock clock;
    private Map<String, CronJob> jobs = new LinkedHashMap<>();
    private final ArrayDeque<String> ready = new ArrayDeque<>();
    /** 已由运行时取走但尚未确认的记录，不允许另一个消费者再次取得。 */
    private final Set<String> leased = new HashSet<>();
    private boolean loaded;

    /** 绑定已有工作区，不创建状态文件；首次工具操作/轮询才读取保存的任务。 */
    public CronScheduler(Path workspace) throws IOException {
        this(workspace, Clock.systemDefaultZone());
    }

    /**
     * 注入时钟使离线验证不需要真实等到某一分钟，生产仍用系统本地时间。
     * @param workspace 已存在的工作区；解析真实路径，不接受模型指定状态文件路径
     * @param clock 计时时钟及默认时区
     * @throws IOException 工作区无法绑定
     */
    public CronScheduler(Path workspace, Clock clock) throws IOException {
        this.workspace = workspace.toRealPath();
        if (!Files.isDirectory(this.workspace)) throw new IOException("Cron workspace must be a directory");
        this.file = this.workspace.resolve(".scheduled_tasks.json");
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    /**
     * 创建定义；durable 成功落盘后才发布到内存，不在这里触发或运行。
     * @param cron 五段表达式，原样保存，创建前完整校验
     * @param prompt 到期后要交给模型的任务，不是直接执行的命令
     * @param recurring 确认交付后是否保留定义
     * @param durable 是否将定义与交付状态保存到工作区
     * @return 实际分配 ID 的不可变记录
     * @throws IOException 恢复或保存失败；旧任务表保持不变
     * @throws IllegalArgumentException 无效规则/正文或任务已达上限
     */
    public synchronized CronJob schedule(String cron, String prompt, boolean recurring, boolean durable) throws IOException {
        load();
        if (jobs.size() >= MAX_JOBS) throw new IllegalArgumentException("At most 100 cron jobs are supported");
        String id = null;
        // 随机碰撞极少，但不能把随机数永不冲突当作循环终止保证。
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = "cron_" + UUID.randomUUID().toString().substring(0, 8);
            if (!jobs.containsKey(candidate)) { id = candidate; break; }
        }
        if (id == null) throw new IllegalStateException("Unable to allocate a cron ID");
        CronJob job = new CronJob(id, cron, prompt, recurring, durable, false, null);
        Map<String, CronJob> next = new LinkedHashMap<>(jobs);
        next.put(id, job);
        commit(next);
        return job;
    }

    /** 返回当前定义与交付状态的只读快照；不认领、不触发、不启动任务。 */
    public synchronized List<CronJob> list() throws IOException {
        load();
        return List.copyOf(jobs.values());
    }

    /**
     * 取消未来/待交付的定义，成功保存后从队列移除；已经交给模型的副作用不能撤销。
     * @param id schedule 返回的真实 ID，不是文件路径
     * @throws IllegalArgumentException ID 不存在
     * @throws IOException 保存失败，定义与队列不变
     */
    public synchronized void cancel(String id) throws IOException {
        load();
        if (!jobs.containsKey(id)) throw new IllegalArgumentException("Cron job not found: " + id);
        Map<String, CronJob> next = new LinkedHashMap<>(jobs);
        next.remove(id);
        commit(next);
        ready.remove(id);
        leased.remove(id);
    }

    /**
     * 检查此刻匹配的任务：同一分钟已触发或仍待交付时不重复排队，不补跑停机分钟。
     * 先统一提交 pending 状态，再发布内存队列；保存失败时本批不交付。
     */
    public synchronized void pollDue() throws IOException {
        load();
        ZonedDateTime now = ZonedDateTime.now(clock);
        String minute = now.truncatedTo(ChronoUnit.MINUTES).toOffsetDateTime().toString();
        Map<String, CronJob> next = new LinkedHashMap<>(jobs);
        List<String> due = new ArrayList<>();
        for (CronJob job : jobs.values()) {
            if (job.pendingDelivery() || minute.equals(job.lastFired())) continue;
            if (CronExpression.parse(job.cron()).matches(now)) {
                next.put(job.id(), job.delivery(true, minute));
                due.add(job.id());
            }
        }
        if (due.isEmpty()) return;
        commit(next);
        ready.addAll(due);
    }

    /**
     * 非阻塞取一项到期任务；不在这里清 pending，首次模型回复前崩溃仍可重启恢复。
     * @return 无待交付项时 null；非 null 的记录由调用方确认或 restore
     */
    public synchronized CronJob takeDue() throws IOException {
        load();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            CronJob job = jobs.get(id);
            if (job != null && job.pendingDelivery() && leased.add(id)) return job;
        }
        return null;
    }

    /** 首次模型回复前失败，将仍存在的待交付项放回；取消的任务不能被复活。 */
    public synchronized void restore(String id) {
        CronJob job = jobs.get(id);
        if (leased.remove(id) && job != null && job.pendingDelivery()) ready.addFirst(id);
    }

    /**
     * 模型已返回首个合法正式回复后确认交付，不代表业务成功。
     * 周期项清 pending，一次项删除；写盘失败不发布候选，保留 lease 避免本会话立即重复副作用。
     * @param id takeDue 已取得的任务 ID；已取消或已确认时无副作用
     * @throws IOException durable 确认无法保存，调用方须告警，不能假装交付状态已可靠落盘
     */
    public synchronized void acknowledge(String id) throws IOException {
        CronJob job = jobs.get(id);
        if (job == null || !leased.contains(id)) return;
        Map<String, CronJob> next = new LinkedHashMap<>(jobs);
        if (job.recurring()) next.put(id, job.delivery(false, job.lastFired()));
        else next.remove(id);
        commit(next);
        leased.remove(id);
    }

    /** 首次恢复采用完整候选；坏记录阻断恢复，不静默跳过后覆盖用户的状态文件。 */
    private void load() throws IOException {
        if (loaded) return;
        checkFile();
        Map<String, CronJob> recovered = new LinkedHashMap<>();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            // 需容纳本类允许的 100 × 16000 字符及 JSON 转义，不能让合法写出的文件自己读不回。
            if (Files.size(file) > 16_000_000) throw new IOException("Cron state exceeds 16 MB");
            JsonNode root = Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isArray() || root.size() > MAX_JOBS) throw new IOException("Invalid cron state list");
            try {
                for (JsonNode node : root) {
                    CronJob job = decode(node);
                    if (!job.durable() || recovered.putIfAbsent(job.id(), job) != null) {
                        throw new IllegalArgumentException("Non-durable or duplicate saved cron job");
                    }
                }
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid saved cron record", invalid);
            }
        }
        jobs = recovered;
        for (CronJob job : jobs.values()) if (job.pendingDelivery()) ready.addLast(job.id());
        loaded = true;
    }

    /** 只读取明确 wire 字段并检查原始 JSON 类型，禁止字符串冒充 boolean。 */
    private static CronJob decode(JsonNode node) {
        if (!node.isObject() || node.size() != 7) throw new IllegalArgumentException("Invalid cron fields");
        for (String key : List.of("id", "cron", "prompt")) {
            if (!node.path(key).isTextual()) throw new IllegalArgumentException("Invalid cron text field");
        }
        for (String key : List.of("recurring", "durable", "pending_delivery")) {
            if (!node.path(key).isBoolean()) throw new IllegalArgumentException("Invalid cron boolean field");
        }
        JsonNode last = node.get("last_fired");
        if (last == null || !(last.isNull() || last.isTextual())) throw new IllegalArgumentException("Invalid last_fired");
        return new CronJob(node.get("id").textValue(), node.get("cron").textValue(), node.get("prompt").textValue(),
                node.get("recurring").booleanValue(), node.get("durable").booleanValue(),
                node.get("pending_delivery").booleanValue(), last.isNull() ? null : last.textValue());
    }

    /** 生成课程风格 JSON，只保存 durable 记录，空 last_fired 也显式保留以便严格恢复。 */
    private static ArrayNode payload(Map<String, CronJob> state) {
        ArrayNode array = Json.mapper().createArrayNode();
        for (CronJob job : state.values()) {
            if (!job.durable()) continue;
            ObjectNode node = array.addObject();
            node.put("id", job.id()).put("cron", job.cron()).put("prompt", job.prompt())
                    .put("recurring", job.recurring()).put("durable", true).put("pending_delivery", job.pendingDelivery());
            if (job.lastFired() == null) node.putNull("last_fired");
            else node.put("last_fired", job.lastFired());
        }
        return array;
    }

    /**
     * 先编码、写临时文件、原子替换，再发布内存；无 durable 变化时不进行无意义写盘。
     * 失败保留旧表，临时清理错误作为 suppressed 异常保留，不覆盖首次保存错误。
     */
    private void commit(Map<String, CronJob> next) throws IOException {
        ArrayNode encoded = payload(next);
        if (!encoded.equals(payload(jobs))) {
            checkFile();
            String json = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(encoded);
            Path temporary = Files.createTempFile(workspace, ".scheduled-tasks-", ".tmp");
            IOException original = null;
            try {
                Files.writeString(temporary, json, StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failed) {
                original = failed;
                throw failed;
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanup) {
                    if (original != null) original.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
        jobs = next;
    }

    /** 拒绝软链、悬空链接和目录型状态文件；不承诺阻止恶意并发替换路径的竞态。 */
    private void checkFile() throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cron state must be a regular non-symlink file: " + file);
        }
    }
}
