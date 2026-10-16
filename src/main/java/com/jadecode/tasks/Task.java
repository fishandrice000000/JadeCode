package com.jadecode.tasks;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一项可以跨会话恢复的工作，不是消息、不是子代理，也不是当前轮的 Todo 清单项。
 *
 * <p>内容与依赖由模型提出，存储层保存并检查状态转换。标记为 completed 只表示“完成操作被接受”，
 * 不代表代码真的通过验收。record 和不可变的依赖列表让调用方不能绕过存储层直接改状态。
 *
 * @param id 存储层分配的稳定任务 ID，不是文件路径
 * @param subject 非空的简短标题
 * @param description 完整工作说明，可以为空字符串但不能为 null
 * @param status pending / in_progress / completed
 * @param owner pending 时为 null，其余状态必须是非空负责者标签，不是登录凭证
 * @param blockedBy 前置任务 ID 列表（不可修改），全部完成才允许认领
 */
public record Task(String id, String subject, String description, Status status,
        @JsonInclude(JsonInclude.Include.ALWAYS) String owner, List<String> blockedBy) {
    /** ID 是公开的文件 / 工具协议，不允许任意文件名或目录穿越。 */
    public static final String ID_PATTERN = "^task_[0-9a-f]{8}$";

    /**
     * 在创建和 JSON 读回时共用结构检查；图的跨记录约束由 TaskStore 检查。
     * 不修改传入列表，任何字段不合法都抛 IllegalArgumentException，不生成半合法任务。
     */
    public Task {
        if (id == null || !id.matches(ID_PATTERN) || subject == null || subject.isBlank()
                || description == null || status == null || blockedBy == null) {
            throw new IllegalArgumentException("任务 ID、标题、描述、状态与依赖字段必须合法");
        }
        // owner 与状态共同描述责任归属；不能出现“尚未认领却有负责者”或“执行中无人负责”。
        // owner 上的 ALWAYS 保留显式 null，覆盖共享 Json 默认省略 null 的策略，使六字段可严格往返。
        if (status == Status.PENDING ? owner != null : owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("任务 owner 与状态不一致");
        }
        var unique = new HashSet<String>();
        for (String dependency : blockedBy) {
            if (dependency == null || !dependency.matches(ID_PATTERN) || dependency.equals(id) || !unique.add(dependency)) {
                throw new IllegalArgumentException("依赖 ID 非法、自依赖或依赖重复");
            }
        }
        subject = subject.strip();
        // List.copyOf 切断对调用方可变列表的依赖；之后只能构造新 Task 再交给存储层保存。
        blockedBy = List.copyOf(blockedBy);
    }

    /** Java 用枚举防止拼错状态；JsonProperty 保持课程要求的小写 wire 格式。 */
    public enum Status {
        @JsonProperty("pending") PENDING,
        @JsonProperty("in_progress") IN_PROGRESS,
        @JsonProperty("completed") COMPLETED
    }
}
