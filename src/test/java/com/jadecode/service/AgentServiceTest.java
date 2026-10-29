package com.jadecode.service;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.*;
import com.jadecode.cli.backend.protocol.*;
import com.jadecode.config.*;
import com.jadecode.llm.*;
import com.jadecode.messages.*;
import com.jadecode.safety.approval.*;
import com.jadecode.util.Json;

/** 使用生产装配和真实协议状态机验证重连，不读取认证或发真实模型请求。 */
class AgentServiceTest {
    @TempDir Path temporary;
    private JadePaths paths;
    private AgentService service(LlmClient client)throws Exception {
        paths=new JadePaths(temporary.resolve("data"));paths.directory(paths.config(),true);
        Files.writeString(paths.config().resolve("config.json"),"{\"memory\":{\"enabled\":false},\"sandbox\":{\"enabled\":false}}");
        JadeSettings settings=new JadeConfigFile(paths.config().resolve("config.json")).load();
        return new AgentService(paths,"fixture-build",(workspace,permissions,model)->
                new RuntimeFactory(settings,permissions,new AppConfig("fixture","http://127.0.0.1:9","fixture",128),client,paths));
    }
    private ModelResponse reply(String text){return new ModelResponse(Message.assistant(List.of(new TextBlock(text))),new ModelResponse.Usage(1,1),"end_turn");}
    private ObjectNode open(Path workspace){return Json.mapper().createObjectNode().put("workspace",workspace.toString());}

    @Test void disconnectKeepsRuntimeAndReconnectionAdoptsPartialStreamAndDeduplicatesSubmission()throws Exception{
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var calls=new AtomicInteger();
        LlmClient client=new LlmClient(){
            public ModelResponse complete(CompleteRequest request){return reply("complete");}
            public ModelResponse complete(CompleteRequest request,ResponseStream stream)throws LlmException{
                calls.incrementAndGet();stream.text("prefix ");entered.countDown();
                try{if(!finish.await(5,TimeUnit.SECONDS))throw new LlmException("fixture timeout");}
                catch(InterruptedException e){throw new LlmException("interrupted",e);}
                stream.text("suffix");return reply("prefix suffix");
            }
        };
        try(var service=service(client)){
            Path workspace=Files.createDirectory(temporary.resolve("workspace"));
            var managed=service.open(open(workspace));
            var first=new Connection(managed);first.handshake();
            first.command("session.submit",Json.mapper().createObjectNode().put("text","fixture task").put("clientSubmissionId","stable"));
            assertTrue(entered.await(5,TimeUnit.SECONDS));first.close();
            assertTrue(managed.backend.runtime().isBusy(),"EOF must not close the harness");
            try(var second=new Connection(managed)){
                second.handshake();
                assertTrue(second.output.toString(StandardCharsets.UTF_8).contains("prefix "),second.output.toString());
                second.command("session.submit",Json.mapper().createObjectNode().put("text","fixture task").put("clientSubmissionId","stable"));
                second.await("command.result",p->p.path("status").asText().equals("already-dispatched"));
                finish.countDown();second.await("task.result",p->p.path("text").asText().equals("prefix suffix"));
                assertEquals(1,calls.get());
                second.command("session.submit",Json.mapper().createObjectNode().put("text","changed").put("clientSubmissionId","stable"));
                second.await("command.error",p->p.path("message").asText().contains("different text"));
            }finally{finish.countDown();}
            String id=managed.id;service.closeSession(id);
            assertTrue(service.list().path("sessions").isEmpty());
            assertNotNull(service.open(open(workspace)),"confirmed close must release its workspace");
        }
    }

    @Test void independentWorkspacesRunInOneServiceButNestedAndSymlinkAliasesConflict()throws Exception{
        try(var service=service(request->reply("ok"))){
            Path a=Files.createDirectory(temporary.resolve("a")),b=Files.createDirectory(temporary.resolve("b"));
            var first=service.open(open(a));var second=service.open(open(b));
            assertNotEquals(first.id,second.id);
            assertSame(first,service.open(open(a)),"plain local launch attaches to the existing workspace");
            assertThrows(IOException.class,()->service.open(open(Files.createDirectory(a.resolve("nested")))));
            Path alias=Files.createSymbolicLink(temporary.resolve("alias"),a);
            assertSame(first,service.open(open(alias)));
            assertEquals(2,service.list().path("sessions").size());
        }
    }

    @Test void aSecondControlConnectionCannotInvalidateTheFirstOrReleaseItsWorkspace()throws Exception{
        try(var service=service(request->reply("ok"))){
            var managed=service.open(open(Files.createDirectory(temporary.resolve("workspace"))));
            try(var first=new Connection(managed)){
                first.handshake();
                assertThrows(IOException.class,()->new Connection(managed));
                assertTrue(managed.channel.connected());
                first.command("session.submit",Json.mapper().createObjectNode().put("text","still connected").put("clientSubmissionId","one"));
                first.await("task.result",p->true);
            }
        }
    }

    @Test void approvalSurvivesDisconnectAndOldPresentationCannotAuthorizeIt()throws Exception{
        try(var service=service(request->reply("ok"))){
            var managed=service.open(open(Files.createDirectory(temporary.resolve("workspace"))));
            var channel=managed.channel;var bridge=new BridgeApproval(managed.wire);
            var request=new ApprovalRequest("approval-fixture","leader","run-fixture","bash",
                    Json.mapper().createObjectNode().put("command","fixture"),temporary,"fixture",0,null);
            var answer=new AtomicReference<ApprovalAnswer>();
            try(var first=new Connection(managed)){
                first.handshake();
                Thread waiter=Thread.ofVirtual().start(()->answer.set(bridge.ask(request)));
                first.await("approval.requested",p->true);
                var presentation=new BridgeApproval.Presentation("old",0,80,24,List.of(new BridgeApproval.Rect(0,0,5,1,0)),
                        "fixture",0,0,0,"run-fixture");
                bridge.presented(request.id(),presentation);first.close();bridge.invalidatePresented();
                assertNull(answer.get());assertEquals(1,bridge.pendingCount());
                try(var second=new Connection(managed)){
                    second.handshake();bridge.reconnect();second.await("approval.requested",p->true);
                    assertThrows(ProtocolException.class,()->bridge.answer(request.id(),0,
                            new BridgeApproval.InputEvidence("PRESS",0,"old",0,1,1,1,"approval.confirm",null)));
                    bridge.presented(request.id(),new BridgeApproval.Presentation("new",0,80,24,presentation.rects(),"fixture",0,0,0,"run-fixture"));
                    bridge.answer(request.id(),0,new BridgeApproval.InputEvidence("PRESS",0,"new",0,1,1,1,"approval.confirm",null));
                    waiter.join(2000);assertEquals(ApprovalAnswer.ALLOW_ONCE,answer.get());
                }finally{bridge.close();}
            }
        }
    }

    @Test void cancellingOneTurnLeavesSessionAbleToHandleTheNext()throws Exception{
        var entered=new CountDownLatch(1);var counter=new AtomicInteger();
        LlmClient client=request->{
            if(counter.incrementAndGet()==1){
                entered.countDown();
                try{new CountDownLatch(1).await();}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new LlmException("fixture cancellation",e);}
            }
            return reply("next turn works");
        };
        try(var service=service(client)){
            var managed=service.open(open(Files.createDirectory(temporary.resolve("workspace"))));
            try(var connection=new Connection(managed)){
                connection.handshake();
                connection.command("session.submit",Json.mapper().createObjectNode().put("text","first").put("clientSubmissionId","first"));
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                connection.command("session.stop",Json.mapper().createObjectNode());
                connection.await("task.failed",p->true);
                connection.command("session.submit",Json.mapper().createObjectNode().put("text","second").put("clientSubmissionId","second"));
                connection.await("task.result",p->p.path("text").asText().equals("next turn works"));
            }
        }
    }

    @Test void realPermissionRequestIsResentAfterReconnectAndOnlyFreshAnswerExecutesTheTool()throws Exception{
        Path outside=Files.writeString(temporary.resolve("outside.txt"),"fixture document");
        var calls=new AtomicInteger();
        LlmClient client=request->calls.incrementAndGet()==1
                ? new ModelResponse(Message.assistant(List.of(new ToolUseBlock("read-fixture","read_file",
                    Json.mapper().createObjectNode().put("path",outside.toString())))),new ModelResponse.Usage(1,1),"tool_use")
                : reply("document reviewed");
        try(var service=service(client)){
            var managed=service.open(open(Files.createDirectory(temporary.resolve("workspace"))));
            var first=new Connection(managed);first.handshake();
            first.command("session.submit",Json.mapper().createObjectNode().put("text","read the fixture").put("clientSubmissionId","approval"));
            var approval=first.await("approval.requested",p->true);first.close();
            assertEquals(1,managed.backend.pendingApprovals().size());
            try(var second=new Connection(managed)){
                second.handshake();
                var resent=second.await("approval.requested",p->true);
                assertEquals(approval.payload().path("requestId"),resent.payload().path("requestId"));
                approve(second,resent.payload());
                second.await("task.result",p->p.path("text").asText().equals("document reviewed"));
                assertEquals(2,calls.get());assertTrue(managed.backend.pendingApprovals().isEmpty());
            }
        }
    }

    @Test void cancellingAnExecutingToolPreservesItsResultPairAndTheNextRequestCanRun()throws Exception{
        Path marker=temporary.resolve("workspace").resolve("started");
        var calls=new AtomicInteger();
        LlmClient client=request->{
            if(calls.incrementAndGet()==1)
                return new ModelResponse(Message.assistant(List.of(new ToolUseBlock("shell-fixture","bash",
                        Json.mapper().createObjectNode().put("command","printf fixture > started; sleep 30")))),
                        new ModelResponse.Usage(1,1),"tool_use");
            ToolExchangeProtocol.validateHistory(request.messages());
            return reply("next request paired");
        };
        try(var service=service(client)){
            var managed=service.open(open(Files.createDirectory(marker.getParent())).put("permission","full-access"));
            try(var connection=new Connection(managed)){
                connection.handshake();
                connection.command("session.submit",Json.mapper().createObjectNode().put("text","start").put("clientSubmissionId","shell"));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(10);
                assertTrue(Files.exists(marker));
                connection.command("session.stop",Json.mapper().createObjectNode());
                connection.await("task.failed",p->true);
                connection.command("session.submit",Json.mapper().createObjectNode().put("text","next").put("clientSubmissionId","next"));
                connection.await("task.result",p->p.path("text").asText().equals("next request paired"));
            }
        }
    }

    private void approve(Connection connection,ObjectNode request)throws Exception{
        String id=request.path("requestId").asText(),revision=request.path("revision").asText();
        var presented=Json.mapper().createObjectNode().put("requestId",id).put("runId",request.path("runId").asText())
                .put("revision",revision).put("terminalEpoch","0").put("frameId","fixture-frame").put("columns",80).put("rows",24)
                .put("layoutDigest","fixture").put("rawReceiptWatermark","0").put("decodedWatermark","0");
        presented.putArray("actionRects").addObject().put("x",0).put("y",0).put("width",10).put("height",1).put("choice",0);
        connection.command("approval.presented",presented);
        var answer=Json.mapper().createObjectNode().put("requestId",id).put("choice",0);
        answer.putObject("evidence").put("phase","PRESS").put("revision",revision).put("frameId","fixture-frame")
                .put("terminalEpoch","0").put("rawStartReceipt","1").put("rawEndReceipt","1").put("decodedSeq","1").put("keyAction","approval.confirm");
        connection.command("approval.answer",answer);
    }

    @Test void anInterruptedHostExecutionPreservesItsRegistryAndRequiresInspection()throws Exception{
        try(var service=service(request->reply("ok"))){assertNotNull(service);}
        Path index=paths.root().resolve("service/sessions.json");
        var saved=Json.mapper().createObjectNode().put("owner",ProcessHandle.current().pid()+"@0");
        saved.putArray("sessions").addObject().put("id",UUID.randomUUID().toString())
                .put("workspace",temporary.toString()).put("hostExecutionPossible",true);
        Files.writeString(index,saved.toString());
        IOException failure=assertThrows(IOException.class,()->new AgentService(paths,"fixture-build",(a,b,c)->{throw new AssertionError();}));
        assertTrue(failure.getMessage().contains("manual inspection"));
        assertEquals(saved.toString(),Files.readString(index),"failed cleanup must preserve the original record");
    }

    @Test void aNewCliPromptCanAttachToALiveWorkspaceAndRunExactlyOneAdditionalTurn()throws Exception{
        var calls=new AtomicInteger();
        try(var service=service(request->reply("turn "+calls.incrementAndGet()))){
            Path workspace=Files.createDirectory(temporary.resolve("workspace"));
            var managed=service.open(open(workspace));
            try(var first=new Connection(managed)){
                first.handshake();
                first.command("session.submit",Json.mapper().createObjectNode().put("text","first").put("clientSubmissionId","first"));
                first.await("task.result",p->true);
            }
            ObjectNode intent=open(workspace).put("prompt","second").put("submissionId","second-cli");
            assertSame(managed,service.open(intent));
            try(var second=new Connection(managed,intent)){
                second.handshake();second.await("task.result",p->p.path("text").asText().equals("turn 2"));
                assertEquals(2,calls.get());
            }
        }
    }

    @Test void serviceHeadlessDoesNotCreateAHumanApprovalChannel()throws Exception{
        var calls=new AtomicInteger();Path workspace=Files.createDirectory(temporary.resolve("workspace"));
        Path sensitive=Files.writeString(workspace.resolve(".env"),"SYNTHETIC_FIXTURE_ONLY=true");
        LlmClient client=request->{
            if(calls.incrementAndGet()==1)return new ModelResponse(Message.assistant(List.of(
                    new ToolUseBlock("sensitive-read","read_file",Json.mapper().createObjectNode().put("path",sensitive.toString())))),
                    new ModelResponse.Usage(1,1),"tool_use");
            assertTrue(request.messages().stream().flatMap(m->m.content().stream()).anyMatch(c->
                    c instanceof ToolResultBlock r && r.isError() && r.content().contains("No human approval channel")));
            return reply("blocked as expected");
        };
        try(var service=service(client)){
            var managed=service.open(open(workspace).put("headless",true).put("permission","workspace").put("prompt","read"));
            try(var connection=new Connection(managed)){
                connection.handshake();connection.await("task.result",p->p.path("text").asText().equals("blocked as expected"));
                assertTrue(managed.backend.pendingApprovals().isEmpty());
            }
        }
    }

    @Test void reportedTokenBudgetStopsTheNextModelRequestAndANewTurnGetsANewBudget()throws Exception{
        Path workspace=Files.createDirectory(temporary.resolve("workspace"));
        Path file=Files.writeString(workspace.resolve("fixture.txt"),"fixture");
        var calls=new AtomicInteger();
        LlmClient client=request->{
            if(calls.incrementAndGet()==1)return new ModelResponse(Message.assistant(List.of(
                    new ToolUseBlock("read","read_file",Json.mapper().createObjectNode().put("path",file.toString())))),
                    new ModelResponse.Usage(600_000,600_000),"tool_use");
            return reply("new budget");
        };
        try(var service=service(client)){
            var managed=service.open(open(workspace));
            try(var connection=new Connection(managed)){
                connection.handshake();
                connection.command("session.submit",Json.mapper().createObjectNode().put("text","first").put("clientSubmissionId","first"));
                connection.await("task.failed",p->p.path("message").asText().contains("budget"));
                assertEquals(1,calls.get());
                connection.command("session.submit",Json.mapper().createObjectNode().put("text","second").put("clientSubmissionId","second"));
                connection.await("task.result",p->p.path("text").asText().equals("new budget"));
            }
        }
    }

    @Test void unconfirmedShutdownRetainsWorkspaceUntilTheModelThreadActuallyEnds()throws Exception{
        var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        LlmClient client=request->{
            entered.countDown();
            while(finish.getCount()>0)try{finish.await();}catch(InterruptedException ignored){}
            return reply("late reply");
        };
        try(var service=service(client)){
            Path workspace=Files.createDirectory(temporary.resolve("workspace"));
            var managed=service.open(open(workspace));
            try(var connection=new Connection(managed)){
                connection.handshake();connection.command("session.submit",Json.mapper().createObjectNode()
                        .put("text","wait").put("clientSubmissionId","wait"));
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                assertThrows(IOException.class,()->service.closeSession(managed.id));
                assertThrows(IOException.class,()->service.open(open(workspace)));
                assertTrue(managed.backend.shutdownFailure().contains("workspace remains held"));
            }finally{finish.countDown();}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!managed.backend.stopped()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue(managed.backend.stopped());
        }finally{finish.countDown();}
    }

    /** 协议测试使用真实异步 outbox，快照结束之后才发送 ready，保持与 Node 相同顺序。 */
    static final class Connection implements AutoCloseable{
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final ProtocolSession protocol;
        String connection="",session;Long generation;long sequence=1;
        Connection(AgentService.Managed managed)throws IOException{this(managed,Json.mapper().createObjectNode());}
        Connection(AgentService.Managed managed,ObjectNode intent)throws IOException{protocol=managed.protocol(output,"fixture-credential",()->{},intent);}
        void handshake()throws Exception{
            protocol.receive(new WireMessage(1,"",sequence++,"client.hello",null,null,null,Json.mapper().createObjectNode()
                    .put("credential","fixture-credential").put("buildId","fixture-build").put("protocolVersion",1)));
            var hello=await("server.hello",p->true);connection=hello.connectionId();
            var end=await("session.snapshot.end",p->true);
            var begin=frames().stream().filter(f->f.type().equals("session.snapshot.begin")).reduce((a,b)->b).orElseThrow();
            session=end.sessionId();generation=end.generation();
            protocol.receive(new WireMessage(1,connection,sequence++,"session.ready",null,session,generation,Json.mapper().createObjectNode()
                    .put("snapshotId",end.payload().path("snapshotId").asText()).put("generation",Long.toString(generation))
                    .put("lastSnapshotSeq",begin.payload().path("sourceSeq").asText())));
        }
        void command(String type,ObjectNode p)throws Exception{
            protocol.receive(new WireMessage(1,connection,sequence++,type,"c-"+sequence,session,generation,p));
        }
        WireMessage await(String type,Predicate<ObjectNode> match)throws Exception{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<end){
                var found=frames().stream().filter(f->f.type().equals(type)&&match.test(f.payload())).reduce((a,b)->b);
                if(found.isPresent())return found.get();
                Thread.sleep(5);
            }
            fail("No "+type+" in "+output.toString(StandardCharsets.UTF_8));return null;
        }
        List<WireMessage> frames()throws Exception{
            byte[] bytes=output.toByteArray();int last=bytes.length-1;
            while(last>=0&&bytes[last]!='\n')last--;
            var in=new ByteArrayInputStream(Arrays.copyOf(bytes,last+1));var frames=new ArrayList<WireMessage>();
            WireMessage frame;while((frame=WireCodec.readFrame(in))!=null)frames.add(frame);return frames;
        }
        public void close(){protocol.close();}
    }
}
