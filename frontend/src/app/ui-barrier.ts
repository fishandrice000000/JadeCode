/**
 * UI 初始化屏障。
 *
 * <p>socket 可能把 hello/bootstrap/完整快照合成一次写入，而 reader 会在同一回调里同步消费
 * 所有帧。若此时编辑器/终端尚未建立，快照安装会访问未初始化的对象，或者 ready 抢在 UI 之前
 * 发出。这里按序暂存依赖 UI 的事件，初始化完成后一次性消费；不丢事件、不用 sleep。</p>
 */
export class UiEventBarrier<T> {
	private ready = false;
	private readonly pending: T[] = [];

	/** @returns true 表示已经直接交给 handler；false 表示已按序暂存。 */
	offer(event: T, handler: (event: T) => void): boolean {
		if (this.ready) {
			handler(event);
			return true;
		}
		this.pending.push(event);
		return false;
	}

	/** UI 就绪后按到达顺序消费暂存事件；空队列时无操作。 */
	activate(handler: (event: T) => void): void {
		this.ready = true;
		const queued = this.pending.splice(0);
		for (const event of queued) handler(event);
	}

	isReady(): boolean { return this.ready; }

	pendingCount(): number { return this.pending.length; }
}
