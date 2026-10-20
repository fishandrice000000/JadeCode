package com.jadecode.cli.tui;

import java.io.IOException;
import java.nio.file.Path;
import com.jadecode.config.JadeConfigFile;
import com.jadecode.config.JadeSettings;

/** 前端对统一配置的异步保存适配；不拥有另一份 tui.json，也不读取模型凭据。 */
public final class TuiPreferences {
    private final JadeConfigFile file;
    /** 串行保存用户选择，避免快速切两次主题时旧写入最后落盘。 */
    private java.util.concurrent.CompletableFuture<Void> pending = java.util.concurrent.CompletableFuture.completedFuture(null);
    public TuiPreferences(Path path) { file = new JadeConfigFile(path); }
    /** 一次取得 UI 和运行设置，交给同次启动的会话，不让界面和压缩器各用一份不同快照。 */
    public JadeSettings loadSettings() throws IOException { return file.load(); }
    /** 保留便于调用的主题读取方法；损坏文件明确抛异常，不静默覆盖。 */
    public String load() throws IOException { return loadSettings().tui().theme(); }
    /** 只改 tui.theme，统一配置里的其它项必须保留。 */
    public void save(String theme) throws IOException { file.saveTheme(theme); }
    /** 只保存用户明确选择的启动默认，不把一次临时切换偷偷持久化。 */
    public void savePermission(com.jadecode.safety.PermissionProfile profile) throws IOException { file.savePermissionProfile(profile); }
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
