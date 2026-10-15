package com.jadecode.tasks;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一项可跨会话恢复的工作，而不是模型消息、子代理或当前轮的 Todo 清单项。
 *
 * <p>模型提出任务内容与依赖，Harness 保存这个值并检查状态转换。记录为 completed 只表示
 * 完成操作被接受，不证明代码通过验收。record 与不可变依赖列表使调用方不能绕过存储层原地改状态。</p>
 *
 * @param id Harness 分配的稳定任务 ID，不是文件路径
 * @param subject 非空简短标题
 * @param description 完整工作说明，允许空字符串但不允许 null
 * @param status pending / in_progress / completed
 * @param owner pending 时为 null，其余状态为非空负责者标签，不是认证凭据
 * @param blockedBy 前置任务 ID 的不可变列表；全部完成才允许认领
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
