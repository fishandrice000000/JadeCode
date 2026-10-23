package com.jadecode.tui.theme;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.jadecode.tui.text.Color;

/**
 * 主题选择状态：逻辑标识、文件身份、预览/确认、终端晚回复、文件监听与 revision。
 *
 * <p>第九轮把“用户选了什么”和“最终解析成什么颜色”分开保存：{@link Selection} 只记录
 * 逻辑标识、来源（system/内置变体/auto 配对/自定义文件）与该来源的不可变
 * {@link ThemeDocument}；每次解析都由 {@link ThemeResolver} 按当前终端事实重新展开，
 * 因此终端晚回复不会丢显式颜色，也不会用某个展开后的调色板名反推用户设置。</p>
 *
 * <p>线程与所有权：</p>
 * <ul>
 *   <li>committed/preview/revision/watch 目标只在 UI 线程写；</li>
 *   <li>预览/监听的文件读取与 JSON 解析在 worker 完成，结果通过不可变
 *       {@link LoadResult}/{@link WatchResult} 回到 UI 线程采纳；</li>
 *   <li>ticket 校验拒绝迟到/旧候选，watch generation 校验拒绝切走后的旧文件事件；</li>
 *   <li>坏文件保留最后有效快照并返回英文错误，不静默套用错误颜色。</li>
 * </ul>
 *
 * <p>本类不写终端、不改配置文件，也不持有编辑器等组件。</p>
 */
public final class ThemeManager implements AutoCloseable {
    /** 主题来源：决定变体选择与是否绑定终端前景/背景。 */
    public enum Kind {
        /** 内置统一文件，按终端明暗选变体并绑定真实前景/背景（S92）。 */
        SYSTEM,
        /** 显式 dark/light，固定变体，不随终端明暗改变。 */
        BUILTIN,
        /** auto：按终端明暗取配置的配对主题。 */
        AUTO,
        /** 自定义名称/路径；v2 按终端明暗选变体，但不绑定 system 前景。 */
        CUSTOM
    }

    /**
     * 用户选择对应的源与文档（不含展开后的颜色）。
     *
     * @param kind 来源类型
     * @param logicalId 用户设置里的逻辑标识（system/auto/dark/light/自定义名）
     * @param sourceId 实际生效来源名；auto 为配对选中的主题名
     * @param file 自定义文件路径；内置/system 为 null
     * @param document 该来源的不可变文档
     */
    public record Selection(Kind kind, String logicalId, String sourceId, Path file, ThemeDocument document) { }

    /**
     * 一次后台读取请求。
     *
     * @param ticket 单调请求号，用于拒绝迟到结果
     * @param logicalId 用户选择的逻辑标识
     * @param file 需要读取的文件；内置资源时为 null
     * @param builtin 是否读取内置统一资源
     */
    public record LoadRequest(long ticket, String logicalId, Path file, boolean builtin) { }

    /**
     * 后台读取结果。
     *
     * @param request 原始请求
     * @param document 解析成功的文档；失败为 null
     * @param error 英文错误；成功为 null
     */
    public record LoadResult(LoadRequest request, ThemeDocument document, String error) { }

    /**
     * 当前自定义文件变化通知。
     *
     * @param generation watch 目标代次，切换主题后旧事件作废
     * @param file 被修改的文件
     * @param document 重新解析成功的文档；坏文件为 null
     * @param error 英文错误；成功为 null
     */
    public record WatchResult(long generation, Path file, ThemeDocument document, String error) { }

    /** 解析结果：实际生效的选择（auto 可能换源）与对应快照。 */
    private record Resolved(Selection selection, Theme theme) { }

    private final boolean colorsAvailable;
    private final Path customDirectory;
    /** 终端事实；只影响 system 绑定、变体选择和派生方向。 */
    private ThemeResolver.TerminalFacts facts = new ThemeResolver.TerminalFacts(true, null, null);
    private String lightTheme;
    private String darkTheme;
    private Selection committedSelection;
    private Selection previewSelection;
    private Theme committed;
    private Theme preview;
    private long revision;
    /** 单调请求号与“当前有效的预览请求”；两者分离才能识别迟到结果。 */
    private long nextTicket;
    private long previewTicket;
    /** 已解析文档缓存：避免终端晚回复反复读盘；watch/preview 成功会刷新。 */
    private final Map<Path, ThemeDocument> documents = new ConcurrentHashMap<>();
    private volatile Path watchTarget;
    private volatile long watchGeneration;
    private Thread watcher;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param name 已配置主题：system/dark/light/auto 或自定义标识
     * @param customDirectory 配置同级 themes 目录，可为 null（只允许内置主题）
     * @param colorsAvailable 终端是否允许颜色（NO_COLOR/dumb 为 false）
     * @param lightTheme auto 模式的浅色主题名
     * @param darkTheme auto 模式的深色主题名
     */
    public ThemeManager(String name, Path customDirectory, boolean colorsAvailable,
            String lightTheme, String darkTheme) {
        this.colorsAvailable = colorsAvailable;
        this.customDirectory = customDirectory;
        this.lightTheme = pairName(lightTheme, ThemeDocument.LIGHT);
        this.darkTheme = pairName(darkTheme, ThemeDocument.DARK);
        this.previewTicket = ++nextTicket;
        // 启动尚未进入 UI 循环，小文件/内置资源同步加载允许；之后运行期读取走 worker。
        Resolved initial = resolve(loadSelection(name, false));
        this.committedSelection = initial.selection();
        this.committed = initial.theme();
        this.previewSelection = committedSelection;
        this.preview = committed;
        retarget();
    }

    /** @return 当前应绘制的主题（预览优先）。 */
    public Theme preview() { return preview; }

    /** @return 用户上次确认/启动时的主题。 */
    public Theme committed() { return committed; }

    /** @return 主题 revision；任何可见变化都会递增。 */
    public long revision() { return revision; }

    /** @return 当前确认的逻辑主题标识（system/auto/自定义），不因预览或变体改变。 */
    public String logicalName() { return committedSelection.logicalId(); }

    /**
     * 开始一轮预览：记录恢复点并让此前在途请求失效。只影响内存。
     */
    public void beginPreview() {
        previewTicket = ++nextTicket;
        previewSelection = committedSelection;
        preview = committed;
        retarget();
    }

    /**
     * 放弃未提交预览，整体恢复已确认选择（逻辑标识、来源、文档与颜色）。
     */
    public void cancelPreview() {
        previewTicket = ++nextTicket;
        previewSelection = committedSelection;
        preview = committed;
        retarget();
    }

    /**
     * 确认当前预览。
     *
     * <p>必须同步内存确认的逻辑标识与 Selection，否则之后的 setPair/reload 会按旧标识
     * 重载回原来主题；写盘成功与否由调用方负责，本方法不代表已持久化。</p>
     */
    public void applyPreview() {
        previewTicket = ++nextTicket;
        if (previewSelection == null || preview == null) return;
        committedSelection = previewSelection;
        committed = adopt(committed, preview);
        retarget();
    }

    /**
     * 登记一次预览读取请求。调用方应在 worker 读取/解析后把结果交给
     * {@link #acceptPreview(LoadResult)}；本方法只分配 ticket 与解析来源，不做 IO。
     *
     * @param identifier 用户候选标识
     * @return 后台读取请求
     * @throws IllegalArgumentException 自定义目录缺失/标识非法
     */
    public LoadRequest requestPreview(String identifier) {
        String id = normalize(identifier);
        long ticket = ++nextTicket;
        previewTicket = ticket;
        Source source = sourceFor(id);
        return new LoadRequest(ticket, id, source.file(), source.builtin());
    }

    /**
     * 采纳后台预览结果。迟到（ticket 已被新请求/确认/取消取代）结果直接忽略。
     *
     * @param result 不可变读取结果
     * @return 成功为空串；失败为英文错误并回退到已确认主题
     */
    public String acceptPreview(LoadResult result) {
        if (result == null || result.request() == null) return "";
        LoadRequest request = result.request();
        if (request.ticket() != previewTicket) return "";
        if (result.document() == null) {
            // 失败后作废该 ticket，并把预览整体退回已确认快照（不提交任何逻辑标识）。
            previewTicket = ++nextTicket;
            previewSelection = committedSelection;
            preview = committed;
            retarget();
            return result.error() == null ? "Cannot read theme: empty result" : result.error();
        }
        Resolved resolved = resolve(selectionFor(request.logicalId(), request.file(), result.document()));
        previewSelection = resolved.selection();
        preview = adopt(preview, resolved.theme());
        retarget();
        return "";
    }

    /**
     * 兼容入口：同步预览（仅用于小文件与旧调用/测试）。生产 UI 预览走
     * {@link #requestPreview(String)} + worker + {@link #acceptPreview(LoadResult)}。
     */
    public String preview(String identifier) {
        LoadRequest request = requestPreview(identifier);
        try {
            ThemeDocument document = request.builtin()
                    ? ThemePalette.builtinDocument() : ThemeDocument.read(request.file());
            return acceptPreview(new LoadResult(request, document, null));
        } catch (RuntimeException failure) {
            return acceptPreview(new LoadResult(request, null, failure.getMessage()));
        }
    }

    /** @return 当前预览是否正好对应该逻辑标识且没有更新的在途请求。 */
    public boolean previewMatches(String identifier) {
        return previewSelection != null
                && previewSelection.logicalId().equals(normalize(identifier))
                && previewTicket == nextTicket;
    }

    /**
     * 终端上报默认前景/背景后更新解析。system 重新绑定普通文字；其他主题只按新明暗重选
     * 变体/配对并重算派生色，显式颜色不会丢失。颜色没变时不增加 revision。
     *
     * @param dark 终端背景是否为深色
     * @param foreground 真实前景；null 表示未报告
     * @param background 真实背景；null 表示未报告
     * @param ansi 0–15 ANSI 色；S92 后产品取色不再使用，保留参数仅为终端查询兼容
     */
    public void updateTerminalColors(boolean dark, Color foreground, Color background, List<Color> ansi) {
        this.facts = new ThemeResolver.TerminalFacts(dark, foreground, background);
        Resolved nextCommitted = resolve(committedSelection);
        committedSelection = nextCommitted.selection();
        committed = adopt(committed, nextCommitted.theme());
        Resolved nextPreview = resolve(previewSelection);
        previewSelection = nextPreview.selection();
        preview = adopt(preview, nextPreview.theme());
        // auto 配对或 v2 变体可能换文件，监听目标随之切换。
        retarget();
    }

    /** 由外部设置变更安装新主题名（例如其它窗口写入配置后的刷新）。 */
    public void reload(String name) {
        previewTicket = ++nextTicket;
        Resolved resolved = resolve(loadSelection(name, true));
        committedSelection = resolved.selection();
        committed = adopt(committed, resolved.theme());
        previewSelection = committedSelection;
        preview = committed;
        retarget();
    }

    /**
     * 更新 auto 的 light/dark 配对并按新配对重解析；逻辑标识不被解析结果反写。
     */
    public void setPair(String light, String dark) {
        lightTheme = pairName(light, ThemeDocument.LIGHT);
        darkTheme = pairName(dark, ThemeDocument.DARK);
        Resolved nextCommitted = resolve(committedSelection);
        committedSelection = nextCommitted.selection();
        committed = adopt(committed, nextCommitted.theme());
        Resolved nextPreview = resolve(previewSelection);
        previewSelection = nextPreview.selection();
        preview = adopt(preview, nextPreview.theme());
        retarget();
    }

    /**
     * 启动唯一定制文件监听线程；目标随当前预览/确认来源切换，worker 只读文件并回传不可变
     * 结果，由 UI 线程调用 {@link #acceptWatch(WatchResult)} 采纳。
     *
     * <p>内置 JAR 资源没有可监听文件；system/内置变体不安装监听。重复调用忽略。</p>
     *
     * @param sink 结果回调（通常是把事件投递给 UI 线程的事件队列）
     */
    public void watch(Consumer<WatchResult> sink) {
        if (sink == null || watcher != null) return;
        watcher = Thread.ofVirtual().name("jade-theme-watch").start(() -> runWatch(sink));
    }

    /**
     * 采纳一次文件变化结果。仅当目标文件与代次仍匹配时生效；坏文件返回错误并保留最后有效
     * 快照，恢复有效文件后继续正常工作。
     *
     * @param result watch 结果
     * @return 成功或迟到为空串；失败为英文错误
     */
    public String acceptWatch(WatchResult result) {
        if (result == null) return "";
        Path target = watchTarget;
        if (target == null || result.generation() != watchGeneration || !target.equals(result.file()))
            return "";
        if (result.document() == null)
            return result.error() == null ? "Cannot read theme file" : result.error();
        documents.put(result.file(), result.document());
        if (matches(committedSelection, result.file()))
            committedSelection = withDocument(committedSelection, result.document());
        if (matches(previewSelection, result.file()))
            previewSelection = withDocument(previewSelection, result.document());
        Resolved nextCommitted = resolve(committedSelection);
        committedSelection = nextCommitted.selection();
        committed = adopt(committed, nextCommitted.theme());
        Resolved nextPreview = resolve(previewSelection);
        previewSelection = nextPreview.selection();
        preview = adopt(preview, nextPreview.theme());
        return "";
    }

    /** 列出可用的自定义主题名（不含扩展名），按文件名排序；读取失败返回空表。 */
    public static List<String> customThemes(Path directory) {
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) return List.of();
        try (var files = java.nio.file.Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted().toList();
        } catch (IOException failure) { return List.of(); }
    }

    /** 可测试的 token 快照；不包含未解析的 JSON。 */
    public Map<String, Color> colors() { return committed.colors(); }

    @Override public void close() {
        closed.set(true);
        if (watcher != null) watcher.interrupt();
    }

    // ===== 解析与来源 =====

    /** 后台读取请求的来源；抽成小 record 避免在 requestPreview 里分枝。 */
    private record Source(Path file, boolean builtin) { }

    private Source sourceFor(String id) {
        if (id.equals("system") || id.equals(ThemeDocument.DARK) || id.equals(ThemeDocument.LIGHT))
            return new Source(null, true);
        String choice = id.equals("auto") ? pairChoice() : id;
        if (choice.equals(ThemeDocument.DARK) || choice.equals(ThemeDocument.LIGHT))
            return new Source(null, true);
        return new Source(customFile(choice), false);
    }

    /** @return 当前终端明暗对应的配对主题名。 */
    private String pairChoice() { return facts.dark() ? darkTheme : lightTheme; }

    /**
     * 把逻辑标识解析成完整选择（读取文档，可能紧张地读一次小文件）。
     *
     * @param logicalId 用户标识
     * @param fresh true 时强制重读自定义文件（外部配置刷新）
     */
    private Selection loadSelection(String logicalId, boolean fresh) {
        String id = normalize(logicalId);
        if (id.equals("system"))
            return new Selection(Kind.SYSTEM, id, id, null, ThemePalette.builtinDocument());
        if (id.equals(ThemeDocument.DARK) || id.equals(ThemeDocument.LIGHT))
            return new Selection(Kind.BUILTIN, id, id, null, ThemePalette.builtinDocument());
        if (id.equals("auto")) {
            String choice = pairChoice();
            if (choice.equals(ThemeDocument.DARK) || choice.equals(ThemeDocument.LIGHT))
                return new Selection(Kind.AUTO, id, choice, null, ThemePalette.builtinDocument());
            Path file = customFile(choice);
            return new Selection(Kind.AUTO, id, choice, file, readDocument(file, fresh));
        }
        Path file = customFile(id);
        return new Selection(Kind.CUSTOM, id, id, file, readDocument(file, fresh));
    }

    /** 后台/启动期加载完成的文档 → Selection。 */
    private Selection selectionFor(String logicalId, Path file, ThemeDocument document) {
        String id = normalize(logicalId);
        if (id.equals("system")) return new Selection(Kind.SYSTEM, id, id, null, document);
        if (id.equals(ThemeDocument.DARK) || id.equals(ThemeDocument.LIGHT))
            return new Selection(Kind.BUILTIN, id, id, null, document);
        if (id.equals("auto")) {
            String choice = pairChoice();
            boolean builtin = choice.equals(ThemeDocument.DARK) || choice.equals(ThemeDocument.LIGHT);
            return new Selection(Kind.AUTO, id, choice, builtin ? null : file, document);
        }
        return new Selection(Kind.CUSTOM, id, id, file, document);
    }

    /**
     * 解析当前选择：auto 目标变化时换源，system 只跟随终端明暗，固定变体不被终端改变。
     */
    private Resolved resolve(Selection selection) {
        Selection effective = selection;
        if (selection.kind() == Kind.AUTO && !pairChoice().equals(selection.sourceId()))
            effective = loadSelection("auto", false);
        ThemeDocument document = effective.document();
        String variant;
        String displayName;
        if (effective.kind() == Kind.BUILTIN) {
            variant = effective.sourceId();
            displayName = effective.sourceId();
        } else if (effective.kind() == Kind.SYSTEM) {
            variant = facts.dark() ? ThemeDocument.DARK : ThemeDocument.LIGHT;
            displayName = "system";
        } else {
            variant = document.isVersion2()
                    ? (facts.dark() ? ThemeDocument.DARK : ThemeDocument.LIGHT)
                    : ThemeDocument.SINGLE;
            displayName = effective.file() == null ? effective.sourceId() : document.name();
        }
        Theme theme = Theme.from(ThemeResolver.resolve(document, variant,
                effective.kind() == Kind.SYSTEM, facts, displayName), colorsAvailable, ++revision);
        return new Resolved(effective, theme);
    }

    /** 读取自定义文件：优先缓存，fresh 时强制重读并刷新缓存。 */
    private ThemeDocument readDocument(Path file, boolean fresh) {
        if (!fresh) {
            ThemeDocument cached = documents.get(file);
            if (cached != null) return cached;
        }
        ThemeDocument loaded = ThemeDocument.read(file);
        documents.put(file, loaded);
        return loaded;
    }

    /** @return 自定义主题的路径；以 .json/分隔符给出的按路径处理，否则在配置目录下补扩展名。 */
    private Path customFile(String name) {
        if (name.endsWith(".json") || name.contains("/") || name.contains("\\")) return Path.of(name);
        if (customDirectory == null) throw new IllegalArgumentException("Unknown theme: " + name);
        return customDirectory.resolve(name + ".json");
    }

    private static String normalize(String name) {
        return name == null || name.isBlank() ? "system" : name;
    }

    private static String pairName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 只替换 Selection 的文档，保持来源/标识/文件身份不变。 */
    private static Selection withDocument(Selection selection, ThemeDocument document) {
        return new Selection(selection.kind(), selection.logicalId(), selection.sourceId(),
                selection.file(), document);
    }

    private static boolean matches(Selection selection, Path file) {
        return selection != null && selection.file() != null && selection.file().equals(file);
    }

    /**
     * 采纳候选快照：颜色/外观/名称/启用状态都没变时保留旧对象，避免无意义 revision 与重绘。
     */
    private static Theme adopt(Theme previous, Theme candidate) {
        if (previous == null || candidate == null) return candidate;
        if (previous.enabled() == candidate.enabled()
                && previous.name().equals(candidate.name())
                && previous.appearance().equals(candidate.appearance())
                && previous.colors().equals(candidate.colors())) return previous;
        return candidate;
    }

    /**
     * 把监听目标指向当前（预览优先）来源文件，并使旧目标事件失效。
     *
     * <p>预览内置候选时本身没有文件；此时继续监听已确认的自定义文件，避免用户预览完
     * 内置主题再取消后看到过期内容。确认切到内置主题后 committed 没有文件，监听自然停止。</p>
     */
    private void retarget() {
        Path previewFile = previewSelection == null ? null : previewSelection.file();
        Path committedFile = committedSelection == null ? null : committedSelection.file();
        watchTarget = previewFile != null ? previewFile : committedFile;
        watchGeneration++;
    }

    /** 监听线程主体：目标切换时重新注册目录，事件只回传不可变结果，不写主题状态。 */
    private void runWatch(Consumer<WatchResult> sink) {
        java.nio.file.WatchService service = null;
        Path registered = null;
        try {
            while (!closed.get()) {
                Path target = watchTarget;
                long generation = watchGeneration;
                if (target == null || target.getParent() == null) {
                    service = closeService(service);
                    registered = null;
                    if (!pause(120)) return;
                    continue;
                }
                if (registered == null || !registered.equals(target.getParent())) {
                    service = closeService(service);
                    registered = target.getParent();
                    try {
                        service = java.nio.file.FileSystems.getDefault().newWatchService();
                        registered.register(service,
                                java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                                java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                                java.nio.file.StandardWatchEventKinds.ENTRY_DELETE);
                    } catch (IOException unavailable) {
                        service = closeService(service);
                        registered = null;
                        sink.accept(new WatchResult(generation, target, null,
                                "Theme watcher stopped: " + unavailable.getMessage()));
                        if (!pause(500)) return;
                        continue;
                    }
                }
                var key = service.poll(200, TimeUnit.MILLISECONDS);
                if (key == null) continue;
                boolean changed = key.pollEvents().stream()
                        .anyMatch(event -> target.getFileName().equals(event.context()));
                key.reset();
                if (!changed || closed.get()) continue;
                // 用户已切到别的主题/文件：旧事件不能覆盖新选择。
                if (generation != watchGeneration) continue;
                ThemeDocument document = null;
                String error = null;
                try {
                    document = ThemeDocument.read(target);
                } catch (RuntimeException failure) {
                    error = failure.getMessage();
                }
                sink.accept(new WatchResult(generation, target, document, error));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            closeService(service);
        }
    }

    private static java.nio.file.WatchService closeService(java.nio.file.WatchService service) {
        if (service != null) {
            try { service.close(); } catch (IOException ignored) { }
        }
        return null;
    }

    /** @return false 表示线程被中断/关闭，调用方应退出循环。 */
    private boolean pause(long millis) {
        if (closed.get()) return false;
        try {
            Thread.sleep(millis);
            return !closed.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
