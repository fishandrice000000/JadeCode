package com.jadecode.cli;

import java.util.concurrent.Callable;
import com.jadecode.cli.tui.TuiApplication;

import picocli.CommandLine.Command;

/**
 * 启动唯一的新 TUI。终端、编辑器、审批及显示循环由 TuiApplication 管理。
 * 不保留旧 REPL 或文本降级开关；非终端用户应使用 run。
 */
@Command(name = "repl", description = "Pi 风格内联界面：Enter 提交，Alt+Enter 换行，Ctrl+D 退出")
public class ReplCommand implements Callable<Integer> {
    /**
     * 交给新界面运行；0 表示正常退出，不表示所有任务都业务成功。
     */
    @Override
    public Integer call() {
        return new TuiApplication().run();
    }
}
