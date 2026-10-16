package com.jadecode.tasks;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.jadecode.tasks.Task.Status;
import com.jadecode.util.Json;

/**
 * 工作区里的持久任务图：检查任务能不能开始，保存负责人和进度。实际工作仍由模型自己做。
 *
 * <p>主流程是创建 → 加依赖 → 认领 → 完成；读取和列举用于恢复和查看。
 * 与 todo_write 整表替换不同，这里每次只更新一项任务的新版本，模型也不能随意改状态或路径。
 *
 * <p>按顺序执行设计：每次操作先取一份快照统一判断，不是数据库事务或并发锁。
 * 写入用临时文件原子替换，保护旧 JSON 不被普通写入失败截断，但不保证断电后的耐久性，
 * 也不保证多个进程同时认领时的互斥。
 */
public final class TaskStore {
    private final Path workspaceRoot;
    private final Path taskDir;

    /**
     * 绑定现有工作区，构造无写盘、无网络；只有创建任务时才创建 .tasks。
     * @param workspaceRoot 实际工作区，不能为 null 或不存在
     * @throws IOException 工作区不能解析或不是目录
     */
    public TaskStore(Path workspaceRoot) throws IOException {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot 不能为 null");
        }
        this.workspaceRoot = workspaceRoot.toRealPath();
        if (!Files.isDirectory(this.workspaceRoot)) {
            throw new IOException("Task 工作区不是目录");
        }
        taskDir = this.workspaceRoot.resolve(".tasks");
    }

    /**
     * 创建无前置依赖、无人认领的 pending 任务；模型得到实际 ID 后才能添加依赖。
     * @param subject 非空标题
     * @param description 完整说明，可为空字符串
     * @return 已成功写入的不可变任务，不以分配了 ID 充当创建成功
     * @throws IOException 建目录 / 写入失败或一百次 ID 冲突；普通写入失败清理本次新文件
     * @throws IllegalArgumentException 标题或描述非法，在建库前拒绝
     */
    public Task create(String subject, String description) throws IOException {
        if (subject == null || subject.isBlank() || description == null) {
            throw new IllegalArgumentException("subject 必须为非空文本，description 必须为文本");
        }
        Path root = checkedRoot(true);
        // 1. ID 由 Harness 生成。CREATE_NEW 才是碰撞保护，不能先 exists 再普通覆盖写。
        for (int attempt = 0; attempt < 100; attempt++) {
            String id = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            Task task = new Task(id, subject, description, Status.PENDING, null, List.of());
            byte[] bytes = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(task);
            Path target = root.resolve(id + ".json");
            OutputStream stream;
            try {
                stream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            // 2. 排他创建成功后才拥有这个新路径；失败只清理自己的文件，不处理碰撞的旧对象。
            try (stream) {
                stream.write(bytes);
            } catch (IOException failure) {
                cleanup(target, failure);
                throw failure;
            }
            return task;
        }
        throw new IOException("无法分配唯一任务 ID");
    }

    /**
     * 按 ID 读取完整任务；不创建库，不接受模型给出的任意路径。
     * @param id 必须符合 ID_PATTERN
     * @return 字段与文件名一致的合法任务
     * @throws IOException 缺文件、链接、坏 JSON 或字段非法；不静默把坏记录当作不存在
     * @throws IllegalArgumentException ID 非法，路径解析前拒绝
     */
    public Task load(String id) throws IOException {
        requireId(id);
        return readTask(checkedRoot(false).resolve(id + ".json"), id);
    }

    /**
     * 按文件名排序返回全部任务，供模型看全局进度；缺库返回空列表，无写入副作用。
     * 仅扫描规范 ID 的 JSON；符合命名但损坏的记录明确报错，避免凭残缺任务图继续决策。
     * @return 不可修改的任务快照，不代表文件系统并发隔离
     * @throws IOException 库根 / 列举 / 合法命名记录读取失败
     */
    public List<Task> list() throws IOException {
        // 不跟随链接判断“缺库”：悬空库根是坏配置，应由 checkedRoot 报错，而非伪装成空库。
        if (Files.notExists(taskDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Path root = checkedRoot(false);
        List<Task> tasks = new ArrayList<>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.sorted().toList()) {
                String filename = path.getFileName().toString();
                if (filename.matches("task_[0-9a-f]{8}\\.json")) {
                    tasks.add(readTask(path, filename.substring(0, filename.length() - 5)));
                }
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
        return List.copyOf(tasks);
    }

    /**
     * 给尚未认领的任务增加前置条件；全部边检查通过才发布一次，失败不留下部分依赖。
     * @param id 目标任务 ID
     * @param additions 至少一个前置 ID，重复添加去重，不改输入列表
     * @return 已保存的新任务值
     * @throws IllegalArgumentException 目标不可更新、依赖不存在、自依赖、成环或参数非法
     * @throws IOException 快照读取或原子保存失败
     */
    public Task updateDependencies(String id, List<String> additions) throws IOException {
        requireId(id);
        if (additions == null || additions.isEmpty()) {
            throw new IllegalArgumentException("addBlockedBy 必须是非空 ID 列表");
        }
        // 1. 用同一份图检查所有候选边；模型参数不能绕过“先创建节点再连边”的约定。
        Map<String, Task> graph = snapshot();
        Task task = requireTask(graph, id);
        if (task.status() != Status.PENDING || task.owner() != null) {
            throw new IllegalArgumentException("只能修改 pending 且无人认领的任务依赖");
        }
        var dependencies = new LinkedHashSet<>(task.blockedBy());
        for (String dependency : additions) {
            requireId(dependency);
            requireTask(graph, dependency);
            // 新增 id → dependency 前，若 dependency 已经能走回 id，新增这条边就形成环。
            if (dependency.equals(id) || dependsOn(graph, dependency, id)) {
                throw new IllegalArgumentException("任务不能自依赖或形成依赖环");
            }
            dependencies.add(dependency);
        }
        // 2. Task 重建后统一保存；调用方持有的旧 Task / List 都不会被原地改变。
        Task updated = new Task(id, task.subject(), task.description(), task.status(), task.owner(), List.copyOf(dependencies));
        save(updated);
        return updated;
    }

    /**
     * 登记“我负责这个已具备开始条件的任务”，不启动线程、子代理或实际工程操作。
     * @param id pending 任务 ID
     * @param owner 非空负责者标签，工具层固定为 agent
     * @return 已保存的 in_progress 记录
     * @throws IllegalArgumentException 状态不可认领、前置未完成 / 缺失、owner 非法
     * @throws IOException 读取 / 保存失败
     */
    public Task claim(String id, String owner) throws IOException {
        requireId(id);
        requireOwner(owner);
        Map<String, Task> graph = snapshot();
        Task task = requireTask(graph, id);
        if (task.status() != Status.PENDING) {
            throw new IllegalArgumentException("只能认领 pending 任务");
        }
        List<String> blocked = incompleteDependencies(task, graph);
        if (!blocked.isEmpty()) {
            throw new IllegalArgumentException("Blocked by: " + blocked);
        }
        // 校验通过只是允许更新状态；文件写入成功之后才能向模型报告“认领成功”。
        Task claimed = new Task(id, task.subject(), task.description(), Status.IN_PROGRESS, owner, task.blockedBy());
        save(claimed);
        return claimed;
    }

    /**
     * 登记完成，并返回由于本次状态变化刚解锁的下游任务；不自动认领或运行它们。
     *
     * <p>先读取快照、检查 owner 并计算新结果，再发布 completed。保存之后没有额外磁盘扫描，
     * 避免“状态已经变了，但报告下游时失败”造成含糊重试。完成声明仍不是代码质量证明。</p>
     * @param id 必须是 in_progress 任务
     * @param owner 必须与记录负责者精确一致
     * @return 已保存任务及刚解锁的任务，不含此前已经可做的任务
     * @throws IllegalArgumentException 状态或负责者不匹配
     * @throws IOException 快照或保存失败，未发布候选完成状态
     */
    public Completion complete(String id, String owner) throws IOException {
        requireId(id);
        requireOwner(owner);
        Map<String, Task> graph = snapshot();
        Task task = requireTask(graph, id);
        if (task.status() != Status.IN_PROGRESS || !owner.equals(task.owner())) {
            throw new IllegalArgumentException("只能由原负责者完成 in_progress 任务");
        }
        // 1. 记录之前就可开始的任务；否则每次完成都会反复把它们误报为“刚解锁”。
        var readyBefore = new HashSet<String>();
        for (Task candidate : graph.values()) {
            if (candidate.status() == Status.PENDING && incompleteDependencies(candidate, graph).isEmpty()) {
                readyBefore.add(candidate.id());
            }
        }
        Task completed = new Task(id, task.subject(), task.description(), Status.COMPLETED, owner, task.blockedBy());
        graph.put(id, completed);
        List<Task> unblocked = new ArrayList<>();
        for (Task candidate : graph.values()) {
            if (candidate.status() == Status.PENDING && !candidate.blockedBy().isEmpty()
                    && !readyBefore.contains(candidate.id()) && incompleteDependencies(candidate, graph).isEmpty()) {
                unblocked.add(candidate);
            }
        }
        Completion result = new Completion(completed, unblocked);
        // 2. 只发布当前任务一个文件；其它任务的前置 ID 不删除，后续据新状态自然判断为可开始。
        save(completed);
        return result;
    }

    /** 一次操作的本地图；坏记录使操作明确失败，不自动删除或猜测其完成状态。 */
    private Map<String, Task> snapshot() throws IOException {
        Map<String, Task> graph = new LinkedHashMap<>();
        for (Task task : list()) {
            graph.put(task.id(), task);
        }
        return graph;
    }

    /** 前置缺失和未完成都阻塞；只有确实存在且 completed 才满足条件。 */
    private static List<String> incompleteDependencies(Task task, Map<String, Task> graph) {
        return task.blockedBy().stream().filter(id -> !graph.containsKey(id)
                || graph.get(id).status() != Status.COMPLETED).toList();
    }

    /**
     * 沿依赖边迭代寻找 target；visited 防止外部坏图导致无限循环，不代表自动修复已有坏图。
     * 例如 B 依赖 A，再给 A 添加 B 时，从 B 出发能到 A，因此拒绝。
     */
    private static boolean dependsOn(Map<String, Task> graph, String start, String target) {
        var pending = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        pending.push(start);
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (current.equals(target)) {
                return true;
            }
            if (visited.add(current)) {
                pending.addAll(requireTask(graph, current).blockedBy());
            }
        }
        return false;
    }

    /** 先编码、写完临时文件，再原子替换旧记录；普通 IO 故障不原地截断旧 JSON。 */
    private void save(Task task) throws IOException {
        byte[] bytes = Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(task);
        Path root = checkedRoot(false);
        Path target = root.resolve(task.id() + ".json");
        requireRegular(target);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".task-update-", ".tmp");
            Files.write(temporary, bytes);
            // 再查目标对象，拒绝把后来出现的链接 / 目录当普通任务覆盖；这仍不是竞态隔离。
            requireRegular(target);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            cleanup(temporary, failure);
            throw failure;
        }
    }

    /** 严格读取一个完整 JSON，record 构造器再检查字段与状态的一致性。 */
    private static Task readTask(Path path, String id) throws IOException {
        requireRegular(path);
        JsonNode document = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(Files.readAllBytes(path));
        // 共享 Jackson 允许部分标量转换 / 未知字段，消息协议需要这些兼容性，任务记录却不应猜值。
        // 先按六字段逐项判型，避免 subject:7 被自动变成 "7"，也避免缺 owner 被猜成未认领。
        if (document == null || !document.isObject() || document.size() != 6
                || !document.path("id").isTextual() || !document.path("subject").isTextual()
                || !document.path("description").isTextual() || !document.path("status").isTextual()
                || !(document.path("owner").isNull() || document.path("owner").isTextual())
                || !document.path("blockedBy").isArray()) {
            throw new IOException("任务记录字段形状无效: " + id);
        }
        for (JsonNode dependency : document.get("blockedBy")) {
            if (!dependency.isTextual()) {
                throw new IOException("任务依赖不是文本 ID: " + id);
            }
        }
        Task task = Json.mapper().treeToValue(document, Task.class);
        if (!task.id().equals(id)) {
            throw new IOException("任务文件 ID 与文件名不一致: " + id);
        }
        return task;
    }

    /** 缺库仅在 create 路径创建；库根链接可指向工作区内目录，但绝不能逃出工作区。 */
    private Path checkedRoot(boolean create) throws IOException {
        if (create && Files.notExists(taskDir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(taskDir);
            } catch (FileAlreadyExistsException createdElsewhere) {
                // 仍需解析并检查新出现的对象，不能相信 exists 就表示安全目录。
            }
        }
        Path root = taskDir.toRealPath();
        if (!root.startsWith(workspaceRoot) || !Files.isDirectory(root)) {
            throw new IOException("Task 库根不是工作区内目录");
        }
        return root;
    }

    /** 任务记录一律拒绝软链与目录，不跟随模型可能借助其它工具放置的链接读写目标。 */
    private static void requireRegular(Path path) throws IOException {
        if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new IOException("任务记录不是普通非链接文件: " + path.getFileName());
        }
    }

    /** 参数校验先于路径拼接，拒绝 null、斜杠与任意文件名。 */
    private static void requireId(String id) {
        if (id == null || !id.matches(Task.ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid task ID");
        }
    }

    /** owner 只是责任标签；匹配用原字符串，不隐式冒充真实用户或进程身份。 */
    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner 必须是非空标签");
        }
    }

    /** 从固定快照取节点，不猜测缺失任务已经完成；仅供状态与依赖操作使用。 */
    private static Task requireTask(Map<String, Task> graph, String id) {
        Task task = graph.get(id);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + id);
        }
        return task;
    }

    /** 只清理本次专属路径，保留原 IO 及清理失败，不扫描删除整个任务目录。 */
    private static void cleanup(Path path, IOException failure) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /** 完成结果的只读快照；下游列表是提示，不是已启动的任务。 */
    public record Completion(Task task, List<Task> unblocked) {
        /** 切断外部列表引用，完成成功后结果不会随着调用方集合修改而漂移。 */
        public Completion {
            unblocked = List.copyOf(unblocked);
        }
    }
}
