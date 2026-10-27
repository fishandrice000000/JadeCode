package com.jadecode.support;

import java.nio.file.Path;
import com.jadecode.config.JadePaths;
import com.jadecode.config.WorkspacePaths;

/** 离线测试显式注入临时用户数据根；不修改 user.home，不读取真实 HOME 的运行状态。 */
public final class TestPaths {
    public static WorkspacePaths workspace(Path workspace) {
        return new WorkspacePaths(workspace, jade(workspace));
    }
    public static JadePaths jade(Path workspace) { return new JadePaths(workspace.resolve("fixture-home/.jadecode")); }
    private TestPaths() { }
}
