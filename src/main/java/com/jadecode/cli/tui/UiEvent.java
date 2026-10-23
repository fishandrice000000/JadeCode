package com.jadecode.cli.tui;

import com.jadecode.execution.ExecutionNotice;
import com.jadecode.tui.input.InputEvent;

/**
 * 工作线程交给 UI 线程的统一不可变事件。view/component 只看到纯数据和已净化的字符串，
 * 不拿到可变 JSON、JS Value、Future 或运行对象。
 *
 * <p>Scoped 包装把每个运行时代次附加到事件上；控制器丢弃旧代次、暂存未来代次。流式事件
 * 带 requestId，工具预览带 requestId/blockIndex，执行 notice 的 opaque ID 复用同一前缀，
 * 因此 UI 能稳定关联而不依赖名称/时间猜测。所有异步结果都在 UI 线程采用。</p>
 */
public sealed interface UiEvent {
    /** 代次包装；generation 是递增的运行时编号，不是持久会话 ID/权限。 */
    record Scoped(long generation, UiEvent event) implements UiEvent { }

    /** 新会话已打开；UI 先安装历史再启动消费者。 */
    record SessionOpened(com.jadecode.cli.SessionHost.Opened opened) implements UiEvent { }

    /** 会话候选清单（/resume）。 */
    record SessionList(java.util.List<com.jadecode.session.history.SessionMetadata> sessions) implements UiEvent { }

    /** 打开会话失败；旧会话保持可用，草稿保留。 */
    record ResumeFailed(String message) implements UiEvent { }

    /** 已解码输入（按键/文字/粘贴/鼠标/回复/resize/EOF）。 */
    record Input(InputEvent input) implements UiEvent { }

    /** 真实执行事实（工具/计时/会话/通知）。 */
    record Execution(ExecutionNotice notice) implements UiEvent { }

    /** 一次正式请求开始，绑定 requestId 与真实 actor。 */
    record StreamStarted(String request, String actor) implements UiEvent { }

    /** 流式正文/思考增量；kind 只允许 REPLY/THINKING（由模型层决定）。 */
    record Delta(String request, String actor, com.jadecode.cli.tui.model.TranscriptEntry.Kind kind, String text)
            implements UiEvent { }

    /** 工具参数预览开始；只建卡，不执行。 */
    record ToolPreviewStarted(String request, String actor, int blockIndex, String callId, String name,
            String initialInput, com.jadecode.execution.ToolIdentity identity) implements UiEvent { }

    /** 工具参数片段；只更新预览。 */
    record ToolPreviewDelta(String request, int blockIndex, String fragment) implements UiEvent { }

    /** 工具参数 JSON 已通过校验；仍不是执行。 */
    record ToolPreviewClosed(String request, int blockIndex) implements UiEvent { }

    /** 请求结束；stopReason 来自服务端，失败保留已显示片段。 */
    record StreamEnd(String request, boolean failed, String stopReason) implements UiEvent { }

    /** 程序整条消息/通知；正文原文保留，外层标题由 UiMessages 决定。 */
    record Message(String actor, String label, String text, boolean error) implements UiEvent { }

    /** 审批申请；revision 用于 RECHECK/防误批，参数为真实完整源文。 */
    record ApprovalRequested(String id, String actor, String tool, String arguments, String reason,
            String suggestion, long revision) implements UiEvent { }

    /** 审批已移除/已由 Gate 重新判断。 */
    record ApprovalRemoved(String id) implements UiEvent { }

    /** 终端/输入故障；UI 走统一收口。 */
    record TerminalFailure(String message) implements UiEvent { }

    /** 后台读取的 Git 分支；只更新页脚，不产生对话卡。 */
    record Branch(String branch) implements UiEvent { }

    /** 用户显式粘贴读取到的文字；只在 UI 线程插入编辑器。 */
    record ClipboardText(String text) implements UiEvent { }

    /** 外部编辑器返回的完整草稿；只在 UI 线程替换编辑器内容。 */
    record ExternalEditorResult(String text) implements UiEvent { }

    /** 后台纯计算结果就绪，只请求一次重排；不携带 JS Value。 */
    record Repaint() implements UiEvent { }

    /** 主题预览的文件读取/解析完成；只在 UI 线程按 ticket 采纳，迟到结果自动丢弃。 */
    record ThemeLoaded(com.jadecode.tui.theme.ThemeManager.LoadResult result) implements UiEvent { }

    /** 当前自定义主题文件被修改；坏文件保留最后有效快照并报告真实错误。 */
    record ThemeWatched(com.jadecode.tui.theme.ThemeManager.WatchResult result) implements UiEvent { }

    /** 右上角临时提示；只影响 UI 显示，不进入转录/模型历史。 */
    record Flash(String text, long durationMillis, boolean error) implements UiEvent { }

    /** 关闭清理完成，可以结束 UI 循环。 */
    record Stopped() implements UiEvent { }
}
