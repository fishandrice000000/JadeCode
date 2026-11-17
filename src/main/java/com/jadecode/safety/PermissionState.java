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
    /**
     * 保存同一版本的权限档位、用户规则和停用默认规则，供工具检查、审批与界面读取一致的权限状态。
     *
     * @param profile 当前选择的权限档位
     * @param rules 用户配置的有序权限规则列表
     * @param disabledDefaults 停用的内置默认规则 ID 集合
     * @param revision 本次权限快照版本，批准后执行前用它核对权限是否变化
     */
    public record Snapshot(PermissionProfile profile, List<PermissionRule> rules, Set<String> disabledDefaults,
            long revision) {
        /**
         * 复制规则和停用 ID 集合，固定此版本权限快照的内容。
         *
         * @param profile 当前选择的权限档位
         * @param rules 用户配置的有序权限规则列表
         * @param disabledDefaults 停用的内置默认规则 ID 集合
         * @param revision 本次权限快照版本，批准后执行前用它核对权限是否变化
         */
        public Snapshot {
            rules = List.copyOf(rules);
            disabledDefaults = Set.copyOf(disabledDefaults);
        }
    }

    private PermissionProfile profile;
    private final List<PermissionRule> rules;
    private final Set<String> disabled;
    private long revision;
    /** UI 仅读这个不可变快照，后台保存规则时不会让重绘线程等待磁盘。 */
    private volatile Snapshot published;
    private final PermissionRuleStore store;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * 复制初始权限规则和停用项，绑定规则存储，发布版本为 0 的权限快照。会话与队友共享此对象，
     * 后续档位或规则变化推进 revision。
     *
     * @param profile 当前选择的权限档位
     * @param rules 用户配置的有序权限规则列表
     * @param disabled 停用的内置默认规则 ID 集合
     * @param store 当前操作使用的持久存储入口
     * @throws NullPointerException profile 为 null
     */
    public PermissionState(PermissionProfile profile, List<PermissionRule> rules, Set<String> disabled,
            PermissionRuleStore store) {
        this.profile = Objects.requireNonNull(profile);
        this.rules = new ArrayList<>(rules);
        this.disabled = Set.copyOf(disabled);
        this.store = store;
        published = new Snapshot(profile, List.copyOf(rules), this.disabled, 0);
    }

    /**
     * 创建 Workspace 档位、空用户规则及空停用表的权限状态，供离线装配使用；长期规则存储为空，
     * remember 调用时报告保存入口缺失。
     *
     * @return Workspace 档位、空规则且没有持久规则存储的离线权限状态
     */
    public static PermissionState defaults() {
        return new PermissionState(PermissionProfile.WORKSPACE, List.of(), Set.of(), null);
    }

    /**
     * 返回最近发布的不可变权限快照，供工具线程和界面直接读取档位、规则及版本。
     *
     * @return 最近完整发布的权限档位、规则和版本快照
     */
    public Snapshot snapshot() {
        return published;
    }

    /**
     * 原子更新档位，锁外通知，避免唤醒审批时互相等待。
     *
     * @param value 本次运行要切换到的权限档位
     * @throws NullPointerException value 为 null
     */
    public void select(PermissionProfile value) {
        Objects.requireNonNull(value);
        synchronized (this) {
            if (profile == value)
                return;
            profile = value;
            revision++;
            published = new Snapshot(profile, List.copyOf(rules), disabled, revision);
        }
        changed();
    }

    /**
     * 先持久化，再发布内存规则。IO 失败时不生成一次虚假的长期批准。
     *
     * @param rule 本次检查、匹配或持久保存的一条权限规则
     * @param expectedRevision 调用方看到的旧版本，用于确认本次修改依据仍有效
     * @throws IOException 期望权限版本已变、未配置规则存储或规则持久保存失败
     */
    public void remember(PermissionRule rule, long expectedRevision) throws IOException {
        synchronized (this) {
            if (revision != expectedRevision)
                throw new IOException("Permissions changed; confirm again");
            if (store == null)
                throw new IOException("No long-term permission store is configured for this run");
            store.save(rule);
            rules.add(rule);
            revision++;
            published = new Snapshot(profile, List.copyOf(rules), disabled, revision);
        }
        changed();
    }

    /**
     * 请求结束后移除监听，避免已完成申请永久占用内存。
     *
     * @param listener 权限版本变化后调用的唤醒函数
     * @return 用于移除本次监听的关闭句柄
     */
    public AutoCloseable onChange(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * 逐个执行权限变化监听器，唤醒正在等待审批的操作重新判断；某个监听器异常时继续通知其余监听器。
     */
    private void changed() {
        listeners.forEach(listener -> {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
            }
        });
    }
}
