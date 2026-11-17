package com.jadecode.cli.backend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.cli.backend.protocol.ProtocolError;
import com.jadecode.cli.backend.protocol.ProtocolException;
import com.jadecode.safety.approval.Approval;
import com.jadecode.safety.approval.ApprovalAnswer;
import com.jadecode.safety.approval.ApprovalRequest;
import com.jadecode.util.Json;

/**
 * 连接工具线程与 Node 审批页面。
 *
 * <p>请求先登记到会话审批账本，再发送申请资料。前端报告已提交帧、终端代次、按钮区域和输入水位，
 * 用户作答时带回新输入证据。后端逐项核对请求、轮次、权限版本、帧与按键或点击位置，
 * 通过后完成原请求的 Future，工具线程取得 ApprovalAnswer 后继续权限流程。</p>
 */
public final class BridgeApproval implements Approval, AutoCloseable {
    /**
     * 三个按钮的线上序号；与界面竖排顺序一致（绿/黄/红）。
     */
    public enum Choice {
        ALLOW_ONCE(0), ALLOW_SIMILAR(1), DENY(2);

        private final int index;

        /**
         * 保存此审批选项在终端协议中的数字编号。
         *
         * @param index 当前条目或协议选项的下标
         */
        Choice(int index) {
            this.index = index;
        }

        /**
         * 返回发送给前端的审批选项编号，供按钮与作答事件对应。
         *
         * @return 选项在审批协议中的整数编号
         */
        public int index() {
            return index;
        }

        /**
         * 将前端传来的选项编号映射为审批枚举；编号未知时抛出 IllegalArgumentException。
         *
         * @param index 当前条目或协议选项的下标
         * @return 与 index 匹配的允许一次、允许相似或拒绝选项
         * @throws IllegalArgumentException 输入编号不对应任何审批选项
         */
        public static Choice of(int index) {
            for (Choice choice : values())
                if (choice.index == index)
                    return choice;
            throw new IllegalArgumentException("Unknown approval choice: " + index);
        }

        /**
         * 将界面选项转换为权限审批使用的允许一次、允许相似操作或拒绝结果。
         *
         * @return 该界面选项对应的权限审批结果
         */
        ApprovalAnswer answer() {
            return switch (this) {
                case ALLOW_ONCE -> ApprovalAnswer.ALLOW_ONCE;
                case ALLOW_SIMILAR -> ApprovalAnswer.ALLOW_SIMILAR;
                case DENY -> ApprovalAnswer.DENY;
            };
        }
    }

    /**
     * 一个已提交帧中的按钮矩形（0 基格坐标）。
     *
     * @param x 鼠标或按钮左上角的零基列坐标
     * @param y 鼠标或按钮左上角的零基行坐标
     * @param width 按钮矩形占用的列数
     * @param height 按钮矩形占用的行数
     * @param choice 用户明确选择的审批选项
     */
    public record Rect(int x, int y, int width, int height, int choice) {
    }

    /**
     * 保存 Node 已提交审批帧的帧 ID、终端代次、按钮区域、输入水位、权限版本和轮次，供后续作答关联同一可见页面。
     *
     * @param frameId 已提交到终端的呈现帧身份
     * @param terminalEpoch 终端布局代次，重建布局后更新
     * @param columns 呈现帧的终端列数
     * @param rows 呈现帧的终端行数
     * @param rects 当前帧可操作按钮的坐标与选项列表
     * @param layoutDigest 前端提交的审批布局摘要
     * @param rawReceiptWatermark 呈现帧提交时已接收原始输入的水位
     * @param decodedWatermark 呈现帧提交时已解码输入的水位
     * @param revision 本次权限快照版本，批准后执行前用它核对权限是否变化
     * @param runId 当前轮次 ID，用于关联取消、后台命令和任务收尾
     */
    public record Presentation(String frameId, long terminalEpoch, int columns, int rows, List<Rect> rects,
            String layoutDigest, long rawReceiptWatermark, long decodedWatermark, long revision, String runId) {
    }

    /**
     * 一次显式输入的来源；paste/release/repeat 不应走到这里。
     *
     * @param phase 只接受 PRESS
     * @param revision 用户在这次按键时看到的权限 revision
     * @param frameId 这次按键所属的已提交帧
     * @param terminalEpoch 终端代次（resize/模式切换/暂停后变化）
     * @param rawStartReceipt 该序列第一个原始 stdin 块的 receipt；早于呈现水位即不新鲜
     * @param rawEndReceipt 本次输入最后一个原始字节块的接收序号
     * @param decodedSeq 解码后的输入序号，用于同一帧内的顺序比较
     * @param keyAction 键盘确认时的动作 ID（必须为 approval.confirm）
     * @param mouse 鼠标确认证据；键盘确认时为 null
     */
    public record InputEvidence(String phase, long revision, String frameId, long terminalEpoch,
            long rawStartReceipt, long rawEndReceipt, long decodedSeq, String keyAction, MouseEvidence mouse) {
    }

    /**
     * 鼠标证据：只有左键按下且落在同帧矩形内才算数。
     *
     * @param type 鼠标事件类型，确认要求 press
     * @param button 鼠标按钮名称，确认使用 left
     * @param x 鼠标或按钮左上角的零基列坐标
     * @param y 鼠标或按钮左上角的零基行坐标
     */
    public record MouseEvidence(String type, String button, int x, int y) {
    }

    /**
     * 保存一项待审批请求、等待结果的 Future 和最近一次界面呈现证据，供作答时核验并唤醒工具线程。
     *
     * @param request 固定本次工具、输入、真实身份和权限版本的审批请求
     * @param result 等待当前审批得到允许、拒绝或重判结果的 Future
     * @param presentation 当前审批页面已提交的帧与输入水位证据
     */
    private record Pending(ApprovalRequest request, CompletableFuture<ApprovalAnswer> result,
            Presentation presentation) {
        /**
         * 保留请求和结果 Future，创建带有新呈现证据的待审批记录，供终端重绘后替换旧记录。
         *
         * @param value 这次终端重绘后提交的新呈现证据
         * @return 沿用原请求和结果 Future、持有新呈现记录的不可变副本
         */
        Pending withPresentation(Presentation value) {
            return new Pending(request, result, value);
        }
    }

    private final WireContext wire;
    private final Map<String, Pending> waiting = new HashMap<>();
    private boolean closed;
    private final com.jadecode.safety.approval.ApprovalBroker broker = new com.jadecode.safety.approval.ApprovalBroker(
            java.time.Duration.ofHours(1));

    /**
     * 绑定当前会话代次的事件出口，后续审批通过该出口请求前端展示并接收作答证据。
     *
     * @param wire 当前会话代次的事件出口与身份上下文
     */
    public BridgeApproval(WireContext wire) {
        this.wire = wire;
    }

    /**
     * 将申请登记到审批账本和展示等待表，发送 approval.requested 后等待该请求的结果。
     * 正常作答由 answer 唤醒；账本关闭或等待中断返回 DENY。finally 移除剩余登记并发送 approval.removed，
     * 供前端结束该卡片。
     *
     * @param request 固定本次工具、输入、真实身份和权限版本的审批请求
     * @return 原请求的作答结果；通道关闭或等待中断时为 DENY
     */
    @Override
    public ApprovalAnswer ask(ApprovalRequest request) {
        com.jadecode.safety.approval.ApprovalBroker.Pending registered;
        try {
            registered = broker.register(request);
        } catch (IllegalStateException closedChannel) {
            return ApprovalAnswer.DENY;
        }
        Pending pending = new Pending(request, registered.result(), null);
        synchronized (this) {
            if (closed)
                return ApprovalAnswer.DENY;
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
            synchronized (this) {
                waiting.remove(request.id());
            }
            sendRemoved(request.id(), answer);
        }
        return answer;
    }

    /**
     * 把审批请求的身份、工具、工作区、权限版本、参数和可选相似操作建议编码为 approval.requested 事件，
     * 供前端生成审批卡片。
     *
     * @param request 固定本次工具、输入、真实身份和权限版本的审批请求
     */
    private void sendRequested(ApprovalRequest request) {
        ObjectNode payload = Json.mapper().createObjectNode();
        payload.put("requestId", request.id());
        payload.put("actor", request.actor());
        payload.put("runId", request.runId() == null ? "" : request.runId());
        payload.put("tool", request.tool());
        payload.put("cwd", request.cwd() == null ? "" : request.cwd().toString());
        payload.put("revision", Long.toString(request.revision()));
        String input = request.input() == null ? "{}" : request.input().toString();
        payload.put("inputJson", input);
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

    /**
     * 按审批结果生成 recheck、closed 或 answered 移除原因，连同当前待审批记录中的最高权限版本通知前端移除旧卡片。
     *
     * @param requestId 本次人工审批申请 ID，用于找到原始请求和结果 Future
     * @param answer 本次审批的允许、拒绝或重新检查结果
     */
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
     *
     * @param requestId 本次人工审批申请 ID，用于找到原始请求和结果 Future
     * @param presentation 当前审批页面已提交的帧与输入水位证据
     * @throws ProtocolException 申请已失效，版本、轮次、帧或可见按钮区域不合法
     */
    public synchronized void presented(String requestId, Presentation presentation) throws ProtocolException {
        Pending pending = waiting.get(requestId);
        if (pending == null)
            throw new ProtocolException(ProtocolError.STALE_GENERATION,
                    "No pending approval matches this presentation");
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
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                        "An approval action rectangle is outside the viewport");
            try {
                Choice.of(rect.choice());
            } catch (IllegalArgumentException invalid) {
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
     * 将前端用户的明确选择交回原审批请求，并唤醒等待的工具线程。
     *
     * <p>处理过程：</p>
     * <ol>
     * <li>定位 requestId，确认仍有待处理申请和对应的呈现记录。</li>
     * <li>核对权限版本、执行轮次、帧与终端代次，再检查该输入发生在呈现水位之后。</li>
     * <li>把选项编号转为 Choice，核对该按钮在本帧启用；鼠标按键还要命中同帧区域，键盘要携带确认动作。</li>
     * <li>在账本中完成原请求结果，移除对应展示等待；后续工具权限流程取得 ApprovalAnswer 后继续。</li>
     * </ol>
     *
     * <p>输入证据或申请身份失配时，返回对应协议错误，原申请仍按账本生命周期处理。</p>
     *
     * @param requestId 本次人工审批申请 ID，用于找到原始请求和结果 Future
     * @param choiceIndex 审批按钮对应的协议选项编号
     * @param evidence 用户作答对应的按键或鼠标输入证据
     * @throws ProtocolException 旧帧/旧 revision/不新鲜输入/坐标不在按钮内/无建议的 similar
     */
    public void answer(String requestId, int choiceIndex, InputEvidence evidence) throws ProtocolException {
        Pending pending;
        synchronized (this) {
            pending = waiting.get(requestId);
        }
        if (pending == null)
            throw new ProtocolException(ProtocolError.STALE_GENERATION, "This approval request is no longer pending");
        Presentation presentation = pending.presentation();
        if (presentation == null)
            throw new ProtocolException(ProtocolError.NOT_READY, "The approval page has not been presented yet");
        Choice choice;
        try {
            choice = Choice.of(choiceIndex);
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException(ProtocolError.INVALID_MESSAGE, "Unknown approval choice");
        }
        ApprovalRequest request = pending.request();
        if (evidence.revision() != presentation.revision() || evidence.revision() != request.revision())
            throw new ProtocolException(ProtocolError.STALE_REVISION, "The approval revision is stale");
        if (!presentation.frameId().equals(evidence.frameId())
                || presentation.terminalEpoch() != evidence.terminalEpoch())
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
                    throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                            "The deny key can only deny the current request");
            } else if (!"approval.confirm".equals(evidence.keyAction())) {
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                        "Only a fresh confirm or deny key answers an approval");
            }
        } else {
            MouseEvidence mouse = evidence.mouse();
            if (!"press".equals(mouse.type()) || !"left".equals(mouse.button()))
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                        "Only a fresh left-button press can answer an approval");
            if (!hits(presentation, choice, mouse.x(), mouse.y()))
                throw new ProtocolException(ProtocolError.INVALID_MESSAGE,
                        "The click did not land on that approval option");
        }
        synchronized (this) {
            if (waiting.remove(requestId) == null)
                throw new ProtocolException(ProtocolError.STALE_GENERATION,
                        "This approval request was already answered");
            if (!broker.answer(requestId, choice.answer()))
                throw new ProtocolException(ProtocolError.STALE_GENERATION, "This approval is no longer pending");
        }
    }

    /**
     * 检查呈现记录中是否存在指定选项的按钮区域，存在即表示该帧可选择此项。
     *
     * @param presentation 当前审批页面已提交的帧与输入水位证据
     * @param choice 用户明确选择的审批选项
     * @return 当前帧含有该选项按钮时为 true
     */
    private static boolean isEnabled(Presentation presentation, Choice choice) {
        return presentation.rects().stream().anyMatch(rect -> rect.choice() == choice.index());
    }

    /**
     * 检查鼠标坐标是否落在指定选项的按钮区域内；矩形包含左、上边界，右、下边界归下一格。
     *
     * @param presentation 当前审批页面已提交的帧与输入水位证据
     * @param choice 用户明确选择的审批选项
     * @param x 鼠标或按钮左上角的零基列坐标
     * @param y 鼠标或按钮左上角的零基行坐标
     * @return 鼠标坐标位于该选项按钮的左闭右开矩形内时为 true
     */
    private static boolean hits(Presentation presentation, Choice choice, int x, int y) {
        return presentation.rects().stream().anyMatch(rect -> rect.choice() == choice.index()
                && x >= rect.x() && x < rect.x() + rect.width()
                && y >= rect.y() && y < rect.y() + rect.height());
    }

    /**
     * 权限版本变化时结束旧版本申请的等待并返回 RECHECK，使 PermissionGate 重新解析目标和权限。
     *
     * @param revision 本次权限快照版本，批准后执行前用它核对权限是否变化
     */
    @Override
    public synchronized void reconsider(long revision) {
        List<Pending> stale = waiting.values().stream()
                .filter(pending -> pending.request().revision() < revision).toList();
        for (Pending pending : stale) {
            waiting.remove(pending.request().id());
            broker.answer(pending.request().id(), ApprovalAnswer.RECHECK);
        }
    }

    /**
     * 在同步块中取得待审批请求的最高权限版本；没有待审批项时返回 0，供移除事件携带版本信息。
     *
     * @return 待审批记录中的最大权限版本；无待审批时为 0
     */
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

    /**
     * 会话关闭：所有未答复申请明确 DENY；单纯断连只失效呈现证据，Future 不会永久悬空。
     */
    @Override
    public synchronized void close() {
        if (closed)
            return;
        closed = true;
        broker.close();
        waiting.clear();
    }

    /**
     * 新连接必须重新呈现；只重发仍在等待的请求，不恢复旧按键或批准。
     */
    public synchronized void reconnect() {
        invalidatePresented();
        for (var request : broker.requests())
            sendRequested(request);
    }

    /**
     * 取消当前运行时结束人工等待，通道仍可供下一轮使用。
     */
    public synchronized void cancelPending() {
        broker.cancelAll(ApprovalAnswer.DENY);
        waiting.clear();
    }

    /**
     * 服务状态只保存请求快照，不保存 Future 或终端帧。
     *
     * @return 会话审批账本中尚待处理的请求快照
     */
    public java.util.List<ApprovalRequest> requests() {
        return broker.requests();
    }

    /**
     * 统计当前展示等待表中的审批请求数量，供状态快照和诊断使用。
     *
     * @return 当前等待答复的数量；测试与快照诊断使用。
     */
    public synchronized int pendingCount() {
        return waiting.size();
    }

    /**
     * 供测试注入/检查的资料：某申请的当前呈现证据。
     *
     * @param requestId 本次人工审批申请 ID，用于找到原始请求和结果 Future
     * @return 该请求最近一次呈现证据；请求不存在时为 null
     */
    public synchronized Presentation presentationOf(String requestId) {
        Pending pending = waiting.get(requestId);
        return pending == null ? null : pending.presentation();
    }

    /**
     * 组装线上按钮矩形数组的辅助方法。
     *
     * @param rects 当前帧可操作按钮的坐标与选项列表
     * @return 包含各按钮位置、尺寸和选项编号的 JSON 数组
     */
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

    /**
     * 供诊断：某次申请的输入 JSON 长度（不返回内容）。
     *
     * @param request 固定本次工具、输入、真实身份和权限版本的审批请求
     * @return 请求参数序列化后的字符数
     */
    public static int inputLength(ApprovalRequest request) {
        try {
            return request.input() == null ? 0 : Json.mapper().writeValueAsString(request.input()).length();
        } catch (IOException ignored) {
            return 0;
        }
    }
}
