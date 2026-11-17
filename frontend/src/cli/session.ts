import { UsageError } from "./options.ts";
import type { BackendClient } from "../bridge/client.ts";
import type { Payload } from "../bridge/wire.ts";
import type { Connection, Installation } from "./transport.ts";

export interface SessionStartup { connection: Connection; installation: Installation; request: Payload; }

/** 先核对服务身份，再创建/选择会话；不读取模型配置，不把不匹配服务自动停止。 */
export async function prepareSession(client: BackendClient, startup: SessionStartup): Promise<string> {
	await verifyService(client, startup.connection, startup.installation);
	const opened = await client.serviceRequest("service.open", startup.request);
	if (typeof opened.credential !== "string" || !opened.credential) throw new Error("Missing service handshake credential");
	return opened.credential;
}

/** 所有服务操作前核对实际进程与构建，不为旧 daemon 保留回退协议。 */
export async function verifyService(client: BackendClient, connection: Connection, installation: Installation): Promise<void> {
	const identity = await client.serviceRequest("service.identity", {});
	const endpoint = connection.endpoint;
	if (endpoint && (identity.pid !== endpoint.pid || identity.started !== endpoint.started)) throw new Error("Service endpoint identity changed; inspect the service before retrying");
	if (identity.buildId !== installation.buildId || identity.protocolVersion !== installation.protocolVersion || identity.paired !== true) throw new UsageError("Service/client build mismatch; stop the old service explicitly and rebuild the paired distribution");
}
