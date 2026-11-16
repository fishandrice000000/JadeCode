package com.jadecode.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.todo.TodoManager;
import com.jadecode.util.Json;

/**
 * todo_write 工具：模型用整份列表更新当前计划。
 *
 * <p>本类只做接口适配：缺参数、类型不对在这里拦住；列表校验和整表替换在
 * {@link TodoManager#update(ArrayNode)}。发给模型的参数结构是手拼的数组（见 {@link #inputSchema()}），
 * 平铺的 properties() 和 required() 只用来检查参数有没有缺。
 */
public class TodoWriteTool implements Tool {
    /** 计划账本:元素校验与整表替换都在它那边 */
    private final TodoManager manager;

    /**
     * @param manager TodoManager;Main 装配时 new 一个注入
     */
    public TodoWriteTool(TodoManager manager) {
        this.manager = manager;
    }

    /**
     * @return 工具名 "todo_write"
     */
    public String name() {
        return "todo_write";
    }

    /**
     * @return 给模型的说明:创建并维护当前会话的任务清单
     */
    public String description() {
        return "Create and manage a task list for your current coding session.";
    }

    /**
     * @return 必填 [todos];execute 的缺参检查循环以此为准
     */

    private List<String> required() {
        return List.of("todos");
    }

    /**
     * 完整 JSON Schema 手拼:properties()/required() 平铺表只能表达单层标量,
     * 表达不了 array + 嵌套 object + enum。maxItems 20 须与 TodoManager 上限同步
     * (改一边别忘另一边)。
     *
     * @return 数组结构 schema 根节点,序列化后随 todo_write 定义发给模型
     */
    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("type", "object");

        // root.properties.todos = {type: array, maxItems: 20, items: {...}}
        ObjectNode todos = root.putObject("properties").putObject("todos");
        todos.put("type", "array").put("maxItems", 20);

        // todos.items = {type: object, properties: {...}, required: [content, status]}
        ObjectNode item = todos.putObject("items");
        item.put("type", "object");

        ObjectNode itemProps = item.putObject("properties");
        itemProps.putObject("content").put("type", "string").put("minLength", 1);
        ObjectNode status = itemProps.putObject("status");
        status.put("type", "string");
        status.putArray("enum").add("pending").add("in_progress").add("completed");

        item.putArray("required").add("content").add("status");
        root.putArray("required").add("todos");
        return root;
    }

    /**
     * 用一份新的完整计划替换旧列表(整表,非增量)。
     *
     * <p>
     * 先按 required() 拦缺失参数,再拦类型错——两者都是回灌给模型自纠的
     * "Error: ..." 文案;元素级校验与整体换页全部委托给 TodoManager。
     * </p>
     *
     * @param input 模型参数,须含 todos 数组,元素形如 {"content": "...", "status": "pending"}
     * @return 执行结果（content 保留输出，isError 明确报告失败）：成功:新列表的渲染文本(来自 manager);失败:"Error: ..." 文案
     * @param context Harness 提供的任务来源与取消检查；不从模型参数读取
     */
    public com.jadecode.tools.ToolOutcome executeOutcome(ObjectNode input,
            com.jadecode.execution.ExecutionContext context) {
        for (String key : required()) {
            if (input.get(key) == null) {
                return new com.jadecode.tools.ToolOutcome("Error: Missing required parameter: " + key, true);
            }
        }
        if (!input.get("todos").isArray()) {
            return new com.jadecode.tools.ToolOutcome("Error: todos must be a list", true);
        }
        // 状态由计划校验产生，不能把错误文案包装成默认成功。
        var result = manager.update((ArrayNode) input.get("todos"));
        return new ToolOutcome(result.text(), result.error());
    }
}
