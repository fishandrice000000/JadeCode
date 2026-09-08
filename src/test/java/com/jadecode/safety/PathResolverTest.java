package com.jadecode.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PathResolverTest {

    @TempDir
    Path ws;

    private PathResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PathResolver(ws);
    }

    /** ① 普通相对路径:解析到工作区内 */
    @Test
    void resolvesInsidePath() {
        assertEquals(ws.resolve("a/b.txt"), resolver.resolve("a/b.txt"));
    }

    /** ② 绝对路径:原样接管 */
    @Test
    void resolvesAbsolutePath() {
        assertEquals(Path.of("/etc/passwd"), resolver.resolve("/etc/passwd"));
    }

    /** ③ ../ 穿透:解析到工作区外(不再拦截,只解析) */
    @Test
    void resolvesDotDotEscape() {
        assertFalse(resolver.resolve("../x.txt").startsWith(resolver.workspaceReal()));
    }

    /** ④ symlink 指向工作区内:解析到目标 */
    @Test
    void resolvesSymlinkInside() throws IOException {
        Files.writeString(ws.resolve("target.txt"), "hi");
        Files.createSymbolicLink(ws.resolve("link.txt"), ws.resolve("target.txt"));

        assertEquals(ws.resolve("target.txt"), resolver.resolve("link.txt"));
    }

    /** ⑤ symlink 指向工作区外:解析到工作区外 */
    @Test
    void resolvesSymlinkOutside() throws IOException {
        Path outside = Files.createTempFile("jadecode-outside", ".txt");
        try {
            Files.createSymbolicLink(ws.resolve("link.txt"), outside);

            assertEquals(outside, resolver.resolve("link.txt"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /** ⑥ 悬空 symlink 指向工作区外:仍解析到工作区外(toRealPath 做不到的) */
    @Test
    void resolvesDanglingSymlinkOutside() throws IOException {
        Files.createSymbolicLink(ws.resolve("dead.txt"), Path.of("/nonexistent-jadecode/x.txt"));

        assertEquals(Path.of("/nonexistent-jadecode/x.txt"), resolver.resolve("dead.txt"));
    }

    /** ⑦ 相对链接目标:相对链接所在目录解析,不是相对工作区 */
    @Test
    void resolvesRelativeSymlinkAgainstItsParent() throws IOException {
        Files.createDirectory(ws.resolve("sub"));
        Files.writeString(ws.resolve("real.txt"), "hi");
        // sub/link.txt → ../real.txt(相对 sub 目录)
        Files.createSymbolicLink(ws.resolve("sub/link.txt"), Path.of("../real.txt"));

        assertEquals(ws.resolve("real.txt"), resolver.resolve("sub/link.txt"));
    }
}
