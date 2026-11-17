package com.jadecode.safety;

/**
 * 定义 Read Only、Workspace、Approve for Me 和 Full Access 四种权限档位，各档位提供配置值、界面名与对应审批策略。
 */
public enum PermissionProfile {
    READ_ONLY("read-only", "Read Only"), WORKSPACE("workspace", "Workspace"), AUTO_REVIEW("auto-review",
            "Approve for Me"), FULL_ACCESS("full-access", "Full Access");

    private final String value, label;

    /**
     * 保存权限档位的配置值和界面名称，使配置解析与用户选择使用相同枚举。
     *
     * @param value 该权限档位用于配置和协议传输的稳定标识
     * @param label 供终端显示的人类可读名称
     */
    PermissionProfile(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * 返回权限档位写入配置与协议的稳定标识。
     *
     * @return 档位在配置与协议中的稳定英文标识
     */
    public String value() {
        return value;
    }

    /**
     * 返回权限档位的界面名称。
     *
     * @return 该档位的界面名称
     */
    public String label() {
        return label;
    }

    /**
     * 只接受明确名称，拼错不能默默退回更宽权限。
     *
     * @param text 本次处理的完整文本，按该入口执行编码、脱敏、显示或组装
     * @return 与输入稳定名称对应的权限档位
     * @throws IllegalArgumentException 输入未匹配任何支持的档位稳定名称
     */
    public static PermissionProfile parse(String text) {
        for (var p : values())
            if (p.value.equals(text) || p.name().equals(text) || p.label.equals(text))
                return p;
        throw new IllegalArgumentException(
                "Permission profile must be read-only / workspace / auto-review / full-access");
    }

    /**
     * 审批策略与审查者分别保存；只有 Full Access 不发起审批。
     *
     * @return 该档位组合出的读取限制、审批策略和审查者
     */
    public PermissionPolicy policy() {
        return new PermissionPolicy(this == READ_ONLY,
                this == FULL_ACCESS ? PermissionPolicy.ApprovalPolicy.NEVER
                        : PermissionPolicy.ApprovalPolicy.ON_REQUEST,
                this == AUTO_REVIEW ? PermissionPolicy.Reviewer.MODEL : PermissionPolicy.Reviewer.USER);
    }
}
