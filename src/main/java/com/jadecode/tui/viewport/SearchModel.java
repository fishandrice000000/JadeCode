package com.jadecode.tui.viewport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.jadecode.tui.text.DisplayWidth;
import com.jadecode.tui.text.StyledLine;

/**
 * 转录全文搜索：保存查询、匹配位置、当前匹配和文档 revision。
 *
 * <p>搜索覆盖整篇已排版转录（包括视口外），不是只搜可见页；匹配按纯文字进行计算，折叠的
 * thinking 是否出现取决于其当前是否已排版成行，不凭空展开或搜索未显示的隐藏内容。结果坐标
 * 在内容/宽度/折叠变化后失效，由 ChatViewport 重建，不能拿旧 frame 坐标高亮。</p>
 */
public final class SearchModel {
    /** 一个匹配：文档行 + 列区间 + 该行内 UTF-16 区间。 */
    public record Match(int row, int fromColumn, int toColumn, int fromOffset, int toOffset) { }

    private String query = "";
    private List<Match> matches = List.of();
    private int current = -1;
    private long documentRevision;

    /** @return 当前查询。 */
    public String query() { return query; }

    /** @return 匹配数量。 */
    public int size() { return matches.size(); }

    /** @return 当前匹配下标；-1 表示无。 */
    public int currentIndex() { return current; }

    /** @return 当前匹配；无则 null。 */
    public Match current() { return current >= 0 && current < matches.size() ? matches.get(current) : null; }

    /** @return 全部匹配的只读快照。 */
    public List<Match> matches() { return matches; }

    /**
     * 更新查询并重建匹配。空查询清空结果；大小写不敏感（不改变原文）。
     * @param document 完整转录行
     * @param revision 当前文档 revision；变化时宿主应重新调用
     */
    public void search(String query, List<StyledLine> document, long revision) {
        this.query = query == null ? "" : query;
        this.documentRevision = revision;
        var found = new ArrayList<Match>();
        if (!this.query.isEmpty()) {
            String needle = this.query.toLowerCase(Locale.ROOT);
            for (int row = 0; row < document.size(); row++) {
                String plain = document.get(row).text();
                String lower = plain.toLowerCase(Locale.ROOT);
                int from = 0;
                while (true) {
                    int index = lower.indexOf(needle, from);
                    if (index < 0) break;
                    int end = index + needle.length();
                    found.add(new Match(row, DisplayWidth.columnAtOffset(plain, index),
                            DisplayWidth.columnAtOffset(plain, end), index, end));
                    from = Math.max(index + 1, end);
                }
            }
        }
        matches = List.copyOf(found);
        current = matches.isEmpty() ? -1 : 0;
    }

    /** 前进到下一个匹配（循环）。 */
    public Match next() {
        if (matches.isEmpty()) return null;
        current = (current + 1) % matches.size();
        return matches.get(current);
    }

    /** 后退到上一个匹配（循环）。 */
    public Match previous() {
        if (matches.isEmpty()) return null;
        current = (current - 1 + matches.size()) % matches.size();
        return matches.get(current);
    }

    /** @return 该行需要高亮的列区间；无匹配返回空。 */
    public List<int[]> highlightsFor(int row) {
        var result = new ArrayList<int[]>();
        for (Match match : matches) if (match.row() == row) result.add(new int[]{match.fromColumn(), match.toColumn()});
        return result;
    }

    /** @return 当前匹配是否位于该行；用于更醒目的当前项样式。 */
    public boolean isCurrent(int row, int column) {
        Match match = current();
        return match != null && match.row() == row && column >= match.fromColumn() && column < match.toColumn();
    }

    /** 清空搜索。 */
    public void clear() { query = ""; matches = List.of(); current = -1; }

    /** @return 结果对应的文档 revision。 */
    public long documentRevision() { return documentRevision; }
}
