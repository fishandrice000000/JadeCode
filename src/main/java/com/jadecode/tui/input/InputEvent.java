package com.jadecode.tui.input;

/**
 * 输入线程交给 UI 的唯一不可变事件。
 *
 * <p>事件附带单调序号和 terminalEpoch：UI 用序号建立“首次显示后的输入水位”，用 epoch
 * 丢弃 resize/暂停恢复之前的旧鼠标/回车；例如旧审批画面的按键不能批准新申请。粘贴是
 * 一整段正文，不会被拆成按键序列；终端查询回复单独成类，绝不插入编辑器。</p>
 *
 * @param kind 事件种类
 * @param key 按键身份；仅 KEY 事件非 null
 * @param text 文字/粘贴内容；仅 TEXT/PASTE 事件使用
 * @param mouse 鼠标信息；仅 MOUSE 事件使用
 * @param reply 终端查询回复；仅 REPLY 使用
 * @param sequence 输入生产者递增序号，从 1 开始
 * @param terminalEpoch 产生事件的终端代次；resize/恢复后递增
 */
public record InputEvent(Kind kind, KeyChord key, String text, Mouse mouse, TerminalReply reply,
        long sequence, long terminalEpoch) {
    /** 输入事件种类。FAILURE 表示读取线程异常，UI 应走统一关闭收口。 */
    public enum Kind { KEY, TEXT, PASTE, MOUSE, REPLY, RESIZE, EOF, FAILURE }

    public InputEvent {
        if (kind == null) throw new IllegalArgumentException("事件种类不能为空");
        if (kind == Kind.TEXT && text == null) text = "";
    }

    /** 键盘事件；释放事件也会保留，但宿主不得执行动作。 */
    public static InputEvent key(KeyChord key, long sequence, long epoch) {
        return new InputEvent(Kind.KEY, key, "", null, null, sequence, epoch);
    }

    /** 普通输入文字（可能包含多个字素），由编辑器按字素规则插入。 */
    public static InputEvent text(String text, long sequence, long epoch) {
        return new InputEvent(Kind.TEXT, null, text, null, null, sequence, epoch);
    }

    /** 整段 bracketed paste；内容中的回车不触发提交。 */
    public static InputEvent paste(String text, long sequence, long epoch) {
        return new InputEvent(Kind.PASTE, null, text, null, null, sequence, epoch);
    }

    /** SGR 鼠标事件；x/y 从 0 开始，已换算成屏幕列/行。 */
    public static InputEvent mouse(Mouse mouse, long sequence, long epoch) {
        return new InputEvent(Kind.MOUSE, null, "", mouse, null, sequence, epoch);
    }

    /** 终端查询回复（颜色、键盘标记、DA、单元格尺寸等）。 */
    public static InputEvent terminalReply(TerminalReply reply, long sequence, long epoch) {
        return new InputEvent(Kind.REPLY, null, "", null, reply, sequence, epoch);
    }

    /** resize；UI 必须使旧 PresentedFrame 失效并重新协商尺寸。 */
    public static InputEvent resize(long sequence, long epoch) {
        return new InputEvent(Kind.RESIZE, null, "", null, null, sequence, epoch);
    }

    /** EOF/TERM/HUP；只收口一次由宿主保证。 */
    public static InputEvent eof(long sequence, long epoch) {
        return new InputEvent(Kind.EOF, null, "", null, null, sequence, epoch);
    }

    /** 读取/终端故障；消息只用于诊断，不能当用户输入。 */
    public static InputEvent failure(String message, long sequence, long epoch) {
        return new InputEvent(Kind.FAILURE, null, message == null ? "输入读取失败" : message, null, null, sequence, epoch);
    }

    /** @return 是否为可执行的动作按键（非释放、非粘贴/回复）。 */
    public boolean actionableKey() { return kind == Kind.KEY && key != null && key.isActionable(); }

    /**
     * 鼠标事件。button 为 SGR 按钮码（0左/1中/2右，64+为滚轮），action 为
     * PRESS/RELEASE/DRAG/MOVE；x/y 是 0 基屏幕坐标。
     */
    public record Mouse(int button, Action action, int x, int y, boolean ctrl, boolean alt, boolean shift) {
        public enum Action { PRESS, RELEASE, DRAG, MOVE }

        /** @return 是否滚轮事件（64-67）。 */
        public boolean wheel() { return button >= 64 && button <= 67; }

        /** @return 滚轮方向：-1 上、1 下、0 非滚轮。 */
        public int wheelDirection() {
            if (!wheel()) return 0;
            return switch (button & 3) { case 0 -> -1; case 1 -> 1; default -> 0; };
        }
    }

    /**
     * 终端回复。kind 对应查询协议，payload 保留原始回复正文（已去除外围序列），
     * 由 TerminalQueries 解释；非法/迟到回复由调用方按 epoch 丢弃。
     */
    public record TerminalReply(String kind, String payload) {
        public TerminalReply {
            if (kind == null) throw new IllegalArgumentException("回复类型不能为空");
            if (payload == null) payload = "";
        }
    }
}
