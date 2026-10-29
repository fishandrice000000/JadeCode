package com.jadecode.workspace;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.util.Json;

/** 独立 CLI/daemon 的真实分块上传与重启持久化验证，不装配模型或接触外部网络。 */
class WorkspaceProcessTest {
    @TempDir Path root;
    @Test void cliAllocatesUploadsMultipleChunksResumesAndDeletesOnlyExplicitly() throws Exception {
        Path config=Files.createDirectories(root.resolve(".jadecode/config"));
        Files.writeString(config.resolve("config.json"),"{\"memory\":{\"enabled\":false},\"modelMetadata\":{\"autoDiscover\":false}}");
        Path source=Files.createDirectory(root.resolve("source")); byte[] bytes=new byte[150_000]; new Random(7).nextBytes(bytes);
        Files.write(source.resolve("binary"),bytes); Files.writeString(source.resolve(".env"),"SYNTHETIC_FIXTURE=excluded");
        try {
            String id=Json.mapper().readTree(run("--create-workspace")).path("workspaceId").asText(); assertFalse(id.isBlank());
            var uploaded=Json.mapper().readTree(run("--workspace-id",id,"--upload-snapshot",source.toString()));
            assertEquals("READY",uploaded.path("state").asText()); assertTrue(uploaded.path("size").asLong()>WorkspaceFiles.CHUNK);
            var repeated=Json.mapper().readTree(run("--workspace-id",id,"--upload-snapshot",source.toString()));
            assertEquals(uploaded.path("id").asText(),repeated.path("id").asText());
            run("--service-stop");
            var retained=Json.mapper().readTree(run("--managed-workspaces")).path("workspaces");
            assertEquals(1,retained.size()); assertEquals(id,retained.get(0).path("id").asText());
            assertEquals(0,Json.mapper().readTree(run("--workspace-id",id,"--artifacts")).path("artifacts").size());
            run("--workspace-id",id,"--delete-workspace");
            assertTrue(Json.mapper().readTree(run("--managed-workspaces")).path("workspaces").isEmpty());
        } finally { run("--service-stop"); }
        assertFalse(Files.exists(root.resolve(".jadecode/service/endpoint.json")));
    }
    /** 子进程采用当前测试 classpath 和专属 user.home，移除真实模型环境变量。 */
    private String run(String... args) throws Exception {
        var argv=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Duser.home="+root,
                "-cp",System.getProperty("java.class.path"),"com.jadecode.Main")); argv.addAll(List.of(args));
        var builder=new ProcessBuilder(argv).redirectErrorStream(true);
        for (String variable:List.of("ANTHROPIC_API_KEY","ANTHROPIC_BASE_URL","MODEL_ID","MAX_TOKENS","MODEL_PROVIDER")) builder.environment().remove(variable);
        Process process=builder.start();
        if (!process.waitFor(25,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Owned workspace CLI timed out"); }
        String text=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),text); return text;
    }
}
