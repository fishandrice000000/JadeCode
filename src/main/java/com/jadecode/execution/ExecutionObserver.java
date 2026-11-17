package com.jadecode.execution;

/**
 * 只读运行观察接口。与可短路的权限 Hook 分开：显示失败不改变工具结果，也不能批准执行。
 * 实现必须快速投递事件，不在调用线程渲染或等用户。未安装前端时使用 NONE。
 */
@FunctionalInterface
public interface ExecutionObserver {
    ExecutionObserver NONE = notice -> {
    };

    /**
     * 接收一条已经由执行路径产生的事实，供实现更新状态或投递界面事件。
     *
     * @param notice 当前执行事实，包含 Agent、事件种类、状态、工具身份与用量
     */
    void accept(ExecutionNotice notice);

    /**
     * 通知隔离：观察器的普通异常不能把已执行的工具改成批次失败。
     *
     * @param notice 当前执行事实，包含 Agent、事件种类、状态、工具身份与用量
     */
    default void publish(ExecutionNotice notice) {
        try {
            accept(notice);
        } catch (RuntimeException ignored) {
            /* 执行结果不归显示端裁决。 */ }
    }

    /**
     * 将所有事件绑定到工厂已知的 Agent；嵌套子代理可再增加显示编号。
     *
     * @param actor 宿主固定的 Agent 身份，供全部后续通知沿用
     * @return 把后续通知绑定到指定 Agent 身份的新观察器
     */
    default ExecutionObserver scoped(String actor) {
        return notice -> publish(notice.actor(notice.actor().isEmpty() ? actor : actor + "/" + notice.actor()));
    }
}
