package com.jadecode.cli;

import java.util.concurrent.Callable;
import com.jadecode.cli.tui.TuiApplication;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 启动唯一的新 TUI。终端、编辑器、审批及显示循环由 TuiApplication 管理。
 * 不保留旧 REPL 或文本降级开关；非终端用户应使用 run。Alt+Enter 在 C08 下不绑定。
 */
@Command(name = "repl", description = "Pi-style terminal UI: Enter submits, Shift+Enter adds a newline, Ctrl+D exits")
public class ReplCommand implements Callable<Integer> {
    /** 缺选项为 null，新建会话；只写 --resume 得空串，打开选择器。 */
    @Option(names = "--resume", arity = "0..1", fallbackValue = "", description = "Resume a workspace session by UUID or name; omit the argument to choose")
    private String resume;
    @Option(names = "--permission", description = "Permission for this run: read-only / workspace / auto-review / full-access")
    private String permission;
    /**
     * 交给新界面运行；0 表示正常退出，不表示所有任务都业务成功。
     */
    @Override
    public Integer call() {
        if (resume != null && !resume.isEmpty()) {
            try { com.jadecode.session.history.SessionMetadata.validTitle(resume); }
            catch (IllegalArgumentException invalid) { System.err.println("Invalid session lookup: " + invalid.getMessage()); return 2; }
        }
        try { return new TuiApplication().run(resume, permission == null ? null : com.jadecode.safety.PermissionProfile.parse(permission)); }
        catch (IllegalArgumentException invalid) { System.err.println(invalid.getMessage()); return 2; }
    }
}
