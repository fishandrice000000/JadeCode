package com.jadecode.cli.backend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.protocol.ProtocolError;
import com.jadecode.cli.backend.protocol.ProtocolException;
import com.jadecode.safety.approval.Approval;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.safety.approval.ApprovalRequest;
import com.jadecode.util.Json;

/**
 * 跨进程审批桥：工具线程在这里等待，Node 只拿到一次申请的显示资料。
 *
 * <p>为什么不能只把 {@code (id, answer)} 放到线上：旧实现里防误批在 Controller 的视图状态中
 * （无默认、新鲜按键、完整参数、当前 revision、Full Access 二次确认）。迁移后必须由 Java
 * 保留这些证据并复核，否则一次陈旧 Enter 或脚本提交就能批准下一条申请。</p>
 *
 * <p>证据链：Node 先在完整参数校验通过且输出提交围栏完成后发 {@code approval.presented}
 * （frameId/terminalEpoch/按钮矩形/输入水位），再在用户真正按键或左键按下时发
 * {@code approval.answer}。Java 校验连接、会话代次、requestId/runId、权限 revision、
 * terminal epoch、显示帧、点击矩形和输入水位后，才完成原 Future。重复/过期答复返回真实
 * 错误，绝不会落到“下一份申请”上。</p>
 *
 * <p>本类不决定哪些操作需要审批（那是 PermissionGate），也不保存长期规则；它只把一次
 * 申请呈现给前端并把用户的明确选择转成 {@link ApprovalAnswer}。</p>
 */
public final class BridgeApproval implements Approval, AutoCloseable {
    /** 三个按钮的线上序号；与界面竖排顺序一致（绿/黄/红）。 */
    public enum Choice {
        ALLOW_ONCE(0), ALLOW_SIMILAR(1), DENY(2);
        private final int index;
        Choice(int index) { this.index = index; }
        public int index() { return index; }
        public static Choice of(int index) {
            for (Choice choice : values()) if (choice.index == index) return choice;
            throw new IllegalArgumentException("Unknown approval choice: " + index);
        }
        ApprovalAnswer answer() {
            return switch (this) {
                case ALLOW_ONCE -> ApprovalAnswer.ALLOW_ONCE;
                case ALLOW_SIMILAR -> ApprovalAnswer.ALLOW_SIMILAR;
                case DENY -> ApprovalAnswer.DENY;
            };
        }
    }

    /** 一个已提交帧中的按钮矩形（0 基格坐标）。 */
    public record Rect(int x, int y, int width, int height, int choice) { }

    /** Node 报告的“这一帧确实提交成功”的证据；不是终端像素 ACK。 */
    public record Presentation(String frameId, long terminalEpoch, int columns, int rows, List<Rect> rects,
            String layoutDigest, long rawReceiptWatermark, long decodedWatermark, long revision, String runId) { }

    /**
     * 一次显式输入的来源；paste/release/repeat 不应走到这里。
     *
     * @param phase      只接受 PRESS
     * @param revision   用户在这次按键时看到的权限 revision
     * @param frameId    这次按键所属的已提交帧
     * @param terminalEpoch 终端代次（resize/模式切换/暂停后变化）
     * @param rawStartReceipt 该序列第一个原始 stdin 块的 receipt；早于呈现水位即不新鲜
     * @param decodedSeq 解码后的输入序号，用于同一帧内的顺序比较
     * @param keyAction  键盘确认时的动作 ID（必须为 approval.confirm）
     * @param mouse      鼠标确认证据；键盘确认时为 null
     */
    public record InputEvidence(String phase, long revision, String frameId, long terminalEpoch,
            long rawStartReceipt, long rawEndReceipt, long decodedSeq, String keyAction, MouseEvidence mouse) { }

    /** 鼠标证据：只有左键按下且落在同帧矩形内才算数。 */
    public record MouseEvidence(String type, String button, int x, int y) { }

    private record Pending(ApprovalRequest request, CompletableFuture<ApprovalAnswer> result,
            Presentation presentation) {
        Pending withPresentation(Presentation value) { return new Pending(request, result, value); }
    }

    private final WireContext wire;
    private final Map<String, Pending> waiting = new HashMap<>();
    private boolean closed;
    private final com.jadecode.safety.approval.ApprovalBroker broker = new com.jadecode.safety.approval.ApprovalBroker(java.time.Duration.ofHours(1));

    public BridgeApproval(WireContext wire) { this.wire = wire; }

    /**
     * 登记一次申请并等待用户答复；工具线程阻塞在这里，界面线程不会。
     *
     * <p>关闭/中断/异常都按 DENY 返回，不把“没人回答”当成允许。申请资料先复制成显示
     * 文本，之后模型或工具不能再改写屏幕上已展示的内容。</p>
     */
    @Override public ApprovalAnswer ask(ApprovalRequest request) {
        com.jadecode.safety.approval.ApprovalBroker.Pending registered;
        try { registered = broker.register(request); }
        catch (IllegalStateException closedChannel) { return ApprovalAnswer.DENY; }
        Pending pending = new Pending(request, registered.result(), null);
        synchronized (this) {
            if (closed) return ApprovalAnswer.DENY;
            waiting.put(request.id(), pending);
        }
        ApprovalAnswer answer = ApprovalAnswer.DENY;
        try {
            // 展示/审计出口也可能失败；登记后的所有路径都必须回收同一份等待。
            sendRequested(request);
            answer = broker.await(registered);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            broker.answer(request.id(), ApprovalAnswer.DENY);
            synchronized (this) { waiting.remove(request.id()); }
            sendRemoved(request.id(), answer);
        }
        return answer;
    }

    private void sendRequested(ApprovalRequest request) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request.id());
        payload.put("actor", request.actor());
        payload.put("runId", request.runId() == null ? "" : request.runId());
        payload.put("tool", request.tool());
        payload.put("cwd", request.cwd() == null ? "" : request.cwd().toString());
        payload.put("revision", Long.toString(request.revision()));
        String input = request.input() == null ? "{}" : request.input().toString();
        if (input.length() > 4096) {
            payload.put("inputJson", "");
            payload.put("inputBlobRef", wire.payloads().sendText("approval-input", input, wire.sessionId(), wire.generation(), "approval"));
        } else {
            payload.put("inputJson", input);
        }
        if (request.suggestion() == null) {
            payload.putNull("suggestionId");
            payload.putNull("suggestionScope");
        } else {
            payload.put("suggestionId", request.suggestion().id());
            payload.put("suggestionScope", request.suggestion().description());
        }
        // reason 只保留在后端诊断里；界面不绘制它（已批准契约）。
        payload.put("reason", request.reason() == null ? "" : request.reason());
        wire.session().sendEvent("approval.requested", payload, wire.sessionId(), wire.generation());
    }

    private void sendRemoved(String requestId, ApprovalAnswer answer) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", requestId);
        String reason = switch (answer) {
            case RECHECK -> "recheck";
            case DENY -> "closed";
            default -> "answered";
        };
        payload.put("reason", reason);
        payload.put("revision", Long.toString(currentRevision()));
        wire.session().sendEvent("approval.removed", payload, wire.sessionId(), wire.generation());
    }

    /**
     * 登记呈现证据（按钮矩形、帧号、输入水位）。参数不完整、越界或 revision 不匹配都拒绝，
     * 界面因此不会进入“能选择但永远无法确认”的死状态。
     */
    public synchronized void presented(String requestId, Presentation presentation) throws ProtocolException {
        Pending pending = waiting.get(requestId);
        if (pending == null)
            throw new ProtocolException(ProtocolError.STALE_GENERATION, "No pending approval matches this presentation");
        ApprovalRequest request = pending.request();
        if (presentation.revision() != request.revision())
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The approval revision changed");
        if (!presentation.runId().equals(request.runId() == null ? "" : request.runId()))
            throw new ProtocolException(ProtocolError.STALE_GENERATION, "The approval run changed");
        if (presentation.columns() < 1 || presentation.rows() < 1)
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "The viewport size is invalid");
        List<Rect> rects = new ArrayList<>();
        for (Rect rect : presentation.rects()) {
            if (rect.width() <= 0 || rect.height() <= 0 || rect.x() < 0 || rect.y() < 0
                    || rect.x() + rect.width() > presentation.columns()
                    || rect.y() + rect.height() > presentation.rows())
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "An approval action rectangle is outside the viewport");
            try { Choice.of(rect.choice()); }
            catch (IllegalArgumentException invalid) {
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Unknown approval choice in a rectangle");
            }
            if (rect.choice() == Choice.ALLOW_SIMILAR.index() && request.suggestion() == null)
                throw new ProtocolException(ProtocolError.UNSUPPORTED, "This request has no similar-rule suggestion");
            rects.add(rect);
        }
        waiting.put(requestId, pending.withPresentation(new Presentation(presentation.frameId(),
                presentation.terminalEpoch(), presentation.columns(), presentation.rows(), List.copyOf(rects),
                presentation.layoutDigest(), presentation.rawReceiptWatermark(), presentation.decodedWatermark(),
                presentation.revision(), presentation.runId())));
    }

    /**
     * 校验一次显式答复并完成原 Future。
     *
     * @throws ProtocolException 旧帧/旧 revision/不新鲜输入/坐标不在按钮内/无建议的 similar
     */
    public void answer(String requestId, int choiceIndex, InputEvidence evidence) throws ProtocolException {
        Pending pending;
        synchronized (this) { pending = waiting.get(requestId); }
        if (pending == null)
            throw new ProtocolException(ProtocolError.STALE_GENERATION, "This approval request is no longer pending");
        Presentation presentation = pending.presentation();
        if (presentation == null)
            throw new ProtocolException(ProtocolError.NOT_READY, "The approval page has not been presented yet");
        Choice choice;
        try { choice = Choice.of(choiceIndex); }
        catch (IllegalArgumentException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Unknown approval choice");
        }
        ApprovalRequest request = pending.request();
        if (evidence.revision() != presentation.revision() || evidence.revision() != request.revision())
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The approval revision is stale");
        if (!presentation.frameId().equals(evidence.frameId()) || presentation.terminalEpoch() != evidence.terminalEpoch())
            throw new ProtocolException(ProtocolError.STALE_REVISION,
                    "That key press belongs to an older layout; the approval is still pending");
        if (!"PRESS".equals(evidence.phase()))
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Only a fresh key press can answer an approval");
        boolean freshInput = evidence.rawStartReceipt() > presentation.rawReceiptWatermark()
                && evidence.decodedSeq() >= presentation.decodedWatermark();
        if (!freshInput)
            throw new ProtocolException(ProtocolError.STALE_REVISION,
                    "That input started before the approval was shown; press again");
        if (choice == Choice.ALLOW_SIMILAR && request.suggestion() == null)
            throw new ProtocolException(ProtocolError.UNSUPPORTED, "This request has no similar-rule suggestion");
        if (!isEnabled(presentation, choice))
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "That approval option is not available");
        if (evidence.mouse() == null) {
            // Esc 是明确的“拒绝当前请求”；它只能产生 DENY，不能借动作名批准。
            if ("approval.deny".equals(evidence.keyAction())) {
                if (choice != Choice.DENY)
                    throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "The deny key can only deny the current request");
            } else if (!"approval.confirm".equals(evidence.keyAction())) {
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Only a fresh confirm or deny key answers an approval");
            }
        } else {
            MouseEvidence mouse = evidence.mouse();
            if (!"press".equals(mouse.type()) || !"left".equals(mouse.button()))
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Only a fresh left-button press can answer an approval");
            if (!hits(presentation, choice, mouse.x(), mouse.y()))
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "The click did not land on that approval option");
        }
        synchronized (this) {
            if (waiting.remove(requestId) == null)
                throw new ProtocolException(ProtocolError.STALE_GENERATION, "This approval request was already answered");
            if (!broker.answer(requestId, choice.answer()))
                throw new ProtocolException(ProtocolError.STALE_GENERATION, "This approval is no longer pending");
        }
    }

    private static boolean isEnabled(Presentation presentation, Choice choice) {
        return presentation.rects().stream().anyMatch(rect -> rect.choice() == choice.index());
    }

    private static boolean hits(Presentation presentation, Choice choice, int x, int y) {
        return presentation.rects().stream().anyMatch(rect -> rect.choice() == choice.index()
                && x >= rect.x() && x < rect.x() + rect.width()
                && y >= rect.y() && y < rect.y() + rect.height());
    }

    /**
     * 权限 revision 变化时唤醒旧申请，由 Gate 重新判断；变化本身不是批准。
     */
    @Override public synchronized void reconsider(long revision) {
        List<Pending> stale = waiting.values().stream()
                .filter(pending -> pending.request().revision() < revision).toList();
        for (Pending pending : stale) {
            waiting.remove(pending.request().id());
            broker.answer(pending.request().id(), ApprovalAnswer.RECHECK);
        }
    }

    private long currentRevision() {
        synchronized (this) {
            return waiting.values().stream().mapToLong(p -> p.request().revision()).max().orElse(0);
        }
    }

    /**
     * 作废所有已呈现证据（resize/模式切换/主题重排/暂停恢复）。
     *
     * <p>申请本身仍然 pending，但 Node 必须重新呈现一帧才能再次确认；旧 Enter 不会批准。
     */
    public synchronized void invalidatePresented() {
        for (Map.Entry<String, Pending> entry : new ArrayList<>(waiting.entrySet()))
            waiting.put(entry.getKey(), entry.getValue().withPresentation(null));
    }

    /** 会话关闭：所有未答复申请明确 DENY；单纯断连只失效呈现证据，Future 不会永久悬空。 */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        broker.close();
        waiting.clear();
    }

    /** 新连接必须重新呈现；只重发仍在等待的请求，不恢复旧按键或批准。 */
    public synchronized void reconnect() {
        invalidatePresented();
        for (var request : broker.requests()) sendRequested(request);
    }

    /** 取消当前运行时结束人工等待，通道仍可供下一轮使用。 */
    public synchronized void cancelPending() { broker.cancelAll(ApprovalAnswer.DENY); waiting.clear(); }

    /** 服务状态只保存请求快照，不保存 Future 或终端帧。 */
    public java.util.List<ApprovalRequest> requests() { return broker.requests(); }

    /** @return 当前等待答复的数量；测试与快照诊断使用。 */
    public synchronized int pendingCount() { return waiting.size(); }

    /** 供测试注入/检查的资料：某申请的当前呈现证据。 */
    public synchronized Presentation presentationOf(String requestId) {
        Pending pending = waiting.get(requestId);
        return pending == null ? null : pending.presentation();
    }

    /** 组装线上按钮矩形数组的辅助方法。 */
    public static ArrayNode rectsJson(List<Rect> rects) {
        ArrayNode array = Json.mapper().createArrayNode();
        for (Rect rect : rects) {
            ObjectNode node = array.addObject();
            node.put("x", rect.x());
            node.put("y", rect.y());
            node.put("width", rect.width());
            node.put("height", rect.height());
            node.put("choice", rect.choice());
        }
        return array;
    }

    /** 供诊断：某次申请的输入 JSON 长度（不返回内容）。 */
    public static int inputLength(ApprovalRequest request) {
        try { return request.input() == null ? 0 : Json.mapper().writeValueAsString(request.input()).length(); }
        catch (IOException ignored) { return 0; }
    }
}
