package com.jadecode.cli.tui;

import java.util.Arrays;
import java.util.List;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.tui.components.SelectList;

/** 只组织权限选择文字；规则判断和存储不放进界面组件。 */
final class PermissionMenu {
    private PermissionMenu() { }
    static List<String> labels() { return Arrays.stream(PermissionProfile.values()).map(PermissionProfile::label).toList(); }
    /** 当前档位先选中；用户仍需 Enter 确认，不因打开列表就切换。 */
    static SelectList choices(PermissionProfile current) {
        var list = new SelectList(labels()); list.move(current.ordinal()); return list;
    }
    /** Full Access 默认选返回，避免一次连按 Enter 就扩大授权。 */
    static SelectList confirm() { return new SelectList(List.of("返回", "启用 Full Access")); }
    /** 说明预设的实际行为，不让四个名字代替产品契约。 */
    static String description(String selected) {
        if (selected == null) return "";
        return switch (PermissionProfile.parse(selected)) {
            case READ_ONLY -> "允许已识别读取；写文件、任务管理和一般程序需用户确认";
            case WORKSPACE -> "普通工作区读写与构建直接执行；删除、越界和复杂脚本先询问";
            case AUTO_REVIEW -> "普通询问交辅助模型审查；不确定时问用户，敏感操作仍由用户决定";
            case FULL_ACCESS -> "普通操作免询问；显式拒绝与默认硬保护保留，强制询问项不执行";
        };
    }
}
