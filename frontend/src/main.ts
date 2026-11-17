import { runCli } from "./cli/run.ts";

/** Node 统一入口：在服务连接前检查运行时，不借助 Java 客户端转发。 */
const [major, minor] = process.versions.node.split(".").map(Number);
if (major! < 22 || major === 22 && minor! < 19) {
    process.stderr.write("JadeCode requires Node >=22.19.0\n");
    process.exitCode = 2;
} else {
    process.exitCode = await runCli(process.argv.slice(2));
}
