package com.jadecode.tui.text;

/**
 * 只影响显示、不属于正文的附加标记：图片占位、编辑器假光标或源位置映射。
 *
 * <p>这些标记不能混进复制文本、搜索文本或审批参数。渲染器通过 {@link StyledLine#annotation()}
 * 读取它，在合成行时决定是否发送图片协议、绘制假光标或记录源映射。它不持有 ImageService
 * 等重对象，只用字符串 ID；这样 Frame 仍然可以安全跨方法传递而不泄漏终端 writer。</p>
 *
 * @param kind 标记种类
 * @param id 图片 ID、源条目 ID 等由宿主生成的不可执行标识
 * @param columns 占位列数，非图片标记为 0
 * @param rows 占位行数，非图片标记为 0
 */
public record LineAnnotation(Kind kind, String id, int columns, int rows) {
    /** 标记种类。IMAGE 是唯一会导致终端图片协议输出的类型。 */
    public enum Kind { IMAGE, CURSOR, SOURCE_MAP }

    public LineAnnotation {
        if (kind == null || id == null) throw new IllegalArgumentException("Annotation kind and id cannot be empty");
        if (columns < 0 || rows < 0) throw new IllegalArgumentException("Placeholder size cannot be negative");
    }

    /** 一张需要占 rows 行、columns 列的终端图片。 */
    public static LineAnnotation image(String id, int columns, int rows) {
        return new LineAnnotation(Kind.IMAGE, id, columns, rows);
    }

    /** 一行隐藏的源映射标记；不产生可见字符。 */
    public static LineAnnotation source(String id) { return new LineAnnotation(Kind.SOURCE_MAP, id, 0, 0); }

    /** @return 是否需要在终端绘制图片。 */
    public boolean isImage() { return kind == Kind.IMAGE; }
}
