package com.jadecode.cli.backend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import com.jadecode.config.JadeConfigFile;
import com.jadecode.config.JadeSettings;
import com.jadecode.safety.PermissionProfile;

/**
 * 统一 {@code config.json} 的唯一进程内写者：设置白名单 patch、默认权限、revision 与退出等待。
 *
 * <p>Node 不写配置文件，只发送“这组白名单字段 + 我看到的 configRevision”。这里串行执行：
 * 每批基于最新文件合并（{@link JadeConfigFile#saveTui} 保留未知字段），写成功后才发布新
 * revision 与设置快照；写失败返回真实错误并保留可重试状态。连续快速切换时后一批等待前一批
 * 落盘，不会因为旧 revision 被误报成冲突。</p>
 *
 * <p>退出最多等 2 秒（沿用既有预算）；超时如实报告未保存，不声称已落盘。</p>
 */
public final class PreferenceService implements AutoCloseable {
    /** 保存结果回调：成功给出新设置与 revision，失败给出真实异常。 */
    public interface Callback {
        void saved(JadeSettings settings, long revision, Map<String, Object> patch);
        void failed(IOException failure, Map<String, Object> patch);
    }

    /** 一次设置的“已保存基线”：Node 用它校验 patch 是否基于当前版本。 */
    public record State(JadeSettings settings, long revision) { }

    private final JadeConfigFile file;
    private final ExecutorService writes = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jade-prefs");
        thread.setDaemon(true);
        return thread;
    });
    private JadeSettings settings;
    private long revision;

    /**
     * @param path 统一配置文件路径（生产为 {@code ~/.jadecode/config/config.json}）
     */
    public PreferenceService(Path path) throws IOException {
        this.file = new JadeConfigFile(path);
        this.settings = file.load();
    }

    /** @return 当前已保存快照；调用方不得修改返回对象。 */
    public synchronized State state() { return new State(settings, revision); }

    /**
     * 排队保存一个 TUI 白名单 patch。
     *
     * @param expectedRevision Node 看到的当前版本；不等于最新版本时明确拒绝
     * @param callback         结果回调（在写线程执行，调用方负责转成事件）
     */
    public void apply(Map<String, Object> patch, long expectedRevision, Callback callback) {
        Map<String, Object> copy = Map.copyOf(patch == null ? Map.of() : patch);
        writes.execute(() -> {
            synchronized (this) {
                if (expectedRevision != revision) {
                    callback.failed(new IOException("The settings changed; reload before saving again"), copy);
                    return;
                }
            }
            try {
                file.saveTui(copy);
                JadeSettings reloaded = file.load();
                long nextRevision;
                synchronized (this) {
                    settings = reloaded;
                    nextRevision = ++revision;
                }
                callback.saved(reloaded, nextRevision, copy);
            } catch (IOException | RuntimeException failure) {
                callback.failed(failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure), copy);
            }
        });
    }

    /** 保存用户显式选择的启动默认权限；与临时选择分开，失败不改变内存档位。 */
    public void saveDefaultPermission(PermissionProfile profile, long expectedRevision, Callback callback) {
        writes.execute(() -> {
            synchronized (this) {
                if (expectedRevision != revision) {
                    callback.failed(new IOException("The settings changed; reload before saving again"), Map.of());
                    return;
                }
            }
            try {
                file.savePermissionProfile(profile);
                JadeSettings reloaded = file.load();
                long nextRevision;
                synchronized (this) {
                    settings = reloaded;
                    nextRevision = ++revision;
                }
                callback.saved(reloaded, nextRevision, Map.of("defaultPermission", profile.value()));
            } catch (IOException | RuntimeException failure) {
                callback.failed(failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure), Map.of());
            }
        });
    }

    /** 退出时等待已排队的写入（最多 2 秒）；超时抛出真实错误。 */
    public void awaitWrites() throws IOException {
        CompletableFuture<Void> done = new CompletableFuture<>();
        writes.execute(() -> done.complete(null));
        try {
            done.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Preference write was interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new IOException("Preference write did not finish", failed);
        }
    }

    @Override public void close() {
        writes.shutdown();
        try {
            writes.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供测试/诊断：把一次设置变化转成快照更新（不写文件）。 */
    static void notifyChange(BiConsumer<JadeSettings, Long> listener, JadeSettings settings, long revision) {
        listener.accept(settings, revision);
    }
}
