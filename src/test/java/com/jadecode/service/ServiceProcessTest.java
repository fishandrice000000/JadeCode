package com.jadecode.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import com.jadecode.util.Json;

/**
 * 真正启动两个独立 JVM 和常驻 daemon，通过私有 UDS 与回环假 Messages 服务验证交付入口。
 * 使用专属 user.home、假认证和显式关闭；不读用户配置、不接触外部模型或真实 SSH 主机。
 */
class ServiceProcessTest {
    @TempDir Path root;
    @Test void headlessUsesDaemonAndRealSdkThenReleasesWorkspaceAndStopsCleanly()throws Exception{
        Path config=Files.createDirectories(root.resolve(".jadecode/config"));
        Files.writeString(config.resolve("config.json"),"""
                {"memory":{"enabled":false},"sandbox":{"enabled":false},"modelMetadata":{"autoDiscover":false}}
                """);
        Path workspace=Files.createDirectory(root.resolve("workspace"));var calls=new AtomicInteger();
        HttpServer server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            String[] events={
                """
                {"type":"message_start","message":{"id":"fixture","type":"message","role":"assistant","model":"fixture","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":0}}}
                """,
                """
                {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                """,
                """
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"fixture final answer"}}
                """,
                """
                {"type":"content_block_stop","index":0}
                """,
                """
                {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":3}}
                """,
                """
                {"type":"message_stop"}
                """
            };
            StringBuilder body=new StringBuilder();
            for(String event:events){
                String kind=Json.mapper().readTree(event).path("type").asText();
                body.append("event: ").append(kind).append("\ndata: ").append(event.strip()).append("\n\n");
            }
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        Path model=config.resolve("fixture-model.json");
        Files.writeString(model,Json.mapper().createObjectNode().put("apiKey","fixture-not-a-secret")
                .put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("model","fixture").put("maxTokens",128).toString());
        ProcessHandle daemon=null;Path socket=null;
        try{
            Result first=run("--headless","--workspace",workspace.toString(),"--model-config",model.toString(),"answer once");
            assertEquals(0,first.code,first.text);assertEquals("fixture final answer\n",first.text);
            var endpoint=Json.mapper().readTree(Files.readString(config.getParent().resolve("service/endpoint.json")));
            daemon=ProcessHandle.of(endpoint.path("pid").asLong()).orElseThrow();
            socket=Path.of(endpoint.path("socket").asText());
            assertTrue(daemon.isAlive());assertTrue(Files.exists(socket));
            Result list=run("--sessions");assertEquals(0,list.code,list.text);
            assertTrue(Json.mapper().readTree(list.text).path("sessions").isEmpty());
            Result second=run("--headless","--workspace",workspace.toString(),"--model-config",model.toString(),"answer again");
            assertEquals(0,second.code,second.text);assertEquals("fixture final answer\n",second.text);
            assertEquals(2,calls.get(),"each independent invocation must execute exactly one model request");
        }finally{
            try{run("--service-stop");}finally{server.stop(0);}
            if(daemon!=null){
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(daemon.isAlive()&&System.nanoTime()<end)Thread.sleep(20);
                if(daemon.isAlive())daemon.destroyForcibly();
                assertFalse(daemon.isAlive(),"owned service must stop");
            }
        }
        if(socket!=null)assertFalse(Files.exists(socket),"owned socket must be removed");
        assertFalse(Files.exists(config.getParent().resolve("service/endpoint.json")));
    }
    private record Result(int code,String text){}
    @Test void sshConnectorUsesFixedRelayCommandAndRemoteRelayReachesTheSamePrivateService()throws Exception{
        Path config=Files.createDirectories(root.resolve(".jadecode/config"));
        Files.writeString(config.resolve("config.json"),"{\"memory\":{\"enabled\":false},\"sandbox\":{\"enabled\":false}}");
        Path bin=Files.createDirectory(root.resolve("bin")),record=root.resolve("ssh-arguments.txt");
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin/java").toString();
        String cp=System.getProperty("java.class.path");
        Path ssh=bin.resolve("ssh");
        Files.writeString(ssh,"#!/bin/sh\nprintf '%s\\n' \"$@\" > "+quote(record.toString())+"\nexec "+quote(javaExecutable)
                +" "+quote("-Duser.home="+root)+" -cp "+quote(cp)+" com.jadecode.Main --service-relay\n");
        Files.setPosixFilePermissions(ssh,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        try{
            extraPath=bin.toString();
            Result result=run("--remote","fixture-host","--sessions");
            assertEquals(0,result.code,result.text);
            assertTrue(Json.mapper().readTree(result.text).path("sessions").isEmpty());
            var arguments=Files.readAllLines(record);
            assertEquals("jade --service-relay",arguments.getLast());
            assertTrue(arguments.contains("BatchMode=yes"));
            assertTrue(arguments.contains("fixture-host"));
        }finally{extraPath=null;run("--service-stop");}
    }
    private String extraPath;
    @Test void sshRelayStdinEofDetachesEvenBeforeFrontendHandshake()throws Exception{
        Path config=Files.createDirectories(root.resolve(".jadecode/config"));
        Files.writeString(config.resolve("config.json"),"{\"memory\":{\"enabled\":false},\"sandbox\":{\"enabled\":false},\"modelMetadata\":{\"autoDiscover\":false}}");
        Path model=config.resolve("fixture.json");
        Files.writeString(model,"{\"apiKey\":\"fixture-not-a-secret\",\"baseUrl\":\"http://127.0.0.1:9\",\"model\":\"fixture\",\"maxTokens\":128}");
        Path workspace=Files.createDirectory(root.resolve("workspace"));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Duser.home="+root,
                "-cp",System.getProperty("java.class.path"),"com.jadecode.Main","--service-relay");
        for(String variable:List.of("ANTHROPIC_API_KEY","ANTHROPIC_BASE_URL","MODEL_ID","MAX_TOKENS","MODEL_PROVIDER"))
            builder.environment().remove(variable);
        builder.redirectError(root.resolve("relay.log").toFile());
        Process relay=builder.start();
        try{
            var peer=new ServiceClient.Peer(relay.getInputStream(),relay.getOutputStream(),()->{});
            var opened=ServiceClient.request(peer,"service.open",Json.mapper().createObjectNode()
                    .put("workspace",workspace.toString()).put("modelConfig",model.toString()));
            relay.getOutputStream().close();
            assertTrue(relay.waitFor(5,TimeUnit.SECONDS),"stdin EOF must release the control socket");
            assertEquals(0,relay.exitValue(),Files.readString(root.resolve("relay.log")));
            var sessions=Json.mapper().readTree(run("--sessions").text).path("sessions");
            assertEquals(1,sessions.size());assertFalse(sessions.get(0).path("connected").asBoolean());
            assertEquals("IDLE",sessions.get(0).path("state").asText());
            assertEquals(0,run("--close-session",opened.path("id").asText()).code);
        }finally{if(relay.isAlive())relay.destroyForcibly();run("--service-stop");}
    }
    private String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    private Result run(String... args)throws Exception{
        String classpath=Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(s->Path.of(s).toAbsolutePath().toString()).reduce((a,b)->a+java.io.File.pathSeparator+b).orElseThrow();
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "-Duser.home="+root,"-cp",classpath,"com.jadecode.Main"));
        command.addAll(List.of(args));
        var builder=new ProcessBuilder(command).redirectErrorStream(true);
        if(extraPath!=null)builder.environment().put("PATH",extraPath+":"+builder.environment().getOrDefault("PATH",""));
        for(String variable:List.of("ANTHROPIC_API_KEY","ANTHROPIC_BASE_URL","MODEL_ID","MAX_TOKENS","MODEL_PROVIDER"))
            builder.environment().remove(variable);
        var process=builder.start();
        if(!process.waitFor(25,TimeUnit.SECONDS)){process.destroyForcibly();fail("Owned CLI process timed out");}
        return new Result(process.exitValue(),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
    }
}
