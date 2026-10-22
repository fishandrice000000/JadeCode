package com.jadecode.safety.checks;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.ToolIdentity;
import com.jadecode.util.PathResolver;
import java.net.URI;
import java.util.List;
import com.jadecode.safety.checks.ToolPermissionCheck.Analysis;

/** 使用宿主注册身份分析外部工具，不相信服务 hints 或模型参数中的 source/readOnly。 */
public final class McpPermissionCheck {
    private final PathResolver resolver;
    public McpPermissionCheck(PathResolver resolver) { this.resolver = resolver; }

    /**
     * 已声明的读取能力按读取处理，未知外部行为保持 uncertain、进入现有审批策略。
     * Fetch 另外检查 URL 格式和分页额度；公网 DNS 与重定向限制仍由现成 Fetch Server 执行。
     * 不解析远程描述为授权，不执行 DNS、HTTP 或程序。
     */
    public Analysis analyze(String modelName, ObjectNode input, ToolIdentity identity) {
        String error = "";
        if (identity.hostReadOnly() && identity.originalName().equals("fetch")) {
            try {
                if (!input.path("url").isTextual()) throw new IllegalArgumentException();
                URI url = URI.create(input.path("url").textValue());
                if (!("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                        || url.getHost() == null || url.getUserInfo() != null) throw new IllegalArgumentException();
                if (input.has("max_length") && (!input.path("max_length").isIntegralNumber()
                        || !input.path("max_length").canConvertToInt() || input.path("max_length").intValue() < 1
                        || input.path("max_length").intValue() > 10_000)) throw new IllegalArgumentException();
                if (input.has("start_index") && (!input.path("start_index").isIntegralNumber()
                        || !input.path("start_index").canConvertToInt() || input.path("start_index").intValue() < 0))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { error = "Fetch requires an HTTP(S) URL without user info; max_length is 1–10000 and start_index must be non-negative"; }
        }
        // hostReadOnly 来自用户配置中的原始工具名白名单；没有声明就不能靠名字 search/get 自动放行。
        return new Analysis(modelName, resolver.workspaceReal(), List.of(), List.of(), !identity.hostReadOnly(),
                identity.hostReadOnly(), !identity.hostReadOnly(), error);
    }
}
