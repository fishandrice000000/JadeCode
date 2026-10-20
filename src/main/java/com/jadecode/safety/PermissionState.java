package com.jadecode.safety;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import com.jadecode.safety.rules.*;

/**
 * 当前 CLI 独有的权限状态，所有队友共享。不存对话，也不从模型参数修改。
 * 快照与版本一并取得；配置变化通知只负责唤醒审批，不直接批准任何操作。
 */
public final class PermissionState {
    public record Snapshot(PermissionProfile profile, List<PermissionRule> rules, Set<String> disabledDefaults, long revision) {
        public Snapshot { rules = List.copyOf(rules); disabledDefaults = Set.copyOf(disabledDefaults); }
    }
    private PermissionProfile profile;
    private final List<PermissionRule> rules;
    private final Set<String> disabled;
    private long revision;
    /** UI 仅读这个不可变快照，后台保存规则时不会让重绘线程等待磁盘。 */
    private volatile Snapshot published;
    private final PermissionRuleStore store;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    public PermissionState(PermissionProfile profile, List<PermissionRule> rules, Set<String> disabled, PermissionRuleStore store) {
        this.profile = Objects.requireNonNull(profile); this.rules = new ArrayList<>(rules);
        this.disabled = Set.copyOf(disabled); this.store = store;
        published = new Snapshot(profile, List.copyOf(rules), this.disabled, 0);
    }
    /** 离线装配不写用户配置；保存操作明确失败，而不是假装持久化。 */
    public static PermissionState defaults() { return new PermissionState(PermissionProfile.WORKSPACE, List.of(), Set.of(), null); }
    public Snapshot snapshot() { return published; }
    /** 原子更新档位，锁外通知，避免唤醒审批时互相等待。 */
    public void select(PermissionProfile value) {
        Objects.requireNonNull(value);
        synchronized (this) {
            if (profile == value) return;
            profile = value; revision++; published = new Snapshot(profile, List.copyOf(rules), disabled, revision);
        }
        changed();
    }
    /** 先持久化，再发布内存规则。IO 失败时不生成一次虚假的长期批准。 */
    public void remember(PermissionRule rule, long expectedRevision) throws IOException {
        synchronized (this) {
            if (revision != expectedRevision) throw new IOException("权限已变化，请重新确认");
            if (store == null) throw new IOException("此运行未配置长期权限存储");
            store.save(rule); rules.add(rule); revision++;
            published = new Snapshot(profile, List.copyOf(rules), disabled, revision);
        }
        changed();
    }
    /** 请求结束后移除监听，避免已完成申请永久占用内存。 */
    public AutoCloseable onChange(Runnable listener) { listeners.add(listener); return () -> listeners.remove(listener); }
    private void changed() { listeners.forEach(listener -> { try { listener.run(); } catch (RuntimeException ignored) { } }); }
}
