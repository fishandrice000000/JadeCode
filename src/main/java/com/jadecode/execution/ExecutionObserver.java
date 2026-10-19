package com.jadecode.execution;

/**
 * 只读运行观察接口。与可短路的权限 Hook 分开：显示失败不改变工具结果，也不能批准执行。
 * 实现必须快速投递事件，不在调用线程渲染或等用户。未安装前端时使用 NONE。
 */
@FunctionalInterface
public interface ExecutionObserver {
    ExecutionObserver NONE = notice -> { };
    void accept(ExecutionNotice notice);
    /** 通知隔离：观察器的普通异常不能把已执行的工具改成批次失败。 */
    default void publish(ExecutionNotice notice) {
        try { accept(notice); } catch (RuntimeException ignored) { /* 执行结果不归显示端裁决。 */ }
    }
    /** 将所有事件绑定到工厂已知的 Agent；嵌套子代理可再增加显示编号。 */
    default ExecutionObserver scoped(String actor) {
        return notice -> publish(notice.actor(notice.actor().isEmpty() ? actor : actor + "/" + notice.actor()));
    }
}
