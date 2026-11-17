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
 * 串行保存 config.json 的白名单设置补丁与默认权限。按客户端所见版本检查修改，再基于最新文件合并；
 * 写入成功发布新的设置快照与 revision，失败经回调返回。退出时通过 awaitWrites 等待已提交保存。
 */
public final class PreferenceService implements AutoCloseable {
    /**
     * 保存结果回调：成功给出新设置与 revision，失败给出真实异常。
     */
    public interface Callback {
        /**
         * 配置写入成功后接收已发布设置、新版本和本次补丁，供后端向界面确认保存结果。
         *
         * @param settings 本次装配或保存的应用设置快照
         * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
         * @param patch 本次需要保存的白名单字段与新值
         */
        void saved(JadeSettings settings, long revision, Map<String, Object> patch);

        /**
         * 配置写入失败后接收 IO 异常和本次补丁，供后端显示错误并保留待重试修改。
         *
         * @param failure 已经发生的 IO、模型或执行异常，供原任务收尾与回执使用
         * @param patch 本次需要保存的白名单字段与新值
         */
        void failed(IOException failure, Map<String, Object> patch);
    }

    /**
     * 一次设置的“已保存基线”：Node 用它校验 patch 是否基于当前版本。
     *
     * @param settings 本次装配或保存的应用设置快照
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     */
    public record State(JadeSettings settings, long revision) {
    }

    private final JadeConfigFile file;
    private final ExecutorService writes = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jade-prefs");
        thread.setDaemon(true);
        return thread;
    });
    private JadeSettings settings;
    private long revision;

    /**
     * 绑定统一配置文件，读取初始设置并建立串行保存队列，供终端设置与默认权限共用。
     *
     * @param path 统一配置文件路径（生产为 {@code ~/.jadecode/config/config.json}）
     * @throws IOException 配置目录或初始设置文件读取失败
     */
    public PreferenceService(Path path) throws IOException {
        this.file = new JadeConfigFile(path);
        this.settings = file.load();
    }

    /**
     * 读取最近成功保存后发布的设置与版本，形成调用方可并发读取的状态快照。
     *
     * @return 当前已保存快照；调用方不得修改返回对象。
     */
    public synchronized State state() {
        return new State(settings, revision);
    }

    /**
     * 排队保存一个 TUI 白名单 patch。
     *
     * @param patch 本次需要保存的白名单字段与新值
     * @param expectedRevision Node 看到的当前版本；不等于最新版本时明确拒绝
     * @param callback 结果回调（在写线程执行，调用方负责转成事件）
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
                callback.failed(failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure),
                        copy);
            }
        });
    }

    /**
     * 保存用户显式选择的启动默认权限；与临时选择分开，失败不改变内存档位。
     *
     * @param profile 当前选择的权限档位
     * @param expectedRevision 调用方看到的旧版本，用于确认本次修改依据仍有效
     * @param callback 配置保存完成或失败后的结果回调
     */
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
                callback.failed(failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure),
                        Map.of());
            }
        });
    }

    /**
     * 退出时等待已排队的写入（最多 2 秒）；超时抛出真实错误。
     *
     * @throws IOException 已有保存任务失败、等待中断或超出退出等待预算
     */
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

    /**
     * 停止接收配置写入任务，最多等待两秒完成队列中已有的保存；等待中断时恢复线程中断标记。
     */
    @Override
    public void close() {
        writes.shutdown();
        try {
            writes.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 供测试/诊断：把一次设置变化转成快照更新（不写文件）。
     *
     * @param listener 配置成功保存后接收新设置与版本的通知函数
     * @param settings 本次装配或保存的应用设置快照
     * @param revision 本次操作依据的版本号，用于读取后修改时核对状态
     */
    static void notifyChange(BiConsumer<JadeSettings, Long> listener, JadeSettings settings, long revision) {
        listener.accept(settings, revision);
    }
}
