package com.jadecode.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型的任务计划表:内存持有,经 todo_write 工具整表更新。
 *
 * <p>更新方式是整体替换：update() 收到的是完整计划（不是增量补丁），
 * 全部校验通过才换页;任何一项失败整批拒绝、旧列表原样保留——
 * 保证模型每次读到的渲染文本永远等于账本的真实状态。
 * </p>
 *
 * <p>
 * 模型看不到内部列表,渲染文本(render)是与它沟通的唯一通道:
 * 每次成功更新后,新列表文本立即作为工具输出返回给模型。
 * </p>
 */
public class TodoManager {
    /** 一次计划更新的实际结果；失败时旧计划不变，正文无需解析错误前缀。 */
    public record UpdateResult(String text, boolean error) {
    }

    /** 当前计划:只有整体校验通过的列表才会进到这里 */
    private List<Todo> items = List.of();

    private static final int MAX_TODO_COUNT = 20;

    /**
     * 导出已经通过 update 校验的实际计划，给会话日志保存；不是重新读取模型的工具参数。
     * 每次构造新数组，调用者修改返回值不会改当前账本，无磁盘和模型副作用。
     */
    public ArrayNode snapshot() {
        ArrayNode result = com.jadecode.util.Json.mapper().createArrayNode();
        for (Todo todo : items)
            result.addObject().put("content", todo.content())
                    .put("status", todo.status().name().toLowerCase(java.util.Locale.ROOT));
        return result;
    }

    /**
     * 加载日志保存的整份计划，不执行 todo_write 工具，也不把 in_progress 当成线程仍在运行。
     * 先严格检查结构再复用原列表校验；失败时旧计划不变，调用者应拒绝恢复这份日志。
     * @throws IllegalArgumentException 快照字段或计划约束非法
     */
    public void restore(ArrayNode snapshot) {
        if (snapshot == null)
            throw new IllegalArgumentException("Todo snapshot is missing");
        for (JsonNode item : snapshot) {
            if (!item.isObject() || item.size() != 2 || !item.path("content").isTextual()
                    || !item.path("status").isTextual())
                throw new IllegalArgumentException("Todo snapshot fields are invalid");
        }
        UpdateResult result = update(snapshot.deepCopy());
        if (result.error())
            throw new IllegalArgumentException(result.text());
    }

    /**
     * 以整份新计划替换当前列表(全集,非增量)。
     *
     * <p>
     * 校验规则:总数不超过 20;每项必须是对象,content 去首尾空白后非空,
     * status 可识别(缺省 pending,大小写不敏感);全局至多一项 in_progress。
     * 任何一条不满足即整批拒绝并返回 "Error: ..." 文案,旧列表不动。
     * </p>
     *
     * @param todos 模型传来的计划数组,元素形如 {"content": "...", "status": "pending"}
     * @return 成功返回新计划文本与 error=false；失败返回原因与 error=true，旧计划不变
     */
    public UpdateResult update(ArrayNode todos) {
        // 先在临时列表检查整份计划，任一失败都不能污染当前 items。
        List<Todo> validated = new ArrayList<>();
        List<Integer> inProgressIndex = new ArrayList<>();

        if (todos.size() > MAX_TODO_COUNT) {
            return new UpdateResult("Error: Max " + MAX_TODO_COUNT + " todos allowed", true);
        }

        // 下标用于向模型指出非法项；规范化后的内容只放进临时列表。
        for (int i = 0; i < todos.size(); i++) {
            JsonNode todo = todos.get(i);

            if (!(todo instanceof ObjectNode)) {
                return new UpdateResult("Error: todos[" + i + "] must be an object", true);
            }

            String content = todo.path("content").asText("").strip();
            if (content.isBlank()) {
                return new UpdateResult("Error: todos[" + i + "] requires content", true);
            }

            String statusStr = todo.path("status").asText("pending");
            Status status = Status.fromString(statusStr);
            if (status == null) {
                return new UpdateResult("Error: todos[" + i + "] has invalid status '" + statusStr + "'", true);
            }

            if (status == Status.IN_PROGRESS) {
                inProgressIndex.add(i);
            }
            validated.add(new Todo(content, status));
        }

        if (inProgressIndex.size() > 1) {
            return new UpdateResult(
                    "Error: Only one todo can be in_progress at a time: in_progress index " + inProgressIndex, true);
        }

        // 全部结构与“至多一项进行中”均通过后，再一次性采用新计划。
        items = validated;
        return new UpdateResult(render(), false);
    }

    /**
     * 把当前计划渲染成模型可读的文本。
     *
     * <p>
     * 格式:每行 "{marker} {content}",marker 按状态取 [ ] / [>] / [x];
     * 尾部空行后附 "(done/total completed)" 计数;空表返回 "No todos."。
     * </p>
     *
     * @return 当前计划的文本表示
     */
    public String render() {
        if (items.isEmpty()) {
            return "No todos.";
        }

        List<String> lines = new ArrayList<>();
        int completedCount = 0;

        for (Todo t : items) {
            lines.add(Status.marker(t.status()) + " " + t.content());
            if (t.status() == Status.COMPLETED) {
                completedCount++;
            }
        }

        lines.add("");
        lines.add("(" + completedCount + "/" + items.size() + " completed)");

        return String.join("\n", lines);
    }

    /** 一项计划:干什么 + 状态(入库前已规范化:content 去空白,status 枚举化) */
    private record Todo(String content, Status status) {
    }

    /** 任务状态:入库后只允许这三种(见 {@link #fromString(String)}) */
    private enum Status {
        PENDING, IN_PROGRESS, COMPLETED;

        /**
         * 从字符串解析状态。
         *
         * @param value 状态字符串,如 "pending"、"IN_PROGRESS"(大小写不敏感,容忍首尾空白)
         * @return 对应的 Status;null 或无法识别的值返回 null(由调用方负责报错)
         */
        private static Status fromString(String value) {
            if (value == null)
                return null;
            switch (value.toLowerCase().strip()) {
                case "pending":
                    return PENDING;
                case "in_progress":
                    return IN_PROGRESS;
                case "completed":
                    return COMPLETED;
                default:
                    return null;
            }
        }

        /**
         * 状态 → 渲染符号。
         *
         * @param status 状态(只可能来自 {@link #fromString(String)} 的合法结果)
         * @return "[ ]"(pending) / "[>]"(in_progress) / "[x]"(completed)
         */
        private static String marker(Status status) {
            return switch (status) {
                case PENDING -> "[ ]";
                case IN_PROGRESS -> "[>]";
                case COMPLETED -> "[x]";
            };
        }
    }

}
