package com.jadecode.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jadecode.util.PathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** 迁移原路径分类用例：真实目标与敏感入口由新的文件权限检查解释。 */
class FilePermissionCheckTest {

    @TempDir
    Path ws;

    private PermissionGate guard;

    private String check(String path, boolean write) {
        return guard.check(write ? "write_file" : "read_file", com.jadecode.util.Json.mapper().createObjectNode().put("path", path));
    }

    @BeforeEach
    void setUp() {
        guard = new PermissionGate(new PathResolver(ws), PermissionState.defaults(), null);
    }

    /** ① 工作区内:硬拒与问询都不命中 */
    @Test
    void insidePathPasses() {
        assertNull(check("a/b.txt", false));
        assertNull(check("a/b.txt", true));
    }

    /** /etc 写入保留强制人工保护，不能靠普通 allow 绕过。 */
    @Test
    void highRiskPathIsDenied() {
        assertNotNull(check("/etc/passwd", true));
    }

    /** ③ ../ 越界:硬拒不命中,问询命中 */
    @Test
    void dotDotEscapeAsks() {
        assertTrue(check("../x.txt", false).contains("工作区外"));
    }

    /** symlink 指向 /etc：写保护依真实目标生效。 */
    @Test
    void symlinkToHighRiskIsDenied() throws IOException {
        Files.createSymbolicLink(ws.resolve("link.txt"), Path.of("/etc/hostname"));

        assertNotNull(check("link.txt", true));
    }

    /** ⑤ symlink 指向工作区外普通目录:问询命中 */
    @Test
    void symlinkToOutsideAsks() throws IOException {
        Path outside = Files.createTempFile("jadecode-outside", ".txt");
        try {
            Files.createSymbolicLink(ws.resolve("link.txt"), outside);

            assertTrue(check("link.txt", false).contains("工作区外"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /** 悬空 symlink 也不能绕过敏感写入保护。 */
    @Test
    void danglingSymlinkToHighRiskIsDenied() throws IOException {
        Files.createSymbolicLink(ws.resolve("dead.txt"), Path.of("/etc/nonexistent-jadecode"));

        assertNotNull(check("dead.txt", true));
    }
}
