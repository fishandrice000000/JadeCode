package com.jadecode.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.config.AppConfig;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.tools.Tool;

/**
 * AnthropicSdkLlmClient -- LlmClient 的官方 SDK 实现
 *
 * 全项目唯一接触 com.anthropic.* SDK 类型的类(隔离设计兑现):
 * 出方向把内部类型换成 SDK 类型(toParams / toMessageParam / toSdkTool ...),
 * 入方向把 SDK 响应立即归一化为内部 ContentBlock(toResponse),
 * 项目其余部分不认识 SDK 类型。SDK 类型全部用全限定名,隔离边界在代码里可见。
 */
public class AnthropicSdkLlmClient implements LlmClient {
    private final AnthropicClient client;
    private final String model;

    public AnthropicSdkLlmClient(AppConfig config) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl()) // SDK 自动在 baseUrl 后拼 /v1/messages
                .build();
        this.model = config.model();
    }

    @Override
    public ModelResponse complete(CompleteRequest request) throws LlmException {
        com.anthropic.models.messages.MessageCreateParams params = toParams(request);
        com.anthropic.models.messages.Message message;
        try {
            message = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            // 子类在前:带 HTTP 状态码(429/402 等),先接
            throw new LlmException(e.getMessage(), e.statusCode(), e);
        } catch (AnthropicException e) {
            // 网络类异常:无状态码
            throw new LlmException(e.getMessage(), e);
        }
        return toResponse(message);
    }

    // ===== 出方向:内部类型 → SDK 类型 =====

    private com.anthropic.models.messages.MessageCreateParams toParams(CompleteRequest request) {
        com.anthropic.models.messages.MessageCreateParams.Builder builder =
                com.anthropic.models.messages.MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(request.maxTokens()) // int 自动宽化为 long
                        .messages(request.messages().stream().map(this::toMessageParam).toList());
        if (request.system() != null) {
            builder.system(request.system());
        }
        if (!request.tools().isEmpty()) {
            builder.tools(request.tools().stream()
                    .map(this::toSdkTool)
                    .map(com.anthropic.models.messages.ToolUnion::ofTool) // SDK 联合类型,必须再包一层
                    .toList());
        }
        return builder.build();
    }

    private com.anthropic.models.messages.MessageParam toMessageParam(Message msg) {
        return com.anthropic.models.messages.MessageParam.builder()
                .role(msg.role() == Role.USER
                        ? com.anthropic.models.messages.MessageParam.Role.USER
                        : com.anthropic.models.messages.MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(msg.content().stream().map(this::toContentBlockParam).toList())
                .build();
    }

    private com.anthropic.models.messages.ContentBlockParam toContentBlockParam(ContentBlock block) {
        return switch (block) {
            case TextBlock t -> com.anthropic.models.messages.ContentBlockParam.ofText(t.text());
            case ToolUseBlock u -> com.anthropic.models.messages.ContentBlockParam.ofToolUse(
                    com.anthropic.models.messages.ToolUseBlockParam.builder()
                            .id(u.id())
                            .name(u.name())
                            .input(toSdkInput(u.input()))
                            .build());
            case ToolResultBlock r -> com.anthropic.models.messages.ContentBlockParam.ofToolResult(
                    com.anthropic.models.messages.ToolResultBlockParam.builder()
                            .toolUseId(r.toolUseId())
                            .content(r.content())
                            .build());
        };
    }

    /** SDK 的 Input 没有 of(JsonNode) 工厂:逐字段塞进 builder */
    private com.anthropic.models.messages.ToolUseBlockParam.Input toSdkInput(JsonNode input) {
        com.anthropic.models.messages.ToolUseBlockParam.Input.Builder builder =
                com.anthropic.models.messages.ToolUseBlockParam.Input.builder();
        for (Map.Entry<String, JsonNode> e : input.properties()) {
            builder.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
        }
        return builder.build();
    }

    private com.anthropic.models.messages.Tool toSdkTool(Tool tool) {
        ObjectNode schema = tool.inputSchema();
        // 把 inputSchema 拆成三段:type / properties / required
        com.anthropic.models.messages.Tool.InputSchema.Properties.Builder propsBuilder =
                com.anthropic.models.messages.Tool.InputSchema.Properties.builder();
        JsonNode propsNode = schema.path("properties"); // path 永不返回 null,缺失时是 MissingNode
        if (propsNode.isObject()) {
            for (Map.Entry<String, JsonNode> e : propsNode.properties()) {
                propsBuilder.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
            }
        }
        List<String> required = new ArrayList<>();
        JsonNode requiredNode = schema.path("required");
        if (requiredNode.isArray()) {
            requiredNode.forEach(n -> required.add(n.asText()));
        }
        com.anthropic.models.messages.Tool.InputSchema inputSchema =
                com.anthropic.models.messages.Tool.InputSchema.builder()
                        .type(JsonValue.fromJsonNode(schema.path("type")))
                        .properties(propsBuilder.build())
                        .required(required)
                        .build();
        return com.anthropic.models.messages.Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(inputSchema)
                .build();
    }

    // ===== 入方向:SDK 类型 → 内部类型 =====

    private ModelResponse toResponse(com.anthropic.models.messages.Message message) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (com.anthropic.models.messages.ContentBlock b : message.content()) {
            if (b.isText()) {
                blocks.add(new TextBlock(b.asText().text()));
            } else if (b.isToolUse()) {
                com.anthropic.models.messages.ToolUseBlock u = b.asToolUse();
                blocks.add(new ToolUseBlock(u.id(), u.name(), u._input().convert(JsonNode.class)));
            } else {
                // 未知块(如 thinking):跳过单块,不整轮失败
                System.err.println("跳过未知响应块: " + b.getClass().getSimpleName());
            }
        }
        com.anthropic.models.messages.Usage usage = message.usage();
        ModelResponse.Usage u = usage == null
                ? new ModelResponse.Usage(0, 0)
                : new ModelResponse.Usage(usage.inputTokens(), usage.outputTokens());
        return new ModelResponse(Message.assistant(blocks), u);
    }
}
