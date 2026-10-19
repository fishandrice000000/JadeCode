package com.jadecode.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.JsonField;
import com.anthropic.core.JsonMissing;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.MessageAccumulator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.jadecode.config.AppConfig;
import com.jadecode.messages.ContentBlock;
import com.jadecode.messages.Message;
import com.jadecode.messages.Role;
import com.jadecode.messages.TextBlock;
import com.jadecode.messages.ToolResultBlock;
import com.jadecode.messages.ToolUseBlock;
import com.jadecode.messages.ThinkingBlock;
import com.jadecode.messages.RedactedThinkingBlock;
import com.jadecode.util.Json;

/**
 * 用官方 Anthropic SDK 实现 {@link LlmClient}。
 *
 * <p>这是全项目唯一引用 {@code com.anthropic.*} 的类：发出请求时把内部消息转成 SDK 类型，
 * 收到响应时马上转回内部的 ContentBlock。其他代码都不认识 SDK 类型。
 */
public class AnthropicSdkLlmClient implements LlmClient {
    /** SDK 客户端：只在木类内部使用，不暴露给其他代码。 */
    private final AnthropicClient client;
    /** 模型 id,每次请求都带上 */
    private final String model;

    /**
     * 用配置里的 apiKey / baseUrl / model 建客户端;SDK 自动在 baseUrl 后拼 /v1/messages。
     *
     * @param config 运行期配置(见 {@link AppConfig})
     */
    public AnthropicSdkLlmClient(AppConfig config) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl()) // SDK 自动在 baseUrl 后拼 /v1/messages
                .build();
        this.model = config.model();
    }

    /**
     * 同步调一次模型：把内部请求转成 SDK 参数发出，再把响应转回内部类型。
     *
     * @param request 内部请求(system / messages / maxTokens / tools)
     * @return 转回内部类型后的响应（assistant 消息 + token 用量）
     * @throws LlmException 服务端错误(带 HTTP 状态码)或网络类异常(状态码 0)
     */
    @Override
    public ModelResponse complete(CompleteRequest request) throws LlmException {
        com.anthropic.models.messages.MessageCreateParams params = toParams(request);
        try {
            return toResponse(client.messages().create(params));
        } catch (AnthropicServiceException e) {
            // 子类在前:带 HTTP 状态码(429/402 等),先接
            throw new LlmException(e.getMessage(), e.statusCode(), e);
        } catch (AnthropicException e) {
            // 网络类异常:无状态码
            throw new LlmException(e.getMessage(), e);
        }
    }

    /**
     * 同步读取 SDK 的事件流，一边显示可读增量，一边由 SDK 拼成完整消息。
     *
     * <p>工具 JSON 只用于校验与拼装，thinking 签名只用于拼装，不输出到终端。
     * 必须收到 message_stop 才返回；EOF、服务端错误或不完整 JSON 均不能当成功。
     * try-with-resources 在成功、异常或展示方因退出而抛错时都会关闭本次网络流。</p>
     * @param request 本次请求，不修改
     * @param display 本次请求独占的可读片段接收者，不能保存在共享客户端字段中
     * @return 与非流式相同的完整内部响应
     * @throws LlmException 服务端、网络、事件格式或消息拼装失败
     */
    @Override
    public ModelResponse complete(CompleteRequest request, ResponseStream display) throws LlmException {
        MessageAccumulator accumulator = MessageAccumulator.create();
        Map<Long, StringBuilder> toolJson = new HashMap<>();
        var openBlocks = new HashSet<Long>();
        boolean hadTextBlock = false;
        try (var response = client.messages().createStreaming(toParams(request))) {
            var events = response.stream().iterator();
            while (events.hasNext()) {
                var event = events.next();
                // SDK 为容纳 max_tokens 截断，会把非法工具 JSON 退化成 {}。
                // Coding Agent 不能拿这个默认值执行工具，所以额外校验原始片段和块是否闭合。
                if (event.isContentBlockStart()) {
                    var start = event.asContentBlockStart();
                    openBlocks.add(start.index());
                    if (start.contentBlock().isToolUse()) toolJson.put(start.index(), new StringBuilder());
                } else if (event.isContentBlockDelta()) {
                    var update = event.asContentBlockDelta();
                    if (!openBlocks.contains(update.index())) throw new LlmException("收到未开始或已结束内容块的增量");
                    if (update.delta().isInputJson() && toolJson.containsKey(update.index())) {
                        toolJson.get(update.index()).append(update.delta().asInputJson().partialJson());
                    }
                } else if (event.isContentBlockStop()) {
                    long index = event.asContentBlockStop().index();
                    if (!openBlocks.remove(index)) throw new LlmException("收到未开始或重复的内容块结束事件");
                    StringBuilder json = toolJson.remove(index);
                    if (json != null) validateToolJson(json.toString());
                } else if (event.isMessageStop() && !openBlocks.isEmpty()) {
                    throw new LlmException("模型流结束时仍有未闭合的内容块");
                }
                // 先拼协议数据，再显示预览。签名和部分 JSON 不进入展示回调。
                accumulator.accumulate(event);
                if (event.isContentBlockStart()) {
                    var block = event.asContentBlockStart().contentBlock();
                    if (block.isText()) {
                        // extractText 会用换行连接多个 text block，流式展示也保持这个间隔。
                        if (hadTextBlock) display.text("\n");
                        hadTextBlock = true;
                        if (!block.asText().text().isEmpty()) display.text(block.asText().text());
                    } else if (block.isThinking() && !block.asThinking().thinking().isEmpty()) {
                        display.thinking(block.asThinking().thinking());
                    }
                } else if (event.isContentBlockDelta()) {
                    var delta = event.asContentBlockDelta().delta();
                    if (delta.isText()) display.text(delta.asText().text());
                    else if (delta.isThinking()) display.thinking(delta.asThinking().thinking());
                }
            }
            // accumulator.message 在缺少结束事件时抛错，不能把已经预览的半份回复写入历史。
            return toResponse(accumulator.message());
        } catch (AnthropicServiceException failure) {
            throw new LlmException(failure.getMessage(), failure.statusCode(), failure);
        } catch (AnthropicException failure) {
            throw new LlmException("模型流读取或拼装失败: " + failure.getMessage(), failure);
        } catch (IllegalStateException failure) {
            throw new LlmException("模型流未形成完整响应", failure);
        }
    }

    /**
     * 工具块结束时检查原始 JSON，不能信任 SDK 对非法片段回退得到的空对象。
     * @param json 仅本次工具调用的原始参数片段，绝不打印到错误信息中
     * @throws LlmException 非完整对象或带尾随内容；空白参数按无参工具的空对象处理
     */
    private static void validateToolJson(String json) throws LlmException {
        if (json.isBlank()) return;
        try {
            JsonNode value = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (value == null || !value.isObject()) throw new LlmException("模型工具参数不是完整 JSON 对象");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new LlmException("模型流包含不完整或非法的工具 JSON，未执行工具", invalid);
        }
    }

    // ===== 出方向:内部类型 → SDK 类型 =====

    /** 出方向:请求 → SDK 参数;system 与 tools 都按"有才注入"处理(空 tools 不占 schema 位) */
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

    /** 单条消息 → SDK 类型:内部 Role 枚举映射到 SDK 的角色枚举 */
    private com.anthropic.models.messages.MessageParam toMessageParam(Message msg) {
        return com.anthropic.models.messages.MessageParam.builder()
                .role(msg.role() == Role.USER
                        ? com.anthropic.models.messages.MessageParam.Role.USER
                        : com.anthropic.models.messages.MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(msg.content().stream().map(this::toContentBlockParam).toList())
                .build();
    }

    /** 内容块转 SDK 类型；thinking 保留原字段，不转成普通正文或重新生成签名。 */
    private com.anthropic.models.messages.ContentBlockParam toContentBlockParam(ContentBlock block) {
        return switch (block) {
            case ThinkingBlock t -> com.anthropic.models.messages.ContentBlockParam.ofThinking(
                    com.anthropic.models.messages.ThinkingBlockParam.builder().thinking(t.thinking())
                            .signature(t.signature() == null ? JsonMissing.of() : JsonField.of(t.signature())).build());
            case RedactedThinkingBlock t -> com.anthropic.models.messages.ContentBlockParam.ofRedactedThinking(
                    com.anthropic.models.messages.RedactedThinkingBlockParam.builder().data(t.data()).build());
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

    /**
     * 工具数据定义转换为 SDK 类型，不持有可执行 Tool。
     * 保留原转换范围：只注入 type/properties/required，根级其它 schema 字段仍未映射。
     */
    private com.anthropic.models.messages.Tool toSdkTool(ToolDefinition tool) {
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

    /** SDK 响应转内部类型；保留两种 thinking，未知其它类型只报告类型名，不回显完整 payload。 */
    private ModelResponse toResponse(com.anthropic.models.messages.Message message) throws LlmException {
        List<ContentBlock> blocks = new ArrayList<>();
        for (com.anthropic.models.messages.ContentBlock b : message.content()) {
            if (b.isText()) {
                blocks.add(new TextBlock(b.asText().text()));
            } else if (b.isToolUse()) {
                com.anthropic.models.messages.ToolUseBlock u = b.asToolUse();
                JsonNode input = u._input().convert(JsonNode.class);
                if (!input.isObject()) throw new LlmException("模型工具参数不是 JSON 对象，未执行工具");
                blocks.add(new ToolUseBlock(u.id(), u.name(), input));
            } else if (b.isThinking()) {
                var thinking = b.asThinking();
                // 兼容后端可以省略签名，但实际提供的非空字段必须是合法字符串，不能静默丢掉坏签名。
                var signature = thinking._signature();
                blocks.add(new ThinkingBlock(thinking.thinking(), signature.isMissing() || signature.isNull()
                        ? null : thinking.signature()));
            } else if (b.isRedactedThinking()) {
                blocks.add(new RedactedThinkingBlock(b.asRedactedThinking().data()));
            } else {
                System.err.println("[Warning] Unsupported response block: " + b.type());
            }
        }
        // 缺少用量不是零 token。保留 null，让界面显示“未报告”而不是虚构实测统计。
        com.anthropic.models.messages.Usage usage = message._usage().isMissing() || message._usage().isNull() ? null : message.usage();
        ModelResponse.Usage u = usage == null
                ? null
                : new ModelResponse.Usage(usage.inputTokens(), usage.outputTokens());
        return new ModelResponse(Message.assistant(blocks), u);
    }
}
