package com.jadecode.tui.theme;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.jadecode.tui.text.Color;

/**
 * 主题的预览、提交、system 晚回复和自定义文件监听。
 *
 * <p>主题选择是 UI 状态：上下移动只改内存中的 {@link #preview()}，Esc 回到已提交值，
 * Enter/apply 后才通知配置保存。system 主题依赖终端 OSC 10/11/4 回复；回复到达前使用
 * Pi 回退色，到达后在同一 epoch 更新快照，不阻塞输入。自定义主题读文件/监听在 worker
 * 完成，坏文件保留最后有效主题；晚到的旧 watch 结果不能覆盖较新的用户选择（由 request
 * 编号校验）。</p>
 *
 * <p>本类不写终端、不改配置文件，也不持有编辑器等组件。</p>
 */
public final class ThemeManager implements AutoCloseable {
    private Theme committed;
    private Theme preview;
    private long request;
    private long revision;
    private final boolean colorsAvailable;
    private final Path customDirectory;
    /** system 主题的终端色板快照；晚回复更新它。 */
    private Color foreground, background;
    private List<Color> ansi = List.of();
    private boolean terminalDark = true;
    private String lightTheme;
    private String darkTheme;
    private String configuredName;
    /** panelBg 是否来自主题显式值；派生的晚回复可重算，显式值不能被覆盖。 */
    private boolean committedPanelExplicit;
    private boolean previewPanelExplicit;
    private Thread watcher;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param name 已配置主题：system/dark/light/auto 或自定义标识
     * @param customDirectory 配置同级 themes 目录，可为 null
     * @param colorsAvailable 终端是否允许颜色（NO_COLOR/dumb 为 false）
     * @param lightTheme auto 模式的浅色主题名
     * @param darkTheme auto 模式的深色主题名
     */
    public ThemeManager(String name, Path customDirectory, boolean colorsAvailable,
            String lightTheme, String darkTheme) {
        this.colorsAvailable = colorsAvailable;
        this.customDirectory = customDirectory;
        this.lightTheme = lightTheme == null || lightTheme.isBlank() ? "light" : lightTheme;
        this.darkTheme = darkTheme == null || darkTheme.isBlank() ? "dark" : darkTheme;
        this.configuredName = name == null || name.isBlank() ? "system" : name;
        Loaded loaded = load(this.configuredName, this.lightTheme, this.darkTheme);
        this.committed = loaded.theme();
        this.committedPanelExplicit = loaded.panelBgExplicit();
        this.preview = committed;
        this.previewPanelExplicit = committedPanelExplicit;
    }

    /** 已解析主题及其 panelBg 来源；派生的底色可由终端晚回复重算。 */
    private record Loaded(Theme theme, boolean panelBgExplicit) { }

    /** @return 当前应绘制的主题（预览优先）。 */
    public Theme preview() { return preview; }

    /** @return 用户上次确认/启动时的主题。 */
    public Theme committed() { return committed; }

    /** @return 主题 revision；任何可见变化都会递增。 */
    public long revision() { return revision; }

    /**
     * 应用到预览并排队持久化。实际写盘由调用方（TuiPreferences）完成：
     * 本类只负责内存状态和失败回滚入口，避免主题模块依赖配置文件格式。
     */
    public void applyPreview() {
        if (preview == committed) return;
        committed = preview; revision++;
    }

    /** 直接安装已成功保存的主题；失败时调用方保留原 committed。 */
    public void commit(Theme theme) {
        committed = theme; preview = theme;
        committedPanelExplicit = true; previewPanelExplicit = true; revision++;
    }

    /** 打开列表/预览时记录恢复点，只影响内存。 */
    public void beginPreview() {
        request++; preview = committed; previewPanelExplicit = committedPanelExplicit;
    }

    /**
     * 预览指定主题标识。读取自定义主题可能在磁盘上进行，调用方应在 worker 调用；
     * 这里同步读取但只做小 JSON 解析，不访问网络。失败返回异常文本且不改变预览。
     */
    public String preview(String identifier) {
        long ticket = ++request;
        try {
            Loaded candidate = load(identifier, lightTheme, darkTheme);
            if (ticket != request) return "Theme selection changed";
            preview = candidate.theme(); previewPanelExplicit = candidate.panelBgExplicit();
            return "";
        } catch (RuntimeException failure) {
            if (ticket == request) preview = committed;
            return "Cannot read theme: " + failure.getMessage();
        }
    }

    /** Esc/关闭选择器：放弃未提交预览。 */
    public void cancelPreview() {
        request++;
        if (preview != committed) {
            preview = committed; previewPanelExplicit = committedPanelExplicit; revision++;
        }
    }

    /**
     * 终端上报默认前景/背景/16 色后更新 system 主题。只在当前主题为 system（或 auto 已选
     * 派生主题）且请求代次仍匹配时替换；不覆盖用户正在预览的自定义主题。
     */
    public void updateTerminalColors(boolean dark, Color fg, Color bg, List<Color> palette) {
        this.terminalDark = dark; this.foreground = fg; this.background = bg;
        // 部分 16 色回复可能缺失（null）；ThemePalette 会回退到假定色板，不能在这里丢信息。
        this.ansi = palette == null ? List.of()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(palette));
        if (committed.name().equals("system")) {
            committed = Theme.from(ThemePalette.system(dark, fg, bg, ansi), colorsAvailable, ++revision);
            committedPanelExplicit = false;
            if (preview.name().equals("system")) {
                preview = committed; previewPanelExplicit = false;
            }
        }
        if (preview.name().equals("system")) {
            preview = Theme.from(ThemePalette.system(dark, fg, bg, ansi), colorsAvailable, ++revision);
            previewPanelExplicit = false;
        }
        // 终端背景晚回复后，所有非显式 panelBg 的主题都按新背景重算；显式值保持不变。
        committed = refreshPanelBg(committed, committedPanelExplicit);
        preview = refreshPanelBg(preview, previewPanelExplicit);
    }

    /** 由外部设置变更安装新主题名（例如其它窗口写入配置后的刷新）。 */
    public void reload(String name) {
        request++;
        configuredName = name == null || name.isBlank() ? "system" : name;
        Loaded loaded = load(configuredName, lightTheme, darkTheme);
        committed = loaded.theme(); committedPanelExplicit = loaded.panelBgExplicit();
        preview = committed; previewPanelExplicit = committedPanelExplicit;
    }

    /**
     * 更新 auto 的 light/dark 配对并重新解析当前逻辑主题；设置页切换配对时使用。
     * 用户当前选择仍由 configuredName 保存，不把解析后的具体色板名写回逻辑设置。
     */
    public void setPair(String light, String dark) {
        lightTheme = light == null || light.isBlank() ? "light" : light;
        darkTheme = dark == null || dark.isBlank() ? "dark" : dark;
        request++;
        Loaded loaded = load(configuredName, lightTheme, darkTheme);
        committed = loaded.theme(); committedPanelExplicit = loaded.panelBgExplicit();
        preview = committed; previewPanelExplicit = committedPanelExplicit;
    }

    /** @return 当前逻辑主题标识（system/auto/自定义），用于诊断与状态同步。 */
    public String logicalName() { return configuredName; }

    /**
     * 监听自定义主题目录的变化，变化后重新读取当前预览/已提交同名主题。
     * 失败只记录到回调，不产生异常线程终止；关闭时停止监听。
     */
    public void watch(Path file, Consumer<String> onError, Runnable onChanged) {
        if (file == null || watcher != null) return;
        watcher = Thread.ofVirtual().name("jade-theme-watch").start(() -> {
            try (var service = java.nio.file.FileSystems.getDefault().newWatchService()) {
                Path directory = file.toAbsolutePath().getParent();
                if (directory == null) return;
                directory.register(service, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                        java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                        java.nio.file.StandardWatchEventKinds.ENTRY_DELETE);
                while (!closed.get()) {
                    var key = service.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (key == null) continue;
                    boolean changed = key.pollEvents().stream()
                            .anyMatch(event -> file.getFileName().equals(event.context()));
                    key.reset();
                    if (!changed || closed.get()) continue;
                    try {
                        Theme reread = ThemeManager.loadFrom(file, colorsAvailable, ++revision);
                        if (!reread.colors().containsKey("panelBg")) reread = refreshPanelBg(reread, false);
                        if (preview.name().equals(reread.name())) {
                            preview = reread; previewPanelExplicit = false; revision++; onChanged.run();
                        }
                    } catch (RuntimeException failure) { onError.accept(failure.getMessage()); }
                }
            } catch (IOException | InterruptedException unavailable) {
                if (!closed.get()) onError.accept("Theme watcher stopped: " + unavailable.getMessage());
            }
        });
    }

    /** @return 解析主题标识：system 派生、auto 配对、内置或自定义文件；并派生 panelBg。 */
    private Loaded load(String name, String lightTheme, String darkTheme) {
        String normalized = name == null || name.isBlank() ? "system" : name;
        ThemePalette.Definition definition;
        if (normalized.equals("system")) {
            definition = ThemePalette.system(terminalDark, foreground, background, ansi);
        } else if (normalized.equals("auto")) {
            String choice = terminalDark ? darkTheme : lightTheme;
            definition = builtinOrCustom(choice);
        } else {
            definition = builtinOrCustom(normalized);
        }
        return new Loaded(decoratePanelBg(definition), definition.panelBgExplicit());
    }

    /**
     * 非显式 panelBg 的主题在加载时按当前终端背景/既有明暗假定派生；派生值会随晚回复重算，
     * 但不会被记成用户显式值，也不改变逻辑 theme 字段。
     */
    private Theme decoratePanelBg(ThemePalette.Definition definition) {
        if (definition.panelBgExplicit()) return Theme.from(definition, colorsAvailable, ++revision);
        boolean dark = definition.appearance() == null || definition.appearance().isBlank()
                ? terminalDark : !definition.appearance().equalsIgnoreCase("light");
        var colors = new LinkedHashMap<>(definition.colors());
        colors.put("panelBg", ThemePalette.derivePanelBg(panelBase(dark), dark));
        var decorated = new ThemePalette.Definition(definition.name(), definition.appearance(), colors, false);
        return Theme.from(decorated, colorsAvailable, ++revision);
    }

    private Theme refreshPanelBg(Theme theme, boolean explicit) {
        if (explicit) return theme;
        boolean dark = !theme.appearance().equals("light");
        var colors = new LinkedHashMap<>(theme.colors());
        colors.put("panelBg", ThemePalette.derivePanelBg(panelBase(dark), dark));
        return new Theme(theme.name(), theme.appearance(), Map.copyOf(colors), theme.enabled(), ++revision);
    }

    private Color panelBase(boolean dark) {
        return background != null && background.kind() == Color.Kind.RGB
                ? background : (dark ? Color.rgb(0, 0, 0) : Color.rgb(255, 255, 255));
    }

    private ThemePalette.Definition builtinOrCustom(String name) {
        if (name.equals("dark") || name.equals("light")) return ThemePalette.builtin(name);
        if (name.endsWith(".json") || name.contains("/") || name.contains("\\")) {
            return readDefinition(Path.of(name));
        }
        if (customDirectory == null) throw new IllegalArgumentException("Unknown theme: " + name);
        Path file = customDirectory.resolve(name + ".json");
        return readDefinition(file);
    }

    /** 读取自定义主题文件；不存在/JSON 错误明确失败，不回退成内置主题。 */
    static Theme loadFrom(Path file, boolean colorsAvailable, long revision) {
        return Theme.from(readDefinition(file), colorsAvailable, revision);
    }

    private static ThemePalette.Definition readDefinition(Path file) {
        try {
            String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            return ThemePalette.parse(text);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + failure.getMessage(), failure);
        }
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
}
