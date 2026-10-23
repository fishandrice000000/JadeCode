import { createHash } from "node:crypto";

/**
 * 大资料组装：Java 发出的 begin/chunk/end 三帧。
 *
 * 只有在三帧齐全、总长与 sha256 都匹配后才允许正式消息引用该 blob；未完成的组装不暴露给
 * 渲染层，避免审批界面显示半截参数。限额与 Java 侧一致：64MiB/逻辑载荷、16 个并发、
 * 64MiB 总预算；超限明确失败。
 */
export const MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
export const MAX_ASSEMBLIES = 16;

interface Assembly {
	kind: string;
	totalBytes: number;
	hash: string;
	chunks: Buffer[];
	nextIndex: number;
	count: number;
	bytes: number;
}

export class PayloadAssembler {
	private readonly active = new Map<string, Assembly>();
	private readonly complete = new Map<string, string>();
	private budget = 0;

	/** 开始一个入站 blob；重复 id/超限明确失败。 */
	begin(id: string, kind: string, totalBytes: number, hash: string): void {
		if (this.active.has(id)) throw new Error(`Duplicate payload id: ${id}`);
		if (!Number.isInteger(totalBytes) || totalBytes < 0 || totalBytes > MAX_PAYLOAD_BYTES) {
			throw new Error(`Payload declared ${totalBytes} bytes`);
		}
		if (this.active.size >= MAX_ASSEMBLIES) throw new Error("Too many payloads are being assembled");
		if (this.budget + totalBytes > MAX_PAYLOAD_BYTES) throw new Error("Payload assembly budget is exhausted");
		this.active.set(id, { kind, totalBytes, hash, chunks: [], nextIndex: 0, count: 0, bytes: 0 });
		this.budget += totalBytes;
	}

	/** 追加一块；序号必须连续。 */
	chunk(id: string, index: number, value: string): void {
		const assembly = this.active.get(id);
		if (!assembly) throw new Error(`Unknown payload id: ${id}`);
		if (index !== assembly.nextIndex) {
			throw new Error(`Payload chunk ${index} arrived after ${assembly.nextIndex}`);
		}
		const bytes = Buffer.from(value, "utf8");
		if (assembly.bytes + bytes.length > assembly.totalBytes) throw new Error("Payload exceeded its declared size");
		assembly.chunks.push(bytes);
		assembly.bytes += bytes.length;
		assembly.nextIndex += 1;
		assembly.count += 1;
	}

	/** 结束并校验；成功返回完整文本并登记可解析的 blob。 */
	end(id: string, count: number, hash: string): string {
		const assembly = this.active.get(id);
		if (!assembly) throw new Error(`Unknown payload id: ${id}`);
		this.active.delete(id);
		this.budget -= assembly.totalBytes;
		const data = Buffer.concat(assembly.chunks);
		if (count !== assembly.count) throw new Error("Payload chunk count mismatch");
		if (data.length !== assembly.totalBytes) throw new Error("Payload size mismatch");
		const digest = createHash("sha256").update(data).digest("hex");
		if (digest !== hash.toLowerCase() || digest !== assembly.hash.toLowerCase()) {
			throw new Error("Payload hash mismatch");
		}
		const value = data.toString("utf8");
		this.complete.set(id, value);
		while (this.complete.size > 16) {
			const oldest = this.complete.keys().next().value;
			if (oldest === undefined) break;
			this.complete.delete(oldest);
		}
		return value;
	}

	/** 取回已校验的 blob；未知引用返回 undefined（不返回空串掩盖错误）。 */
	resolve(ref: string | null | undefined): string | undefined {
		if (!ref) return undefined;
		return this.complete.get(ref);
	}

	/** 断连/切换时丢弃未完成组装。 */
	clear(): void {
		this.active.clear();
		this.budget = 0;
	}
}
