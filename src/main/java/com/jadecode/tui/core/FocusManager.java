package com.jadecode.tui.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import com.jadecode.tui.input.InputEvent;

/**
 * 焦点栈与输入路由优先级：审批独占 > 活动 modal/菜单/补全 > 全屏视口/搜索 > 编辑器。
 *
 * <p>组件不直接订阅输入；宿主把事件交给当前焦点 token，未消费再按栈向下/向上传递。
 * 审批焦点是特殊 modal：打开时保留原编辑器草稿和恢复点，关闭后回到原焦点。非捕获焦点的
 * Overlay 仍覆盖其区域（点击不能穿透），但键盘可继续由父层处理。</p>
 */
public final class FocusManager {
    /**
     * 一个焦点元素。
     * @param id 稳定标识（editor/approval/search/select/...）
     * @param component 接收输入/绘制的组件
     * @param modal 是否独占键盘直到关闭
     * @param capturesPointer 是否拦截其矩形内的点击（不等于独占键盘）
     * @param restore 关闭后要恢复的焦点 ID；空串表示无
     */
    public record FocusToken(String id, Component component, boolean modal, boolean capturesPointer, String restore) {
        public FocusToken {
            if (id == null || component == null) throw new IllegalArgumentException("Focus id and component cannot be empty");
        }
    }

    private final Deque<FocusToken> stack = new ArrayDeque<>();
    private final Map<String, FocusToken> byId = new java.util.LinkedHashMap<>();

    /** 注册/替换一个焦点 token（不自动入栈）。 */
    public void register(FocusToken token) { byId.put(token.id(), token); }

    /** 压入焦点；modal 会遮蔽下方键盘。 */
    public void push(FocusToken token) {
        register(token);
        stack.removeIf(existing -> existing.id().equals(token.id()));
        stack.push(token);
    }

    /** 关闭指定焦点并返回到其 restore 目标（若仍注册）。 */
    public void pop(String id) {
        stack.removeIf(token -> token.id().equals(id));
        if (stack.isEmpty()) {
            FocusToken editor = byId.get("editor");
            if (editor != null) stack.push(editor);
        }
    }

    /** @return 栈顶焦点；空栈返回 null。 */
    public FocusToken top() { return stack.peek(); }

    /** @return 当前是否被 modal 独占。 */
    public boolean modal() { return stack.stream().anyMatch(FocusToken::modal); }

    /** @return 指向审批的焦点是否存在；审批优先于所有浮层。 */
    public boolean approvalFocused() {
        return stack.stream().anyMatch(token -> token.id().equals("approval"));
    }

    /**
     * 路由事件：从栈顶向下，跳过被 modal 遮蔽的下层；谁先消费谁停止。
     * @return 是否有组件消费
     */
    public boolean route(InputEvent event) {
        boolean blocked = false;
        for (FocusToken token : stack) {
            if (blocked && !token.modal()) continue;
            if (token.component().handle(event)) return true;
            if (token.modal()) blocked = true;
        }
        return false;
    }

    /** @return 当前可点击的焦点 token（按栈顶优先且 capturesPointer）。 */
    public FocusToken pointerTarget() {
        return stack.stream().filter(FocusToken::capturesPointer).findFirst().orElse(null);
    }

    /** @return 栈快照，栈顶在前；测试/诊断用。 */
    public List<FocusToken> snapshot() { return List.copyOf(stack); }

    /** 清空栈并恢复编辑焦点；用于会话切换后重建。 */
    public void reset(Component editor) {
        stack.clear();
        FocusToken token = new FocusToken("editor", editor, false, false, "");
        register(token); stack.push(token);
    }
}
