package com.jadecode.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * 按文件保存正式知识、经验来源、候选笔记、反馈和维护作业。
 *
 * <p>工作区真实路径决定 scope；全局分区与当前工作区分区参与本次整理。
 * 普通读写在 MemoryLibraryLock 的短锁内完成。publish 根据预期快照核对版本，重建分区正文，
 * 通过 MemoryTransaction 备份与逐文件替换发布；后续库访问恢复未完成事务。</p>
 *
 * @see MemoryLibraryLock
 * @see MemoryTransaction
 */
final class MemoryStore {
    static final String EMPTY_SUMMARY = "# Memory routes\n\nNo consolidated memories yet.\n";
    static final String EMPTY_HANDBOOK = "# Memory handbook\n\nNo consolidated memories yet.\n";
    private final Path root, workspace;
    private final com.jadecode.config.WorkspacePaths paths;
    private final String scope;
    private final Consumer<String> warnings;

    /**
     * 从工作区布局绑定记忆根、真实工作区和作用域，保存恢复告警出口，供首次 initialize 建立库结构。
     *
     * @param paths 当前工作区的真实目录与用户数据布局
     * @param warnings 接收文件或维护告警的文本出口
     */
    MemoryStore(com.jadecode.config.WorkspacePaths paths, Consumer<String> warnings) {
        this.paths = paths;
        this.root = paths.jade().memory();
        this.workspace = paths.workspace();
        this.scope = paths.id();
        this.warnings = warnings;
    }

    /**
     * 返回本库绑定的工作区数据布局，供证据读取定位对应的 sessions 目录。
     *
     * @return 此库绑定的真实工作区数据布局
     */
    com.jadecode.config.WorkspacePaths paths() {
        return paths;
    }

    /**
     * 返回正式知识、材料和维护状态共用的记忆库根目录。
     *
     * @return 正式知识、经验和维护状态共用的记忆根
     */
    Path root() {
        return root;
    }

    /**
     * 返回当前真实工作区对应的哈希作用域 ID，供作业筛选与知识分区发布使用。
     *
     * @return 当前真实工作区的哈希作用域 ID
     */
    String scope() {
        return scope;
    }

    /**
     * 返回本库绑定的真实工作区目录，供整理输入说明知识适用位置。
     *
     * @return 当前绑定的真实工作区路径
     */
    Path workspace() {
        return workspace;
    }

    /**
     * 逐级确认或创建库目录，在短锁内建立经验、候选、事件和状态目录，恢复未完成事务并补齐初始清单与空文档。
     * 已有发布版本缺失正文或无法识别的旧库内容会抛出 IOException。
     *
     * @throws IOException 目录类型不合法、事务恢复失败、旧库无法识别或已发布文件缺失
     */
    void initialize() throws IOException {
        directory(root, true);
        MemoryLibraryLock.run(root, () -> {
            for (String dir : List.of("rollout_summaries", "pending", "events", "state/jobs", "state/materials"))
                directory(root.resolve(dir), true);
            MemoryTransaction.recover(root, warnings);
            Path manifest = root.resolve("memory_manifest.json");
            if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                // 未识别旧全局库不能套空清单，否则首次整理可能覆盖用户已有手册。
                for (var entry : Map.of("MEMORY.md", EMPTY_HANDBOOK, "memory_summary.md", EMPTY_SUMMARY).entrySet()) {
                    Path existing = root.resolve(entry.getKey());
                    if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && !text(existing).equals(entry.getValue()))
                        throw new IOException("Unrecognized existing global memory; explicit import required");
                }
                create(manifest, "{\"revision\":0,\"sections\":{}}");
            } else if (json(manifest).path("revision").asLong() > 0
                    && (!Files.exists(root.resolve("MEMORY.md"), LinkOption.NOFOLLOW_LINKS)
                            || !Files.exists(root.resolve("memory_summary.md"), LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Published memory artifact is missing; review required");
            }
            create(root.resolve("MEMORY.md"), EMPTY_HANDBOOK);
            create(root.resolve("memory_summary.md"), EMPTY_SUMMARY);
            return null;
        });
    }

    /**
     * 在同一库锁内恢复未完成事务，读取并校验清单，取得总正文、当前工作区与全局分区、处理基线及已处理 ID，
     * 附上清单哈希返回一代快照。文件格式或 IO 错误向上传递。
     *
     * @return 同一代正文、分区、处理基线与清单指纹组成的快照
     * @throws IOException 未完成事务无法恢复，清单格式错误，或正式正文读取失败
     */
    Snapshot snapshot() throws IOException {
        return MemoryLibraryLock.run(root, () -> {
            MemoryTransaction.recover(root, warnings);
            JsonNode manifest = json(root.resolve("memory_manifest.json"));
            if (!manifest.path("revision").isIntegralNumber() || !manifest.path("sections").isObject())
                throw new IOException("Invalid memory manifest");
            JsonNode local = manifest.path("sections").path(scope), global = manifest.path("sections").path("global");
            return new Snapshot(manifest.path("revision").asLong(), text(root.resolve("memory_summary.md")),
                    text(root.resolve("MEMORY.md")), local.path("baseline").asText(),
                    local.path("updated_at").asText("1970-01-01T00:00:00Z"),
                    local.path("summary").asText(EMPTY_SUMMARY), local.path("handbook").asText(EMPTY_HANDBOOK),
                    global.path("summary").asText(""), global.path("handbook").asText(""),
                    java.util.stream.StreamSupport.stream(local.path("processed_notes").spliterator(), false)
                            .map(JsonNode::asText).toList(),
                    java.util.stream.StreamSupport.stream(local.path("processed_materials").spliterator(), false)
                            .map(JsonNode::asText).toList(),
                    hash(text(root.resolve("memory_manifest.json"))));
        });
    }

    /**
     * 表示锁内读取的一代正式知识，保存总正文、当前工作区与全局分区、已处理输入和版本指纹，
     * 供整理输入与发布冲突核对使用。
     *
     * @param revision 当前正式记忆的发布代数
     * @param summary 全库按作用域拼接后的正式 memory_summary.md 完整正文
     * @param handbook 全库按作用域拼接后的正式 MEMORY.md 完整正文
     * @param baseline 提示词与材料、笔记共同计算的整理基线哈希
     * @param updatedAt 当前作用域上次成功维护的时间文本
     * @param workspaceSummary 当前工作区作用域的正式短路由
     * @param workspaceHandbook 当前工作区作用域的正式手册正文
     * @param globalSummary 全局作用域的正式短路由
     * @param globalHandbook 全局作用域的正式手册正文
     * @param processedNotes 本工作区已成功确认处理的候选笔记 ID 列表
     * @param processedMaterials 本工作区已成功确认处理的经验材料 ID 列表
     * @param manifestHash 读取快照时的清单内容哈希，供发布时比较
     */
    record Snapshot(long revision, String summary, String handbook, String baseline, String updatedAt,
            String workspaceSummary, String workspaceHandbook, String globalSummary, String globalHandbook,
            List<String> processedNotes, List<String> processedMaterials, String manifestHash) {
    }

    /**
     * 保存一个作用域待发布的完整短路由和手册正文，供存储层替换该分区并重建全库文档。
     *
     * @param summary 一个作用域的完整短路由文本
     * @param handbook 一个作用域的完整详细手册正文
     */
    record Artifacts(String summary, String handbook) {
    }

    /**
     * 发布本批候选并确认输入基线，沿用快照中已有的已处理笔记与材料 ID。委托完整发布入口，返回正式正文是否变化；
     * 快照冲突或文件操作失败抛出 IOException。
     *
     * @param expected 整理开始时取得的正式快照，发布前用它核对版本与正文
     * @param changes 按作用域保存的完整候选短路由与手册正文
     * @param baseline 提示词与材料、笔记共同计算的整理基线哈希
     * @param sources 本批来源引用或来源元数据，用于构造允许清单和确认处理输入
     * @return 正式正文发生变化并成功发布时为 true；只确认处理基线时为 false
     * @throws IOException 预期快照已过期、候选跨工作区、备份或正式文件发布失败
     */
    boolean publish(Snapshot expected, Map<String, Artifacts> changes, String baseline, List<String> sources)
            throws IOException {
        return publish(expected, changes, baseline, sources, expected.processedNotes());
    }

    /**
     * 发布本批候选并登记传入的已处理笔记，沿用快照中的已处理材料 ID。委托完整发布入口，返回正式正文是否变化，
     * 失败以 IOException 报告。
     *
     * @param expected 整理开始时取得的正式快照，发布前用它核对版本与正文
     * @param changes 按作用域保存的完整候选短路由与手册正文
     * @param baseline 提示词与材料、笔记共同计算的整理基线哈希
     * @param sources 本批来源引用或来源元数据，用于构造允许清单和确认处理输入
     * @param processedNotes 本批发布成功后确认已处理的候选笔记 ID
     * @return 正式正文发生变化并成功发布时为 true；只确认处理基线时为 false
     * @throws IOException 预期快照已过期、候选跨工作区、备份或正式文件发布失败
     */
    boolean publish(Snapshot expected, Map<String, Artifacts> changes, String baseline, List<String> sources,
            List<String> processedNotes) throws IOException {
        return publish(expected, changes, baseline, sources, processedNotes, expected.processedMaterials());
    }

    /**
     * 在短库锁内发布候选知识，并确认本批来源和笔记已处理。
     *
     * <p>先恢复未完成事务，再核对 expected 的 revision、清单哈希和两份正文。校验当前工作区或 global 候选，
     * 更新对应分区、处理基线及 ID，并推进 revision。正文相同时原子更新清单并返回 false；
     * 正文变化时按 scope 排序重建全库短路由和手册，保留其他分区。</p>
     * <p>有正文变化时先准备新旧备份，再逐文件原子替换并写入事务完成标记，成功返回 true。
     * 替换失败尝试回滚，快照冲突、范围错误或文件操作失败以 IOException 返回。</p>
     *
     * @param expected 整理开始时取得的正式快照，发布前用它核对版本与正文
     * @param changes 按作用域保存的完整候选短路由与手册正文
     * @param baseline 提示词与材料、笔记共同计算的整理基线哈希
     * @param sources 本批来源引用或来源元数据，用于构造允许清单和确认处理输入
     * @param processedNotes 本批发布成功后确认已处理的候选笔记 ID
     * @param processedMaterials 本批发布成功后确认已处理的经验材料 ID
     * @return 正式正文发生变化并成功发布时为 true；只确认处理基线时为 false
     * @throws IOException 预期快照已过期、候选跨工作区、备份或正式文件发布失败
     */
    boolean publish(Snapshot expected, Map<String, Artifacts> changes, String baseline, List<String> sources,
            List<String> processedNotes, List<String> processedMaterials) throws IOException {
        return MemoryLibraryLock.run(root, () -> {
            MemoryTransaction.recover(root, warnings);
            Path manifest = root.resolve("memory_manifest.json"), shortFile = root.resolve("memory_summary.md");
            JsonNode current = json(manifest);
            if (current.path("revision").asLong() != expected.revision()
                    || !hash(text(manifest)).equals(expected.manifestHash())
                    || !text(shortFile).equals(expected.summary())
                    || !text(root.resolve("MEMORY.md")).equals(expected.handbook()))
                throw new IOException("Memory changed while consolidation was running");
            var next = (ObjectNode) current.deepCopy();
            next.put("revision", expected.revision() + 1);
            ObjectNode sections = (ObjectNode) next.path("sections");
            boolean contentChanged = false;
            // 仅允许本工作区及明确的全局用户知识变化，其它项目的块由程序原样保留。
            for (var change : changes.entrySet()) {
                if (!change.getKey().equals(scope) && !change.getKey().equals("global"))
                    throw new IOException("Cannot edit another workspace's memory");
                JsonNode previous = sections.path(change.getKey());
                // 模型可能原样重发一个完整分区；这不构成新的正文，不应触发文件重写。
                if (previous.path("summary").asText().equals(change.getValue().summary())
                        && previous.path("handbook").asText().equals(change.getValue().handbook()))
                    continue;
                contentChanged = true;
                ObjectNode section = sections.putObject(change.getKey());
                section.put("summary", change.getValue().summary()).put("handbook", change.getValue().handbook())
                        .put("workspace", change.getKey().equals("global") ? "global" : workspace.toString())
                        .put("updated_at", Instant.now().toString()).put("baseline", baseline);
                section.set("sources", Json.mapper().valueToTree(sources));
            }
            // 即使没有正文改动也保存本工作区的处理基线，避免同一份材料反复调用模型。
            ObjectNode local = sections.path(scope) instanceof ObjectNode object ? object : sections.putObject(scope);
            local.put("baseline", baseline).put("workspace", workspace.toString()).put("updated_at",
                    Instant.now().toString());
            local.set("processed_notes", Json.mapper().valueToTree(processedNotes));
            local.set("processed_materials", Json.mapper().valueToTree(processedMaterials));
            if (!contentChanged) {
                // 清单是一个原子文件；另外两份文件保持原样，读者仍在库锁内读取一致快照。
                replace(manifest, next.toString());
                return false;
            }
            StringBuilder summary = new StringBuilder("# Memory routes\n");
            StringBuilder handbook = new StringBuilder("# Memory handbook\n");
            var names = new ArrayList<String>();
            sections.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                JsonNode section = sections.get(name);
                // 处理基线可能存在而正式知识为空，不能把任务处理元数据渲染成记忆。
                if (section.path("summary").asText().isBlank() && section.path("handbook").asText().isBlank())
                    continue;
                String heading = "\n## Scope: " + name + "\nWorkspace: " + section.path("workspace").asText() + "\n\n";
                summary.append(heading).append(section.path("summary").asText());
                handbook.append(heading).append(section.path("handbook").asText());
            }
            Map<String, String> originals = Map.of("memory_summary.md", expected.summary(), "memory_manifest.json",
                    text(manifest));
            Map<String, String> replacements = Map.of("memory_summary.md", summary.toString(), "memory_manifest.json",
                    next.toString());
            var transaction = MemoryTransaction.prepare(root, originals, replacements, handbook.toString());
            try {
                for (var item : replacements.entrySet())
                    MemoryTransaction.atomicReplace(root.resolve(item.getKey()), item.getValue());
                MemoryTransaction.atomicReplace(root.resolve("MEMORY.md"), handbook.toString());
                transaction.commit();
            } catch (IOException | RuntimeException failure) {
                try {
                    transaction.rollback();
                } catch (IOException recovery) {
                    failure.addSuppressed(recovery);
                }
                throw failure;
            }
            return true;
        });
    }

    /**
     * 按作业哈希 ID 在短锁内创建 JSON 文件；已有同 ID 作业保持原状态，供重复轮次登记去重。
     *
     * @param job 提取作业的完整 JSON 状态，包含 ID、作用域、事件区间和重试进度
     * @throws IOException 作业 ID 或目标类型不合法，或原子创建失败
     */
    void enqueue(ObjectNode job) throws IOException {
        MemoryLibraryLock.run(root, () -> {
            create(root.resolve("state/jobs/" + id(job.path("id").asText()) + ".json"), job.toString());
            return null;
        });
    }

    /**
     * 在短锁内扫描持久提取作业，返回独立 JSON 对象列表，供维护者筛选与接管积压任务。
     *
     * @return 按文件名扫描得到的独立作业 JSON 对象列表
     * @throws IOException 作业目录或某份 JSON 状态无法读取
     */
    List<ObjectNode> jobs() throws IOException {
        return MemoryLibraryLock.run(root, () -> nodes(root.resolve("state/jobs")));
    }

    /**
     * 在短锁内原子替换指定 ID 的作业状态，保存 running、done、retry 和尝试次数等处理进度。
     *
     * @param job 提取作业的完整 JSON 状态，包含 ID、作用域、事件区间和重试进度
     * @throws IOException 作业 ID 不合法或状态原子替换失败
     */
    void saveJob(ObjectNode job) throws IOException {
        MemoryLibraryLock.run(root, () -> {
            replace(root.resolve("state/jobs/" + id(job.path("id").asText()) + ".json"), job.toString());
            return null;
        });
    }

    /**
     * 按固定 ID 先保存经验 Markdown，再保存来源元数据，使完整元数据成为材料可见入口。相同内容的重试复用已有文件，
     * 内容冲突抛出 IOException。
     *
     * @param materialId 经验材料的 64 位小写十六进制身份
     * @param summary 一个作用域的完整短路由文本
     * @param metadata 当前会话或经验来源的持久元数据
     * @throws IOException 同 ID 已有内容与新材料冲突，或来源正文、元数据写入失败
     */
    void material(String materialId, String summary, ObjectNode metadata) throws IOException {
        String name = id(materialId);
        MemoryLibraryLock.run(root, () -> {
            immutable(root.resolve("rollout_summaries/" + name + ".md"), summary);
            Path target = root.resolve("state/materials/" + name + ".json");
            String encoded = metadata.toString();
            immutable(target, encoded);
            return null;
        });
    }

    /**
     * 在短锁内读取来源元数据，筛选当前工作区及 global 作用域，返回本次整理可使用的材料列表。
     *
     * @return 当前工作区和 global 的来源元数据列表
     * @throws IOException 来源目录或元数据文件读取解析失败
     */
    List<ObjectNode> materials() throws IOException {
        return MemoryLibraryLock.run(root, () -> nodes(root.resolve("state/materials")).stream()
                .filter(n -> n.path("scope").asText().equals(scope) || n.path("scope").asText().equals("global"))
                .toList());
    }

    /**
     * 将候选类型、正文、用户原话与会话轮次组成笔记，按固定 ID 保存到 pending。相同 ID 和内容复用，内容冲突时报错；
     * 返回候选 ID，供后续整理确认处理。
     *
     * @param noteId 候选笔记的 64 位小写十六进制身份
     * @param kind 候选类型，remember、correct 或 forget
     * @param body 候选记忆正文，保存时已经完成来源绑定与脱敏
     * @param userRequest 随候选保存的真实用户原话证据
     * @param session 本轮所属的持久会话 ID
     * @param turn 提交候选笔记的用户轮次 ID
     * @return 已持久保存的候选笔记 ID
     * @throws IOException 候选 ID 不合法、同 ID 内容冲突或写入失败
     */
    String note(String noteId, String kind, String body, String userRequest, String session, String turn)
            throws IOException {
        String name = id(noteId);
        ObjectNode note = Json.mapper().createObjectNode();
        note.put("id", name).put("scope", scope).put("kind", kind).put("body", body).put("user_request", userRequest)
                .put("session_id", session).put("turn_id", turn);
        MemoryLibraryLock.run(root, () -> {
            immutable(root.resolve("pending/" + name + ".json"), note.toString());
            return null;
        });
        return name;
    }

    /**
     * 读取 pending 中当前工作区及 global 的候选笔记，返回列表；正式快照中的 processedNotes 用于后续筛选尚待处理项。
     *
     * @return 当前工作区和 global 的候选笔记列表
     * @throws IOException 候选目录或 JSON 对象读取解析失败
     */
    List<ObjectNode> notes() throws IOException {
        return MemoryLibraryLock.run(root, () -> nodes(root.resolve("pending")).stream()
                .filter(n -> n.path("scope").asText().equals(scope) || n.path("scope").asText().equals("global"))
                .toList());
    }

    /**
     * 在短锁内将一条反馈 JSON 追加到 feedback.jsonl 并刷新文件，保存后续整理所需的读取、引用或候选事件。
     * event_id 由调用方提供，汇总读取时负责去重。
     *
     * @param event 带稳定 event_id 的读取、引用或候选反馈 JSON
     * @throws IOException 反馈日志追加刷新或读取失败
     */
    void feedback(ObjectNode event) throws IOException {
        MemoryLibraryLock.run(root, () -> {
            Path path = root.resolve("events/feedback.jsonl");
            plainIfExists(path);
            try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                var bytes = StandardCharsets.UTF_8.encode(event.toString() + "\n");
                while (bytes.hasRemaining())
                    channel.write(bytes);
                channel.force(true);
            }
            return null;
        });
    }

    /**
     * 在短锁内读取反馈日志，以 event_id 保留首次出现的事件并返回独立列表。日志缺失返回空列表，
     * 格式错误的行产生告警后跳过。
     *
     * @return 按 event_id 去重的反馈列表；反馈文件不存在时为空列表
     * @throws IOException 反馈日志追加刷新或读取失败
     */
    List<ObjectNode> feedback() throws IOException {
        return MemoryLibraryLock.run(root, () -> {
            Path path = root.resolve("events/feedback.jsonl");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                return List.of();
            var unique = new LinkedHashMap<String, ObjectNode>();
            for (String line : text(path).lines().toList()) {
                try {
                    JsonNode node = Json.mapper().readTree(line);
                    if (node instanceof ObjectNode object && object.path("event_id").isTextual())
                        unique.putIfAbsent(object.path("event_id").asText(), object);
                } catch (IOException malformed) {
                    warnings.accept("[Warning] [Memory] Ignored a malformed feedback event");
                }
            }
            return List.copyOf(unique.values());
        });
    }

    /**
     * 检查目标属于正式手册、短路由或哈希命名的经验 Markdown，并检查父目录与普通文件类型。符合可读布局时返回 true，
     * 其他路径或 IO 错误返回 false。
     *
     * @param path 记忆库中的候选读取或写入路径，按方法说明核对布局和文件类型
     * @return 路径、目录和文件类型均满足记忆读取规则时为 true
     */
    boolean readable(Path path) {
        Path p = path.toAbsolutePath().normalize();
        if (!p.startsWith(root))
            return false;
        String relative = root.relativize(p).toString().replace('\\', '/');
        if (!relative.equals("MEMORY.md") && !relative.equals("memory_summary.md")
                && !relative.matches("rollout_summaries/[a-f0-9]{64}\\.md"))
            return false;
        try {
            directory(p.getParent(), false);
            plainIfExists(p);
            return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException invalid) {
            return false;
        }
    }

    /**
     * 按固定格式解析一份 rollout 或 pending 相对来源名，读取普通文件；
     * pending 额外核对内部 ID、类型、正文、用户原话和 scope。返回来源文本，名称或结构非法时抛出 IOException；
     * 本次可用范围由维护者的来源清单筛选。
     *
     * @param relative 记忆库内符合固定格式的来源相对路径
     * @return 通过固定命名与来源结构检查的完整正文
     * @throws IOException 来源名称或候选结构不合法，或目标文件无法读取
     */
    String readSource(String relative) throws IOException {
        if (!relative.matches("(?:rollout_summaries/[a-f0-9]{64}\\.md|pending/[a-f0-9]{64}\\.json)"))
            throw new IOException("Invalid memory source reference");
        Path file = root.resolve(relative);
        directory(file.getParent(), false);
        String body = text(file);
        if (relative.startsWith("pending/")) {
            JsonNode note = Json.mapper().readTree(body);
            String expected = relative.substring("pending/".length(), relative.length() - ".json".length());
            if (note == null || !note.isObject() || !note.path("id").asText().equals(expected)
                    || !Set.of("remember", "correct", "forget").contains(note.path("kind").asText())
                    || !note.path("body").isTextual() || !note.path("user_request").isTextual()
                    || !note.path("scope").asText().matches("(?:global|[a-f0-9]{64})"))
                throw new IOException("Invalid pending memory source");
        }
        return body;
    }

    /**
     * 读取当前 scope 的整理尝试状态，返回 baseline、attempts、retry_at 等字段；状态文件缺失时返回空对象，
     * 格式错误抛出 IOException。
     *
     * @return 当前 scope 的整理尝试状态；尚无文件时为空对象
     * @throws IOException 当前 scope 状态文件格式错误或读写失败
     */
    ObjectNode maintenanceState() throws IOException {
        return MemoryLibraryLock.run(root, () -> {
            Path path = root.resolve("state/maintenance-" + scope + ".json");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                return Json.mapper().createObjectNode();
            JsonNode node = json(path);
            if (!(node instanceof ObjectNode object))
                throw new IOException("Invalid maintenance retry state");
            return object;
        });
    }

    /**
     * 在短库锁内原子保存当前 scope 的整理状态，持久记录尝试次数、下次处理时间和发布结果；
     * 调用方在维护锁内协调同一基线的处理。
     *
     * @param state 当前作用域的整理基线、尝试次数和下一次重试时间
     * @throws IOException 当前 scope 状态文件格式错误或读写失败
     */
    void maintenanceState(ObjectNode state) throws IOException {
        MemoryLibraryLock.run(root, () -> {
            Path path = root.resolve("state/maintenance-" + scope + ".json");
            plainIfExists(path);
            replace(path, state.toString());
            return null;
        });
    }

    /**
     * 按文件名排序扫描指定目录的 JSON 普通文件，逐项解析为对象，返回不可变列表。
     * 目录、文件或对象格式错误以 IOException 返回。
     *
     * @param dir 本次扫描的记忆状态或材料目录
     * @return 按文件名排序的 JSON 对象不可变列表
     * @throws IOException 目录不可访问、状态文件不是有效 JSON 对象或读取失败
     */
    private static List<ObjectNode> nodes(Path dir) throws IOException {
        directory(dir, false);
        var result = new ArrayList<ObjectNode>();
        try (var files = Files.list(dir)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                JsonNode value = json(path);
                if (!(value instanceof ObjectNode object))
                    throw new IOException("Invalid memory state object");
                result.add(object);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 从绝对路径根开始逐级核对普通目录，create 为 true 时为缺失级创建私有目录，并接纳并发创建的同名目录。
     * 链接或其他类型报错，create 为 false 且目录缺失时抛出 NoSuchFileException。
     *
     * @param path 记忆库中的候选读取或写入路径，按方法说明核对布局和文件类型
     * @param create 是否为缺失路径创建目录
     * @throws IOException 路径包含链接或非目录，目录缺失且未允许创建，或创建失败
     */
    static void directory(Path path, boolean create) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Memory path contains a non-directory or symlink");
            } else if (create) {
                try {
                    try {
                        Files.createDirectory(cursor, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
                    } catch (UnsupportedOperationException unsupported) {
                        Files.createDirectory(cursor);
                    }
                } catch (FileAlreadyExistsException raced) {
                    if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))
                        throw raced;
                }
            } else
                throw new NoSuchFileException(cursor.toString());
        }
    }

    /**
     * 校验目标为普通文件并检查 8000000 字节读取上限，再以 UTF-8 返回完整正文；超限或读取失败抛出 IOException。
     *
     * @param path 记忆库中的候选读取或写入路径，按方法说明核对布局和文件类型
     * @return 通过类型与大小检查的完整 UTF-8 文本
     * @throws IOException 目标不是普通文件、超过读取字节预算或 UTF-8 读取失败
     */
    static String text(Path path) throws IOException {
        plainIfExists(path);
        if (Files.size(path) > 8_000_000)
            throw new IOException("Memory file exceeds the input limit");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 按记忆文件的读取规则取得正文并解析 JSON；空内容抛出 IOException，返回的节点供作业、清单和来源校验使用。
     *
     * @param path 记忆库中的候选读取或写入路径，按方法说明核对布局和文件类型
     * @return 文件正文解析得到的非空 JSON 节点
     * @throws IOException 文本读取失败、JSON 格式错误或解析结果为空
     */
    static JsonNode json(Path path) throws IOException {
        JsonNode value = Json.mapper().readTree(text(path));
        if (value == null)
            throw new IOException("Empty memory state");
        return value;
    }

    /**
     * 目标已存在时检查其为普通文件；目录、链接等类型抛出 IOException，尚未创建的路径可继续用于新文件发布。
     *
     * @param path 记忆库中的候选读取或写入路径，按方法说明核对布局和文件类型
     * @throws IOException 目标已存在且不是普通文件
     */
    private static void plainIfExists(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Memory path is not a regular file");
    }

    /**
     * 调用方持有库锁时创建缺失文件：先确认路径类型，已有普通文件保持原内容，缺失文件通过 replace 完整发布。
     * 用于初始化和同 ID 作业去重。
     *
     * @param path 记忆库中的候选读取或写入路径，按方法说明核对布局和文件类型
     * @param content 本次准备保存到目标文件的完整 UTF-8 文本
     * @throws IOException 目标类型不合法或缺失文件原子写入失败
     */
    private static void create(Path path, String content) throws IOException {
        plainIfExists(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            return;
        // 调用者持库锁；先写临时完整文件再原子发布，崩溃不能留下半份作业 JSON。
        replace(path, content);
    }

    /**
     * 将内容通过同目录临时文件原子替换到目标，再刷新父目录，供作业、笔记和维护状态持久写入使用。
     *
     * @param path 记忆库中的候选读取或写入路径，按方法说明核对布局和文件类型
     * @param content 本次准备保存到目标文件的完整 UTF-8 文本
     * @throws IOException 临时文件写入、原子替换或父目录刷新失败
     */
    private static void replace(Path path, String content) throws IOException {
        MemoryTransaction.atomicReplace(path, content);
        try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    /**
     * 保存内容固定的经验来源：文件缺失时创建，已有且内容一致时复用，已有内容与本次不同则抛出 IOException。
     *
     * @param path 记忆库中的候选读取或写入路径，按方法说明核对布局和文件类型
     * @param content 本次准备保存到目标文件的完整 UTF-8 文本
     * @throws IOException 已有文件正文与输入不一致，或新文件发布失败
     */
    private static void immutable(Path path, String content) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!text(path).equals(content))
                throw new IOException("Immutable memory evidence changed");
        } else
            create(path, content);
    }

    /**
     * 校验作业或材料身份是 64 位小写十六进制哈希，成功返回原 ID，格式不匹配时抛出 IOException。
     *
     * @param id 用于作业或材料文件名的 64 位小写十六进制哈希
     * @return 通过格式检查的原始 64 位哈希 ID
     * @throws IOException 身份不是 64 位小写十六进制字符串
     */
    private static String id(String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid memory identity");
        return id;
    }

    /**
     * 对 UTF-8 文本计算小写十六进制 SHA-256，供作业命名、版本比较和去重使用。
     *
     * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
     * @return 文本 UTF-8 内容对应的小写十六进制 SHA-256
     * @throws IllegalStateException 当前 Java 运行环境无法提供 SHA-256 算法
     */
    static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
