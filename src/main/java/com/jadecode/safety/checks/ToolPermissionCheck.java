package com.jadecode.safety.checks;

import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 工具参数的静态解释，不执行工具、不询问用户，也不证明任意脚本没有副作用。 */
public interface ToolPermissionCheck {
    record Resource(Path path, String operation) { }
    record Command(List<String> argv, Path cwd, boolean redirected) { public Command { argv = List.copyOf(argv); } }
    /** error 是非法参数；uncertain 是语法或副作用无法可靠分析，两者不能混为一谈。 */
    record Analysis(String tool, Path cwd, List<Resource> resources, List<Command> commands,
            boolean mutating, boolean readOnlyCommand, boolean uncertain, String error, String uncertaintyReason) {
        public Analysis { resources = List.copyOf(resources); commands = List.copyOf(commands); }
        /** 文件工具等旧调用没有 shell 语法说明，保留原构造方式。 */
        public Analysis(String tool, Path cwd, List<Resource> resources, List<Command> commands,
                boolean mutating, boolean readOnlyCommand, boolean uncertain, String error) {
            this(tool, cwd, resources, commands, mutating, readOnlyCommand, uncertain, error, "");
        }
    }
    Analysis analyze(String tool, ObjectNode input);
}
