package com.jadecode.cli.tui;

import java.util.ArrayList;
import java.util.List;
import com.jadecode.safety.PermissionProfile;
import com.jadecode.tui.components.SelectList;

/**
 * 四档权限与 Full Access 显式确认的 Pi 式选择器；规则判断/保存仍由安全和配置模块负责。
 *
 * <p>无默认 Full Access：确认菜单默认停在 Cancel，一次连按 Enter 不会扩大授权；Esc 取消
 * 不改权限。每档附真实行为说明；选择后仍由 PermissionState/Gate 检查，不把 UI 选择当作
 * 单次工具批准。候选 ID 用稳定 value，标签英文。</p>
 */
final class PermissionMenu {
    private PermissionMenu() { }

    static List<String> labels() {
        var result = new ArrayList<String>();
        for (PermissionProfile profile : PermissionProfile.values()) result.add(profile.value());
        return List.copyOf(result);
    }

    /** 当前档位先选中；用户仍需 Enter 确认。ID 为 value。 */
    static SelectList choices(PermissionProfile current) {
        var items = new ArrayList<SelectList.Item>();
        for (PermissionProfile profile : PermissionProfile.values())
            items.add(new SelectList.Item(profile.value(), profile.label(), description(profile.value())));
        var list = new SelectList(items, true);
        list.selectId(current.value());
        return list;
    }

    /** Full Access 默认选 Cancel，避免一次连按 Enter 就扩大授权。 */
    static SelectList confirm() {
        var list = new SelectList(List.of(
                new SelectList.Item("cancel", "Cancel", "Keep the current permission level"),
                new SelectList.Item("confirm", "Enable Full Access", "Routine actions skip prompts")), true);
        return list;
    }

    /** @return 该档位的真实行为说明；未知档位空。 */
    static String description(String selected) {
        if (selected == null) return "";
        PermissionProfile profile;
        try { profile = PermissionProfile.parse(selected); }
        catch (IllegalArgumentException invalid) { return ""; }
        return switch (profile) {
            case READ_ONLY -> "Reads only; writes, task changes and general commands ask first.";
            case WORKSPACE -> "Workspace reads/writes and builds run directly; deletes and outside paths ask.";
            case AUTO_REVIEW -> "Routine prompts go to the review model; uncertain or sensitive actions still ask.";
            case FULL_ACCESS -> "Routine actions skip prompts; explicit denies and hard protections remain.";
        };
    }
}
