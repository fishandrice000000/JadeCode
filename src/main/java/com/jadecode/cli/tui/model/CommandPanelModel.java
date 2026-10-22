package com.jadecode.cli.tui.model;

import java.util.List;

/**
 * 命令信息弹窗的不可变只读快照：不持有运行时、writer、StyledLine、ANSI 或 Markdown AST。
 *
 * <p>由 HotkeysView/SessionInfoView/McpStatusView 从当前真实只读数据构造，由
 * CommandPanelView 消费。generation/sourceRevision 用于让旧异步结果失效；外部名称始终
 * 只是数据，渲染时统一净化，不作为 Markdown/终端控制执行。</p>
 *
 * @param kind 页面类型
 * @param title 标题
 * @param generation 所属会话代次
 * @param sourceRevision 数据快照 revision
 * @param groups 分组条目（快捷键上下文、usage 分组等）
 * @param fields 键值字段（Model/Session 概览）
 * @param services MCP 服务与工具
 * @param emptyText 无数据时的真实英文提示
 */
public record CommandPanelModel(Kind kind, String title, long generation, long sourceRevision,
        List<Group> groups, List<Field> fields, List<Service> services, String emptyText) {
    /** 页面类型。 */
    public enum Kind { HOTKEYS, MODEL, SESSION, MCP }

    /** 一个分组；entries 按原顺序显示。 */
    public record Group(String id, String title, List<Entry> entries) {
        public Group {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** 条目：key 是键/命令/名称，label 是说明或数值。 */
    public record Entry(String key, String label, String description) {
        public Entry {
            key = key == null ? "" : key;
            label = label == null ? "" : label;
            description = description == null ? "" : description;
        }
    }

    /** 字段：label/value 都是数据，可为空字符串。 */
    public record Field(String label, String value) {
        public Field {
            label = label == null ? "" : label;
            value = value == null ? "" : value;
        }
    }

    /** MCP 服务；工具名只读，不触发连接/发现。 */
    public record Service(String name, boolean enabled, boolean connected, List<String> tools) {
        public Service {
            name = name == null ? "" : name;
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public CommandPanelModel {
        title = title == null ? "" : title;
        groups = groups == null ? List.of() : List.copyOf(groups);
        fields = fields == null ? List.of() : List.copyOf(fields);
        services = services == null ? List.of() : List.copyOf(services);
        emptyText = emptyText == null ? "" : emptyText;
    }
}
