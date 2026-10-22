package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.jadecode.tui.terminal.ClipboardService.CopyResult;

/** R3-05/S32：正常结果只短暂 Copied!，真实失败保留 5 秒错误；内部枚举不被 UI 混淆。 */
class TuiCopyFeedbackTest {
    @Test void desktopAndOsc52SentMapToSameShortSuccessButRemainDistinctEnums() {
        var desktop = TuiApplication.copyFeedback(CopyResult.DESKTOP);
        var osc52 = TuiApplication.copyFeedback(CopyResult.OSC52_SENT);
        for (var flash : java.util.List.of(desktop, osc52)) {
            assertEquals("Copied!", flash.text());
            assertEquals(1000, flash.durationMillis());
            assertFalse(flash.error(), "normal copy must not be styled as an error");
        }
        assertNotEquals(CopyResult.DESKTOP, CopyResult.OSC52_SENT,
                "OSC52_SENT stays a send-only state internally, not a fake ACK");
    }

    @Test void failedCopyShowsRealErrorForFiveSeconds() {
        var failed = TuiApplication.copyFeedback(CopyResult.FAILED);
        assertTrue(failed.error());
        assertEquals(5000, failed.durationMillis());
        assertTrue(failed.text().startsWith(UiMessages.COPY_FAILED), failed.text());
        assertTrue(failed.text().contains("OSC52"), "failure must not claim success");
        assertNotEquals("Copied!", failed.text());
    }
}
