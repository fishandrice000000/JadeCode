package com.jadecode.cron;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 五段式 cron 表达式的解析和匹配。
 *
 * <p>只支持本章需要的写法：星号、星号步进、数字、闭区间和逗号列表，不是完整的 crontab 解析器。
 * 星期用周日为 0；日和星期都做了限制时按课程的规则取或。匹配只回答“这一分钟是否命中”，
 * 不启动任务、不读系统时钟、不修改状态。
 */
final class CronExpression {
    private static final int[] MIN = {0, 0, 1, 1, 0};
    private static final int[] MAX = {59, 23, 31, 12, 6};
    private final List<Set<Integer>> fields;
    private final boolean anyDay;
    private final boolean anyWeekday;

    /** 已校验集合只读持有；两个星号标志用于日/星期的特殊组合规则。 */
    private CronExpression(List<Set<Integer>> fields, boolean anyDay, boolean anyWeekday) {
        this.fields = List.copyOf(fields);
        this.anyDay = anyDay;
        this.anyWeekday = anyWeekday;
    }

    /**
     * 编译并校验表达式，不接受六段秒字段或隐式数字转换。
     * @param expression 模型或持久记录中的原表达式，不修改
     * @return 不可变匹配器
     * @throws IllegalArgumentException 字段数、语法或取值不合法
     */
    static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > 256) {
            throw new IllegalArgumentException("cron must be a non-empty expression of at most 256 characters");
        }
        String[] parts = expression.strip().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("cron requires exactly 5 fields");
        }
        List<Set<Integer>> fields = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            Set<Integer> values = new HashSet<>();
            // 列表先拆开，每项仍按相同字段范围校验；split 的 -1 保留尾部空项，拒绝 1,。
            for (String part : parts[i].split(",", -1)) {
                addValues(part, MIN[i], MAX[i], values);
            }
            fields.add(Set.copyOf(values));
        }
        return new CronExpression(fields, parts[2].equals("*"), parts[4].equals("*"));
    }

    /**
     * 展开一个字段分项；步进沿课程按 value % step == 0，而不是对区间起点求偏移。
     * 不支持范围步进、英文月份/星期、L/W/# 等扩展，未知语法直接拒绝。
     */
    private static void addValues(String part, int min, int max, Set<Integer> values) {
        if (part.equals("*") || part.matches("\\*/[0-9]+")) {
            int step = part.equals("*") ? 1 : number(part.substring(2));
            if (step <= 0) {
                throw new IllegalArgumentException("cron step must be positive");
            }
            for (int value = min; value <= max; value++) {
                if (value % step == 0) values.add(value);
            }
            return;
        }
        String[] range = part.split("-", -1);
        if (range.length > 2) throw new IllegalArgumentException("Invalid cron field: " + part);
        int first = number(range[0]);
        int last = range.length == 1 ? first : number(range[1]);
        if (first < min || last > max || first > last) {
            throw new IllegalArgumentException("cron field outside [" + min + "-" + max + "]: " + part);
        }
        for (int value = first; value <= last; value++) values.add(value);
    }

    /** 数字必须为十进制非负整数字面量，溢出同样按无效输入拒绝。 */
    private static int number(String text) {
        if (!text.matches("[0-9]+")) throw new IllegalArgumentException("Invalid cron number: " + text);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("cron number is too large", invalid);
        }
    }

    /**
     * 判断给定本地时刻是否匹配，不关心秒数和任务此前是否触发；去重由调度器负责。
     * @param moment 带明确时区的时刻，生产使用进程默认时区
     * @return 分/时/月匹配，且日/星期满足课程组合规则
     */
    boolean matches(ZonedDateTime moment) {
        if (!fields.get(0).contains(moment.getMinute()) || !fields.get(1).contains(moment.getHour())
                || !fields.get(3).contains(moment.getMonthValue())) return false;
        boolean day = fields.get(2).contains(moment.getDayOfMonth());
        boolean weekday = fields.get(4).contains(moment.getDayOfWeek().getValue() % 7);
        if (anyDay) return weekday;
        if (anyWeekday) return day;
        return day || weekday;
    }
}
