package com.jadecode.tui.markdown;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadecode.tui.text.DisplayWidth;

/**
 * 固定 GraalJS 纯计算引擎：单工作线程、单封闭 Context、固定 bundle。
 *
 * <p>按 C04 只运行 marked/highlight.js/grok-mermaid/Pi LaTeX 的纯计算代码。Context 关闭
 * host 反射、IO、线程与 native 访问，只暴露两个 ProxyExecutable：ICU 字素边界与终端列宽。
 * Java 拥有布局/终端；本类不执行模型代码、不读文件/网络。结果 JSON 立即转成不可变 DTO，
 * JS Value 不跨线程。超时/失败关闭并重建 Context，返回失败而不静默降级为另一种解析器。</p>
 */
public final class JsTextEngine implements AutoCloseable {
    private static final String BUNDLE = "/com/jadecode/tui/js/text-engine.bundle.js";
    private static final String MANIFEST = "/com/jadecode/tui/js/text-engine.manifest.json";
    /** 单次计算看门狗；超时取消并重建 Context，保留原文由调用方显示。 */
    private static final long WATCHDOG_SECONDS = 5;

    private final ExecutorService worker;
    /** 仅由 JS worker 使用；保留当前文档已独立完成的块，固定库及原始 lexJson 不变。 */
    private final MarkdownFragments fragments = new MarkdownFragments();
    /** 看门狗独立于 JS worker；仅对当前实际执行的任务计时。 */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "jade-text-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private Context context;
    private Value engine;
    private volatile boolean broken;
    private volatile boolean closed;
    /** 受 this 锁保护，防止晚到的超时关闭下一个任务的 Context。 */
    private CompletableFuture<?> executing;
    private final String bundleHash;

    private JsTextEngine(ExecutorService worker, String bundleHash) {
        this.worker = worker;
        this.bundleHash = bundleHash;
    }

    /**
     * 加载固定 bundle 并校验 manifest 哈希。
     * @return 引擎；资源缺失/哈希不符/初始化失败抛 IOException，由调用方保留 Java 回退
     */
    public static JsTextEngine create() throws IOException {
        byte[] bundle = resource(BUNDLE);
        byte[] manifest = resource(MANIFEST);
        JsonNode root = new ObjectMapper().readTree(manifest);
        String expected = root.path("bundle").path("sha256").asText("");
        String actual = sha256(bundle);
        if (expected.isBlank() || !expected.equals(actual))
            throw new IOException("text engine bundle hash mismatch");
        var worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "jade-text-engine");
            thread.setDaemon(true);
            return thread;
        });
        var result = new JsTextEngine(worker, actual);
        // Context 必须在它自己的单工作线程创建；跨线程创建/使用会破坏 Graal 线程约束。
        try {
            worker.submit(() -> {
                try { result.ensureContext(); }
                catch (IOException failure) { throw new RuntimeException(failure); }
            }).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("text engine init interrupted", interrupted);
        } catch (ExecutionException failed) {
            worker.shutdownNow();
            Throwable cause = failed.getCause() instanceof RuntimeException runtime && runtime.getCause() != null
                    ? runtime.getCause() : failed.getCause();
            throw cause instanceof IOException io ? io : new IOException("text engine init failed", cause);
        } catch (TimeoutException timedOut) {
            worker.shutdownNow();
            throw new IOException("text engine init timed out", timedOut);
        }
        return result;
    }

    /** @return bundle SHA-256；manifest 校验通过后可用。 */
    public String bundleHash() { return bundleHash; }

    /** 解析 Markdown 为 DTO；调用方在 UI 线程先显示回退，完成后重排。 */
    public CompletableFuture<TextRenderResult> lex(String text, long version) {
        return submit(() -> fragments.parse(text, version,
                part -> MarkedTokenAdapter.adapt(call("lex", part), version)));
    }

    /** @return 固定解析器实际处理的累计字符数；通过 worker barrier 后可用于性能诊断。 */
    public long lexedCharacters() { return fragments.parsedCharacters(); }

    /** @return 原始 lex JSON；差分测试/诊断使用，不进入产品布局。 */
    public CompletableFuture<String> lexJson(String text) {
        return submit(() -> call("lex", text));
    }

    /** @return 指定语言的语义高亮 JSON；未知语言返回 null。 */
    public CompletableFuture<String> highlight(String code, String language) {
        return submit(() -> {
            Value result = engine.getMember("highlight").execute(code, language == null ? "" : language);
            return result == null || result.isNull() ? null : result.asString();
        });
    }

    /** @return LaTeX 转 Unicode 文本；不支持返回 null。 */
    public CompletableFuture<String> latex(String source, boolean display) {
        return submit(() -> {
            Value result = engine.getMember("latex").execute(source, display);
            return result == null || result.isNull() ? null : result.asString();
        });
    }

    /** @return Mermaid 图 JSON；无法解析返回 null。 */
    public CompletableFuture<String> mermaid(String source) {
        return submit(() -> {
            Value result = engine.getMember("mermaid").execute(source);
            return result == null || result.isNull() ? null : result.asString();
        });
    }

    /**
     * 沙箱探针：确认 host 对象/require/process 不可见；测试与启动自检使用。
     * @return JSON 字符串，例如 `{"java":"undefined",...}`
     */
    public CompletableFuture<String> sandboxProbe() {
        return submit(() -> context.eval("js",
                "JSON.stringify({java:typeof Java,polyglot:typeof Polyglot,require:typeof require,"
                        + "process:typeof process,"
                        + "javaType:(function(){try{return typeof Java.type('java.lang.System')}catch(e){return 'blocked'}})(),"
                        + "polyglotImport:(function(){try{return typeof Polyglot.import('x')}catch(e){return 'blocked'}})()})")
                .asString());
    }

    /**
     * 登记待完成 future 后入队；此处不启动计算期限。关闭/拒绝入队时完成失败或取消，
     * 不让调用方永久等待；真正执行与看门狗由 execute 管理。
     */
    private <T> CompletableFuture<T> submit(java.util.concurrent.Callable<T> task) {
        var future = new CompletableFuture<T>();
        pending.add(future);
        future.whenComplete((value, failure) -> pending.remove(future));
        try {
            if (closed) { future.cancel(false); return future; }
            worker.execute(() -> execute(task, future));
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            future.completeExceptionally(stopped);
        }
        return future;
    }

    /**
     * worker 串行执行：取消的排队任务直接跳过，排队时间不计入五秒计算上限。
     * 定时器与收尾在同一锁下核对 executing；无论谁先结束，都不能误关下一份 Context。
     * future 回调在锁外发布，允许组件安全提交自己的最新正文。
     */
    private <T> void execute(java.util.concurrent.Callable<T> task, CompletableFuture<T> future) {
        if (closed || future.isDone()) return;
        ScheduledFuture<?> timeout = null;
        try {
            synchronized (this) {
                if (closed || future.isDone()) return;
                if (broken) ensureContext();
                executing = future;
                timeout = watchdog.schedule(() -> {
                    synchronized (JsTextEngine.this) {
                        if (executing != future) return;
                        // 只有真正超时的当前任务能关闭引擎；取消不等于超时。
                        broken = true;
                        closeContext();
                        executing = null;
                    }
                    future.completeExceptionally(new TimeoutException("Text engine execution timed out"));
                }, WATCHDOG_SECONDS, TimeUnit.SECONDS);
            }
            T value = task.call();
            synchronized (this) {
                if (executing != future) return;
                executing = null;
            }
            future.complete(value);
        } catch (Exception failure) {
            synchronized (this) {
                if (executing == future) { broken = true; executing = null; }
            }
            future.completeExceptionally(failure);
        } finally {
            if (timeout != null) timeout.cancel(false);
        }
    }

    private String call(String member, String argument) {
        Value function = engine.getMember(member);
        if (function == null || !function.canExecute())
            throw new IllegalStateException("text engine missing function: " + member);
        Value result = function.execute(argument == null ? "" : argument);
        if (result == null || result.isNull()) throw new IllegalStateException("text engine returned null: " + member);
        return result.asString();
    }

    /** 在 worker 线程创建封闭 Context；调用方保证串行。 */
    private void ensureContext() throws IOException {
        closeContext();
        try {
            context = Context.newBuilder("js")
                    .allowHostAccess(HostAccess.NONE)
                    .allowHostClassLookup(name -> false)
                    .allowIO(IOAccess.NONE)
                    .allowCreateThread(false)
                    .allowNativeAccess(false)
                    .allowCreateProcess(false)
                    // 解释器模式在无 JVMCI 的普通 JDK 上会打印启动警告；关掉它，避免污染 UI 通知。
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();
            Value bindings = context.getBindings("js");
            bindings.putMember("__jadeGraphemes", (ProxyExecutable) arguments -> {
                if (arguments.length < 1) return List.of();
                return DisplayWidth.clusters(arguments[0].asString());
            });
            bindings.putMember("__jadeWidth", (ProxyExecutable) arguments -> {
                if (arguments.length < 1) return 0;
                return DisplayWidth.width(arguments[0].asString());
            });
            Source source = Source.newBuilder("js", new String(resource(BUNDLE), StandardCharsets.UTF_8),
                    "text-engine.bundle.js").build();
            context.eval(source);
            engine = context.getBindings("js").getMember("__jadeTextEngine");
            if (engine == null || !engine.hasMembers())
                throw new IOException("text engine bundle did not expose __jadeTextEngine");
            broken = false;
        } catch (IOException | RuntimeException failure) {
            closeContext();
            throw failure instanceof IOException io ? io : new IOException("text engine init failed", failure);
        }
    }

    private synchronized void closeContext() {
        if (context != null) {
            try { context.close(true); } catch (RuntimeException ignored) { }
            context = null; engine = null;
        }
    }

    /** 关闭工作线程与 Context；幂等。 */
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true; executing = null;
            closeContext();
        }
        // 关闭时不等待整个旧队列逐项计算；取消回调仍会按组件代次检查。
        for (CompletableFuture<?> future : pending) future.cancel(false);
        worker.shutdownNow();
        watchdog.shutdownNow();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); worker.shutdownNow();
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream input = JsTextEngine.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing resource: " + path);
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** @return 当前 Context 是否仍可用；测试/诊断。 */
    public boolean ready() { return !broken && engine != null; }
}
