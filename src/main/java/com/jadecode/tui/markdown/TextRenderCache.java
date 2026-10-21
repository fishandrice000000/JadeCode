package com.jadecode.tui.markdown;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.jadecode.tui.text.StyledLine;

/**
 * 有界排版缓存：按源文/宽度/主题 revision/基础样式复用已排好的行。
 *
 * <p>主题切换若只改颜色，条目仍可复用（颜色在 span 中已解析，故按 revision 失效）；
 * 解析与布局分开缓存是后续 JS 引擎优化点。缓存只保存可重建的显示结果，不保存业务正文，
 * 也不无限保留每个流式前缀；超过容量淘汰最旧项。</p>
 */
public final class TextRenderCache {
    /** 缓存键；text 使用完整源文比较，hash 只用于快速路径。 */
    public record Key(String text, int width, long themeRevision, String styleKey) { }

    private static final int CAPACITY = 256;
    private final Map<Key, List<StyledLine>> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, List<StyledLine>> eldest) {
            return size() > CAPACITY;
        }
    };
    private long hits, misses;

    /** @return 命中结果；未命中 null。 */
    public List<StyledLine> get(Key key) {
        List<StyledLine> value = cache.get(key);
        if (value == null) misses++; else hits++;
        return value;
    }

    /** 保存排版结果；null 忽略。 */
    public void put(Key key, List<StyledLine> lines) {
        if (key == null || lines == null) return;
        cache.put(key, List.copyOf(lines));
    }

    /** 清空（会话切换/主题结构变化）。 */
    public void clear() { cache.clear(); }

    /** @return 命中数，压力测试/诊断。 */
    public long hits() { return hits; }

    /** @return 未命中数。 */
    public long misses() { return misses; }

    /** @return 当前条目数。 */
    public int size() { return cache.size(); }
}
