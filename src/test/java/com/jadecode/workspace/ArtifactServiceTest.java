package com.jadecode.workspace;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.config.JadePaths;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.safety.*;
import com.jadecode.safety.rules.PermissionRule;
import com.jadecode.tools.BashTool;
import com.jadecode.util.*;

/** 使用合成成品文件验证发布、权限及失败清理；不需要 Docker、Git 仓库或模型。 */
class ArtifactServiceTest {
    @TempDir Path root;

    /** 每次返回指向同一测试数据根的存储，便于验证磁盘重开，不读取用户配置。 */
    private ManagedWorkspaceStore store() throws IOException {
        return new ManagedWorkspaceStore(new JadePaths(root.resolve("data")));
    }

    /** 创建普通用户调用上下文；测试只使用工作区内允许的读取，不触发真实人工审批。 */
    private ExecutionContext context() { return ExecutionContext.create(ExecutionContext.Source.USER, null); }

    /** 构造真实发布参数；路径均是相对工作区的文件名，不修改调用者给出的字符串。 */
    private ObjectNode publication(String... paths) {
        var input = Json.mapper().createObjectNode().put("summary", "fixture delivery");
        var names = input.putArray("paths");
        for (String path : paths) names.add(path);
        return input;
    }

    /** 只读取明确的成品；未选中的素材、脚本和保护入口不扫描，也不依赖输入基线。 */
    @Test void publishesOnlySelectedBytesWithoutGitOrArchiveAndSurvivesReopen() throws Exception {
        var workspace = store().create();
        Files.createDirectory(workspace.path().resolve("output"));
        byte[] bytes = new byte[]{0, 7, 1, 9, 0};
        Files.write(workspace.path().resolve("output/presentation.pptx"), bytes);
        Files.writeString(workspace.path().resolve("make.py"), "unselected script");
        Files.writeString(workspace.path().resolve(".env"), "SYNTHETIC_FIXTURE=unselected");
        var checked = new ArrayList<String>();
        var published = workspace.artifacts().publish(publication("output/presentation.pptx"), context(),
                () -> false, (file, name) -> checked.add(name)).path("artifacts");
        assertEquals(1, published.size());
        assertEquals(List.of("output/presentation.pptx"), checked);
        var entry = published.get(0);
        String id = entry.path("artifactId").asText();
        assertEquals("presentation.pptx", entry.path("filename").asText());
        assertEquals(bytes.length, entry.path("size").asLong());
        assertArrayEquals(bytes, Files.readAllBytes(workspace.artifacts().file(id)));
        assertEquals(WorkspaceFiles.sha256(workspace.artifacts().file(id)), entry.path("sha256").asText());
        for (String old : List.of("inputs", "executions", "changed", "deleted", "bundleSize", "bundleSha256"))
            assertFalse(entry.has(old), old);
        var reopened = store().open(workspace.id());
        var listed = reopened.artifacts().list().path("artifacts").get(0);
        assertEquals(id, listed.path("artifactId").asText());
        assertEquals("presentation.pptx", listed.path("filename").asText());
        // JSON 落盘再解析可能将小的 long 读为 int；比较字节数，而非 Jackson 节点的实现类型。
        assertEquals(bytes.length, listed.path("size").asLong());
        assertEquals(entry.path("sha256").asText(), listed.path("sha256").asText());
        assertArrayEquals(bytes, Files.readAllBytes(reopened.artifacts().file(id)));
    }

    /** 每个文件独立编号；再次发布生成新版本，源文件改写不影响旧下载内容。 */
    @Test void multipleFilesHaveSeparateIdsAndLaterPublicationKeepsOldVersion() throws Exception {
        var workspace = store().create();
        Files.writeString(workspace.path().resolve("report.pdf"), "report-v1");
        Files.writeString(workspace.path().resolve("data.csv"), "a,b\n1,2\n");
        var first = workspace.artifacts().publish(publication("report.pdf", "data.csv"), context(),
                () -> false, (file, name) -> {}).path("artifacts");
        assertEquals(2, first.size());
        String old = first.get(0).path("artifactId").asText();
        assertNotEquals(old, first.get(1).path("artifactId").asText());
        Files.writeString(workspace.path().resolve("report.pdf"), "report-v2");
        var next = workspace.artifacts().publish(publication("report.pdf"), context(),
                () -> false, (file, name) -> {}).path("artifacts").get(0);
        assertNotEquals(old, next.path("artifactId").asText());
        assertEquals("report-v1", Files.readString(workspace.artifacts().file(old)));
        assertEquals("report-v2", Files.readString(workspace.artifacts().file(next.path("artifactId").asText())));
        assertEquals(3, workspace.artifacts().list().path("artifacts").size());
    }

    /** 明确文件选择不自动展开目录，仍拒绝越界、保护文件、符号链接和硬链接。 */
    @Test void refusesDirectoriesMissingFilesTraversalProtectedPathsAndLinks() throws Exception {
        var workspace = store().create();
        Files.createDirectory(workspace.path().resolve("output"));
        Path original = Files.writeString(workspace.path().resolve("original.txt"), "fixture");
        Files.writeString(workspace.path().resolve(".env"), "SYNTHETIC_FIXTURE=protected");
        Files.createSymbolicLink(workspace.path().resolve("symbolic.txt"), original);
        Files.createLink(workspace.path().resolve("hard.txt"), original);
        for (String path : List.of("output", ".", "missing.pptx", "../outside", ".env", "symbolic.txt", "hard.txt")) {
            assertThrows(IOException.class, () -> workspace.artifacts().publish(publication(path), context(),
                    () -> false, (file, name) -> {}), path);
        }
        assertTrue(workspace.artifacts().list().path("artifacts").isEmpty());
        try (var entries = Files.list(workspace.metadata().resolve("artifacts"))) { assertEquals(0, entries.count()); }
    }

    /** 一批中第二个文件拒绝或取消时，不发布第一个副本，也不删除既有成果。 */
    @Test void deniedOrCancelledBatchLeavesNoPartialPublication() throws Exception {
        var workspace = store().create();
        Files.writeString(workspace.path().resolve("first.txt"), "first");
        Files.writeString(workspace.path().resolve("second.txt"), "second");
        String previous = workspace.artifacts().publish(publication("first.txt"), context(), () -> false,
                (file, name) -> {}).path("artifacts").get(0).path("artifactId").asText();
        assertThrows(IOException.class, () -> workspace.artifacts().publish(publication("first.txt", "second.txt"),
                context(), () -> false, (file, name) -> { if (name.equals("second.txt")) throw new IOException("fixture deny"); }));
        var cancelled = new AtomicBoolean();
        assertThrows(IOException.class, () -> workspace.artifacts().publish(publication("first.txt", "second.txt"),
                context(), cancelled::get, (file, name) -> { if (name.equals("second.txt")) cancelled.set(true); }));
        assertEquals(1, workspace.artifacts().list().path("artifacts").size());
        assertEquals("first", Files.readString(workspace.artifacts().file(previous)));
        try (var entries = Files.list(workspace.metadata().resolve("artifacts"))) {
            assertFalse(entries.anyMatch(file -> file.getFileName().toString().startsWith(".pending-")));
        }
    }

    /** 发布已生成文件无需等待工作区独占操作；锁仍归原操作所有，发布不会释放它。 */
    @Test void publicationDoesNotAcquireTheWorkspaceExecutionBarrier() throws Exception {
        var workspace = store().create();
        Files.writeString(workspace.path().resolve("ready.pptx"), "completed");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var lease = workspace.exclusive()) {
            var published = executor.submit(() -> workspace.artifacts().publish(publication("ready.pptx"), context(),
                    () -> false, (file, name) -> {}));
            assertEquals(1, published.get(2, TimeUnit.SECONDS).path("artifacts").size());
        }
    }

    /** 空文件可正常发布；超限文件在复制前拒绝，使用稀疏文件避免真实分配大量磁盘。 */
    @Test void supportsEmptyFileAndRejectsOversizedFile() throws Exception {
        var workspace = store().create();
        Files.createFile(workspace.path().resolve("empty.txt"));
        var empty = workspace.artifacts().publish(publication("empty.txt"), context(), () -> false,
                (file, name) -> {}).path("artifacts").get(0);
        assertEquals(0, empty.path("size").asLong());
        Path huge = workspace.path().resolve("huge.bin");
        try (var file = FileChannel.open(huge, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            file.position(WorkspaceFiles.MAX_BYTES);
            file.write(ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> workspace.artifacts().publish(publication("huge.bin"), context(),
                () -> false, (file, name) -> {}));
        assertEquals(1, workspace.artifacts().list().path("artifacts").size());
    }

    /** 走真实工具包装器，确保 publish_result 仍逐文件应用 read_file 的 deny 规则。 */
    @Test void publisherToolCannotBypassReadFileDeny() throws Exception {
        var workspace = store().create();
        Path file = Files.writeString(workspace.path().resolve("report.pdf"), "fixture report");
        var rule = new PermissionRule("fixture.deny", PermissionRule.Effect.DENY, PermissionRule.Kind.PATH,
                "read_file", workspace.path().toString(), "READ", file.toString(), false, List.of());
        var state = new PermissionState(PermissionProfile.WORKSPACE, List.of(rule), Set.of(), null);
        var gate = new PermissionGate(new PathResolver(workspace.path()), state, null);
        var tool = WorkspaceTools.forWorkspace(workspace, gate, state, new BashTool(workspace.path())).stream()
                .filter(value -> value.name().equals("publish_result")).findFirst().orElseThrow();
        var input = publication("report.pdf");
        var context = context();
        assertNull(gate.check(tool.name(), input, context));
        assertTrue(tool.executeOutcome(input, context).isError());
        assertTrue(workspace.artifacts().list().path("artifacts").isEmpty());
    }
}
