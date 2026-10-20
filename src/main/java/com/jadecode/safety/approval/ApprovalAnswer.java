package com.jadecode.safety.approval;

/** 用户界面只有前三项；RECHECK 仅供权限切换唤醒旧请求，不是第四个按钮。 */
public enum ApprovalAnswer { ALLOW_ONCE, ALLOW_SIMILAR, DENY, RECHECK }
