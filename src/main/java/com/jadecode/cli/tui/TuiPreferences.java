package com.jadecode.cli.tui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.jadecode.util.Json;

/** 仅保存主题，不读取 .env 或其它机器配置。路径由外层传入，便于离线验证隔离真实 HOME。 */
public final class TuiPreferences {
    private final Path path;
    /** 串行保存用户选择，避免快速切两次主题时旧写入最后落盘。 */
    private java.util.concurrent.CompletableFuture<Void> pending = java.util.concurrent.CompletableFuture.completedFuture(null);
    public TuiPreferences(Path path) { this.path = path; }
    /** 配置不存在使用深色；损坏配置抛出异常，外层提示后继续，不静默覆盖。 */
    public String load() throws IOException {
        if (!Files.exists(path)) return "dark";
        var value = Json.mapper().readTree(Files.readString(path));
        String name = value == null ? "" : value.path("theme").asText();
        if (!name.equals("dark") && !name.equals("light")) throw new IOException("无效的 TUI theme: " + path);
        return name;
    }
    /** 只在明确选择时写；同目录临时文件后原子替换，失败清理本次临时文件，不删除用户配置。 */
    public void save(String theme) throws IOException {
        if (!theme.equals("dark") && !theme.equals("light")) throw new IllegalArgumentException("Unknown theme");
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "tui-", ".tmp");
        try {
            Files.writeString(temporary, Json.mapper().createObjectNode().put("version", 1).put("theme", theme).toPrettyString());
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    /** 排队保存，不在界面线程等待磁盘；回调只报告结果，不修改界面状态。 */
    public synchronized void saveLater(String theme, java.util.function.Consumer<IOException> completed) {
        pending = pending.handle((value, error) -> null).thenRunAsync(() -> {
            try { save(theme); completed.accept(null); }
            catch (IOException failed) { completed.accept(failed); }
        });
    }
    /** 退出清理线程等待已明确选择的偏好写入，最多两秒；超时不能假称已保存。 */
    public void awaitWrites() throws IOException {
        java.util.concurrent.CompletableFuture<Void> writes;
        synchronized (this) { writes = pending; }
        try { writes.get(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("主题保存等待被中断", interrupted); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) { throw new IOException("主题保存尚未完成", failed); }
    }
}
