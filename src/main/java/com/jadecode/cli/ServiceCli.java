package com.jadecode.cli;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.protocol.*;
import com.jadecode.cli.backend.transport.LocalControlServer;
import com.jadecode.service.*;
import com.jadecode.util.Json;
import com.jadecode.util.TerminalText;

/**
 * 本地/远程共用客户端入口。CLI 持有连接和 Node，服务持有运行时；
 * 窗口退出只关闭连接。headless 也走服务协议，得到最终结果后明确关闭专属会话。
 */
public final class ServiceCli {
    private ServiceCli() { }
    /** 解析服务选项后沿用现有 prompt/权限语法；help/version 不创建服务。 */
    public static int run(String[] args) {
        if(args.length==1 && args[0].equals("--service-daemon")) return ServiceDaemon.run();
        if(args.length==1 && args[0].equals("--service-relay")) return ServiceClient.relay();
        try {
            // 1. 服务连接参数与既有任务语法分开解析；-- 之后保留用户字面内容。
            var remaining=new ArrayList<String>();String remote=null,workspace=null,attach=null,close=null,cancel=null;
            boolean list=false,stop=false;
            String workspaceId=null,upload=null,artifact=null,output=null;
            boolean isolated=false,workspaceList=false,workspaceCreate=false,workspaceDelete=false,artifacts=false;
            for(int i=0;i<args.length;i++) {
                String arg=args[i];
                if(arg.equals("--")){remaining.addAll(Arrays.asList(args).subList(i,args.length));break;}
                switch(arg) {
                    case "--remote","--workspace","--attach","--close-session","--stop-session" -> {
                        if(++i>=args.length) throw new LaunchOptions.UsageException(arg+" requires a value");
                        switch(arg){case "--remote"->remote=args[i];case "--workspace"->workspace=args[i];
                            case "--attach"->attach=args[i];case "--stop-session"->cancel=args[i];default->close=args[i];}
                    }
                    case "--sessions"->list=true;
                    case "--service-stop"->stop=true;
                    case "--isolated"->isolated=true;
                    case "--managed-workspaces"->workspaceList=true;
                    case "--create-workspace"->workspaceCreate=true;
                    case "--delete-workspace"->workspaceDelete=true;
                    case "--artifacts"->artifacts=true;
                    case "--workspace-id","--upload-snapshot","--download-artifact","--output" -> {
                        if (++i>=args.length) throw new LaunchOptions.UsageException(arg+" requires a value");
                        switch(arg) { case "--workspace-id"->workspaceId=args[i]; case "--upload-snapshot"->upload=args[i];
                            case "--download-artifact"->artifact=args[i]; default->output=args[i]; }
                    }
                    default->remaining.add(arg);
                }
            }
            LaunchOptions options=LaunchOptions.parse(remaining.toArray(String[]::new));
            if(options.mode()==LaunchOptions.Mode.HELP){System.out.println(LaunchOptions.help()+help());return 0;}
            if(options.mode()==LaunchOptions.Mode.VERSION){System.out.println(InteractiveLauncher.version());return 0;}
            boolean workspaceManagement=workspaceList || workspaceCreate || workspaceDelete || artifacts || upload!=null || artifact!=null;
            int workspaceActions=(workspaceList?1:0)+(workspaceCreate?1:0)+(workspaceDelete?1:0)+(artifacts?1:0)+(upload!=null?1:0)+(artifact!=null?1:0);
            if (workspaceActions>1 || workspaceManagement && (isolated || attach!=null || workspace!=null || list || stop || close!=null || cancel!=null || options.hasPrompt()))
                throw new LaunchOptions.UsageException("Choose one workspace management operation without an Agent task");
            if (workspaceManagement && (options.headless() || options.resume()!=null || options.permission()!=null || options.modelConfig()!=null))
                throw new LaunchOptions.UsageException("Workspace management does not accept Agent launch options");
            if (output!=null && artifact==null) throw new LaunchOptions.UsageException("--output requires --download-artifact");
            if (attach!=null && workspaceId!=null) throw new LaunchOptions.UsageException("Choose --attach or --workspace-id");
            if ((workspaceDelete || artifacts || upload!=null || artifact!=null) && workspaceId==null)
                throw new LaunchOptions.UsageException("This operation requires --workspace-id <id>");
            if (artifact!=null && output==null) throw new LaunchOptions.UsageException("--download-artifact requires --output <local-file>");
            if ((isolated && (workspace!=null || workspaceId!=null || attach!=null)) || (workspaceId!=null && workspace!=null))
                throw new LaunchOptions.UsageException("Choose one of --isolated, --workspace, --workspace-id or --attach");
            if(remote!=null && workspace==null && workspaceId==null && !isolated && !workspaceManagement && attach==null && !list && !stop && close==null && cancel==null)
                throw new LaunchOptions.UsageException("--remote requires --workspace <server-path> or --attach <id>");
            if(attach!=null && (options.hasPrompt() || options.resume()!=null || options.permission()!=null || options.modelConfig()!=null))
                throw new LaunchOptions.UsageException("--attach connects the existing configuration; send new input after connecting");
            // 前端检查发生在启动服务/创建会话之前，缺 TTY 或成套资产不留下无人接收的任务。
            FrontendDistribution distribution=null;
            boolean management=list || stop || close!=null || cancel!=null || workspaceManagement;
            if(!management && !options.headless()) {
                if(System.console()==null) throw new LaunchOptions.UsageException("The interactive UI needs a real terminal; use --headless");
                distribution=FrontendDistribution.locate();distribution.nodeExecutable();
            }
            // 2. 管理命令只操作服务；普通启动先选择会话，再交给 TUI 或 headless 协议循环。
            try(var peer=ServiceClient.connect(remote)) {
                if (workspaceManagement) {
                    if (upload!=null) System.out.println(WorkspaceClient.upload(peer,workspaceId,Path.of(upload)).toPrettyString());
                    else if (artifact!=null) WorkspaceClient.download(peer,workspaceId,artifact,Path.of(output));
                    else System.out.println(WorkspaceClient.request(peer,workspaceCreate?"create":workspaceList?"list":workspaceDelete?"delete":"artifacts",workspaceId).toPrettyString());
                    return 0;
                }
                if(management){
                    var p=Json.mapper().createObjectNode();if(close!=null)p.put("id",close);if(cancel!=null)p.put("id",cancel);
                    var answer=ServiceClient.request(peer,stop?"service.stop":close!=null?"service.close":cancel!=null?"service.cancel":"service.list",p);
                    if(list) System.out.println(answer.toPrettyString());
                    return 0;
                }
                if(options.headless() && !options.hasPrompt()) throw new LaunchOptions.UsageException("--headless requires a task");
                var request=Json.mapper().createObjectNode().put("workspace",workspace==null?Path.of("").toAbsolutePath().toString():remote==null?Path.of(workspace).toAbsolutePath().normalize().toString():workspace)
                        .put("prompt",options.initialPrompt()).put("headless",options.headless()).put("submissionId",UUID.randomUUID().toString());
                if(attach!=null)request.put("attach",attach);
                if(isolated)request.put("isolated",true);
                if(workspaceId!=null)request.put("workspaceId",workspaceId);
                if(options.resume()!=null) request.put("resume",options.resume());
                if(options.resume()!=null && options.resume().isEmpty())request.put("picker",true);
                if(options.permission()!=null)request.put("permission",options.permission().value());
                if(options.modelConfig()!=null)request.put("modelConfig",remote==null?options.modelConfig().toAbsolutePath().toString():options.modelConfig().toString());
                // 3. 配置路径在执行机器解释；跨 SSH 只传路径与选择，不上传配置正文。
                var opened=ServiceClient.request(peer,"service.open",request);
                String credential=opened.path("credential").asText();
                return options.headless()?headless(peer,credential):interactive(peer,credential,distribution);
            }
        }catch(LaunchOptions.UsageException invalid){System.err.println(invalid.getMessage());return 2;}
        catch(FrontendDistribution.Missing missing){System.err.println(missing.getMessage());return 2;}
        catch(Exception failure){System.err.println("Agent service operation failed: "+failure.getMessage());return 1;}
    }
    private static String help(){return """

            Agent service:
              --workspace <path>       Use this directory on the execution machine
              --remote <user@host>     Connect through SSH (configured noninteractive authentication)
              --attach <id>            Reconnect a live service session
              --sessions               List live service sessions
              --stop-session <id>      Cancel a current turn, including a pending approval
              --close-session <id>     Stop and close a service session
              --service-stop           Stop the service and its sessions
              --isolated               Allocate an empty persistent managed workspace
              --create-workspace       Allocate a workspace without starting an Agent
              --managed-workspaces     List retained managed workspaces
              --workspace-id <id>      Open a retained managed workspace
              --delete-workspace       Delete --workspace-id after closing its session
              --upload-snapshot <dir>  Upload local files to --workspace-id (does not import)
              --artifacts              List published results for --workspace-id
              --download-artifact <id> Download with --workspace-id and --output <local-file>

            Closing the UI detaches. Use /stop to cancel the current turn or /close to close the session.
            """;}
    /** Node 只拿连接凭据；本机代理不解析审批或执行工具。 */
    private static int interactive(ServiceClient.Peer peer,String credential,FrontendDistribution distribution)throws Exception{
        try(var server=LocalControlServer.open()){
            Process node=new InteractiveLauncher().spawn(distribution,server.socketPath(),credential);
            try(var channel=server.acceptOne(30_000)){
                var writer=Thread.ofVirtual().start(()->{
                    try{forwardClientInput(ChannelStreams.input(channel),peer.output);}catch(IOException ignored){}
                    finally{try{peer.close();}catch(IOException ignored){}}
                });
                peer.input.transferTo(ChannelStreams.output(channel));
                writer.interrupt();
            }
            return InteractiveLauncher.finish(0,node);
        }
    }
    /**
     * 将前端字节及时送到服务，不解析或改写协议，也不关闭调用方持有的流。
     * SSH 子进程的 stdin 带缓冲；连接保持打开时，较小的 client.hello 不能等到 EOF
     * 或缓冲区装满才发送，否则前端和服务会一直互相等握手。每次读取后刷新即可保留流式时序。
     */
    static void forwardClientInput(InputStream input,OutputStream output) throws IOException {
        byte[] buffer=new byte[8192];int count;
        // read 返回当前可用的一段，不要求凑满整个数组；一次小握手也能立即发出。
        while((count=input.read(buffer))!=-1) {
            if(count==0)continue;
            output.write(buffer,0,count);
            output.flush();
        }
    }
    /** 无界面客户端只安装协议屏障；忽略显示事件，完整组装大结果，绝不询问 stdin。 */
    private static int headless(ServiceClient.Peer peer,String credential)throws Exception{
        String build=FrontendDistribution.BuildInfo.load().buildId();
        long seq=1;String connection="",session=null;Long generation=null;
        send(peer,new WireMessage(1,"",seq++,"client.hello",null,null,null,Json.mapper().createObjectNode()
                .put("protocolVersion",1).put("buildId",build).put("credential",credential)));
        var assembler=new PayloadTransfer.Assembler();Map<String,String> blobs=new HashMap<>();
        String snapshot="",cursor=""; boolean finished=false;int code=0;
        WireMessage message;
        // 分块先校验再引用；只有 task.result/failed 决定本轮结果，显示文字不算完成证据。
        while((message=WireCodec.readFrame(peer.input))!=null){
            ObjectNode p=message.payload();
            switch(message.type()){
                case "server.hello"->connection=message.connectionId();
                case "session.snapshot.begin"->{snapshot=p.path("snapshotId").asText();cursor=p.path("sourceSeq").asText();session=message.sessionId();generation=message.generation();}
                case "session.snapshot.end"->send(peer,new WireMessage(1,connection,seq++,"session.ready",null,session,generation,
                        Json.mapper().createObjectNode().put("snapshotId",snapshot).put("generation",Long.toString(generation)).put("lastSnapshotSeq",cursor)));
                case "payload.begin"->assembler.begin(p.path("id").asText(),p.path("kind").asText(),p.path("totalBytes").asInt(),p.path("sha256").asText());
                case "payload.chunk"->assembler.chunk(p.path("id").asText(),p.path("index").asInt(),p.path("text").asText());
                case "payload.end"->{String id=p.path("id").asText();blobs.put(id,assembler.end(id,p.path("count").asInt(),p.path("sha256").asText()));}
                case "task.result","task.failed"->{
                    if(!finished){
                        finished=true;
                        if(message.type().equals("task.failed")){code=1;System.err.println(p.path("message").asText());}
                        else{String value=p.hasNonNull("textBlobRef")?blobs.get(p.path("textBlobRef").asText()):p.path("text").asText();
                            if(value==null)throw new IOException("Missing complete task result");
                            if(!value.isBlank())System.out.println(TerminalText.display(value));}
                        send(peer,new WireMessage(1,connection,seq++,"session.close","headless-close",session,generation,Json.mapper().createObjectNode()));
                    }
                }
                case "command.error"->{throw new IOException(p.path("message").asText());}
                case "server.stopped"->{return finished?code:1;}
                default->{}
            }
        }
        if(!finished)throw new IOException("Service disconnected before a task result");
        return code;
    }
    private static void send(ServiceClient.Peer peer,WireMessage message)throws Exception{
        peer.output.write(WireCodec.encode(message));peer.output.flush();
    }
}
