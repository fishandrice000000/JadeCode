/** CLI 的纯参数解析：不读配置、不打开服务；错误必须先于任何业务副作用。 */
export class UsageError extends Error {}

export interface Options {
	headless: boolean; help: boolean; version: boolean; prompt: string;
	resume?: string; permission?: string; modelConfig?: string;
	remote?: string; workspace?: string; attach?: string; close?: string; cancel?: string;
	list: boolean; stop: boolean; isolated: boolean;
	workspaceList: boolean; workspaceCreate: boolean; workspaceDelete: boolean; artifacts: boolean;
	workspaceId?: string; upload?: string; artifact?: string; output?: string;
}

/** 保留 -- 之后的字面参数及单个参数内的空白；多位置参数用一个空格连接。 */
export function parseOptions(args: string[]): Options {
	const o: Options = { headless: false, help: false, version: false, prompt: "", list: false, stop: false,
		isolated: false, workspaceList: false, workspaceCreate: false, workspaceDelete: false, artifacts: false };
	const prompts: string[] = [];
	const valued: Record<string, keyof Options> = { "--remote": "remote", "--workspace": "workspace", "--attach": "attach",
		"--close-session": "close", "--stop-session": "cancel", "--workspace-id": "workspaceId", "--upload-snapshot": "upload",
		"--download-artifact": "artifact", "--output": "output", "--permission": "permission", "--model-config": "modelConfig" };
	const flags: Record<string, keyof Options> = { "--headless": "headless", "--sessions": "list", "--service-stop": "stop",
		"--isolated": "isolated", "--managed-workspaces": "workspaceList", "--create-workspace": "workspaceCreate",
		"--delete-workspace": "workspaceDelete", "--artifacts": "artifacts", "--help": "help", "-h": "help", "--version": "version", "-V": "version" };
	for (let i = 0; i < args.length; i++) {
		const arg = args[i]!;
		if (arg === "--") { prompts.push(...args.slice(i + 1)); break; }
		if (arg === "--resume" || arg.startsWith("--resume=")) {
			if (o.resume !== undefined) throw new UsageError("--resume may only be specified once");
			o.resume = arg === "--resume" ? "" : arg.slice(9);
			if (arg !== "--resume" && !o.resume.trim()) throw new UsageError("Use --resume to choose, or --resume=<id|name>");
		} else if (flags[arg]) Object.assign(o, { [flags[arg]!]: true });
		else {
			const equal = arg.indexOf("=");
			const key = equal < 0 ? arg : arg.slice(0, equal);
			const field = valued[key];
			if (field) {
				const value = equal < 0 ? args[++i] : arg.slice(equal + 1);
				if (!value?.trim() || value.startsWith("--")) throw new UsageError(`${key} requires a value`);
				if (field === "modelConfig" && o.modelConfig !== undefined) throw new UsageError("--model-config may only be specified once");
				Object.assign(o, { [field]: value });
			} else if (arg.startsWith("-") && arg.length > 1) throw new UsageError(`Unknown option: ${arg}`);
			else prompts.push(arg);
		}
	}
	o.prompt = prompts.join(" ");
	if (o.help || o.version) return o;
	if (o.permission && !["read-only", "workspace", "auto-review", "full-access"].includes(o.permission)) throw new UsageError("Invalid permission profile");
	if (o.headless && !o.prompt.trim()) throw new UsageError("--headless requires a non-empty task");
	if (o.prompt && !o.prompt.trim()) throw new UsageError("The task description must not be blank");
	if (o.resume !== undefined && (o.prompt || o.headless)) throw new UsageError("--resume cannot be combined with a task or --headless");
	const transfers = [o.workspaceList, o.workspaceCreate, o.workspaceDelete, o.artifacts, o.upload, o.artifact].filter(Boolean).length;
	const management = [o.list, o.stop, o.close, o.cancel].filter(Boolean).length;
	if (transfers > 1 || management > 1 || transfers && (management || o.isolated || o.attach || o.workspace || o.prompt)) throw new UsageError("Choose one management operation without an Agent task");
	if ((transfers || management) && (o.headless || o.resume !== undefined || o.permission || o.modelConfig || o.prompt)) throw new UsageError("Management does not accept Agent launch options");
	if (o.output && !o.artifact) throw new UsageError("--output requires --download-artifact");
	if ((o.workspaceDelete || o.artifacts || o.upload || o.artifact) && !o.workspaceId) throw new UsageError("This operation requires --workspace-id <id>");
	if (o.artifact && !o.output) throw new UsageError("--download-artifact requires --output <local-file>");
	if (o.isolated && (o.workspace || o.workspaceId || o.attach) || o.workspaceId && (o.workspace || o.attach)) throw new UsageError("Choose one workspace or attachment");
	if (o.attach && (o.prompt || o.resume !== undefined || o.permission || o.modelConfig)) throw new UsageError("--attach uses the existing configuration");
	if (o.remote && !o.workspace && !o.workspaceId && !o.isolated && !o.attach && !transfers && !management) throw new UsageError("--remote requires --workspace <server-path> or --attach <id>");
	return o;
}

/** 所有客户端命令共享此帮助；不加载终端、模型配置或 Java。 */
export const HELP = `JadeCode - a CLI coding agent

Usage:
  jade                         Open the UI; reconnect this workspace if already live
  jade <prompt>                Open the UI and submit a turn
  jade --headless <prompt>     Run without a UI and print only the final reply
  jade --resume[=<id|name>]    Choose or restore saved history
  jade --remote <user@host> --workspace <server-path>
  jade --attach <id>           Attach a live session
  jade --sessions              List service sessions
  jade --stop-session <id>     Cancel the current turn
  jade --close-session <id>    Close a service session
  jade --service-stop          Stop the service
  jade --isolated              Allocate an empty managed workspace
  jade --create-workspace | --managed-workspaces
  jade --workspace-id <id> [--delete-workspace | --artifacts]
  jade --workspace-id <id> --upload-snapshot <directory>
  jade --workspace-id <id> --download-artifact <id> --output <file>

Options:
  --workspace <path>           Workspace on the execution machine
  --permission <profile>       read-only | workspace | auto-review | full-access
  --model-config <path>        Model JSON path on the execution machine
  --                          Remaining arguments form a literal prompt
  -h, --help                  Show this help without starting a service
  -V, --version               Show the version

All client modes require Node >=22.19.0. Local execution additionally requires Java 21.
Headless defaults to auto-review; operations still requiring a human are denied.
Closing the UI detaches. /stop cancels a turn; /close closes its session.
`;
