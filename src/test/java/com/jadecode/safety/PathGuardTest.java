package com.jadecode.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PathGuardTest {

    @TempDir
    Path ws;

    private PathGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PathGuard(ws);
    }

    /** ① 工作区内合法路径:通过并返回工作区内的词法路径 */
    @Test
    void insidePathPasses() {
        Path p = guard.check("a/b.txt");
        assertEquals(ws.resolve("a/b.txt"), p);
    }

    /** ② 绝对路径:拒绝 */
    @Test
    void absolutePathIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> guard.check("/etc/passwd"));
    }

    /** ③ ../ 穿透:拒绝 */
    @Test
    void dotDotEscapeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> guard.check("../outside.txt"));
    }

    /** ④ 归一化后越界(a/../../b):拒绝 */
    @Test
    void normalizedEscapeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> guard.check("a/../../b.txt"));
    }

    /** ⑤ 符号链接指向工作区外:拒绝 */
    @Test
    void symlinkPointingOutsideIsRejected() throws IOException {
        Path outside = Files.createTempFile("jadecode-outside", ".txt");
        try {
            Files.createSymbolicLink(ws.resolve("link.txt"), outside);

            assertThrows(IllegalArgumentException.class, () -> guard.check("link.txt"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /** ⑥ 符号链接指向工作区内:通过 */
    @Test
    void symlinkPointingInsidePasses() throws IOException {
        Files.writeString(ws.resolve("target.txt"), "hi");
        Files.createSymbolicLink(ws.resolve("link.txt"), ws.resolve("target.txt"));

        assertEquals(ws.resolve("link.txt"), guard.check("link.txt"));
    }

    /** ⑦ 悬空符号链接指向工作区外:拒绝 */
    @Test
    void danglingSymlinkPointingOutsideIsRejected() throws IOException {
        Files.createSymbolicLink(ws.resolve("dead.txt"), Path.of("/nonexistent-outside/x.txt"));

        assertThrows(IllegalArgumentException.class, () -> guard.check("dead.txt"));
    }
}
