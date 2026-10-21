package com.jadecode.cli.tui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import com.jadecode.config.JadeConfigFile;
import com.jadecode.config.JadeSettings;

/**
 * 前端对统一 config.json 的异步白名单保存；不拥有第二份 tui.json，也不读取凭据。
 *
 * <p>写入串行化，避免快速切换主题/设置时旧写入最后落盘。每次保存由 JadeConfigFile 重新读取
 * 当前文件、只 merge TUI 子树并保留未知字段；失败回调如实显示 Warning，不假称成功。退出时
 * 最多等待 2 秒，超时不声称已保存。权限默认仍走既有权限子树接口。</p>
 */
public final class TuiPreferences {
    private final JadeConfigFile file;
    private java.util.concurrent.CompletableFuture<Void> pending =
            java.util.concurrent.CompletableFuture.completedFuture(null);

    public TuiPreferences(Path path) { file = new JadeConfigFile(path); }

    /** 一次取得 UI 和运行设置，交给同次启动的会话，不让界面和压缩器各用一份不同快照。 */
    public JadeSettings loadSettings() throws IOException { return file.load(); }

    /** 保留旧调用：读取主题。损坏文件明确抛异常，不静默覆盖。 */
    public String load() throws IOException { return loadSettings().tui().theme(); }

    /** 只改 tui.theme（通过白名单 patch），其它项保留。 */
    public void save(String theme) throws IOException { file.saveTui(Map.of("theme", theme)); }

    /** 保存任意已批准 TUI 字段；未知字段由配置层拒绝。 */
    public void saveTui(Map<String, Object> patch) throws IOException { file.saveTui(patch); }

    /** 只保存用户明确选择的启动默认，不把一次临时切换偷偷持久化。 */
    public void savePermission(com.jadecode.safety.PermissionProfile profile) throws IOException {
        file.savePermissionProfile(profile);
    }

    /** 排队保存主题，不在界面线程等待磁盘。 */
    public synchronized void saveLater(String theme, java.util.function.Consumer<IOException> completed) {
        saveLater(Map.of("theme", theme), completed);
    }

    /** 排队保存 TUI patch；回调只报告结果，不修改界面状态。 */
    public synchronized void saveLater(Map<String, Object> patch, java.util.function.Consumer<IOException> completed) {
        pending = pending.handle((value, error) -> null).thenRunAsync(() -> {
            try { saveTui(patch); completed.accept(null); }
            catch (IOException failed) { completed.accept(failed); }
        }, command -> Thread.ofVirtual().name("jade-tui-prefs").start(command));
    }

    /** 退出清理线程等待已明确选择的偏好写入，最多两秒；超时不能假称已保存。 */
    public void awaitWrites() throws IOException {
        java.util.concurrent.CompletableFuture<Void> writes;
        synchronized (this) { writes = pending; }
        try { writes.get(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Preference write was interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new IOException("Preference write did not finish", failed);
        }
    }
}
