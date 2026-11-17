package com.jadecode.workspace;

import java.nio.file.*;
import com.jadecode.config.JadePaths;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.util.Json;
import com.jadecode.workspace.ManagedWorkspaceStore;

/**
 * 发行验证的准备夹具：只向显式临时 HOME 中的已建工作区发布固定二进制数据。
 * 不运行 Agent、模型或 Docker；正式下载仍由 Node CLI 经真实服务协议完成。
 */
public final class PublishedFileFixture {
    private PublishedFileFixture() {
    }

    /** 参数为临时用户目录和工作区 ID；输出真实发布产生的 artifactId。 */
    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]).toRealPath();
        if (!home.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))
            throw new IllegalArgumentException("Fixture requires an isolated temporary home");
        var workspace = new ManagedWorkspaceStore(new JadePaths(home.resolve(".jadecode"))).open(args[1]);
        byte[] bytes = new byte[180_000];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) (i % 251);
        Files.write(workspace.path().resolve("fixture.bin"), bytes, StandardOpenOption.CREATE_NEW);
        var request = Json.mapper().createObjectNode().put("summary", "isolated verification fixture");
        request.putArray("paths").add("fixture.bin");
        var result = workspace.artifacts().publish(request,
                ExecutionContext.create(ExecutionContext.Source.USER, null), () -> false, (file, name) -> {
                });
        System.out.println(result.path("artifacts").get(0).path("artifactId").asText());
    }
}
