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
 * 使用同一份设置、权限状态和模型配置创建会话运行时。服务先绑定工作区和范围领取器；create 准备模型元数据与客户端，
 * 再将显式依赖交给 JadeCodeFactory，供交互和 headless 共用装配。
 */
public final class RuntimeFactory {
    private final JadeSettings settings;
    private final PermissionState permissions;
    private final AppConfig modelConfig;
    private com.jadecode.llm.LlmClient injectedClient;
    private com.jadecode.config.JadePaths paths = com.jadecode.config.JadePaths.defaults();
    /** 0 表示未显式请求任务预算；它与单次模型请求的输入/输出限额无关。 */
    private long tokenBudget;
    private boolean created;
    private Path workspace = Path.of("").toAbsolutePath();
    private com.jadecode.sandbox.DockerRuntime.WriteScopes writeScopes;
    private com.jadecode.workspace.ManagedWorkspace managed;

    /**
     * 在创建运行时前绑定受管工作区并返回工厂自身，供服务继续接线；工厂已使用时抛出 IllegalStateException。
     *
     * @param value 此工厂后续运行时共用的受管工作区
     * @return 当前工厂，供继续链式接线
     * @throws IllegalStateException 已经创建运行时，受管工作区接线已固定
     */
    public RuntimeFactory managed(com.jadecode.workspace.ManagedWorkspace value) {
        if (created)
            throw new IllegalStateException("Runtime factory is already fixed");
        managed = value;
        return this;
    }

    /**
     * 返回此工厂绑定的受管工作区，普通项目会话返回 null。
     *
     * @return 已绑定的受管工作区；普通项目为 null
     */
    public com.jadecode.workspace.ManagedWorkspace managed() {
        return managed;
    }

    /**
     * 一次启动固定模型配置，恢复/切换会话不重新混入项目文件；不联网，不改变工作区。
     *
     * @param settings 本次装配或保存的应用设置快照
     * @param permissions 会话与工具检查器共用的权限状态
     * @param selectedModelConfig 显式选择的 JSON 路径，null 选全局默认；缺少全局文件时仅用进程环境
     * @throws IllegalStateException 配置缺失、损坏或无效；诊断不包含凭据原文
     */
    public RuntimeFactory(JadeSettings settings, PermissionState permissions, Path selectedModelConfig) {
        this.settings = settings;
        this.permissions = permissions;
        this.modelConfig = ModelConfigLoader.load(selectedModelConfig);
    }

    /**
     * 在创建运行时前绑定真实工作区和可写范围领取器，返回工厂自身；已创建运行时后抛出 IllegalStateException。
     *
     * @param path 本次读取、授权或校验的路径，按方法说明解析到实际目标
     * @param scopes 为容器可写挂载领取范围占用的服务接口
     * @return 当前工厂，供继续链式接线
     * @throws IllegalStateException 已经创建运行时，目录与可写范围入口已固定
     */
    public RuntimeFactory workspace(Path path, com.jadecode.sandbox.DockerRuntime.WriteScopes scopes) {
        if (created)
            throw new IllegalStateException("Runtime factory workspace is already fixed");
        workspace = path;
        writeScopes = scopes;
        return this;
    }

    /**
     * 仅为明确请求预算的调用方设置每轮累计用量软上限，默认不调用此入口。
     * 正整数包含同组 Agent 和辅助请求；/new、/resume 沿用显式策略。
     * 不修改用户配置、不发模型请求；工厂装配后或非正数输入立即失败。
     *
     * @param value 每轮累计用量的正数软上限，单位 token
     * @return 保存正数轮次 token 软上限后的当前工厂
     * @throws IllegalArgumentException 额度不是正数或运行时工厂已经使用
     */
    public RuntimeFactory tokenBudget(long value) {
        if (created || value < 1)
            throw new IllegalArgumentException("Set a positive budget before creating a runtime");
        tokenBudget = value;
        return this;
    }

    /**
     * 返回此工厂创建会话时使用的工作区目录。
     *
     * @return 后续会话实际使用的工作目录
     */
    public Path workspace() {
        return workspace;
    }

    /**
     * 返回当前执行机器的用户数据布局，供会话存储、缓存和服务接线使用。
     *
     * @return 本执行机器的用户数据布局
     */
    public com.jadecode.config.JadePaths paths() {
        return paths;
    }

    /**
     * 离线验证注入客户端和数据根，不读取真实认证或联网查询模型目录。
     *
     * @param settings 本次装配或保存的应用设置快照
     * @param permissions 会话与工具检查器共用的权限状态
     * @param config 已解析的模型运行配置，提供模型名、端点和请求额度
     * @param client 用于请求模型的客户端，由装配方确定具体后端
     * @param paths 执行机器用户数据根与配置、缓存布局
     */
    public RuntimeFactory(JadeSettings settings, PermissionState permissions, AppConfig config,
            com.jadecode.llm.LlmClient client, com.jadecode.config.JadePaths paths) {
        this.settings = settings;
        this.permissions = permissions;
        modelConfig = config;
        injectedClient = client;
        this.paths = paths;
    }

    /**
     * 读取工厂后续创建会话时共用的应用设置快照。
     *
     * @return 本会话共享的设置快照（只读）。
     */
    public JadeSettings settings() {
        return settings;
    }

    /**
     * 读取工厂与终端操作共用的权限状态，供新建与恢复会话沿用。
     *
     * @return 本会话共享的权限状态；TUI 与 headless 都必须用这一份。
     */
    public PermissionState permissions() {
        return permissions;
    }

    /**
     * 首次创建时固定工厂，使用注入客户端或按模型配置解析元数据并建立 SDK 客户端，发送模型资料事件。
     * 将工作区、设置、权限、显示与压缩依赖交给 JadeCodeFactory，应用显式轮次预算，返回待启动运行时。
     *
     * @param messages 会话消息接收者
     * @param streams 每次正式 Leader 请求的流式出口工厂
     * @param observer 工具、轮次、用量与后台状态观察器
     * @return 已完成装配、等待宿主恢复和启动的会话
     */
    public SessionRuntime create(Consumer<com.jadecode.execution.RuntimeMessage> messages,
            Supplier<ResponseStream> streams, ExecutionObserver observer) {
        created = true;
        // 真实启动只在这里发现模型参数；注入客户端的离线调用不联网、不读取认证。
        var client = injectedClient;
        if (client == null) {
            var metadata = new com.jadecode.llm.ModelMetadataResolver(
                    com.jadecode.llm.ModelMetadataResolver.defaultCacheDirectory())
                    .resolve(modelConfig, settings.modelMetadata());
            observer.publish(
                    com.jadecode.execution.ExecutionNotice.event(com.jadecode.execution.ExecutionNotice.Kind.MODEL,
                            "", modelConfig.model(), metadata.describe(), "READY").actor("leader"));
            client = new com.jadecode.llm.AnthropicSdkLlmClient(modelConfig, metadata);
        }
        var runtime = JadeCodeFactory.create(new JadeCodeFactory.Dependencies(modelConfig, workspace, client,
                settings, permissions, paths, writeScopes, managed, messages, streams, observer,
                com.jadecode.hooks.Hooks::new,
                (summary, workspacePaths) -> new com.jadecode.context.ProductionCompactor(summary, workspacePaths,
                        settings.contextCompaction())));
        if (tokenBudget > 0)
            runtime.executionTokenBudget(tokenBudget);
        return runtime;
    }
}
