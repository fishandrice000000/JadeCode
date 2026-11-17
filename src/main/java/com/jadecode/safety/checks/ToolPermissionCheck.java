package com.jadecode.safety.checks;

import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 将工具输入解释成权限判断使用的静态资料。分析结果列出真实工作目录、资源、命令、读写与不确定属性，
 * 交给规则层选择允许、拒绝或审批。
 */
public interface ToolPermissionCheck {
    /**
     * 表示一个实际文件资源与对应 READ、WRITE 或 DELETE 操作，供路径规则匹配。
     *
     * @param path 本次读取、授权或校验的路径，按方法说明解析到实际目标
     * @param operation 该资源上的 READ、WRITE 或 DELETE 操作
     */
    record Resource(Path path, String operation) {
    }

    /**
     * 保存一个 Shell 命令段的参数边界、实际工作目录和重定向标记，供命令规则匹配。
     *
     * @param argv 解析后保留参数边界的命令参数列表
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param redirected 此命令段是否包含重定向，供完整命令 allow 判断
     */
    record Command(List<String> argv, Path cwd, boolean redirected) {
        /**
         * 复制命令参数列表，固定解析得到的一个命令段。
         *
         * @param argv 解析后保留参数边界的命令参数列表
         * @param cwd 本次操作起始工作目录，相对路径以此为基准
         * @param redirected 此命令段是否包含重定向，供完整命令 allow 判断
         */
        public Command {
            argv = List.copyOf(argv);
        }
    }

    /**
     * 汇总一次工具调用的权限资料。error 表示参数不合法，
     * uncertain 和 uncertaintyReason 表示可解析但资源或副作用尚无法完整确定；规则层分别处理这两类情况。
     *
     * @param tool 模型调用的工具注册名
     * @param cwd 本次操作起始工作目录，相对路径以此为基准
     * @param resources 命令或文件工具涉及的真实目标资源列表
     * @param commands 按原顺序提取的 Shell 命令段列表
     * @param mutating 可识别操作是否修改或删除资源
     * @param readOnlyCommand 命令是否属于已识别的只读查询集合
     * @param uncertain 命令是否包含当前分析器无法确定资源的语法
     * @param error 本次操作产生的错误说明
     * @param uncertaintyReason 命令或工具资源仍无法完全确定的具体说明
     */
    record Analysis(String tool, Path cwd, List<Resource> resources, List<Command> commands,
            boolean mutating, boolean readOnlyCommand, boolean uncertain, String error, String uncertaintyReason) {
        /**
         * 将资源列表与命令列表复制为不可变集合，保留当前调用的读写、错误与不确定属性。
         *
         * @param tool 模型调用的工具注册名
         * @param cwd 本次操作起始工作目录，相对路径以此为基准
         * @param resources 命令或文件工具涉及的真实目标资源列表
         * @param commands 按原顺序提取的 Shell 命令段列表
         * @param mutating 可识别操作是否修改或删除资源
         * @param readOnlyCommand 命令是否属于已识别的只读查询集合
         * @param uncertain 命令是否包含当前分析器无法确定资源的语法
         * @param error 本次操作产生的错误说明
         * @param uncertaintyReason 命令或工具资源仍无法完全确定的具体说明
         */
        public Analysis {
            resources = List.copyOf(resources);
            commands = List.copyOf(commands);
        }

    }

    /**
     * 解释工具参数中的资源与命令，返回本次权限决策使用的完整分析资料。
     *
     * @param tool 模型调用的工具注册名
     * @param input 本次工具参数 JSON 对象，包含该工具 schema 约定的字段
     * @return 供规则匹配的资源、命令、读写和输入错误分析结果
     */
    Analysis analyze(String tool, ObjectNode input);
}
