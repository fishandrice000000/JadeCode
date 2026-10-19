package com.jadecode.safety;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jadecode.execution.Approval;
import com.jadecode.execution.ExecutionContext;
import com.jadecode.util.Ansi;

/**
 * 在执行工具前检查命令或路径：禁止的直接拒绝，需要用户同意的先询问。
 *
 * <p>先查绝对禁止的规则，命中就返回原因，不问用户。再查需要询问的规则，
 * 没有命中就允许执行；命中了才调用 Approval.ask。用户拒绝、不能询问或用户已经退出，
 * 都不允许这次需询问的操作。定时任务并非所有工具都不能用，只是不能向用户申请批准。</p>
 *
 * <p>是否用户任务看程序传来的 ExecutionContext，不看执行线程或模型自称的身份。
 * 这里不读 stdin；审批适配器统一接收用户答复。本类也不执行工具，只返回允许或拒绝原因，
 * 由执行器把原因放进 tool_result。</p>
 */
public class PermissionGate {
    /** 检查 bash 命令是否禁止或需要先问用户。 */
    private final CommandPolicy policy;
    /** 检查文件路径是否危险或在工作区之外。 */
    private final PathGuard guard;
    /** 两参数 check 用的默认批准函数；CLI 则从当前任务取得真正询问用户的函数。 */
    private final Approval approval;
    /** 绝对禁止的规则，命中就拒绝，不询问用户。 */
    private final List<Rule> hardRules;
    /** 需要先问用户的规则，命中后才调用批准函数。 */
    private final List<Rule> askRules;

    /**
     * 创建权限检查对象。旧的两参数 check 没有用户回答时默认拒绝需询问的操作；
     * CLI 使用带 ExecutionContext 的 check，从当前任务取得询问用户的方法。
     * @param policy 判断命令是否禁止或需要询问
     * @param guard 判断文件路径是否危险或在工作区之外
     */
    public PermissionGate(CommandPolicy policy, PathGuard guard) {
        this(policy, guard, (name, input, reason) -> false);
    }

    /**
     * 给旧调用者或离线检查指定一个批准函数，不在构造时读取输入或执行检查。
     *
     * <p>只有两参数 check 使用这个默认函数。带 ExecutionContext 的 check 始终看任务本身，
     * 不能因为这里传了“总是同意”的函数，就给定时任务增加人工批准。</p>
     * @param policy 命令规则
     * @param guard 文件路径规则
     * @param approval 默认询问函数，不应为 null；离线检查可传固定 true/false
     */
    public PermissionGate(CommandPolicy policy, PathGuard guard, Approval approval) {
        this.policy = policy;
        this.guard = guard;
        this.approval = approval;
        this.hardRules = buildHardRules();
        this.askRules = buildAskRules();
    }

    /**
     * 列出绝对不能执行的操作，用户说同意也不行。
     * @return 按顺序检查 bash 禁止命令、文件高风险路径的规则；这里只保存检查函数，不执行工具
     */
    private List<Rule> buildHardRules() {
        return List.of(
                new Rule(List.of("bash"),
                        input -> policy.checkDenyList(input.path("command").asText(""))),
                new Rule(List.of("read_file", "write_file", "edit_file"),
                        input -> guard.highRiskReason(input.path("path").asText(""))));
    }

    /**
     * 列出需要先问用户的操作，不能用这些规则绕过绝对禁止的检查。
     * @return 按顺序检查命令、受保护目录和工作区外文件的规则，第一个命中原因用来询问用户
     */
    private List<Rule> buildAskRules() {
        return List.of(
                new Rule(List.of("bash"),
                        input -> policy.checkRules(input.path("command").asText(""))),
                new Rule(List.of("bash"),
                        input -> policy.checkProtectedDirs(input.path("command").asText(""))),
                new Rule(List.of("read_file", "write_file", "edit_file"),
                        input -> guard.outsideReason(input.path("path").asText(""))));
    }

    /**
     * 旧调用者使用的检查方法，使用构造时传入的默认批准函数，不自己读取终端。
     * @param toolName 要执行的工具名
     * @param input 工具参数，只读取 command/path 等内容
     * @return null 表示允许，非 null 表示拒绝原因，调用者应把它作为工具结果交给模型
     */
    public String check(String toolName, ObjectNode input) {
        return checkWith(toolName, input, approval);
    }

    /**
     * 按这项任务实际来自用户还是定时器，决定能否询问用户，再检查工具参数。
     *
     * <p>用户任务还必须有 approval 函数且没退出会话，才能询问。
     * 定时任务即使传了 approval，也不能询问；它委派子代理后仍如此。
     * 没有 context 时只拒绝需询问的操作，普通允许的工具仍可用。这里也不是停止工具的开关，
     * 执行器在真正执行前仍应检查用户是否退出。</p>
     * @param toolName 工具表中的名称
     * @param input 非 null 参数对象，只读，不执行工具
     * @param context 程序记录的任务信息，可为 null；不能从模型 JSON 取同名字段来代替
     * @return null 表示允许；非 null 是拒绝原因，由调用者包装进 tool_result
     */
    public String check(String toolName, ObjectNode input, ExecutionContext context) {
        Approval channel = context != null && !context.isCancelled() && context.source() == ExecutionContext.Source.USER
                && context.approval() != null ? context.approval() : (name, args, reason) -> false;
        return checkWith(toolName, input, channel);
    }

    /**
     * 依次查绝对禁止的规则、需要询问的规则，最后才询问用户。
     * @param toolName 待检查工具名
     * @param input 非 null 的只读工具参数
     * @param channel 已按任务来源选好的询问函数，可能等待用户回答，但本类不直接读输入
     * @return 允许时为 null；拒绝时是原因。绝对禁止的操作还打印 blocked 提示
     */
    private String checkWith(String toolName, ObjectNode input, Approval channel) {
        // 1. 绝对禁止的操作直接拒绝并说明原因，不能让用户批准绕过。
        String denied = findReason(hardRules, toolName, input);
        if (denied != null) {
            System.out.println(Ansi.red("[Blocked] " + denied));
            return denied;
        }

        // 2. 没有规则要求询问，就允许执行，不必每条命令都问用户。
        String reason = findReason(askRules, toolName, input);
        if (reason == null) {
            return null;
        }

        // 3. 询问用户；不同意时返回原因，让模型知道这次操作不能执行。
        return channel.ask(toolName, input, reason) ? null : reason;
    }

    /**
     * 按顺序找适用于这个工具的第一条命中规则，不检查无关工具的规则。
     * @param rules 要遍历的规则表
     * @param toolName 工具名
     * @param input 只读参数对象
     * @return 第一个命中原因；没有适用或命中的规则就返回 null
     */
    private static String findReason(List<Rule> rules, String toolName, ObjectNode input) {
        for (Rule rule : rules) {
            if (rule.tools().contains(toolName)) {
                String reason = rule.reason(input);
                if (reason != null) {
                    return reason;
                }
            }
        }
        return null;
    }

    /**
     * 把规则适用的工具名和具体检查函数放在一起，不在这里执行工具。
     * @param tools 这条规则要检查的工具名称列表
     * @param check 读取参数并返回原因的函数，null 表示没有命中
     */
    private record Rule(List<String> tools, Function<ObjectNode, String> check) {
        /**
         * 调用本规则的检查函数。
         * @param input 只读的工具参数
         * @return 命中原因，没命中为 null
         */
        String reason(ObjectNode input) {
            return check.apply(input);
        }
    }
}
