package com.jadecode.context;

import com.jadecode.llm.CompleteRequest;
import com.jadecode.llm.ModelResponse;
import com.jadecode.llm.ToolDefinition;
import com.jadecode.messages.Message;
import com.jadecode.util.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一条历史的 Token 锚点，不放在可被父子共享的压缩器里。正式响应的全部输入加输出只计一次，
 * 之后仅估算新消息。system、工具、思考设置或旧前缀改变时自动放弃锚点。
 * 这里不请求模型、不修改消息、不持久化计费账本；usage 缺失不被当作零占用。
 */
public final class ContextTokenTracker {
    private String system;
    private List<ToolDefinition> tools;
    private com.jadecode.llm.ThinkingSettings thinking;
    private List<Message> prefix;
    private long occupied;

    /** 当前输入的估计与来源；estimated=false 仅表示没有新增内容且使用了服务端锚点。 */
    public record Estimate(long tokens, boolean estimated) { }

    /**
     * 只在调用方确认助手回复完整且会被采用后登记。请求历史先复制，再追加该回复，
     * 所以 output 已包含对应助手文本/思考/工具参数，下一次不能再估算它们。
     */
    public synchronized void accepted(CompleteRequest request, ModelResponse response) {
        // 防御调用方遗漏校验：不把截断、拒绝或非助手消息登记为可复用的完整锚点。
        if (response.message().role() != com.jadecode.messages.Role.ASSISTANT
                || response.stopReason().equals("max_tokens") || response.stopReason().equals("refusal")) return;
        var usage = response.usage();
        if (usage == null || Math.addExact(usage.totalInputTokens(), usage.outputTokens()) == 0) return;
        system = request.system(); tools = List.copyOf(request.tools()); thinking = request.thinking();
        var afterReply = new ArrayList<>(request.messages()); afterReply.add(response.message());
        prefix = List.copyOf(afterReply);
        occupied = Math.addExact(usage.totalInputTokens(), usage.outputTokens());
    }

    /**
     * 检查真正准备发出的投影；追加只估算尾部，旧位置变化则整份重估，不复用旧窗口占用。
     * 返回值是预算启发式而非精确分词。消息来源字段只属宿主，不计入线上估算。
     */
    public synchronized Estimate estimate(CompleteRequest request) {
        if (prefix != null && Objects.equals(system, request.system()) && tools.equals(request.tools())
                && thinking == request.thinking() && request.messages().size() >= prefix.size()
                && prefix.equals(request.messages().subList(0, prefix.size()))) {
            long delta = messages(request.messages().subList(prefix.size(), request.messages().size()));
            return new Estimate(Math.addExact(occupied, delta), request.messages().size() != prefix.size());
        }
        return new Estimate(projected(request), true);
    }

    /** 历史替换后由拥有者清除；不影响别的 Agent，也不改已有计费记录。 */
    public synchronized void invalidate() { prefix = null; tools = null; system = null; occupied = 0; }

    /** 完整请求的本地估计：固定部分只计一次，认证、额度和内部来源不属于上下文。 */
    public static long projected(CompleteRequest request) {
        return Math.addExact(messages(request.messages()),
                Math.addExact(text(request.system()), text(Json.mapper().valueToTree(request.tools()).toString())) + 16);
    }

    /** 消息类型由既有 content JSON 表达，覆盖文本、思考、工具名/参数/结果，并留角色边界开销。 */
    public static long messages(List<Message> messages) {
        long total = 0;
        for (Message message : messages) total = Math.addExact(total, message(message));
        return total;
    }

    /** 单条消息预算，供近期尾部选择使用；不改消息中的任何块。 */
    public static long message(Message message) {
        return 8 + text(Json.mapper().valueToTree(message.content()).toString());
    }

    /**
     * 英文/代码按四个 ASCII 字符约一 Token，非 ASCII 按 UTF-8 字节保守补齐。
     * 不把中文也除以四；粗估仍可能有误差，不能宣称服务端绝不会拒绝。
     */
    public static long text(String value) {
        if (value == null || value.isEmpty()) return 0;
        long ascii = 0, unicode = 0;
        for (int index = 0; index < value.length();) {
            int point = value.codePointAt(index); index += Character.charCount(point);
            if (point < 128) ascii++;
            else unicode += point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
        }
        return (ascii + 3) / 4 + unicode;
    }
}
