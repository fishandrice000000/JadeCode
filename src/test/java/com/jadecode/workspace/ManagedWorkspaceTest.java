package com.jadecode.workspace;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.config.JadePaths;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.util.Json;

/** 普通文件/私有临时目录验证边界与持久化；不读用户配置，不联网或使用真实模型。 */
class ManagedWorkspaceTest {
    @TempDir Path temporary;
    private ExecutionContext context() { return ExecutionContext.create(ExecutionContext.Source.USER,request->ApprovalAnswer.ALLOW_ONCE); }
    private ObjectNode command(String action,String id) { return Json.mapper().createObjectNode().put("action",action).put("workspaceId",id); }
    private ManagedWorkspaceStore store() throws Exception { return new ManagedWorkspaceStore(new JadePaths(temporary.resolve("data"))); }

    private String upload(ManagedWorkspaceStore store,ManagedWorkspace workspace,Path archive) throws Exception {
        var state=store.command(command("upload.begin",workspace.id()).put("size",Files.size(archive)).put("sha256",WorkspaceFiles.sha256(archive)));
        String id=state.path("id").asText(); byte[] bytes=Files.readAllBytes(archive);
        store.command(command("upload.chunk",workspace.id()).put("resourceId",id).put("offset",0).put("data",Base64.getEncoder().encodeToString(bytes)));
        store.command(command("upload.finish",workspace.id()).put("resourceId",id)); return id;
    }

    @Test void deterministicSnapshotSkipsProtectedDataAndPreservesExecutableFiles() throws Exception {
        Path source=Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("hello.py"),"print('fixture')\n"); source.resolve("hello.py").toFile().setExecutable(true,true);
        Files.writeString(source.resolve(".env"),"SYNTHETIC_FIXTURE=hidden");
        Files.createDirectories(source.resolve(".venv")); Files.writeString(source.resolve(".venv/ignored"),"dependency");
        Path first=temporary.resolve("first.zip"), second=temporary.resolve("second.zip");
        WorkspaceFiles.zip(source,first,(file,name)->{}); WorkspaceFiles.zip(source,second,(file,name)->{});
        assertEquals(WorkspaceFiles.sha256(first),WorkspaceFiles.sha256(second));
        Path target=WorkspaceFiles.directory(temporary.resolve("target")); WorkspaceFiles.unzip(first,target,()->false);
        assertEquals("print('fixture')\n",Files.readString(target.resolve("hello.py")));
        assertTrue(Files.isExecutable(target.resolve("hello.py"))); assertFalse(Files.exists(target.resolve(".env")));
        assertFalse(Files.exists(target.resolve(".venv")));
    }

    @Test void extractionRejectsTraversalDuplicatesAndProtectedEntries() throws Exception {
        for (String name:List.of("../escaped","/absolute",".env",".git/config","node_modules/package/file")) {
            Path zip=temporary.resolve(UUID.randomUUID()+".zip");
            try (var stream=new ZipOutputStream(Files.newOutputStream(zip))) { stream.putNextEntry(new ZipEntry(name)); stream.write(1); stream.closeEntry(); }
            Path target=WorkspaceFiles.directory(temporary.resolve(UUID.randomUUID().toString()));
            assertThrows(IOException.class,()->WorkspaceFiles.unzip(zip,target,()->false),name);
        }
        assertFalse(Files.exists(temporary.resolve("escaped")));
    }

    @Test void snapshotRefusesSymlinksAndHardlinks() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("source")); Path outside=Files.writeString(temporary.resolve("outside"),"fixture");
        Files.createSymbolicLink(root.resolve("link"),outside);
        assertThrows(IOException.class,()->WorkspaceFiles.inspect(root,List.of("."),(file,name)->{}));
        Files.delete(root.resolve("link")); Files.createLink(root.resolve("hard"),outside);
        assertThrows(IOException.class,()->WorkspaceFiles.inspect(root,List.of("."),(file,name)->{}));
    }

    @Test void compressedExpansionLimitIsCheckedBeforeCreatingFiles() throws Exception {
        Path zip=temporary.resolve("expansion.zip");
        try (var stream=new ZipOutputStream(Files.newOutputStream(zip))) {
            stream.putNextEntry(new ZipEntry("large")); byte[] buffer=new byte[WorkspaceFiles.CHUNK];
            for (long written=0;written<=WorkspaceFiles.MAX_BYTES;written+=buffer.length) stream.write(buffer);
            stream.closeEntry();
        }
        Path target=WorkspaceFiles.directory(temporary.resolve("extracted"));
        assertThrows(IOException.class,()->WorkspaceFiles.unzip(zip,target,()->false));
        try (var files=Files.list(target)) { assertEquals(0,files.count()); }
    }

    @Test void uploadResumesChecksRepeatedBytesAndOnlyFinalizesCompleteDigest() throws Exception {
        var store=store(); var workspace=store.create(); Path source=Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("note"),"fixture"); Path archive=temporary.resolve("snapshot.zip"); WorkspaceFiles.zip(source,archive,(file,name)->{});
        byte[] bytes=Files.readAllBytes(archive); var begin=command("upload.begin",workspace.id()).put("size",bytes.length).put("sha256",WorkspaceFiles.sha256(archive));
        var initial=store.command(begin); String id=initial.path("id").asText();
        var part=command("upload.chunk",workspace.id()).put("resourceId",id).put("offset",0)
                .put("data",Base64.getEncoder().encodeToString(Arrays.copyOf(bytes,10)));
        assertEquals(10,store.command(part).path("offset").asInt()); assertEquals(10,store.command(part).path("offset").asInt());
        assertEquals(id,store.command(begin).path("id").asText());
        assertThrows(IOException.class,()->store.command(command("upload.finish",workspace.id()).put("resourceId",id)));
        assertThrows(IOException.class,()->store.command(part.deepCopy().put("data",Base64.getEncoder().encodeToString(new byte[10]))));
        store.command(part.deepCopy().put("offset",10).put("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,10,bytes.length))));
        assertEquals("READY",store.command(command("upload.finish",workspace.id()).put("resourceId",id)).path("state").asText());
        var other=store.create(); assertThrows(IOException.class,()->store.command(command("upload.finish",other.id()).put("resourceId",id)));
    }

    @Test void importedSnapshotHasPinnedBaselineRejectsOverwriteAndSurvivesReopen() throws Exception {
        var store=store(); var workspace=store.create(); Path source=Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("note.txt"),"original"); Path archive=temporary.resolve("snapshot.zip"); WorkspaceFiles.zip(source,archive,(file,name)->{});
        String resource=upload(store,workspace,archive);
        var input=Json.mapper().createObjectNode().put("destination","project"); input.putObject("source").put("kind","snapshot").put("resourceId",resource);
        var imported=workspace.resources().importResource(input,context(),()->false,(file,name)->{});
        assertEquals("original",Files.readString(workspace.path().resolve("project/note.txt"))); assertEquals("READY",imported.path("state").asText());
        assertThrows(IOException.class,()->workspace.resources().importResource(input,context(),()->false,(file,name)->{}));
        var reopened=store().open(workspace.id()); assertEquals(1,reopened.status().path("inputs").size());
        assertTrue(Files.exists(reopened.path().resolve("project/note.txt")));
    }

    @Test void cancelledOrDeniedImportNeverPublishesPartialDirectory() throws Exception {
        var store=store(); var workspace=store.create(); Path source=Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("note.txt"),"fixture"); Path archive=temporary.resolve("snapshot.zip"); WorkspaceFiles.zip(source,archive,(file,name)->{});
        var input=Json.mapper().createObjectNode().put("destination","project"); input.putObject("source").put("kind","snapshot").put("resourceId",upload(store,workspace,archive));
        assertThrows(IOException.class,()->workspace.resources().importResource(input,context(),()->false,(file,name)->{throw new IOException("fixture deny");}));
        assertFalse(Files.exists(workspace.path().resolve("project"))); assertTrue(workspace.resources().inputs().isEmpty());
        assertThrows(IOException.class,()->workspace.resources().importResource(input,context(),()->true,(file,name)->{}));
        assertFalse(Files.exists(workspace.path().resolve("project")));
    }

    @Test void exclusiveLeaseBlocksAnotherWriterAndInterruptedWaitDoesNotReleaseItsOwner() throws Exception {
        var workspace=store().create(); var entered=new CountDownLatch(1); var executor=Executors.newSingleThreadExecutor();
        try (var first=workspace.exclusive()) {
            var waiting=executor.submit(()->{entered.countDown(); try(var second=workspace.exclusive()){return true;} });
            assertTrue(entered.await(2,TimeUnit.SECONDS)); assertFalse(waiting.isDone()); waiting.cancel(true);
            assertFalse(executor.submit(()->Thread.currentThread().isInterrupted()).get(2,TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        try (var acquired=workspace.exclusive()) { assertNotNull(acquired); }
    }

    @Test void gitRejectsCredentialUrlsAndGeneratedBinaryPatchActuallyApplies() throws Exception {
        for (String url:List.of("file:///tmp/repo","http://example.test/repo","https://token@example.test/repo","https://example.test/repo?token=x"))
            assertThrows(IOException.class,()->GitWorker.validate(url,"main"));
        GitWorker.validate("https://example.test/repo.git","refs/heads/main");
        Path original=WorkspaceFiles.directory(temporary.resolve("original")), result=WorkspaceFiles.directory(temporary.resolve("result"));
        Files.writeString(original.resolve("old.txt"),"before\n"); Files.writeString(result.resolve("old.txt"),"after\n");
        Files.write(original.resolve("binary"),new byte[]{0,1,2}); Files.write(result.resolve("binary"),new byte[]{0,9,2});
        Files.writeString(result.resolve("new.txt"),"new\n");
        GitWorker.execute(temporary,Json.mapper().createObjectNode().put("operation","patch"));
        var process=new ProcessBuilder("git","-C",original.toString(),"apply","-p2",temporary.resolve("changes.patch").toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(5,TimeUnit.SECONDS)); assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
        assertEquals("after\n",Files.readString(original.resolve("old.txt"))); assertEquals("new\n",Files.readString(original.resolve("new.txt")));
        assertArrayEquals(new byte[]{0,9,2},Files.readAllBytes(original.resolve("binary")));
    }
}
