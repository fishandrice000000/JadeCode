package com.jadecode.cli;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 父进程必须保留界面独立收到的信号身份；故障断连不能把它改成普通错误。 */
class InteractiveExitTest {
    @Test void preservesSignalIdentityAndRejectsOtherFrontendFailures() throws Exception {
        for (int code : new int[] { 129, 130, 143 }) {
            Process child = new ProcessBuilder("/bin/sh", "-c", "exit " + code).start();
            assertEquals(code, InteractiveLauncher.finish(1, child));
        }
        assertEquals(1, InteractiveLauncher.finish(0, new ProcessBuilder("/bin/sh", "-c", "exit 7").start()));
        assertEquals(1, InteractiveLauncher.finish(1, new ProcessBuilder("/bin/sh", "-c", "exit 0").start()));
        assertEquals(0, InteractiveLauncher.finish(0, new ProcessBuilder("/bin/sh", "-c", "exit 0").start()));
    }
}
