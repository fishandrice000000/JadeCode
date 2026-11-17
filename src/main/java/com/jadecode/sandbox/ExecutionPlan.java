package com.jadecode.sandbox;

import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Harness 对一次确切工具调用签发的执行快照。模型不能用 JSON 自填本对象。
 * 计划固定 actor/run、权限版本、输入与挂载，后台线程收到同一份快照；不把临时授权放进共享档位。
 * reusable=false 的实例结束后立即删除；任务级挂载的授权本身另由 SandboxSession 管理。
 *
 * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
 * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
 * @param revision 本次权限快照版本，批准后执行前用它核对权限是否变化
 * @param tool 模型调用的工具注册名
 * @param input 本次工具参数 JSON 对象，包含该工具 schema 约定的字段
 * @param workspace 会话绑定的真实工作区目录
 * @param readOnly 当前计划是否按只读权限执行
 * @param network 该次计划或服务是否需要容器桥接网络
 * @param reusable 该实例是否可以按相同策略复用
 * @param mounts 固定的宿主来源、容器目标及读写方式列表
 * @param restrictions 需要覆盖为隐藏或只读的路径限制列表
 */
public record ExecutionPlan(String actor, String runId, long revision, String tool, ObjectNode input,
        Path workspace, boolean readOnly, boolean network, boolean reusable, List<Mount> mounts,
        List<Restriction> restrictions) {

    /**
     * hidden 拒绝读取；否则扣回只读。路径必须在已暴露视图内，缺失或可变链接明确失败。
     *
     * @param path 本次读取、授权或校验的路径，按方法说明解析到实际目标
     * @param hidden 该保护点是否需要隐藏；false 表示覆盖为只读
     */
    public record Restriction(Path path, boolean hidden) {
    }

    /**
     * 一个已校验的本地源与容器目标；首版保持同绝对路径，避免修改任意 Shell 文字。
     *
     * @param source 容器可见数据对应的宿主绝对来源路径
     * @param target 容器内访问这份来源的绝对目标路径
     * @param writable 该挂载是否允许写入宿主来源
     */
    public record Mount(Path source, Path target, boolean writable) {
        /**
         * 将宿主来源路径和容器目标路径转为规范化绝对路径，供复用键比较与 Docker bind mount 参数生成使用。
         *
         * @param source 容器可见数据对应的宿主绝对来源路径
         * @param target 容器内访问这份来源的绝对目标路径
         * @param writable 该挂载是否允许写入宿主来源
         */
        public Mount {
            source = source.toAbsolutePath().normalize();
            target = target.toAbsolutePath().normalize();
        }
    }

    /**
     * 输入和列表复制，避免审批结束后被另一个线程改写。
     *
     * @param actor 产生此操作的 Agent 身份，用于事件展示、用量归属和资源回收
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     * @param revision 本次权限快照版本，批准后执行前用它核对权限是否变化
     * @param tool 模型调用的工具注册名
     * @param input 本次工具参数 JSON 对象，包含该工具 schema 约定的字段
     * @param workspace 会话绑定的真实工作区目录
     * @param readOnly 当前计划是否按只读权限执行
     * @param network 该次计划或服务是否需要容器桥接网络
     * @param reusable 该实例是否可以按相同策略复用
     * @param mounts 固定的宿主来源、容器目标及读写方式列表
     * @param restrictions 需要覆盖为隐藏或只读的路径限制列表
     */
    public ExecutionPlan {
        input = input.deepCopy();
        mounts = List.copyOf(mounts);
        restrictions = List.copyOf(restrictions);
    }

    /**
     * 返回本次执行计划固定参数的深拷贝，供 Worker 请求和 shell 参数核对使用。
     *
     * @return 当前计划已固定参数的深拷贝
     */
    @Override
    public ObjectNode input() {
        return input.deepCopy();
    }
}
