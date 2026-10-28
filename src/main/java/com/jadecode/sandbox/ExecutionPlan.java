package com.jadecode.sandbox;

import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Harness 对一次确切工具调用签发的执行快照。模型不能用 JSON 自填本对象。
 * 计划固定 actor/run、权限版本、输入与挂载，后台线程收到同一份快照；不把临时授权放进共享档位。
 * reusable=false 的实例结束后立即删除；任务级挂载的授权本身另由 SandboxSession 管理。
 */
public record ExecutionPlan(String actor, String runId, long revision, String tool, ObjectNode input,
        Path workspace, boolean readOnly, boolean network, boolean reusable, List<Mount> mounts, List<Restriction> restrictions) {
    /** 没有用户额外路径限制的旧调用，仍使用默认敏感入口保护。 */
    public ExecutionPlan(String actor, String runId, long revision, String tool, ObjectNode input,
            Path workspace, boolean readOnly, boolean network, boolean reusable, List<Mount> mounts) {
        this(actor, runId, revision, tool, input, workspace, readOnly, network, reusable, mounts, List.of());
    }
    /** hidden 拒绝读取；否则扣回只读。路径必须在已暴露视图内，缺失或可变链接明确失败。 */
    public record Restriction(Path path, boolean hidden) { }
    /** 一个已校验的本地源与容器目标；首版保持同绝对路径，避免修改任意 Shell 文字。 */
    public record Mount(Path source, Path target, boolean writable) {
        public Mount {
            source = source.toAbsolutePath().normalize(); target = target.toAbsolutePath().normalize();
        }
    }
    /** 输入和列表复制，避免审批结束后被另一个线程改写。 */
    public ExecutionPlan { input = input.deepCopy(); mounts = List.copyOf(mounts); restrictions = List.copyOf(restrictions); }
    @Override public ObjectNode input() { return input.deepCopy(); }
}
