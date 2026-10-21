package com.jadecode.tui.completion;

import java.util.List;

/**
 * 一次补全请求的不可变快照与结果 DTO。
 *
 * <p>请求携带 generation/draftVersion/caret 和替换范围；结果必须原样带回这些身份，Editor
 * 只在新请求仍是最新且文本版本一致时采用。替换范围使用 UTF-16 偏移（不是显示列），避免
 * CJK/emoji 路径被切错。{@link Candidate} 的 id 是稳定选择值，label 只用于显示，不能拿
 * 可能重复的标题当唯一身份。</p>
 */
public final class CompletionRequest {
    /** 补全上下文类型。 */
    public enum Kind { COMMAND, PATH, MIXED }

    /**
     * 请求。
     * @param requestId 单调请求号
     * @param generation 会话代次
     * @param draftVersion 草稿版本（每次编辑递增）
     * @param text 完整草稿
     * @param caret UTF-16 光标
     * @param replaceFrom 替换起点（UTF-16）
     * @param replaceTo 替换终点（UTF-16）
     * @param kind 期望上下文
     */
    public record Request(long requestId, long generation, long draftVersion, String text, int caret,
            int replaceFrom, int replaceTo, Kind kind) {
        public Request {
            text = text == null ? "" : text;
            replaceFrom = Math.max(0, Math.min(replaceFrom, text.length()));
            replaceTo = Math.max(replaceFrom, Math.min(replaceTo, text.length()));
            caret = Math.max(0, Math.min(caret, text.length()));
        }
    }

    /** 一个候选；replace 范围未给出时沿用请求范围。 */
    public record Candidate(String id, String label, String description, int replaceFrom, int replaceTo,
            boolean executableOnEnter) {
        public Candidate {
            label = label == null ? "" : label;
            description = description == null ? "" : description;
        }
    }

    /**
     * 结果。
     * @param request 原请求身份
     * @param candidates 按优先级排序的候选
     * @param error 失败说明；成功为空
     */
    public record Result(Request request, List<Candidate> candidates, String error) {
        public Result { candidates = candidates == null ? List.of() : List.copyOf(candidates); }

        /** 成功结果。 */
        public static Result of(Request request, List<Candidate> candidates) { return new Result(request, candidates, ""); }

        /** 失败结果；Editor 保留原草稿并显示真实原因。 */
        public static Result failed(Request request, String error) { return new Result(request, List.of(), error); }

        /** @return 是否仍对应给定请求身份/草稿版本。 */
        public boolean matches(long requestId, long generation, long draftVersion, String text) {
            return request.requestId() == requestId && request.generation() == generation
                    && request.draftVersion() == draftVersion && request.text().equals(text);
        }
    }

    private CompletionRequest() { }
}
