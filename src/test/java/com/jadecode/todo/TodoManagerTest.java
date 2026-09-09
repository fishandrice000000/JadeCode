package com.jadecode.todo;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.util.Json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TodoManager 校验矩阵:整体替换语义、错误文案(逐字)、容错解析、失败不污染。
 *
 * <p>渲染断言按单空行书写:render 在 marker 行后 add("") 空行元素,
 * 经 String.join("\\n") 后计数行前恰好一个空行(两个换行符),与课程一致。</p>
 */
class TodoManagerTest {

    /** {content, status} 单项 */
    private static ObjectNode item(String content, String status) {
        return Json.mapper().createObjectNode().put("content", content).put("status", status);
    }

    /** {content} 缺省 status(测默认 pending) */
    private static ObjectNode item(String content) {
        return Json.mapper().createObjectNode().put("content", content);
    }

    /** 任意多项 → todos 数组 */
    private static ArrayNode todos(ObjectNode... items) {
        ArrayNode arr = Json.mapper().createArrayNode();
        for (ObjectNode item : items) {
            arr.add(item);
        }
        return arr;
    }

    /** ① 合法列表整批替换:返回渲染文本;再喂新列表,旧内容整体消失 */
    @Test
    void validListReplacesWholly() {
        TodoManager manager = new TodoManager();
        String first = manager.update(
                todos(item("step1", "pending"), item("step2", "in_progress"), item("step3", "completed")));

        assertEquals("[ ] step1\n[>] step2\n[x] step3\n\n(1/3 completed)", first);
        assertEquals(first, manager.render()); // update 返回值即当前状态文本

        String second = manager.update(todos(item("solo", "completed")));
        assertEquals("[x] solo\n\n(1/1 completed)", second);
        assertEquals(second, manager.render()); // step1~3 已被替换掉
    }

    /** ② 空数组合法:清空列表,渲染 "No todos." */
    @Test
    void emptyArrayClearsToNoTodos() {
        TodoManager manager = new TodoManager();
        manager.update(todos(item("a", "pending")));
        assertEquals("No todos.", manager.update(todos()));
        assertEquals("No todos.", manager.render());
    }

    /** ③ content 缺失 / strip 后纯空白:整批拒绝,文案带下标 */
    @Test
    void blankContentRejected() {
        TodoManager manager = new TodoManager();
        // 只给 status、没给 content
        assertEquals("Error: todos[0] requires content",
                manager.update(todos(Json.mapper().createObjectNode().put("status", "pending"))));
        // 全空白 content:第 1 项合法、第 2 项空白 → 报 [1] 且整批不落库
        assertEquals("Error: todos[1] requires content",
                manager.update(todos(item("ok", "pending"), item("   ", "pending"))));
        assertEquals("No todos.", manager.render()); // 两次失败都没污染状态
    }

    /** ④ 21 项(超上限 20):整批拒绝 */
    @Test
    void overTwentyRejected() {
        TodoManager manager = new TodoManager();
        ObjectNode[] many = new ObjectNode[21];
        for (int i = 0; i < many.length; i++) {
            many[i] = item("task" + i, "pending");
        }
        assertEquals("Error: Max 20 todos allowed", manager.update(todos(many)));
    }

    /** ⑤ in_progress 多于一个:整批拒绝,文案带全部冲突下标(用户保留的增强后缀) */
    @Test
    void multipleInProgressRejected() {
        TodoManager manager = new TodoManager();
        assertEquals("Error: Only one todo can be in_progress at a time: in_progress index [0, 2]",
                manager.update(todos(item("a", "in_progress"), item("b", "pending"), item("c", "in_progress"))));
    }

    /** ⑥ status 无法识别:文案带原值(不转小写后的值) */
    @Test
    void invalidStatusKeepsRawValue() {
        TodoManager manager = new TodoManager();
        assertEquals("Error: todos[1] has invalid status 'DONE'",
                manager.update(todos(item("a", "pending"), item("b", "DONE"))));
    }

    /** ⑦ status 大小写不敏感 + 容忍首尾空白;缺省默认 pending */
    @Test
    void statusParsingIsLenient() {
        TodoManager manager = new TodoManager();
        String out = manager.update(todos(item("a"), item("b", "IN_PROGRESS"), item("c", "  completed  ")));
        assertEquals("[ ] a\n[>] b\n[x] c\n\n(1/3 completed)", out);
    }

    /** ⑧ 失败不污染:坏列表被拒后,旧列表原样保留 */
    @Test
    void failedUpdateLeavesOldListIntact() {
        TodoManager manager = new TodoManager();
        manager.update(todos(item("keep", "pending")));
        manager.update(todos(item("bad", "bogus"))); // 非法 status,整批拒绝
        assertEquals("[ ] keep\n\n(0/1 completed)", manager.render());
    }

    /** ⑨ 多余键丢弃:入库只留 content/status,渲染不出现额外字段 */
    @Test
    void extraKeysAreDropped() {
        TodoManager manager = new TodoManager();
        ObjectNode extra = item("real", "completed");
        extra.put("id", 42);
        extra.put("note", "whatever");
        assertEquals("[x] real\n\n(1/1 completed)", manager.update(todos(extra)));
    }
}
