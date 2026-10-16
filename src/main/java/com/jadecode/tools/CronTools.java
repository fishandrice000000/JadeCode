package com.jadecode.tools;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cron.CronScheduler;
import com.jadecode.util.Json;

/**
 * 三把定时工具：模型只能管理时间规则和 prompt，不能指定文件路径，也不能直接改交付状态。
 * 三个实例共用父会话的一个调度器，仍然由 AgentLoop 在权限检查通过后执行。
 */
public final class CronTools {
    private CronTools() { }

    /** 构造工具定义，不读写磁盘、不启动调度线程。 */
    public static List<Tool> forScheduler(CronScheduler scheduler) {
        return List.of(new CronTool(scheduler, "schedule_cron"), new CronTool(scheduler, "list_crons"),
                new CronTool(scheduler, "cancel_cron"));
    }

    /** 工具名固定，schema 与执行分支共用同一个名字，不暴露任意状态编辑入口。 */
    private record CronTool(CronScheduler scheduler, String name) implements Tool {
        /** @return 区分保存定义、只读列举与取消；durable 不等于 CLI 退出后还会执行。 */
        @Override
        public String description() {
            return switch (name) {
                case "schedule_cron" -> "Schedule a prompt using five-field local-time cron. recurring and durable default to true."
                        + " Runs only while this REPL is alive; durable preserves state, not an external daemon. Delivery may repeat after crashes.";
                case "list_crons" -> "List cron definitions and pending delivery state; does not run them.";
                default -> "Cancel a cron definition by job_id. Does not undo an already delivered task.";
            };
        }

        /** @return 模型可填写字段；pending_delivery / last_fired / path 均由 Harness 管理。 */
        @Override
        public Map<String, String> properties() {
            return switch (name) {
                case "schedule_cron" -> Map.of("cron", "string", "prompt", "string", "recurring", "boolean", "durable", "boolean");
                case "cancel_cron" -> Map.of("job_id", "string");
                default -> Map.of();
            };
        }

        /** @return 两个布尔可省略；取消必须使用实际返回的 ID。 */
        @Override
        public List<String> required() {
            return switch (name) {
                case "schedule_cron" -> List.of("cron", "prompt");
                case "cancel_cron" -> List.of("job_id");
                default -> List.of();
            };
        }

        /** @return 明确拒绝额外参数的 schema；运行时仍重新校验，不信任模型自报合法。 */
        @Override
        public ObjectNode inputSchema() {
            ObjectNode schema = ToolSchema.objectSchema(properties(), required());
            schema.put("additionalProperties", false);
            return schema;
        }

        /**
         * 先校验完整输入再操作；失败作为 Error 回灌，输入对象不修改。
         * @param input 模型 JSON 参数，不允许数字/字符串冒充布尔或控制内部状态
         * @return JSON 定义/列表或取消回执，不表示定时 prompt 已执行
         */
        @Override
        public String execute(ObjectNode input) {
            try {
                if (input == null) throw new IllegalArgumentException("Input must be an object");
                var fields = input.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    String type = properties().get(field.getKey());
                    JsonNode value = field.getValue();
                    if (type == null || (type.equals("string") ? !value.isTextual() : !value.isBoolean())) {
                        throw new IllegalArgumentException("Invalid parameter: " + field.getKey());
                    }
                }
                for (String key : required()) {
                    if (!input.has(key)) throw new IllegalArgumentException("Missing required parameter: " + key);
                }
                // 只有全部形状检查通过才调用存储，默认 true 不把显式 false 覆盖掉。
                return switch (name) {
                    case "schedule_cron" -> Json.mapper().writeValueAsString(scheduler.schedule(input.get("cron").textValue(),
                            input.get("prompt").textValue(), input.path("recurring").asBoolean(true), input.path("durable").asBoolean(true)));
                    case "list_crons" -> Json.mapper().writeValueAsString(scheduler.list());
                    default -> {
                        scheduler.cancel(input.get("job_id").textValue());
                        yield "Cancelled " + input.get("job_id").textValue();
                    }
                };
            } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                return "Error: " + failure.getMessage();
            }
        }
    }
}
