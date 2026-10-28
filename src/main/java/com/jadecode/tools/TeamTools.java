package com.jadecode.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.execution.AssignmentScope;
import com.jadecode.tasks.Task;
import com.jadecode.team.TeamManager;
import com.jadecode.util.Json;

/**
 * 把 Team 的固定操作开放给模型，调用者身份在装配时绑定，不接受 from_agent 参数。
 * Leader 有管理与派工工具，队友只有通信和提交结果工具；任务图只由 Leader 管理。
 * 这是工具层的弱隔离，不阻止共享工作区 bash 直接改文件，不声称实现了沙箱。
 */
public final class TeamTools {
    private TeamTools() { }

    /** 创建当前身份可见的工具列表，不启动队友、写文件或请求模型。 */
    public static List<Tool> forActor(TeamManager team, String actor) {
        List<String> names = new ArrayList<>(List.of("send_message", "retry_team_message"));
        if (actor.equals(TeamManager.LEADER)) names.addAll(List.of("spawn_teammate", "list_teammates",
                "assign_task", "shutdown_teammate", "reopen_task", "review_task_result"));
        else names.add("submit_task_result");
        return names.stream().map(name -> (Tool) new TeamTool(team, actor, name)).toList();
    }

    /** 一份工具绑定一个动作和真实身份，避免模型改变参数后获得 Leader 职责。 */
    private record TeamTool(TeamManager team, String actor, String name) implements Tool {
        /** 说明完成事实，不把派工或投递成功说成业务完成。 */
        @Override public String description() {
            return switch (name) {
                case "spawn_teammate" -> "Create an idle persistent teammate with empty independent history. Max 3. role MUST be a short English name on line 1, newline, then responsibilities. Example: Explorer\\nInvestigate execution flow. Returns agent_id; assign separately.";
                case "list_teammates" -> "List actual teammate runtime states. IDLE is not evidence of business success.";
                case "assign_task" -> "Claim an existing ready task for a teammate, persist instructions, then enqueue. Returns assignment_id. A busy teammate queues it. wait_for_completion defaults true: the current leader turn waits for the report. scope is a fixed snapshot with read_paths and write_paths relative to the workspace. Use English task titles and summaries.";
                case "shutdown_teammate" -> "Close a teammate and its processes. Unfinished assignments become blocked. Does not undo file changes.";
                case "reopen_task" -> "Explicitly restore a failed/blocked task to pending before reassignment. Does not execute business work.";
                case "submit_task_result" -> "Submit outcome completed/failed/blocked, short summary and full report. Persists body, updates task, notifies leader. Retry with identical arguments after partial failure; do not redo business.";
                case "review_task_result" -> "Record leader review notes for the exact assignment_id and report_id. Does not change task dependencies or prove correctness.";
                case "retry_team_message" -> "Retry only a previously failed delivery by message_id. Does not rerun commands or overwrite message bodies.";
                default -> "Send summary plus persisted full body to leader/a teammate. kind=message/question/progress/supplement. Defaults: insertion=true; wake=true except progress=false. Teammates may only message leader. Include task_id and assignment_id for task-specific messages. Set reply_to to the original question message_id when replying. Use short English UI summaries.";
            };
        }

        /** JSON 参数统一 snake_case；摘要与正文由模型分别生成，不额外调用摘要模型。 */
        @Override public Map<String, String> properties() {
            return switch (name) {
                case "spawn_teammate" -> Map.of("role", "string");
                case "list_teammates" -> Map.of();
                case "assign_task" -> Map.of("agent_id", "string", "task_id", "string", "summary", "string", "body", "string",
                        "wait_for_completion", "boolean", "scope", "object");
                case "shutdown_teammate" -> Map.of("agent_id", "string");
                case "reopen_task" -> Map.of("task_id", "string");
                case "review_task_result" -> Map.of("assignment_id", "string", "report_id", "string", "notes", "string");
                case "retry_team_message" -> Map.of("message_id", "string");
                case "submit_task_result" -> Map.of("assignment_id", "string", "status", "string", "summary", "string", "body", "string");
                default -> Map.of("to_agent", "string", "task_id", "string", "assignment_id", "string",
                        "kind", "string", "summary", "string", "body", "string", "reply_to", "string",
                        "allow_insertion", "boolean", "wake_when_idle", "boolean");
            };
        }

        /** 必填字段和执行时校验一致，可选参数不能用 null 冒充缺省。 */
        @Override public List<String> required() {
            return switch (name) {
                case "spawn_teammate" -> List.of("role");
                case "list_teammates" -> List.of();
                case "assign_task" -> List.of("agent_id", "task_id", "summary", "body");
                case "shutdown_teammate" -> List.of("agent_id");
                case "reopen_task" -> List.of("task_id");
                case "review_task_result" -> List.of("assignment_id", "report_id", "notes");
                case "retry_team_message" -> List.of("message_id");
                case "submit_task_result" -> List.of("assignment_id", "status", "summary", "body");
                default -> List.of("to_agent", "summary", "body");
            };
        }

        /** 不接受未知字段，模型不能通过额外 source/owner 绕过真实调用方身份。 */
        @Override public ObjectNode inputSchema() {
            ObjectNode schema = Tool.super.inputSchema();
            schema.put("additionalProperties", false);
            if (name.equals("assign_task")) {
                // 嵌套范围需要完整 schema；不能只声明 object 却不给模型说明列表元素。
                ObjectNode scope = (ObjectNode) schema.path("properties").path("scope");
                scope.put("additionalProperties", false);
                ObjectNode props = scope.putObject("properties");
                for (String key : List.of("read_paths", "write_paths")) {
                    ObjectNode paths = props.putObject(key).put("type", "array").put("maxItems", 64);
                    paths.putObject("items").put("type", "string");
                }
                scope.putArray("required").add("read_paths").add("write_paths");
            }
            if (name.equals("spawn_teammate")) {
                ((ObjectNode) schema.path("properties").path("role")).put("description",
                        "First line: short English display name. Following lines: responsibilities. Example: Explorer\\nInvestigate execution flow.");
            }
            return schema;
        }

        /** 执行时也校验嵌套字段；schema 不是权限边界，不能信任模型已通过服务端校验。 */
        private static AssignmentScope scope(ObjectNode input) {
            if (!input.has("scope")) return AssignmentScope.EMPTY;
            var value = input.path("scope");
            var fields = value.fieldNames();
            while (fields.hasNext()) if (!List.of("read_paths", "write_paths").contains(fields.next()))
                throw new IllegalArgumentException("Unknown scope field");
            return new AssignmentScope(paths(value.get("read_paths")), paths(value.get("write_paths")));
        }

        /** 复制纯字符串数组，拒绝 null、标量或混入其它类型；不修改 JSON 输入。 */
        private static List<String> paths(com.fasterxml.jackson.databind.JsonNode value) {
            if (value == null || !value.isArray()) throw new IllegalArgumentException("Scope requires read_paths and write_paths arrays");
            List<String> result = new ArrayList<>();
            for (var path : value) {
                if (!path.isTextual()) throw new IllegalArgumentException("Scope paths must be strings");
                result.add(path.textValue());
            }
            return result;
        }

        /** Team 派工需要可信 context；旧的无 context 调用明确失败，不伪造用户授权。 */
        @Override public String execute(ObjectNode input) { return "Error: Team tools require execution context"; }

        /**
         * 先检查完整参数，再调用唯一动作。普通参数/IO 错误作为工具结果返回，留给模型决策。
         * 不自动重试业务；部分失败的报告或消息 ID 保留在错误说明与文件中。
         */
        @Override public String execute(ObjectNode input, ExecutionContext context) {
            try {
                if (context == null || context.isCancelled()) throw new IllegalStateException("Caller exited or has no task source");
                for (String key : required()) if (!input.has(key)) throw new IllegalArgumentException("Missing parameter: " + key);
                var fields = input.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    String type = properties().get(field.getKey());
                    if (type == null || (type.equals("boolean") ? !field.getValue().isBoolean()
                            : type.equals("object") ? !field.getValue().isObject()
                            : !field.getValue().isTextual() || field.getValue().textValue().isBlank())) {
                        throw new IllegalArgumentException("Invalid parameter type or field: " + field.getKey());
                    }
                }
                Object result = switch (name) {
                    case "spawn_teammate" -> Map.of("agent_id", team.spawn(input.path("role").asText()));
                    case "list_teammates" -> team.list();
                    case "assign_task" -> Map.of("assignment_id", team.assign(input.path("agent_id").asText(),
                            input.path("task_id").asText(), input.path("summary").asText(), input.path("body").asText(), context,
                            input.path("wait_for_completion").asBoolean(true), scope(input)));
                    case "shutdown_teammate" -> { team.shutdown(input.path("agent_id").asText()); yield "Teammate closed"; }
                    case "reopen_task" -> team.reopen(input.path("task_id").asText());
                    case "review_task_result" -> Map.of("report_id", team.review(input.path("assignment_id").asText(),
                            input.path("report_id").asText(), input.path("notes").asText()));
                    case "retry_team_message" -> Map.of("message_id", team.retry(actor, input.path("message_id").asText(), context));
                    case "submit_task_result" -> {
                        if (context.assignment() == null || !context.assignment().actor().equals(actor)
                                || !context.assignment().id().equals(input.path("assignment_id").asText()))
                            throw new IllegalArgumentException("Only the currently executing assignment can submit its result");
                        yield Map.of("message_id", team.submitResult(actor, input.path("assignment_id").asText(),
                                Task.Status.valueOf(input.path("status").asText().toUpperCase(java.util.Locale.ROOT)),
                                input.path("summary").asText(), input.path("body").asText()));
                    }
                    default -> {
                        if (!actor.equals(TeamManager.LEADER) && input.has("assignment_id")
                                && (context.assignment() == null || !context.assignment().id().equals(input.path("assignment_id").asText())))
                            throw new IllegalArgumentException("Task messages must reference the currently executing assignment");
                        String kind = input.path("kind").asText("message");
                        if (!List.of("message", "question", "progress", "supplement").contains(kind)) {
                            throw new IllegalArgumentException("Invalid communication kind");
                        }
                        if (kind.equals("supplement") && (!input.has("task_id") || !input.has("assignment_id"))) {
                            throw new IllegalArgumentException("Task supplements require task_id and assignment_id");
                        }
                        yield Map.of("message_id", team.send(actor, input.path("to_agent").asText(),
                                input.path("task_id").asText(null), input.path("assignment_id").asText(null), kind,
                                input.path("summary").asText(), input.path("body").asText(),
                                input.path("allow_insertion").asBoolean(true),
                                input.path("wake_when_idle").asBoolean(!kind.equals("progress")), context, input.path("reply_to").asText(null)));
                    }
                };
                return Json.mapper().writeValueAsString(result);
            } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                return "Error: " + failure.getMessage()
                        + (failure.getSuppressed().length == 0 ? "" : "; recovery/recording also failed, count=" + failure.getSuppressed().length)
                        + ". Some state or files may already be saved; check get_task and mail records first, and do not redo the business work.";
            }
        }
    }
}
