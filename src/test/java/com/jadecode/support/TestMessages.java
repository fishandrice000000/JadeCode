package com.jadecode.support;

import java.util.function.Consumer;
import com.jadecode.execution.RuntimeMessage;

/** 测试边界的纯文本显示器，保留原有输出断言；不解析或修改消息正文。 */
public final class TestMessages {
    /** 仅在测试收集器将结构化字段拼成可读文字，不进入生产消息数据流。 */
    public static Consumer<RuntimeMessage> toText(Consumer<String> output, Consumer<String> errors) {
        return message -> (message.error() ? errors : output).accept(
                (message.actor().isBlank() ? ""
                        : "[" + (message.actor().equals("leader") ? "Leader" : "Teammate " + message.actor()) + "] ")
                        + "[" + message.label() + "] " + message.text());
    }

    private TestMessages() {
    }
}
