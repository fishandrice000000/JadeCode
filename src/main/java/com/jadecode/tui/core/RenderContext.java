package com.jadecode.tui.core;

import com.jadecode.tui.theme.Theme;

/** 本帧使用的可用尺寸和主题快照。width 已保留最后一列，避免终端自动换行。 */
public record RenderContext(int width, int height, Theme theme) { }
