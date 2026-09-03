package com.jadecode.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.safety.PathGuard;
import com.jadecode.util.Json;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

    @TempDir
    Path ws;

    private PathGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PathGuard(ws);
    }

    private static ObjectNode input(String key, String value) {
        ObjectNode node = Json.mapper().createObjectNode();
        node.put(key, value);
        return node;
    }

    /** ① read:读回文件内容;limit=1 只留首行并附截断提示 */
    @Test
    void readFileReturnsContentAndTruncatesWithLimit() throws IOException {
        Files.writeString(ws.resolve("f.txt"), "a\nb\nc");

        ReadFileTool read = new ReadFileTool(guard);
        assertEquals("a\nb\nc", read.execute(input("path", "f.txt")));

        ObjectNode limited = input("path", "f.txt");
        limited.put("limit", 1);
        assertEquals("a\n... (2 more lines)", read.execute(limited));
    }

    /** ② write:父目录不存在时一并创建,返回消息带 UTF-8 字节数 */
    @Test
    void writeFileCreatesParentsAndReportsBytes() throws IOException {
        WriteFileTool write = new WriteFileTool(guard);

        String out = write.execute(input("path", "nested/dir/f.txt").put("content", "hello"));

        assertEquals("Wrote 5 bytes to nested/dir/f.txt", out);
        assertEquals("hello", Files.readString(ws.resolve("nested/dir/f.txt")));
    }

    /** ③ edit:只替换第一次出现;找不到时返回错误 */
    @Test
    void editFileReplacesFirstOccurrenceOnly() throws IOException {
        Files.writeString(ws.resolve("f.txt"), "hello world hello");
        EditFileTool edit = new EditFileTool(guard);

        ObjectNode in = input("path", "f.txt");
        in.put("old_text", "hello");
        in.put("new_text", "hi");
        assertEquals("Edited f.txt", edit.execute(in));
        assertEquals("hi world hello", Files.readString(ws.resolve("f.txt")));

        ObjectNode missing = input("path", "f.txt");
        missing.put("old_text", "not-there");
        missing.put("new_text", "x");
        assertTrue(edit.execute(missing).contains("not found"));
    }

    /** ④ glob:匹配结果排序输出;无匹配返回占位符 */
    @Test
    void globListsSortedMatchesAndPlaceholder() throws IOException {
        Files.writeString(ws.resolve("b.txt"), "");
        Files.writeString(ws.resolve("a.txt"), "");
        Files.writeString(ws.resolve("sub/c.md"), "");
        GlobTool glob = new GlobTool(guard);

        assertEquals("a.txt\nb.txt", glob.execute(input("pattern", "*.txt")));
        assertEquals("(no matches)", glob.execute(input("pattern", "*.java")));
    }
}
