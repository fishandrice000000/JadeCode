package com.jadecode.service;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.*;
import com.jadecode.cli.backend.protocol.*;
import com.jadecode.config.*;
import com.jadecode.safety.*;
import com.jadecode.session.SessionRuntime;
import com.jadecode.util.Json;

/**
 * 同一用户的常驻 Agent 服务。它拥有会话和配置写者，UDS 连接只是可替换的控制入口。
 * 每个工作区至多一个打开的顶层运行组，Team/子代理仍由原工厂装配和关闭。
 * 服务崩溃后的记录只标记中断，不自动创建运行时或重放旧操作。
 */
public final class AgentService implements AutoCloseable {
    @FunctionalInterface public interface Factories {
        RuntimeFactory create(Path workspace, PermissionState permissions, Path modelConfig);
    }
    private final JadePaths paths;
    private final Path directory;
    private final PreferenceService preferences;
    private final Factories factories;
    private final WorkspaceClaims claims = new WorkspaceClaims();
    private final Map<String, Managed> sessions = new LinkedHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();
    private final String buildId;
    private final String executionHost = hostname();
    /** 主机名仅供确认执行目标；不作为认证或权限依据。 */
    private static String hostname() {
        try { return Files.readString(Path.of("/etc/hostname")).strip(); }
        catch(IOException unavailable) { return "unknown"; }
    }
    private volatile boolean closed;
    /** 生产只读装配配置，不启动模型；测试通过 factories 注入假客户端与临时数据根。 */
    public AgentService(JadePaths paths, String buildId, Factories factories) throws IOException {
        this.paths=paths; this.buildId=buildId; directory=paths.root().resolve("service");
        paths.directory(directory,true);
        preferences=new PreferenceService(paths.config().resolve("config.json"));
        this.factories=factories == null ? (workspace, permissions, model) ->
                new RuntimeFactory(preferences.state().settings(),permissions,model) : factories;
        // 旧清单只提供中断证据。存活进程由外层 daemon 文件锁排除，不恢复旧审批/线程。
        Path index=directory.resolve("sessions.json");
        if (Files.exists(index,LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(index,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid service session index");
            var previous=Json.mapper().readTree(Files.readString(index));
            if(previous.path("sessions").isArray() && previous.path("sessions").isEmpty()) Files.delete(index);
            else { recoverInterrupted(index); Files.move(index,directory.resolve("interrupted-"+UUID.randomUUID()+".json")); }
        }
        reaper.scheduleWithFixedDelay(this::maintain,1,1,TimeUnit.SECONDS);
    }
    /** 真实目录解析后批量检查占用；失败不留下半初始化的会话或租约。 */
    public synchronized Managed open(ObjectNode request) throws IOException {
        if (closed) throw new IOException("Agent service is stopping");
        // 1. 连接已有会话只选择身份，不重新装配、不改变其权限或模型。
        String attach=request.path("attach").asText();
        if (!attach.isBlank()) {
            Managed selected=sessions.get(attach);
            if(selected==null)selected=sessions.values().stream().filter(m->m.wire.sessionId().equals(attach)).findFirst().orElse(null);
            if (selected==null || selected.backend.closed()) throw new IOException("No live session matches "+attach);
            return selected;
        }
        // 2. 工作区必须是执行机器上的真实项目目录；别名和父子目录参与同一占用检查。
        Path workspace=Path.of(request.path("workspace").asText()).toRealPath();
        if (!Files.isDirectory(workspace)) throw new IOException("Workspace must be a directory");
        if(workspace.getNameCount()<2 || workspace.equals(Path.of(System.getProperty("user.home")))
                || workspace.startsWith(paths.root()) || paths.root().startsWith(workspace))
            throw new IOException("Choose a narrow project directory separate from Harness data");
        for (Managed value:sessions.values()) if (value.workspace.equals(workspace) && !value.backend.closed()) {
            if (!request.path("headless").asBoolean() && (value.backend.started() || request.path("prompt").asText().isBlank())
                    && !request.hasNonNull("permission") && !request.hasNonNull("modelConfig") && request.path("resume").asText().isBlank())
                return value;
            throw new IOException("Workspace is held by session "+value.id+"; attach or close it first");
        }
        if (sessions.size()>=8) throw new IOException("Service live session limit reached");
        String id=UUID.randomUUID().toString();
        var owner=claims.owner(id);
        // 3. 先领取范围，再装配资源；失败必须确认清理后才能释放。
        var lease=owner.acquire(List.of(workspace));
        try {
            var configFile=new JadeConfigFile(paths.config().resolve("config.json"));
            // 长期规则可能刚由另一会话保存；新会话从已落盘规则创建独立状态，临时授权不共享。
            PermissionState permissions=configFile.load().permissions().state(configFile);
            String profile=request.path("permission").asText();
            if (!profile.isBlank()) permissions.select(PermissionProfile.parse(profile));
            else if (request.path("headless").asBoolean()) permissions.select(PermissionProfile.AUTO_REVIEW);
            Path model=request.hasNonNull("modelConfig") ? Path.of(request.path("modelConfig").asText()) : null;
            if(model!=null && !model.isAbsolute())model=workspace.resolve(model).normalize();
            RuntimeFactory factory=factories.create(workspace,permissions,model).workspace(workspace,owner::acquire).tokenBudget(1_000_000);
            Managed managed=new Managed(id,workspace,lease,factory,request);
            sessions.put(id,managed);
            try { persist(); }
            catch(IOException failed) {
                managed.backend.close();
                if(managed.backend.stopped())sessions.remove(id);
                else throw new IOException("Session registration failed and cleanup is incomplete",failed);
                throw failed;
            }
            return managed;
        } catch (Exception failure) {
            if(!sessions.containsKey(id)) lease.close();
            throw failure instanceof IOException io ? io : new IOException("Cannot create session: "+failure.getMessage(),failure);
        }
    }
    /** 列表仅含安全的身份和运行资料，不返回配置对象或审批参数。 */
    public synchronized ObjectNode list() {
        var result=Json.mapper().createObjectNode().put("host",executionHost).put("owner",ProcessHandle.current().pid()+"@"+
                ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());
        var array=result.putArray("sessions");
        for (Managed managed:sessions.values()) {
            var row=array.addObject().put("id",managed.id).put("sessionId",managed.wire.sessionId())
                    .put("workspace",managed.workspace.toString()).put("connected",managed.channel.connected())
                    .put("pendingApprovals",managed.backend.pendingApprovals().size()).put("hostExecutionPossible",managed.hostExecution);
            if (managed.backend.closed()) row.put("state",managed.backend.stopped() && !managed.cleanupPending ? "CLOSED" : "CLEANUP_PENDING");
            else {
                row.put("model",managed.backend.runtime().model())
                        .put("permission",managed.backend.runtime().permissionState().snapshot().profile().value());
                row.put("state",managed.backend.pendingApprovals().isEmpty() ? managed.backend.runtime().state().name() : "WAITING_APPROVAL");
            }
        }
        return result;
    }
    /** 管理命令通过私有服务连接到达；工作区占用只在确切资源停止后释放。 */
    public synchronized void closeSession(String id) throws IOException {
        Managed managed=sessions.get(id);
        if(managed==null)managed=sessions.values().stream().filter(m->m.wire.sessionId().equals(id)).findFirst().orElse(null);
        if (managed==null) throw new IOException("No live session matches "+id);
        managed.backend.close();
        try {
            if (!managed.backend.stopped()) throw new IOException("Session cleanup is incomplete; workspace remains held");
            managed.journal.close();
            managed.lease.close(); sessions.remove(managed.id); persist();
        } catch(IOException failure) {
            managed.cleanupPending=true;
            managed.backend.publishClosed(failure.getMessage()); throw failure;
        }
        managed.backend.publishClosed(null);
    }
    /** 同用户管理连接的明确停止请求；窗口是否正在审批不影响这个取消入口。 */
    public synchronized void stopSession(String id) throws IOException {
        Managed managed=sessions.get(id);
        if(managed==null)managed=sessions.values().stream().filter(m->m.wire.sessionId().equals(id)).findFirst().orElse(null);
        if(managed==null)throw new IOException("No live session matches "+id);
        try { managed.backend.cancelCurrent().get(20,TimeUnit.SECONDS); }
        catch(Exception failure) { throw new IOException("Cancellation did not finish",failure); }
    }
    private synchronized void persist() throws IOException {
        JadePaths.atomicWrite(directory.resolve("sessions.json"),list().toString());
    }
    /**
     * 崩溃恢复先确认旧进程死亡，再回收其确切标签容器；失败保留原清单，不能放行工作区。
     * 曾执行 Full Access/非沙箱工具的旧会话无法仅凭 Docker 确认清理，要求操作者先检查现场。
     */
    private void recoverInterrupted(Path index) throws IOException {
        var saved=Json.mapper().readTree(Files.readString(index));
        if(!saved.path("sessions").isArray())throw new IOException("Invalid interrupted session index");
        if(saved.path("sessions").isEmpty())return;
        String owner=saved.path("owner").asText();
        if(!owner.matches("[0-9]+@[0-9]+"))throw new IOException("Interrupted service owner is unknown; index preserved");
        String[] identity=owner.split("@");
        var old=ProcessHandle.of(Long.parseLong(identity[0]));
        if(old.filter(ProcessHandle::isAlive).map(p->p.info().startInstant()
                .map(t->t.toEpochMilli()==Long.parseLong(identity[1])).orElse(true)).orElse(false))
            throw new IOException("Previous service owner is still alive");
        for(var row:saved.path("sessions")){
            String id=row.path("id").asText();
            if(!id.matches("[0-9a-f-]{36}"))throw new IOException("Invalid interrupted session identity");
            boolean host=row.path("hostExecutionPossible").asBoolean(true);
            Path journal=directory.resolve(id+".jsonl");
            if(Files.isRegularFile(journal,LinkOption.NOFOLLOW_LINKS)){
                // 清单最多落后一秒，审计中的 host 标志在操作开始前落盘，不能仅信旧清单。
                try(var lines=Files.lines(journal)){
                    host=host||lines.anyMatch(line->{
                        try{return Json.mapper().readTree(line).path("payload").path("_hostExecutionPossible").asBoolean();}
                        catch(IOException invalid){return true;}
                    });
                }
            }
            if(host)throw new IOException("Interrupted host execution needs manual inspection; original index preserved at "+index);
        }
        var docker=new com.jadecode.sandbox.DockerCli();docker.initialize();
        var listed=docker.call(List.of("ps","-aq","--filter","label=io.jadecode.sandbox=1","--filter","label=io.jadecode.owner="+owner),"",10,()->false);
        if(listed.exitCode()!=0)throw new IOException("Cannot inspect interrupted sandbox cleanup");
        for(String id:listed.output().split("\\s+"))if(!id.isBlank()){
            if(!id.matches("[0-9a-f]+"))throw new IOException("Invalid owned container identity");
            if(docker.call(List.of("rm","-f",id),"",10,()->false).exitCode()!=0)
                throw new IOException("Interrupted sandbox cleanup is incomplete; index preserved");
        }
    }
    /** 定向取消超长执行；等待审批有自己的期限。无连接且真正闲置才按半小时回收。 */
    private synchronized void maintain() {
        if (closed) return;
        // 每份会话独立处理；一个清理失败保留其占用，不驱逐仍在运行的其他会话。
        for (Managed managed:List.copyOf(sessions.values())) {
            try {
                if (managed.backend.closed()) {
                    if (managed.backend.stopped()) closeSession(managed.id);
                    continue;
                }
                boolean idle=managed.backend.runtime().idleForReaping();
                if (!idle) {
                    managed.idleSince=System.nanoTime();
                    if (managed.busySince==0) managed.busySince=System.nanoTime();
                    if (System.nanoTime()-managed.busySince>TimeUnit.HOURS.toNanos(1)) {
                        managed.backend.runtime().cancelCurrent(); managed.busySince=System.nanoTime();
                    }
                } else { managed.busySince=0; if(managed.channel.connected()) managed.idleSince=System.nanoTime(); }
                if (idle && !managed.channel.connected() && managed.backend.pendingApprovals().isEmpty()
                        && System.nanoTime()-managed.idleSince>TimeUnit.MINUTES.toNanos(30))
                    closeSession(managed.id);
            } catch (Exception failure) { System.err.println("[Service] Session maintenance failed: "+failure.getMessage()); }
        }
        try { persist(); } catch (IOException failure) { System.err.println("[Service] State persistence failed"); }
    }
    @Override public synchronized void close() {
        closed=true; reaper.shutdownNow();
        for (Managed value:List.copyOf(sessions.values())) try { closeSession(value.id); }
        catch (IOException failure) { System.err.println("[Service] "+failure.getMessage()); }
        preferences.close();
    }

    /** 顶层运行组；持有后端、输出、审计和工作区租约，不持有某个终端窗口。 */
    public final class Managed {
        public final String id;
        public final Path workspace;
        final WorkspaceClaims.Lease lease;
        public final SessionChannel channel=new SessionChannel();
        public final WireContext wire=new WireContext(channel,new PayloadTransfer(channel));
        public final BackendSessionService backend;
        private final ServiceJournal journal;
        private final BackendSessionService.Startup startup;
        long idleSince=System.nanoTime(), busySince;
        volatile boolean hostExecution;
        volatile boolean cleanupPending;
        Managed(String id,Path workspace,WorkspaceClaims.Lease lease,RuntimeFactory factory,ObjectNode request) throws IOException {
            this.id=id; this.workspace=workspace; this.lease=lease;
            journal=new ServiceJournal(directory.resolve(id+".jsonl"));
            channel.audit((kind,payload)->{
                if(!factory.settings().sandbox().enabled() || factory.permissions().snapshot().profile()==PermissionProfile.FULL_ACCESS)
                    hostExecution=true;
                var record=payload.deepCopy().put("_hostExecutionPossible",hostExecution);
                journal.append(kind,record);
            });
            String prompt=request.path("prompt").asText(), resume=request.path("resume").asText();
            startup=new BackendSessionService.Startup(request.path("picker").asBoolean() ? "picker" : resume.isBlank() ? prompt.isBlank() ? "new" : "prompt" : "resume",
                    prompt,resume,!prompt.isBlank());
            var snapshots=new BackendSnapshots(wire,()->preferences.state().revision(),
                    baseline->channel.snapshot(wire.generation(),baseline));
            backend=new BackendSessionService(wire,preferences,factory,snapshots,message->{},startup,true);
            backend.headless(request.path("headless").asBoolean());
            try { backend.openInitial(); }
            catch (Exception failure) { backend.close(); journal.close(); throw new IOException(failure); }
        }
        /** 为一次控制连接创建协议实例；凭据仅经同用户 UDS/SSH 传给连接者。 */
        public ProtocolSession protocol(OutputStream output,String credential,Runnable disconnected) throws IOException {
            return protocol(output,credential,disconnected,Json.mapper().createObjectNode());
        }
        /** 新 CLI 可连接已有会话并提交新一轮；ready 重复不重复提交启动意图。 */
        public ProtocolSession protocol(OutputStream output,String credential,Runnable disconnected,ObjectNode intent) throws IOException {
            // 新连接使用新身份与一次性凭据；旧窗口的输入证据和命令答复不能迁移。
            String connectionId="conn-"+UUID.randomUUID();
            boolean submitAfterReady=backend.started() && !intent.path("prompt").asText().isBlank();
            var submitted=new java.util.concurrent.atomic.AtomicBoolean();
            var reference=new java.util.concurrent.atomic.AtomicReference<ProtocolSession>();
            ProtocolSession protocol=new ProtocolSession(output,credential,connectionId,hello->{
                var greeting=Json.mapper().createObjectNode().put("connectionId",connectionId)
                        .put("protocolVersion",WireCodec.PROTOCOL_VERSION).put("buildId",buildId).put("projectVersion","1.0.0");
                greeting.putArray("capabilities").add("session.submit").add("session.resume").add("approval.presented");
                var bootstrap=Json.mapper().createObjectNode().put("projectVersion","1.0.0").put("buildId",buildId)
                        .put("protocolVersion",WireCodec.PROTOCOL_VERSION).put("workspace",workspace.toString())
                        .put("themeDirectory",paths.config().resolve("themes").toString())
                        .put("configRevision",Long.toString(preferences.state().revision())).put("serviceSessionId",id).put("executionHost",executionHost);
                bootstrap.set("tui",BackendSnapshot.capture(0,null,null,preferences.state().settings(),0,List.of(),Map.of(),
                        "","",Map.of(),Map.of()).toJson().get("tui"));
                bootstrap.putObject("startup").put("mode",!backend.started() && startup.mode().equals("picker") ? "picker" : "new")
                        .put("autoSubmit",false).put("resumeTarget","");
                return new ProtocolSession.Greeting(greeting,bootstrap);
            }, command->dispatch(command,connectionId), ()->{
                if (channel.detach(reference.get())) { inbound.clear(); backend.disconnected(); disconnected.run(); }
            }, ()->{
                backend.ready();
                if(submitAfterReady && submitted.compareAndSet(false,true)){
                    var payload=Json.mapper().createObjectNode().put("text",intent.path("prompt").asText())
                            .put("clientSubmissionId",intent.path("submissionId").asText("connect-"+connectionId));
                    dispatch(new WireMessage(1,connectionId,0,"session.submit",null,wire.sessionId(),wire.generation(),payload),connectionId);
                }
            }, ()->backend.reconnect(channel::synchronizedDisplay).exceptionally(failure->{
                reference.get().close(); return null;
            }));
            reference.set(protocol);
            if (!channel.attach(protocol,connectionId)) { protocol.close(); throw new IOException("Session already has a controlling client"); }
            return protocol;
        }
        private final PayloadTransfer.Assembler inbound=new PayloadTransfer.Assembler();
        /** 入站分块必须完整校验后才交给后端；普通命令仍经过其会话/代次检查。 */
        private void dispatch(WireMessage original, String connectionId) {
            WireMessage command = new WireMessage(original.version(),original.connectionId(),original.seq(),original.type(),
                    original.commandId()==null ? null : "@"+connectionId+"/"+original.commandId(), original.sessionId(),original.generation(),original.payload());
            try {
                ObjectNode p=command.payload(); String blob=p.path("id").asText();
                switch(command.type()) {
                    case "session.close" -> {
                        if(!Objects.equals(command.sessionId(),wire.sessionId()) || !Objects.equals(command.generation(),wire.generation()))
                            throw new ProtocolException(ProtocolError.STALE_GENERATION,"Close request belongs to another session");
                        AgentService.this.closeSession(id);
                    }
                    case "payload.begin" -> inbound.begin(blob,p.path("kind").asText(),p.path("totalBytes").asInt(),p.path("sha256").asText());
                    case "payload.chunk" -> inbound.chunk(blob,p.path("index").asInt(),p.path("text").asText());
                    case "payload.end" -> backend.registerBlob(blob,inbound.end(blob,p.path("count").asInt(),p.path("sha256").asText()));
                    default -> backend.dispatch(command);
                }
            } catch(ProtocolException failure) {
                wire.session().sendError(command.commandId(),failure.error(),failure.getMessage(),wire.sessionId(),wire.generation());
            } catch(IOException failure) {
                wire.session().sendError(command.commandId(),ProtocolError.PERSISTENCE_FAILED,failure.getMessage(),wire.sessionId(),wire.generation());
            }
        }
    }
}
