package com.jadecode.execution;

/**
 * 程序生成的一整条展示消息；身份、标签和失败标志由 Harness 显式提供。
 * 模型正文保持原样，哪怕以 [Leader] 或 [Error] 开头也只是正文。
 * 此对象只用于显示，不授予审批权，也不改变模型历史。
 */
public record RuntimeMessage(String actor, String label, String text, boolean error) {
    /** 同一消息绑定所属 Agent；只重建身份，保留原正文和状态，无 IO。 */
    public RuntimeMessage actor(String value) {
        return new RuntimeMessage(value, label, text, error);
    }
}
