package com.jadecode.workspace;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.config.JadePaths;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.util.Json;

/** 服务重建后的资源持久化契约；真实 Node CLI 上传/续传另由发行与 Node 测试覆盖。 */
class WorkspaceProcessTest {
    @TempDir
    Path root;

    @Test
    void allocatesUploadsMultipleChunksResumesAndDeletesOnlyExplicitly() throws Exception {
        Path config = Files.createDirectories(root.resolve(".jadecode/config"));
        Files.writeString(config.resolve("config.json"),
                "{\"version\":1,\"memory\":{\"enabled\":false},\"modelMetadata\":{\"autoDiscover\":false}}");
        Path source = Files.createDirectory(root.resolve("source"));
        byte[] bytes = new byte[150_000];
        new Random(7).nextBytes(bytes);
        Files.write(source.resolve("binary"), bytes);
        Files.writeString(source.resolve(".env"), "SYNTHETIC_FIXTURE=excluded");
        try {
            String id = Json.mapper().readTree(run("--create-workspace")).path("workspaceId").asText();
            assertFalse(id.isBlank());
            var uploaded = Json.mapper().readTree(run("--workspace-id", id, "--upload-snapshot", source.toString()));
            assertEquals("READY", uploaded.path("state").asText());
            assertTrue(uploaded.path("size").asLong() > WorkspaceFiles.CHUNK);
            var repeated = Json.mapper().readTree(run("--workspace-id", id, "--upload-snapshot", source.toString()));
            assertEquals(uploaded.path("id").asText(), repeated.path("id").asText());
            run("--service-stop");
            var retained = Json.mapper().readTree(run("--managed-workspaces")).path("workspaces");
            assertEquals(1, retained.size());
            assertEquals(id, retained.get(0).path("id").asText());
            assertEquals(0, Json.mapper().readTree(run("--workspace-id", id, "--artifacts")).path("artifacts").size());
            run("--workspace-id", id, "--delete-workspace");
            assertTrue(Json.mapper().readTree(run("--managed-workspaces")).path("workspaces").isEmpty());
        } finally {
            run("--service-stop");
        }
        assertFalse(Files.exists(root.resolve(".jadecode/service/endpoint.json")));
    }

    /** 发布固定文件后重建服务，检查资源 API 仍能列出它并从非整块边界读取原字节。 */
    @Test
    void readsPublishedFileAfterServiceRestart() throws Exception {
        Path config = Files.createDirectories(root.resolve(".jadecode/config"));
        Files.writeString(config.resolve("config.json"),
                "{\"version\":1,\"memory\":{\"enabled\":false},\"modelMetadata\":{\"autoDiscover\":false}}");
        try {
            String id = Json.mapper().readTree(run("--create-workspace")).path("workspaceId").asText();
            var workspace = new ManagedWorkspaceStore(new JadePaths(root.resolve(".jadecode"))).open(id);
            byte[] bytes = new byte[90_000];
            new Random(23).nextBytes(bytes);
            Files.write(workspace.path().resolve("presentation.pptx"), bytes);
            var input = Json.mapper().createObjectNode().put("summary", "fixture final file");
            input.putArray("paths").add("presentation.pptx");
            String artifact = workspace.artifacts()
                    .publish(input, ExecutionContext.create(ExecutionContext.Source.USER, null),
                            () -> false, (file, name) -> {
                            })
                    .path("artifacts").get(0).path("artifactId").asText();
            run("--service-stop");
            var listed = Json.mapper().readTree(run("--workspace-id", id, "--artifacts")).path("artifacts");
            assertEquals(1, listed.size());
            assertEquals("presentation.pptx", listed.get(0).path("filename").asText());
            Path output = root.resolve("result.pptx");
            Path partial = output.resolveSibling(output.getFileName() + ".jade-" + artifact + ".part");
            Files.write(partial, Arrays.copyOf(bytes, 23_000));
            run("--workspace-id", id, "--download-artifact", artifact, "--output", output.toString());
            assertArrayEquals(bytes, Files.readAllBytes(output));
            assertFalse(Files.exists(partial));
            run("--workspace-id", id, "--delete-workspace");
        } finally {
            run("--service-stop");
        }
    }

    private com.jadecode.service.AgentService service;

    /** 只在测试中映射已有调用到服务 API；不启动模型、Node 或旧 Java CLI。 */
    private String run(String... args) throws Exception {
        if (args[0].equals("--service-stop")) {
            if (service != null)
                service.close();
            service = null;
            return "{}";
        }
        if (service == null)
            service = new com.jadecode.service.AgentService(new JadePaths(root.resolve(".jadecode")), "fixture",
                    (workspace, permissions, config) -> {
                        throw new AssertionError("Resource operations must not create an Agent");
                    });
        var request = Json.mapper().createObjectNode();
        if (args[0].equals("--create-workspace"))
            request.put("action", "create");
        else if (args[0].equals("--managed-workspaces"))
            request.put("action", "list");
        else {
            String id = args[1];
            request.put("workspaceId", id);
            switch (args[2]) {
                case "--artifacts" -> request.put("action", "artifacts");
                case "--delete-workspace" -> request.put("action", "delete");
                case "--upload-snapshot" -> {
                    Path zip = root.resolve("snapshot-" + UUID.randomUUID() + ".zip");
                    try {
                        WorkspaceFiles.zip(Path.of(args[3]), zip, (file, name) -> {
                        });
                        var state = service.workspaceCommand(request.deepCopy().put("action", "upload.begin")
                                .put("size", Files.size(zip)).put("sha256", WorkspaceFiles.sha256(zip)));
                        if (state.path("state").asText().equals("READY"))
                            return state.toString();
                        byte[] bytes = Files.readAllBytes(zip);
                        long offset = state.path("offset").asLong();
                        String resource = state.path("id").asText();
                        while (offset < bytes.length) {
                            int end = (int) Math.min(bytes.length, offset + WorkspaceFiles.CHUNK);
                            var reply = service.workspaceCommand(request.deepCopy().put("action", "upload.chunk")
                                    .put("resourceId", resource).put("offset", offset)
                                    .put("data", Base64.getEncoder()
                                            .encodeToString(Arrays.copyOfRange(bytes, (int) offset, end))));
                            assertEquals(end, reply.path("offset").asLong());
                            offset = end;
                        }
                        return service
                                .workspaceCommand(request.put("action", "upload.finish").put("resourceId", resource))
                                .toString();
                    } finally {
                        Files.deleteIfExists(zip);
                    }
                }
                case "--download-artifact" -> {
                    Path target = Path.of(args[5]);
                    Path part = target.resolveSibling(target.getFileName() + ".jade-" + args[3] + ".part");
                    var output = new java.io.ByteArrayOutputStream();
                    output.write(Files.readAllBytes(part));
                    String expectedSha = null;
                    while (true) {
                        var reply = service.workspaceCommand(request.deepCopy().put("action", "artifact.read")
                                .put("artifactId", args[3]).put("offset", output.size()));
                        assertEquals(output.size(), reply.path("offset").asLong());
                        expectedSha = reply.path("sha256").asText();
                        output.write(Base64.getDecoder().decode(reply.path("data").asText()));
                        if (reply.path("eof").asBoolean()) {
                            assertEquals(output.size(), reply.path("size").asInt());
                            break;
                        }
                    }
                    Files.write(target, output.toByteArray());
                    assertEquals(expectedSha, WorkspaceFiles.sha256(target));
                    Files.delete(part);
                    return "{}";
                }
                default -> throw new AssertionError("Unsupported test action");
            }
        }
        return service.workspaceCommand(request).toString();
    }
}
