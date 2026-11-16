import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.internal.formatter.DefaultCodeFormatter;
import org.eclipse.jface.text.Document;

/**
 * 命令行格式化适配器，由 format-java.py 使用 VS Code Java 扩展的运行时和类路径启动。
 * 它读取项目固定的 Eclipse 配置，以 Java 21 语法处理显式指定的文件，不参与应用构建。
 * 检查模式只报告差异；写入模式覆盖需要格式化的文件，解析或 IO 失败直接向调用方报错。
 */
public class JavaFormatter {
    /**
     * 依次读取配置、生成编辑操作并检查或写回源码，不改变源码的业务逻辑。
     *
     * @param args 配置文件路径、是否只检查，以及至少一个 Java 文件路径；由 Python 入口校验
     * @throws Exception 配置解析、Java 格式化或文件读写失败；已经写入的文件不会自动回滚
     */
    public static void main(String[] args) throws Exception {
        // 编辑器与命令行共用这些 setting；编译选项另行固定为项目实际使用的 Java 21。
        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(args[0]);
        var nodes = xml.getElementsByTagName("setting");
        var settings = new HashMap<String, String>();
        for (int i = 0; i < nodes.getLength(); i++) {
            var attributes = nodes.item(i).getAttributes();
            settings.put(attributes.getNamedItem("id").getNodeValue(), attributes.getNamedItem("value").getNodeValue());
        }
        settings.put("org.eclipse.jdt.core.compiler.source", "21");
        settings.put("org.eclipse.jdt.core.compiler.compliance", "21");
        settings.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", "21");
        boolean check = Boolean.parseBoolean(args[1]);
        boolean changed = false;
        for (int i = 2; i < args.length; i++) {
            Path path = Path.of(args[i]);
            String source = Files.readString(path);
            var formatter = new DefaultCodeFormatter(settings);
            // 是否重排注释由共享配置决定；返回 null 表示解析失败，必须报错，不能静默跳过。
            var edits = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
                    source, 0, source.length(), 0, "\n");
            if (edits == null) {
                throw new IllegalArgumentException("Cannot format Java source: " + path);
            }
            var document = new Document(source);
            edits.apply(document);
            if (!source.equals(document.get())) {
                changed = true;
                System.out.println((check ? "Needs formatting: " : "Formatted: ") + path);
                if (!check) {
                    Files.writeString(path, document.get());
                }
            }
        }
        // 检查模式以非零退出码供提交前检查使用；没有差异时正常退出。
        if (check && changed) {
            System.exit(1);
        }
    }
}
