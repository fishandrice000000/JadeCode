package com.jadecode.tui.core;

import java.util.List;
import com.jadecode.tui.input.InputEvent;
import com.jadecode.tui.text.StyledLine;

/**
 * 通用显示/输入组件契约：根据只读上下文产生行，不写终端、不请求模型、不执行工具。
 *
 * <p>组件只持有自己的 UI 状态（编辑器文本、选中项等），所有状态修改都发生在 UI 线程。
 * 行、光标和命中区域由上层在同一次布局中组合成 {@link Frame}；组件不得自行记录屏幕坐标或
 * 保留终端 writer。默认不处理输入，需要交互的组件覆盖 {@link #handle(InputEvent)}，由
 * {@link FocusManager} 按焦点路由。</p>
 */
public interface Component {
    /**
     * 按给定列宽排版。
     * @param context 同帧只读上下文（尺寸、主题、能力、时钟）
     * @param width 可用列数；必须使用完整宽度，不再保留“最后一列”
     * @return 完整显示行；不含换行控制符
     */
    List<StyledLine> render(RenderContext context, int width);

    /** @return 理想高度；默认按一次排版结果计算。父布局可缩小/裁剪。 */
    default int height(RenderContext context, int width) { return render(context, width).size(); }

    /**
     * 处理已路由到本组件的输入。
     * @return true 表示消费了事件；false 允许父层继续处理
     */
    default boolean handle(InputEvent event) { return false; }

    /** @return 当前焦点元素（若有）；用于硬件光标/IME 定位。 */
    default FocusManager.FocusToken focusToken() { return null; }
}
