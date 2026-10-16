package com.jadecode.execution;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 询问用户“是否允许这次操作”的函数，不负责决定哪些命令危险。
 *
 * <p>正式运行用 ConsoleInput：执行线程等回答，终端线程读输入。
 * 离线检查可以直接返回 true/false。这个函数由程序传给任务，不是模型可填写的参数。</p>
 */
@FunctionalInterface
public interface Approval {
    /**
     * 请用户明确同意或拒绝这次操作，可以等用户回答，但不能绕过前面的绝对禁止规则。
     * 没有答复、会话退出或线程中断时应按拒绝处理，不把任意下一行输入当作批准。
     * @param toolName 待批准工具名
     * @param input 展示的工具参数
     * @param reason 为什么需要先问用户
     * @return true 表示明确同意，false 表示拒绝
     */
    boolean ask(String toolName, ObjectNode input, String reason);
}
