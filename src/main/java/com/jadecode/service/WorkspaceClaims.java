package com.jadecode.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 同一服务内的可写目录占用表。顶层会话持有 Owner，Team/子代理共用它；
 * 模型不能选择 Owner，也不能通过路径字符串借用其他会话的占用。
 * 这是应用内并发协调，不是对编辑器、其他用户或宿主进程的文件锁。
 */
public final class WorkspaceClaims {
    private final Map<Lease, Path> leases = new IdentityHashMap<>();

    /** 服务创建可信身份；创建本身不占目录，初始工作区和额外挂载走同一领取逻辑。 */
    public Owner owner(String id) { return new Owner(Objects.requireNonNull(id)); }

    public final class Owner {
        private final String id;
        private Owner(String id) { this.id = id; }
        /** 批量校验后一次登记，冲突不留下部分占用；所有输入必须已存在。 */
        public Lease acquire(List<Path> paths) throws IOException {
            List<Path> real = new ArrayList<>();
            for (Path path : paths) real.add(path.toRealPath());
            synchronized (WorkspaceClaims.this) {
                for (Path candidate : real) for (var held : leases.entrySet()) {
                    if (held.getKey().owner != this && (candidate.startsWith(held.getValue()) || held.getValue().startsWith(candidate)))
                        throw new IOException("Writable path is held by session " + held.getKey().owner.id + ": " + candidate);
                }
                Lease lease = new Lease(this);
                // 一份 Lease 可以持有多个目录；用子 Lease 让登记项各自保留精确路径。
                for (Path path : real) {
                    Lease part = new Lease(this); lease.parts.add(part); leases.put(part, path);
                }
                return lease;
            }
        }
    }
    /** 容器确认删除或会话确认停止之后才 close；重复释放安全。 */
    public final class Lease implements AutoCloseable {
        private final Owner owner;
        private final List<Lease> parts = new ArrayList<>();
        private Lease(Owner owner) { this.owner = owner; }
        @Override public void close() {
            synchronized (WorkspaceClaims.this) {
                for (Lease part : parts) leases.remove(part);
                parts.clear();
            }
        }
    }
}
