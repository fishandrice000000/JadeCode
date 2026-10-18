package com.jadecode.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.tasks.Task;
import com.jadecode.tasks.TaskStore;
import com.jadecode.util.Json;

/**
 * 六个任务工具的接口适配：把模型参数转成 {@link TaskStore} 的受控操作，再把结果转成文字。
 *
 * <p>六个实例共用同一个工作区存储，不是六份任务库。状态、依赖和文件规则都在 TaskStore；
 * 本类只检查参数形状、声明参数结构、序列化结果，不调模型、不启动任务或子代理。
 */
public final class TaskTools {
    /** 本章只有一个任务管理者；模型不能通过参数冒充另一个 owner。 */
    private static final String OWNER = "agent";

    /** 纯装配工厂，不需要实例。 */
    private TaskTools() {
    }

    /**
     * 创建可注册进父循环的六把工具；调用本方法不读写任务文件。
     * @param store 已绑定工作区的共享存储
     * @return 六个不可变工具实例的不可修改列表
     * @throws NullPointerException store 为 null，属于装配错误
     */
    public static List<Tool> forStore(TaskStore store) {
        return forStore(store, OWNER);
    }

    /** 身份由装配固定，不让模型在工具参数中冒充另一个负责人。 */
    public static List<Tool> forStore(TaskStore store, String owner) {
        Objects.requireNonNull(store, "store");
        List<Tool> tools = new ArrayList<>();
        for (Action action : Action.values()) {
            tools.add(new TaskTool(store, action, owner));
        }
        return List.copyOf(tools);
    }

    /** 工具名与说明是固定协议，不接受运行期任意方法名或反射调用。 */
    private enum Action {
        CREATE("create_task", "Create a persistent pending task and return its generated ID. Add dependencies only after receiving real IDs."),
        UPDATE("update_task", "Add prerequisite IDs to a pending unowned task. All prerequisites must exist; cycles are rejected."),
        GET("get_task", "Read a persistent task including its full description, status, owner and dependencies."),
        LIST("list_tasks", "List all persistent tasks with IDs, subjects, status, owner and dependencies; does not start work."),
        CLAIM("claim_task", "Mark a pending task as in_progress for this agent, only when all prerequisites are completed. Does not execute it."),
        COMPLETE("complete_task", "Mark this agent's in_progress task completed after verifying its work; return newly unblocked tasks without starting them.");

        private final String name;
        private final String description;

        /** 固定元数据，不持有任务的当前状态。 */
        Action(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    /** 单个工具实例；Action 固定后只暴露对应参数与一个操作，不提供任意字段写盘接口。 */
    private static final class TaskTool implements Tool {
        private final TaskStore store;
        private final Action action;
        private final String owner;

        /** 仅由工厂构造，存储共享、动作固定，无文件或模型副作用。 */
        private TaskTool(TaskStore store, Action action, String owner) {
            this.store = store;
            this.action = action;
            this.owner = owner;
        }

        /** @return 本实例精确匹配 tool_use.name 的工具名 */
        @Override
        public String name() {
            return action.name;
        }

        /** @return 说明工具用途、限制和结果，不把“认领”描述成“实际执行” */
        @Override
        public String description() {
            return action.description;
        }

        /** @return 平铺参数类型；依赖数组的元素约束在 inputSchema 中补足 */
        @Override
        public Map<String, String> properties() {
            return switch (action) {
                case CREATE -> Map.of("subject", "string", "description", "string");
                case UPDATE -> Map.of("task_id", "string", "addBlockedBy", "array");
                case LIST -> Map.of();
                default -> Map.of("task_id", "string");
            };
        }

        /** @return 与 execute 一致的必填参数；description 可省略为课程默认空字符串 */
        @Override
        public List<String> required() {
            return switch (action) {
                case CREATE -> List.of("subject");
                case UPDATE -> List.of("task_id", "addBlockedBy");
                case LIST -> List.of();
                default -> List.of("task_id");
            };
        }

        /**
         * 声明完整 schema，禁止 owner / status / path 等额外字段；运行时仍重新校验，不能只信模型。
         * @return 新建 schema 对象，调用方修改它不会影响后续工具调用
         */
        @Override
        public ObjectNode inputSchema() {
            ObjectNode schema = ToolSchema.objectSchema(properties(), required());
            schema.put("additionalProperties", false);
            ObjectNode properties = (ObjectNode) schema.get("properties");
            if (properties.has("task_id")) {
                ((ObjectNode) properties.get("task_id")).put("pattern", Task.ID_PATTERN);
            }
            if (action == Action.CREATE) {
                ((ObjectNode) properties.get("subject")).put("minLength", 1);
            }
            if (action == Action.UPDATE) {
                ObjectNode dependencies = (ObjectNode) properties.get("addBlockedBy");
                dependencies.put("minItems", 1);
                dependencies.putObject("items").put("type", "string").put("pattern", Task.ID_PATTERN);
            }
            return schema;
        }

        /**
         * 执行固定的一项操作，错误作为 Error: 回灌给模型；不改变传入参数。
         *
         * <p>先检查全部参数形状，再调用存储。list 返回目录摘要，get / 变更返回完整记录，
         * complete 返回 task 与 newlyUnblocked。合法完成不表示下游已经执行。</p>
         * @param input 模型参数对象，null / 错误类型 / 额外字段明确拒绝
         * @return JSON 成功结果，或 Error: 开头的失败；普通 IO 含清理失败数量，不泄露文件正文
         */
        @Override
        public String execute(ObjectNode input) {
            try {
                // 1. schema 是给模型看的声明，执行时的判型才真正拦下 null、数字冒充字符串等输入。
                validate(input);
                Object result = switch (action) {
                    case CREATE -> store.create(input.get("subject").textValue(), input.has("description")
                            ? input.get("description").textValue() : "");
                    case UPDATE -> {
                        List<String> ids = new ArrayList<>();
                        for (JsonNode id : input.get("addBlockedBy")) {
                            ids.add(id.textValue());
                        }
                        yield store.updateDependencies(input.get("task_id").textValue(), ids);
                    }
                    case GET -> store.load(input.get("task_id").textValue());
                    case LIST -> {
                        // 不把全部 description 塞入上下文；模型需要任务正文时再 get_task。
                        var catalog = Json.mapper().createArrayNode();
                        for (Task task : store.list()) {
                            ObjectNode item = Json.mapper().valueToTree(task);
                            item.remove("description");
                            catalog.add(item);
                        }
                        yield catalog;
                    }
                    case CLAIM -> store.claim(input.get("task_id").textValue(), owner);
                    case COMPLETE -> {
                        var completed = store.complete(input.get("task_id").textValue(), owner);
                        var reply = Json.mapper().createObjectNode();
                        reply.set("task", Json.mapper().valueToTree(completed.task()));
                        reply.set("newlyUnblocked", Json.mapper().valueToTree(completed.unblocked()));
                        yield reply;
                    }
                };
                // 2. 存储方法只有在成功写完后才返回值；不能拿模型要求的状态假装落盘成功。
                return Json.mapper().writeValueAsString(result);
            } catch (IllegalArgumentException invalid) {
                return "Error: " + invalid.getMessage();
            } catch (IOException failure) {
                return "Error: Task IO failure (" + failure.getClass().getSimpleName()
                        + "), cleanup errors: " + failure.getSuppressed().length;
            }
        }

        /** 整体形状检查无 IO 副作用；数组元素也逐个判型，ID 语法与图关系交给存储层。 */
        private void validate(ObjectNode input) {
            if (input == null) {
                throw new IllegalArgumentException("参数必须是对象");
            }
            for (String required : required()) {
                if (!input.has(required)) {
                    throw new IllegalArgumentException("Missing required parameter: " + required);
                }
            }
            var fields = input.fields();
            Map<String, String> types = properties();
            while (fields.hasNext()) {
                var field = fields.next();
                String type = types.get(field.getKey());
                JsonNode value = field.getValue();
                if (type == null) {
                    throw new IllegalArgumentException("Unknown parameter: " + field.getKey());
                }
                if (type.equals("string") ? !value.isTextual() : !value.isArray()) {
                    throw new IllegalArgumentException("Invalid parameter type: " + field.getKey());
                }
                if (value.isArray()) {
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException("addBlockedBy 不能为空");
                    }
                    for (JsonNode id : value) {
                        if (!id.isTextual()) {
                            throw new IllegalArgumentException("依赖必须是文本 ID");
                        }
                    }
                }
            }
        }
    }
}
