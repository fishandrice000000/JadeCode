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
 * 在 JadeCode 内部消息与 Anthropic Messages SDK 之间转换请求和响应。
 * 正式流式请求边接收正文、思考和工具预览边累计完整消息，辅助请求使用非流式入口；
 * 根据配置应用兼容端点或 OpenCode Go 的认证、稳定会话头与思考字段。
 */
public class AnthropicSdkLlmClient implements LlmClient {
    /** SDK 客户端：只在木类内部使用，不暴露给其他代码。 */
    private final AnthropicClient client;
    /** 模型 id,每次请求都带上 */
    private final String model;
    /** 服务商已知输出上限；未知时不改变各调用方原有额度。 */
    private final ModelMetadata metadata;
    /** 原兼容入口只按精确官方主机识别 DeepSeek；显式 Go 档案不套用直连能力。 */
    private final boolean deepseek;
    /** Go 的能力来自显式配置档案，不因为模型名称含 DeepSeek 就套用直连服务规则。 */
    private final boolean openCodeGo;
    private final List<ThinkingSettings> thinkingChoices;

    /**
     * 使用运行配置创建 SDK 客户端，以未知模型限额元数据沿用请求原预算。
     * 需要窗口上限的生产装配使用显式 metadata 构造入口。
     *
     * @param config 已解析的模型运行配置，提供模型名、端点和请求额度
     */
    public AnthropicSdkLlmClient(AppConfig config) {
        this(config, new ModelMetadata(ModelMetadata.Limits.unknown(),
                "No model parameters provided; using the original request budget"));
    }

    /**
     * 使用运行配置与已解析元数据建立 SDK 客户端，固定端点、模型及供应商适配，计算可用思考档位；
     * 后续请求按同一模型上限收紧输出预算。
     *
     * @param config 已解析的模型运行配置，提供模型名、端点和请求额度
     * @param metadata 当前模型的窗口和输出限额资料
     * @throws NullPointerException metadata 为 null
     */
    public AnthropicSdkLlmClient(AppConfig config, ModelMetadata metadata) {
        openCodeGo = config.provider() == com.jadecode.config.ModelProvider.OPENCODE_GO;
        var builder = AnthropicOkHttpClient.builder().baseUrl(config.baseUrl());
        // Console 文档要求 Bearer；旧兼容服务仍用 x-api-key，不把两种认证同时发送。
        if (openCodeGo)
            builder.authToken(config.apiKey()).replaceHeaders("User-Agent", "jadecode/1.1.0")
                    .putHeader("x-opencode-client", "jadecode");
        else
            builder.apiKey(config.apiKey());
        this.client = builder.build();
        this.model = config.model();
        this.metadata = java.util.Objects.requireNonNull(metadata, "metadata");
        var endpoint = java.net.URI.create(config.baseUrl());
        String host = "https".equalsIgnoreCase(endpoint.getScheme()) ? endpoint.getHost() : null;
        deepseek = !openCodeGo && "api.deepseek.com".equalsIgnoreCase(host);
        // 只开放当前档案适配的格式。Go 档位依据 Pi 资料；网关实际效果仍需真实服务验收。
        if (openCodeGo)
            thinkingChoices = List.of(ThinkingSettings.DEFAULT, ThinkingSettings.LOW,
                    ThinkingSettings.HIGH, ThinkingSettings.MAX);
        else if (deepseek)
            thinkingChoices = List.of(ThinkingSettings.DEFAULT, ThinkingSettings.OFF,
                    ThinkingSettings.LOW, ThinkingSettings.HIGH, ThinkingSettings.MAX);
        else if ("api.anthropic.com".equalsIgnoreCase(host)
                && (model.startsWith("claude-opus-4-6") || model.startsWith("claude-sonnet-4-6")))
            thinkingChoices = List.of(ThinkingSettings.DEFAULT, ThinkingSettings.OFF, ThinkingSettings.LOW,
                    ThinkingSettings.MEDIUM, ThinkingSettings.HIGH, ThinkingSettings.MAX);
        else
            thinkingChoices = List.of(ThinkingSettings.DEFAULT);
    }

    /**
     * 返回当前模型已解析的窗口、输出额度等元数据，供请求预算与上下文压缩使用。
     *
     * @return 当前模型的窗口和输出限额元数据
     */
    @Override
    public ModelMetadata metadata() {
        return metadata;
    }

    /**
     * 返回当前模型支持的思考档位列表，供会话设置和前端选择器使用。
     *
     * @return 当前模型适配的可选思考档位
     */
    @Override
    public List<ThinkingSettings> thinkingChoices() {
        return thinkingChoices;
    }

    /**
     * 将思考档位转换为界面名称；DeepSeek 的 DEFAULT 显示为 high，其他值使用客户端通用名称。
     *
     * @param value 需要转换为界面名称的思考档位
     * @return 供界面显示的思考档位名称
     */
    @Override
    public String thinkingLabel(ThinkingSettings value) {
        return value == ThinkingSettings.DEFAULT && deepseek ? "high" : LlmClient.super.thinkingLabel(value);
    }

    /**
     * 同步调一次模型：把内部请求转成 SDK 参数发出，再把响应转回内部类型。
     *
     * @param request 内部请求(system / messages / maxTokens / tools)
     * @return 转回内部类型后的响应（assistant 消息 + token 用量）
     * @throws LlmException 服务端错误(带 HTTP 状态码)或网络类异常(状态码 0)
     */

    private ModelResponse completeNonStreaming(CompleteRequest request) throws LlmException {
        com.anthropic.models.messages.MessageCreateParams params = toParams(request);
        try {
            return toResponse(client.messages().create(params));
        } catch (AnthropicServiceException e) {
            // 子类在前:带 HTTP 状态码(429/402 等),先接
            throw serviceFailure(e);
        } catch (AnthropicException e) {
            // 网络类异常:无状态码
            throw openCodeGo ? new LlmException("OpenCode Go network request failed")
                    : new LlmException(e.getMessage(), e);
        }
    }

    /**
     * display 为 NONE 时发送非流式请求；其他情况读取 SDK 事件流，按块开始、增量和结束维护状态，
     * 转发正文、思考和工具参数预览，并检查工具 JSON 完整性。正常取得 message_stop 后将累计消息转为 ModelResponse；
     * 网络、协议或拼装错误以 LlmException 返回，资源块最终关闭网络流。
     *
     * @param request 本次完整模型请求，含 system、历史、工具目录、额度与思考设置
     * @param display 本次请求的流式片段接收者，NONE 选择非流式请求
     * @return 完整助手消息、停止原因和可选用量报告
     * @throws LlmException 模型服务、网络、流事件或完整消息组装失败
     * @throws NullPointerException display 为 null
     */
    @Override
    public ModelResponse complete(CompleteRequest request, ResponseStream display) throws LlmException {
        java.util.Objects.requireNonNull(display, "display");
        // NONE 明确选择普通请求：辅助调用不创建事件流，也不伪造增量片段。
        if (display == ResponseStream.NONE)
            return completeNonStreaming(request);
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
                    if (start.contentBlock().isToolUse()) {
                        toolJson.put(start.index(), new StringBuilder());
                        var tool = start.contentBlock().asToolUse();
                        // 只建立预览：完整参数仍要等 JSON 闭合与整响应校验后才执行。
                        display.toolBlockStarted(Math.toIntExact(start.index()), tool.id(), tool.name(),
                                tool._input() == null ? "{}" : tool._input().toString());
                    }
                } else if (event.isContentBlockDelta()) {
                    var update = event.asContentBlockDelta();
                    if (!openBlocks.contains(update.index()))
                        throw new LlmException("Received a delta for a content block that is not open");
                    if (update.delta().isInputJson() && toolJson.containsKey(update.index())) {
                        String fragment = update.delta().asInputJson().partialJson();
                        toolJson.get(update.index()).append(fragment);
                        display.toolInputDelta(Math.toIntExact(update.index()), fragment);
                    }
                } else if (event.isContentBlockStop()) {
                    long index = event.asContentBlockStop().index();
                    if (!openBlocks.remove(index))
                        throw new LlmException(
                                "Received a stop event for a content block that is not open or was already stopped");
                    StringBuilder json = toolJson.remove(index);
                    if (json != null) {
                        validateToolJson(json.toString());
                        display.toolBlockClosed(Math.toIntExact(index));
                    }
                } else if (event.isMessageStop() && !openBlocks.isEmpty()) {
                    throw new LlmException("The model stream ended with unclosed content blocks");
                }
                // 先拼协议数据，再显示预览。签名和部分 JSON 不进入展示回调。
                accumulator.accumulate(event);
                if (event.isContentBlockStart()) {
                    var block = event.asContentBlockStart().contentBlock();
                    if (block.isText()) {
                        // extractText 会用换行连接多个 text block，流式展示也保持这个间隔。
                        if (hadTextBlock)
                            display.text("\n");
                        hadTextBlock = true;
                        if (!block.asText().text().isEmpty())
                            display.text(block.asText().text());
                    } else if (block.isThinking() && !block.asThinking().thinking().isEmpty()) {
                        display.thinking(block.asThinking().thinking());
                    }
                } else if (event.isContentBlockDelta()) {
                    var delta = event.asContentBlockDelta().delta();
                    if (delta.isText())
                        display.text(delta.asText().text());
                    else if (delta.isThinking())
                        display.thinking(delta.asThinking().thinking());
                }
            }
            // accumulator.message 在缺少结束事件时抛错，不能把已经预览的半份回复写入历史。
            return toResponse(accumulator.message());
        } catch (AnthropicServiceException failure) {
            throw serviceFailure(failure);
        } catch (AnthropicException failure) {
            throw openCodeGo ? new LlmException("OpenCode Go stream did not form a complete response")
                    : new LlmException("Failed to read or assemble the model stream: " + failure.getMessage(), failure);
        } catch (IllegalStateException failure) {
            throw new LlmException("The model stream did not form a complete response", failure);
        }
    }

    /**
     * 把 SDK 服务错误转换为项目 LlmException。兼容端点保留状态码和原错误链；
     * OpenCode Go 按 HTTP 状态生成固定的参数、认证、权限、额度或限流说明，供上层选择错误处理路径。
     *
     * @param failure 已经发生的 IO、模型或执行异常，供原任务收尾与回执使用
     * @return 带当前供应商错误分类与 HTTP 状态的模型异常
     */
    private LlmException serviceFailure(AnthropicServiceException failure) {
        if (!openCodeGo)
            return new LlmException(failure.getMessage(), failure.statusCode(), failure);
        String message = switch (failure.statusCode()) {
            case 400 -> "OpenCode Go rejected the request; check parameters and configured model limits";
            case 401 -> "OpenCode Go authentication failed; check the selected API key";
            case 403 -> "OpenCode Go access was denied; check workspace subscription and model access settings";
            case 402 -> "OpenCode Go allowance or balance is unavailable; check the Console";
            case 429 -> "OpenCode Go request was limited; retry later or check the plan allowance";
            case 404 -> "OpenCode Go Messages endpoint or target model was not found";
            default -> "OpenCode Go request failed";
        };
        return new LlmException(message + " (HTTP " + failure.statusCode() + ")", failure.statusCode(), null);
    }

    /**
     * 在工具参数块结束时解析累计原始 JSON，要求为单个完整对象；空白按无参工具处理，
     * 非对象、语法错误或尾随内容抛出 LlmException。
     *
     * @param json 当前工具参数块累计的原始 JSON 文本
     * @throws LlmException 参数不是单个完整 JSON 对象，或含无效与尾随内容
     */
    private static void validateToolJson(String json) throws LlmException {
        if (json.isBlank())
            return;
        try {
            JsonNode value = Json.mapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            if (value == null || !value.isObject())
                throw new LlmException("Model tool arguments are not a complete JSON object");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new LlmException(
                    "The model stream contained incomplete or invalid tool JSON; the tool was not executed", invalid);
        }
    }

    // ===== 出方向:内部类型 → SDK 类型 =====

    /**
     * 将内部请求转换为 SDK 参数：映射历史、使用模型上限收紧输出额度，按存在情况加入 system 与工具 schema。
     * OpenCode Go 附带稳定会话头；核对思考档位并生成对应 thinking 与 output_config 字段。返回构建完成的请求。
     *
     * @param request 本次完整模型请求，含 system、历史、工具目录、额度与思考设置
     * @return 可直接交给 SDK 发送的请求参数
     * @throws IllegalArgumentException Go 请求缺少稳定会话 ID，或所选思考档位尚未适配
     */
    private com.anthropic.models.messages.MessageCreateParams toParams(CompleteRequest request) {
        com.anthropic.models.messages.MessageCreateParams.Builder builder = com.anthropic.models.messages.MessageCreateParams
                .builder()
                .model(model)
                .maxTokens(metadata.capOutput(request.maxTokens())) // 只封顶，不自动扩大请求额度
                .messages(request.messages().stream().map(this::toMessageParam).toList());
        if (openCodeGo) {
            // 身份随请求不可变地传入，不在并发共用的 SDK 客户端上切换全局 header。
            if (request.sessionId().isEmpty())
                throw new IllegalArgumentException("OpenCode Go requests require a stable model session ID");
            builder.putAdditionalHeader("x-opencode-session", request.sessionId());
        }
        if (request.system() != null) {
            builder.system(request.system());
        }
        if (!request.tools().isEmpty()) {
            builder.tools(request.tools().stream()
                    .map(this::toSdkTool)
                    .map(com.anthropic.models.messages.ToolUnion::ofTool) // SDK 联合类型,必须再包一层
                    .toList());
        }
        // 这是实际请求字段，不是提示词，也与 Ctrl+T 是否显示思考无关。
        // DEFAULT 完全省略，辅助请求保持原行为；SDK 支持追加标准协议字段，无需升级依赖。
        ThinkingSettings thinking = request.thinking();
        if (!thinkingChoices.contains(thinking))
            throw new IllegalArgumentException("This endpoint or model is not adapted for that thinking effort");
        if (thinking != ThinkingSettings.DEFAULT) {
            String type = thinking == ThinkingSettings.OFF ? "disabled"
                    : deepseek || openCodeGo ? "enabled" : "adaptive";
            builder.putAdditionalBodyProperty("thinking", JsonValue.from(Map.of("type", type)));
            if (thinking != ThinkingSettings.OFF)
                builder.putAdditionalBodyProperty("output_config", JsonValue.from(Map.of("effort", thinking.value())));
        }
        return builder.build();
    }

    /**
     * 将内部消息的 USER 或 ASSISTANT 角色映射到 SDK 角色，逐块转换内容，返回单条 SDK 消息参数。
     *
     * @param msg 待转换为 SDK 参数的内部消息
     * @return 角色和内容块都已转换的 SDK 消息
     */
    private com.anthropic.models.messages.MessageParam toMessageParam(Message msg) {
        return com.anthropic.models.messages.MessageParam.builder()
                .role(msg.role() == Role.USER
                        ? com.anthropic.models.messages.MessageParam.Role.USER
                        : com.anthropic.models.messages.MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(msg.content().stream().map(this::toContentBlockParam).toList())
                .build();
    }

    /**
     * 按内容类型转换正文、工具调用、工具结果、思考和隐藏思考块，保留工具 ID、参数、错误标记及思考签名等协议字段。
     * 返回对应 SDK 联合类型。
     *
     * @param block 待转换的一个模型内容块
     * @return 与原内容块类型对应的 SDK 参数块
     */
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
                            .isError(r.isError())
                            .content(r.content())
                            .build());
        };
    }

    /**
     * 遍历工具输入对象，将每个 JSON 字段转换为 SDK JsonValue 后加入 Input builder，返回完整参数对象。
     *
     * @param input 本次工具参数 JSON 对象，包含该工具 schema 约定的字段
     * @return 保留所有输入 JSON 字段的 SDK 工具参数
     */
    private com.anthropic.models.messages.ToolUseBlockParam.Input toSdkInput(JsonNode input) {
        com.anthropic.models.messages.ToolUseBlockParam.Input.Builder builder = com.anthropic.models.messages.ToolUseBlockParam.Input
                .builder();
        for (Map.Entry<String, JsonNode> e : input.properties()) {
            builder.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue()));
        }
        return builder.build();
    }

    /**
     * 将工具名、说明和完整 JSON schema 转为 SDK 工具定义。type、properties、required 使用对应 builder 入口，
     * 其余根字段逐项保留，嵌套 JSON 原样转换。
     *
     * @param tool 本条目已固定的宿主工具身份资料
     * @return 包含完整 schema 的 SDK 工具定义
     */
    private com.anthropic.models.messages.Tool toSdkTool(ToolDefinition tool) {
        ObjectNode schema = tool.inputSchema();
        // 已知字段走 SDK 对应的入口，避免 type 等字段同时出现在普通字段和额外字段中。
        var inputBuilder = com.anthropic.models.messages.Tool.InputSchema.builder();
        if (schema.has("type"))
            inputBuilder.type(JsonValue.fromJsonNode(schema.get("type")));
        if (schema.path("properties").isObject()) {
            var properties = com.anthropic.models.messages.Tool.InputSchema.Properties.builder();
            for (var field : schema.get("properties").properties())
                properties.putAdditionalProperty(field.getKey(), JsonValue.fromJsonNode(field.getValue()));
            inputBuilder.properties(properties.build());
        }
        if (schema.path("required").isArray()) {
            List<String> required = new ArrayList<>();
            schema.get("required").forEach(value -> required.add(value.asText()));
            inputBuilder.required(required);
        }
        // 其它根级约束（如 $defs、additionalProperties）也必须发送，嵌套 JSON 原样保留。
        // 这里只读取 ToolDefinition 交出的副本，不删改服务发现时取得的原定义。
        for (var field : schema.properties()) {
            if (!java.util.Set.of("type", "properties", "required").contains(field.getKey()))
                inputBuilder.putAdditionalProperty(field.getKey(), JsonValue.fromJsonNode(field.getValue()));
        }
        var inputSchema = inputBuilder.build();
        return com.anthropic.models.messages.Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(inputSchema)
                .build();
    }

    // ===== 入方向:SDK 类型 → 内部类型 =====

    /**
     * 逐块将 SDK 响应转换为内部正文、工具、思考和隐藏思考内容，核对工具参数为对象，未知块输出类型告警。
     * 转换普通输入、输出与缓存用量，缺报保留 null，返回带停止原因的内部 ModelResponse。
     *
     * @param message 待编码、校验或投影的完整消息
     * @return 转换后的内部消息、停止原因与用量
     * @throws LlmException 模型工具参数不是 JSON 对象或内部内容转换失败
     */
    private ModelResponse toResponse(com.anthropic.models.messages.Message message) throws LlmException {
        List<ContentBlock> blocks = new ArrayList<>();
        for (com.anthropic.models.messages.ContentBlock b : message.content()) {
            if (b.isText()) {
                blocks.add(new TextBlock(b.asText().text()));
            } else if (b.isToolUse()) {
                com.anthropic.models.messages.ToolUseBlock u = b.asToolUse();
                JsonNode input = u._input().convert(JsonNode.class);
                if (!input.isObject())
                    throw new LlmException("Model tool arguments are not a JSON object; the tool was not executed");
                blocks.add(new ToolUseBlock(u.id(), u.name(), input));
            } else if (b.isThinking()) {
                var thinking = b.asThinking();
                // 兼容后端可以省略签名，但实际提供的非空字段必须是合法字符串，不能静默丢掉坏签名。
                var signature = thinking._signature();
                blocks.add(new ThinkingBlock(thinking.thinking(), signature.isMissing() || signature.isNull()
                        ? null
                        : thinking.signature()));
            } else if (b.isRedactedThinking()) {
                blocks.add(new RedactedThinkingBlock(b.asRedactedThinking().data()));
            } else {
                System.err.println("[Warning] Unsupported response block: " + b.type());
            }
        }
        // 普通输入与缓存读写是分开的协议字段；不得漏掉缓存，也不得把缓存写入明细再加一次。
        // 缺少整份 usage 仍为 null，状态栏会隐藏无报告值，账本保留缺失事实。
        com.anthropic.models.messages.Usage usage = message._usage().isMissing() || message._usage().isNull() ? null
                : message.usage();
        ModelResponse.Usage u = usage == null
                ? null
                : new ModelResponse.Usage(usage.inputTokens(), usage.outputTokens(),
                        usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L));
        // 保留“为什么停止”，辅助 JSON 回复可能因额度耗尽而截断，不能只看到残缺文本。
        // 只保存协议字段，不把思考或原始响应放进诊断日志。
        return new ModelResponse(Message.assistant(blocks), u,
                message.stopReason().map(reason -> reason.asString()).orElse(""));
    }
}
