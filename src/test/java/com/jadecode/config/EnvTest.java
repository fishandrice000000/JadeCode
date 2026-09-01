package com.jadecode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EnvTest {

    @TempDir
    Path tempDir;

    /** 把内容写进临时目录的 .env 并解析,不碰项目真实 ./.env */
    private Env parse(String content) throws Exception {
        Path file = tempDir.resolve(".env");
        Files.writeString(file, content);
        return Env.load(file);
    }

    /** ① 基本解析:键值、注释、空行、两侧空白 */
    @Test
    void parsesKeyValuesAndSkipsComments() throws Exception {
        Env env = parse("""
                # 注释行
                ANTHROPIC_API_KEY=sk-test-123

                MODEL_ID = deepseek-v4-flash
                """);

        assertEquals("sk-test-123", env.get("ANTHROPIC_API_KEY"));
        assertEquals("deepseek-v4-flash", env.get("MODEL_ID"));
        assertNull(env.get("不存在的KEY"));
    }

    /** ② 值里可以含 '=':按第一个 '=' 分割 */
    @Test
    void valueMayContainEquals() throws Exception {
        Env env = parse("URL=a=b=c");
        assertEquals("a=b=c", env.get("URL"));
    }

    /** ③ 重复 key:后出现者胜 */
    @Test
    void duplicateKeyLastWins() throws Exception {
        Env env = parse("KEY=first\nKEY=second");
        assertEquals("second", env.get("KEY"));
    }

    /** ④ 畸形行(无 '='、空 key)跳过,不报错 */
    @Test
    void malformedLinesAreSkipped() throws Exception {
        Env env = parse("""
                noequalshere
                =emptykey
                GOOD=value
                """);

        assertEquals("value", env.get("GOOD"));
        assertNull(env.get("noequalshere"));
    }

    /** ⑤ 空 Env 查询回退到系统环境变量 */
    @Test
    void emptyEnvFallsBackToSystemEnv() {
        Env empty = Env.of(java.util.Map.of());
        assertEquals(System.getenv("PATH"), empty.get("PATH"));
    }

    /** ⑥ override 语义:.env 里的值覆盖同名系统环境变量 */
    @Test
    void dotenvOverridesProcessEnv() throws Exception {
        Env env = parse("PATH=/fake");
        assertEquals("/fake", env.get("PATH"));
    }

    /** ⑦ loadDotEnv:目录里没有 .env 时返回空 Env,不报错 */
    @Test
    void loadDotEnvWithoutFileReturnsEmpty() {
        Env env = Env.loadDotEnv(tempDir);
        assertEquals(System.getenv("PATH"), env.get("PATH"));
    }

    /** ⑧ loadDotEnv:目录里有 .env 时正常解析 */
    @Test
    void loadDotEnvParsesFileWhenPresent() throws Exception {
        Files.writeString(tempDir.resolve(".env"), "MODEL_ID=x\n");
        assertEquals("x", Env.loadDotEnv(tempDir).get("MODEL_ID"));
    }
}
