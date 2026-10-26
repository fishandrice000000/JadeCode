package com.jadecode.cli.backend;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.config.JadeSettings;
import com.jadecode.config.AppConfig;
import com.jadecode.config.ModelConfigLoader;
import java.nio.file.Path;
import com.jadecode.execution.ExecutionObserver;
import com.jadecode.llm.ResponseStream;
import com.jadecode.safety.PermissionState;
import com.jadecode.session.SessionRuntime;

/**
 * 两种运行方式共用的会话装配：交互后端与 headless 只决定注入谁，不复制工厂。
 *
 * <p>为什么需要它：{@link JadeCodeFactory} 已有完整装配（工具、权限、Team、MCP、Memory），
 * 但它的多个重载会自行读取用户配置。这里固定使用“同一份设置快照 + 同一份权限状态”，
 * 保证 TUI 保存的设置与压缩器/提示词使用同一版本，也保证 headless 不会因为另一次读盘而
 * 悄悄换了权限档位。</p>
 *
 * <p>不承担：不创建终端、不启动 Node、不写配置文件、不执行模型请求。</p>
 */
public final class RuntimeFactory {
    private final JadeSettings settings;
    private final PermissionState permissions;
    private final AppConfig modelConfig;

    /**
     * 一次启动固定模型配置，恢复/切换会话不重新混入项目文件；不联网，不改变工作区。
     * @param selectedModelConfig 显式选择的 JSON 路径，null 选全局默认；缺少全局文件时仅用进程环境
     * @throws IllegalStateException 配置缺失、损坏或无效；诊断不包含凭据原文
     */
    public RuntimeFactory(JadeSettings settings, PermissionState permissions, Path selectedModelConfig) {
        this.settings = settings;
        this.permissions = permissions;
        this.modelConfig = ModelConfigLoader.load(selectedModelConfig);
    }

    /** @return 本会话共享的设置快照（只读）。 */
    public JadeSettings settings() { return settings; }

    /** @return 本会话共享的权限状态；TUI 与 headless 都必须用这一份。 */
    public PermissionState permissions() { return permissions; }

    /**
     * 装配一个尚未 start 的会话。
     *
     * @param output   程序整条输出（最终回答、通知）；实现必须立即返回
     * @param errors   真实错误输出
     * @param streams  Leader 每个正式请求的流式出口
     * @param observer 只读运行观察（工具/计时/后台）
     */
    public SessionRuntime create(Consumer<String> output, Consumer<String> errors,
            Supplier<ResponseStream> streams, ExecutionObserver observer) {
        return JadeCodeFactory.create((hooks, cwd, ignored) -> { }, output, errors, streams, observer, settings, permissions, modelConfig);
    }
}
