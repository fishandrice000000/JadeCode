package com.jadecode.agent;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.jadecode.safety.PermissionGate;

/**
 * 记录这项任务由谁发起，以及程序如何处理它的权限和退出。
 *
 * <p>用户和定时器都用同一线程执行任务，不能靠线程判断要不要询问用户。
 * 所以程序在排队前记录 source，再原样传给工具和子代理；模型不能在 JSON 里填写它。
 * 子代理接到定时任务仍然是 SCHEDULED，不能因此获得人工批准。</p>
 *
 * <p>id 用于找回这项任务启动的后台命令。approval 是询问用户的函数，不负责判断哪些命令
 * 需要批准；那由 PermissionGate 决定。sessionClosed 是一个检查函数，调用时才读取
 * 用户是否退出，让子代理在等模型或等批准后，也能停止后续操作。</p>
 * @param id 本次执行的 run_ ID，不同于定时定义 ID、工具调用 ID 和后台命令 bg_id
 * @param source USER 表示用户提交，SCHEDULED 表示定时触发
 * @param approval 询问用户的函数，null 表示不能询问
 * @param sessionClosed 检查所属会话是否已退出的函数，由程序提供，不是模型参数
 */
public record ExecutionContext(String id, Source source, PermissionGate.Approval approval, BooleanSupplier sessionClosed) {
    /**
     * 区分用户直接发起和定时触发。后台结果和 Stop 要求继续，都沿用原任务来源。
     */
    public enum Source {
        /** 用户直接提交的任务；还需要有询问用户的函数，且会话没退出，才能申请批准。 */
        USER,
        /** 时间到了自动触发的任务，工具和子代理都不能向用户申请批准。 */
        SCHEDULED
    }

    /**
     * 检查任务 ID 和必须提供的字段，不在构造时检查命令权限或执行任何工作。
     * @param id 非空白的任务 ID
     * @param source 任务来源，不可为 null
     * @param approval 可以为 null；即使提供，SCHEDULED 也不能询问用户
     * @param sessionClosed 检查会话是否退出的函数，不可为 null，应能安全地由不同线程调用
     * @throws IllegalArgumentException id 为 null 或空白
     * @throws NullPointerException source 或 sessionClosed 为 null
     */
    public ExecutionContext {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Execution ID is required");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sessionClosed, "sessionClosed");
    }

    /**
     * 给没有父会话的旧调用者使用，默认 isCancelled 一直返回 false。
     *
     * <p>真正的 CLI 任务应传四个参数，把检查会话退出的函数也传进来，
     * 否则子代理不知道父会话已经退出。这里默认 false 不代表跳过权限检查。</p>
     * @param id 非空白的任务 ID
     * @param source 任务来源
     * @param approval 询问用户的函数，可为 null
     * @throws IllegalArgumentException id 为 null 或空白
     * @throws NullPointerException source 为 null
     */
    public ExecutionContext(String id, Source source, PermissionGate.Approval approval) {
        this(id, source, approval, () -> false);
    }

    /**
     * 创建一个新的 run_ ID，供独立调用者执行一项任务，不启动线程或命令。
     * @param source 任务来自用户还是定时器
     * @param approval 询问用户的函数，可为 null
     * @return 新任务的信息，没有父会话退出检查，isCancelled 默认一直为 false
     * @throws NullPointerException source 为 null
     */
    public static ExecutionContext create(Source source, PermissionGate.Approval approval) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval);
    }

    /**
     * 为刚提交的任务分配 run_ ID，并记下以后如何检查用户是否退出。
     *
     * <p>同一任务的工具和子代理使用同一份信息，不另换来源或任务 ID。
     * 这里仅保存函数，不调用它来等待退出，也不启动线程、模型或命令。</p>
     * @param source 程序确定的任务来源，不能从模型文字中取
     * @param approval 询问用户的函数，可为 null
     * @param sessionClosed 检查会话是否退出的函数，例如读取 SessionRuntime 的 volatile closed 字段
     * @return 新任务的信息，工具和子代理沿用它
     * @throws NullPointerException source 或 sessionClosed 为 null
     */
    public static ExecutionContext create(Source source, PermissionGate.Approval approval, BooleanSupplier sessionClosed) {
        return new ExecutionContext("run_" + UUID.randomUUID(), source, approval, sessionClosed);
    }

    /**
     * 检查用户现在是否已退出这项任务所在的会话。
     * @return 已退出为 true；false 只表示此刻没退出，不保证下一刻仍然如此。
     *         true 也不会还原已经写入的文件或自动停止所有正在进行的操作
     */
    public boolean isCancelled() { return sessionClosed.getAsBoolean(); }
}
