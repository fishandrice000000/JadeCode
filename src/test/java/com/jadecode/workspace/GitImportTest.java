package com.jadecode.workspace;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.util.Json;

/** 真实 Git 获取/检出/commit 固定；仅在测试传输层将 HTTPS fixture URL 换成本地自有仓库。 */
class GitImportTest {
    @TempDir Path root;
    @Test void fetchPinsTheActualCommitWithFixedArgumentsAndNoRemoteNetwork() throws Exception {
        Path origin=root.resolve("origin"); git("init","--initial-branch=main",origin.toString());
        Files.writeString(origin.resolve("README.md"),"fixture repository\n");
        git("-C",origin.toString(),"add","README.md");
        git("-C",origin.toString(),"-c","user.name=Fixture","-c","user.email=fixture@example.test","commit","-m","fixture");
        String commit=git("-C",origin.toString(),"rev-parse","HEAD").strip();
        Path bin=Files.createDirectory(root.resolve("bin")), record=root.resolve("argv.jsonl");
        String script="#!/usr/bin/python3\nimport os,sys,json\na=sys.argv[1:]\nwith open("+Json.mapper().writeValueAsString(record.toString())+",'a') as f: f.write(json.dumps(a)+'\\n')\n"
                +"a=["+Json.mapper().writeValueAsString(origin.toUri().toString())+" if s=='https://fixture.invalid/repo.git' else ('protocol.file.allow=always' if s=='protocol.file.allow=never' else s) for s in a]\nos.execv('/usr/bin/git',['git']+a)\n";
        Path wrapper=bin.resolve("git"); Files.writeString(wrapper,script); wrapper.toFile().setExecutable(true,true);
        Path destination=Files.createDirectory(root.resolve("destination"));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),Fixture.class.getName(),destination.toString()).redirectErrorStream(true);
        builder.environment().clear(); builder.environment().put("PATH",bin+":/usr/bin:/bin"); builder.environment().put("LANG","C.UTF-8");
        Process process=builder.start();
        try {
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));
            String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),result); assertEquals(commit,result.strip());
            assertEquals("fixture repository\n",Files.readString(destination.resolve("repository/README.md")));
            String arguments=Files.readString(record);
            assertTrue(arguments.contains("--depth=1")); assertTrue(arguments.contains("protocol.file.allow=never"));
            assertTrue(arguments.contains("https://fixture.invalid/repo.git")); assertTrue(arguments.contains("core.hooksPath=/dev/null"));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    /** 子 JVM 只运行 GitWorker，无模型配置、Docker worker 或宿主进程清理逻辑。 */
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            System.out.print(GitWorker.execute(Path.of(args[0]),Json.mapper().createObjectNode().put("operation","fetch")
                    .put("url","https://fixture.invalid/repo.git").put("revision","main")));
        }
    }
    private String git(String... args) throws Exception {
        var command=new ArrayList<>(List.of("/usr/bin/git","-c","core.hooksPath=/dev/null")); command.addAll(List.of(args));
        var builder=new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM","1"); builder.environment().put("GIT_CONFIG_GLOBAL","/dev/null");
        Process process=builder.start();
        try {
            assertTrue(process.waitFor(10,TimeUnit.SECONDS)); String result=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),result); return result;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
