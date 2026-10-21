package com.jadecode.tui.render;

import java.io.IOException;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.core.RenderContext;

/**
 * regular/fullscreen 共用的呈现端口：只规定生命周期与“flush 成功才发布”的语义。
 *
 * <p>实现不得持有消息/编辑器状态；帧由上层组装。每一时刻只允许一个活动 renderer 写入
 * 唯一终端 writer。{@link #present} 返回的 PresentedFrame 是动作证据；写失败不得发布。
 * 切换模式先 {@link #close()} 旧 renderer（清理协议/图像），再启动新 renderer。</p>
 */
public interface ScreenRenderer extends AutoCloseable {
    /**
     * renderer 依赖的最小终端端口；生产实现包装 TerminalSession，测试可注入内存 writer。
     * 只暴露唯一 writer、尺寸和 epoch，不暴露 JLine Terminal/Attribute 写权限。
     */
    interface Port {
        int columns();
        int rows();
        long epoch();
        java.io.PrintWriter writer();
        /** @return 最近写出是否失败；每次 flush 后 renderer 必须检查。 */
        boolean outputFailed();
        /** @return 是否允许颜色/属性。 */
        boolean colorsEnabled();
    }

    /** 显示模式。 */
    enum Mode { REGULAR, FULLSCREEN }

    /** @return 当前模式。 */
    Mode mode();

    /** 进入模式所需终端序列（备用屏/鼠标捕获/自动换行等）。 */
    void start() throws IOException;

    /**
     * 呈现一帧。
     * @return flush 成功后的证据；写失败抛出 IOException，不返回旧动作
     */
    PresentedFrame present(Frame frame, RenderContext context) throws IOException;

    /** 使旧动作/坐标立即失效（resize、模式切换、焦点变化）。 */
    void invalidate();

    /** 最近成功呈现的帧；从未呈现返回 null。 */
    PresentedFrame presented();

    /** 正常结束时的收尾（光标/模式/备用屏退出）；不关闭业务 Session。 */
    void finish() throws IOException;

    /** 幂等关闭并清理所有终端模式/图片 ID。 */
    @Override void close() throws IOException;
}
