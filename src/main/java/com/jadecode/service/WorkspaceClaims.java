package com.jadecode.service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 协调同一常驻服务中不同顶层会话的可写目录。会话创建 Owner，队友与子代理沿用该身份；
 * 领取时解析真实路径并检查父子范围重叠，整批成功后登记 Lease，容器删除或会话停止后释放。
 */
public final class WorkspaceClaims {
    private final Map<Lease, Path> leases = new IdentityHashMap<>();

    /**
     * 服务创建可信身份；创建本身不占目录，初始工作区和额外挂载走同一领取逻辑。
     *
     * @param id 服务为顶层运行组分配的身份，用于可写范围归属
     * @return 归属于指定顶层运行组的范围领取入口
     * @throws NullPointerException id 为 null
     */
    public Owner owner(String id) {
        return new Owner(Objects.requireNonNull(id));
    }

    /**
     * 表示一个顶层会话的可写范围所有者，负责为此会话及其队友领取目录占用，并与其他会话的范围做重叠检查。
     */
    public final class Owner {
        private final String id;

        /**
         * 保存服务分配的会话身份，供范围冲突诊断定位当前占用者。
         *
         * @param id 服务为顶层运行组分配的身份，用于可写范围归属
         */
        private Owner(String id) {
            this.id = id;
        }

        /**
         * 批量校验后一次登记，冲突不留下部分占用；所有输入必须已存在。
         *
         * @param paths 本次计划领取或核对的宿主路径列表
         * @return 整批路径成功登记后返回的占用句柄，关闭时归还全部子占用
         * @throws IOException 路径真实解析失败或与其他所有者的写入范围重叠
         */
        public Lease acquire(List<Path> paths) throws IOException {
            List<Path> real = new ArrayList<>();
            for (Path path : paths)
                real.add(path.toRealPath());
            synchronized (WorkspaceClaims.this) {
                for (Path candidate : real)
                    for (var held : leases.entrySet()) {
                        if (held.getKey().owner != this
                                && (candidate.startsWith(held.getValue()) || held.getValue().startsWith(candidate)))
                            throw new IOException(
                                    "Writable path is held by session " + held.getKey().owner.id + ": " + candidate);
                    }
                Lease lease = new Lease(this);
                // 一份 Lease 可以持有多个目录；用子 Lease 让登记项各自保留精确路径。
                for (Path path : real) {
                    Lease part = new Lease(this);
                    lease.parts.add(part);
                    leases.put(part, path);
                }
                return lease;
            }
        }
    }

    /**
     * 容器确认删除或会话确认停止之后才 close；重复释放安全。
     */
    public final class Lease implements AutoCloseable {
        private final Owner owner;
        private final List<Lease> parts = new ArrayList<>();

        /**
         * 建立归属于指定 Owner 的占用句柄，后续领取过程把各路径子占用挂到 parts。
         *
         * @param owner 本句柄归属的顶层会话可写范围所有者
         */
        private Lease(Owner owner) {
            this.owner = owner;
        }

        /**
         * 在全局占用表锁内移除本句柄的全部路径子占用并清空 parts，供容器删除确认或会话停止后释放范围。
         * 重复关闭时子项已为空。
         */
        @Override
        public void close() {
            synchronized (WorkspaceClaims.this) {
                for (Lease part : parts)
                    leases.remove(part);
                parts.clear();
            }
        }
    }
}
