package com.jadecode.workspace;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Docker 内的窄 Git 操作，不接受 shell 字符串或宿主配置。获取不运行子模块/钩子，
 * 补丁只比较私有快照，不加载项目外部 diff/textconv；网络授权由外层工具审批处理。
 */
public final class GitWorker {
    private GitWorker() { }
    /** 无内嵌凭据、查询串或文件协议；来源身份可安全记录到审批和输入清单。 */
    public static void validate(String url,String revision) throws IOException {
        try {
            URI uri=URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null || url.length()>2048
                    || !revision.matches("[A-Za-z0-9][A-Za-z0-9._/\\-]{0,199}")) throw new IOException("Expected HTTPS repository without credentials and a simple commit/ref");
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid Git source",invalid); }
    }
    /** 操作目录由 Harness 挂载；返回实际 commit 或补丁位置，错误不会自动重试旧操作。 */
    public static String execute(Path cwd,ObjectNode input) throws Exception {
        if (input.path("operation").asText().equals("fetch")) {
            String url=input.path("url").asText(), revision=input.path("revision").asText(); validate(url,revision);
            call(cwd,List.of("init","repository"),false,null);
            call(cwd,List.of("-C","repository","fetch","--depth=1","--no-tags","--",url,revision),false,null);
            String commit=call(cwd,List.of("-C","repository","rev-parse","FETCH_HEAD^{commit}"),false,null).strip();
            if (!commit.matches("[a-f0-9]{40,64}")) throw new IOException("Git did not resolve a full commit");
            // 检出失败或服务中断时仍保留已解析版本；显式重试须提交这个 commit，不能再追踪移动分支。
            Files.writeString(cwd.resolve("resolved-commit"),commit,StandardOpenOption.CREATE_NEW);
            call(cwd,List.of("-C","repository","checkout","--detach",commit),false,null); return commit;
        }
        if (input.path("operation").asText().equals("patch")) {
            call(cwd,List.of("diff","--no-index","--no-ext-diff","--no-textconv","--binary","--","original","result"),true,cwd.resolve("changes.patch"));
            return "changes.patch";
        }
        throw new IOException("Unsupported managed Git operation");
    }
    /** 固定 argv，隔离系统/用户 Git 配置；输出文件仅能是调用方给定的新暂存文件。 */
    private static String call(Path cwd,List<String> operation,boolean differences,Path output) throws Exception {
        var args=new ArrayList<>(List.of("git","-c","protocol.file.allow=never","-c","core.hooksPath=/dev/null")); args.addAll(operation);
        var builder=new ProcessBuilder(args).directory(cwd.toFile());
        builder.environment().put("GIT_CONFIG_NOSYSTEM","1"); builder.environment().put("GIT_CONFIG_GLOBAL","/dev/null");
        builder.environment().put("GIT_TERMINAL_PROMPT","0"); builder.environment().put("GIT_ASKPASS","/bin/false");
        if (output!=null) builder.redirectOutput(output.toFile());
        Process process=builder.start();
        var errors=CompletableFuture.supplyAsync(()->drain(process.getErrorStream()));
        var stdout=output==null ? CompletableFuture.supplyAsync(()->drain(process.getInputStream())) : CompletableFuture.completedFuture("");
        try {
            if (!process.waitFor(240,TimeUnit.SECONDS)) throw new IOException("Managed Git operation timed out");
            String error=errors.get(2,TimeUnit.SECONDS), text=stdout.get(2,TimeUnit.SECONDS);
            if (process.exitValue()!=0 && !(differences && process.exitValue()==1)) throw new IOException("Git failed: "+error);
            if (output!=null && Files.size(output)>WorkspaceFiles.MAX_BYTES) throw new IOException("Patch limit exceeded");
            return text;
        } finally { process.destroyForcibly(); errors.cancel(true); stdout.cancel(true); }
    }
    /** 排空整个管道，仅保存有限诊断，防止仓库输出撑满堆或阻塞 Git。 */
    private static String drain(InputStream stream) {
        try (stream; var kept=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192]; int count;
            while ((count=stream.read(buffer))>=0) if (kept.size()<64*1024) kept.write(buffer,0,Math.min(count,64*1024-kept.size()));
            return kept.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
