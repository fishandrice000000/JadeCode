package com.jadecode.tui.input;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 规范化后的按键身份：物理/逻辑键名、修饰键和按下阶段。
 *
 * <p>不能用“终端收到的可打印字符”反推物理键：法语布局的 `é` 与美式布局的 `Alt+e`
 * 必须产生不同的编辑结果；Kitty CSI-u 还可能把 base-layout 键和实际文字分开。因此
 * {@link #baseKey()} 保存布局相关的基础键（如 `a`），{@link #text()} 事件单独携带实际
 * 输入文字。修饰键集合不可变，便于 KeyBindings 比较和测试。</p>
 *
 * @param key 规范化键名：单字符（小写）或 enter/tab/escape/left 等名字
 * @param modifiers 修饰键集合；不包含隐式 shift（字符大小写由文字事件表达）
 * @param phase 按下/重复/释放
 */
public record KeyChord(String key, Set<Modifier> modifiers, Phase phase) {
    /** 修饰键。SUPER 只在终端支持 CSI-u 时出现。 */
    public enum Modifier { CTRL, ALT, SHIFT, META, SUPER }

    /** 按键阶段。释放事件不能触发编辑/审批动作。 */
    public enum Phase { PRESS, REPEAT, RELEASE }

    public KeyChord {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Key name cannot be empty");
        key = key.length() == 1 ? key.toLowerCase(Locale.ROOT) : key;
        modifiers = modifiers == null ? Set.of() : Set.copyOf(modifiers);
        phase = phase == null ? Phase.PRESS : phase;
    }

    /** 无修饰的普通按下。 */
    public static KeyChord of(String key) { return new KeyChord(key, Set.of(), Phase.PRESS); }

    /** 带修饰的按下；可变参数便于测试和默认键表。 */
    public static KeyChord of(String key, Modifier... modifiers) {
        var set = EnumSet.noneOf(Modifier.class);
        java.util.Collections.addAll(set, modifiers);
        return new KeyChord(key, set, Phase.PRESS);
    }

    /** @return 是否包含指定修饰键。 */
    public boolean has(Modifier modifier) { return modifiers.contains(modifier); }

    /** @return 除 SHIFT 外是否有控制修饰；用于拒绝把快捷键当正文。 */
    public boolean hasControl() {
        return modifiers.stream().anyMatch(m -> m != Modifier.SHIFT);
    }

    /** @return 是否是有效可执行按键（释放事件不执行）。 */
    public boolean isActionable() { return phase != Phase.RELEASE; }

    /** @return 人类可读形式，例如 `ctrl+shift+left`；供帮助与冲突报告。 */
    public String display() {
        var names = new java.util.ArrayList<String>();
        for (Modifier modifier : modifiers.stream().sorted(Comparator.comparing(Enum::name)).toList())
            names.add(modifier.name().toLowerCase(Locale.ROOT));
        names.add(key);
        return String.join("+", names);
    }

    /**
     * 比较是否匹配一个动作绑定。绑定忽略 phase（动作只关心按下），但要求修饰键完全一致；
     * shift 不在字符键上隐式加入，因为大写字母通过文字事件表达。
     */
    public boolean matches(KeyChord binding) {
        return binding != null && key.equals(binding.key) && modifiers.equals(binding.modifiers);
    }
}
