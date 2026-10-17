package com.jadecode.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jadecode.util.PathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** PathGuard v2:分类器矩阵——工作区内 / 高危区 / 工作区外 */
class PathGuardTest {

    @TempDir
    Path ws;

    private PathGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PathGuard(new PathResolver(ws));
    }

    /** ① 工作区内:硬拒与问询都不命中 */
    @Test
    void insidePathPasses() {
        assertNull(guard.highRiskReason("a/b.txt"));
        assertNull(guard.outsideReason("a/b.txt"));
    }

    /** ② 绝对高危路径(/etc):硬拒命中 */
    @Test
    void highRiskPathIsDenied() {
        assertNotNull(guard.highRiskReason("/etc/passwd"));
    }

    /** ③ ../ 越界:硬拒不命中,问询命中 */
    @Test
    void dotDotEscapeAsks() {
        assertNull(guard.highRiskReason("../x.txt"));
        assertEquals("Access outside workspace", guard.outsideReason("../x.txt"));
    }

    /** ④ symlink 指向 /etc:硬拒命中 */
    @Test
    void symlinkToHighRiskIsDenied() throws IOException {
        Files.createSymbolicLink(ws.resolve("link.txt"), Path.of("/etc/hostname"));

        assertNotNull(guard.highRiskReason("link.txt"));
    }

    /** ⑤ symlink 指向工作区外普通目录:问询命中 */
    @Test
    void symlinkToOutsideAsks() throws IOException {
        Path outside = Files.createTempFile("jadecode-outside", ".txt");
        try {
            Files.createSymbolicLink(ws.resolve("link.txt"), outside);

            assertNull(guard.highRiskReason("link.txt"));
            assertEquals("Access outside workspace", guard.outsideReason("link.txt"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /** ⑥ 悬空 symlink 指向 /etc:仍命中硬拒 */
    @Test
    void danglingSymlinkToHighRiskIsDenied() throws IOException {
        Files.createSymbolicLink(ws.resolve("dead.txt"), Path.of("/etc/nonexistent-jadecode"));

        assertNotNull(guard.highRiskReason("dead.txt"));
    }
}
