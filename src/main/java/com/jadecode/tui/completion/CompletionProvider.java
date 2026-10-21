package com.jadecode.tui.completion;

import java.util.concurrent.CompletableFuture;

/**
 * 异步补全端口：接收不可变请求，返回带请求身份的结果。
 *
 * <p>实现不得直接修改 Editor、执行命令或等待 UI；耗时枚举/路径读取应在工作线程完成。
 * 取消/过期由 Editor 按 requestId、generation 与草稿版本校验，provider 只需尽快返回。
 * 失败返回 {@link CompletionRequest.Result#failed}，不能把异常吞成“无候选”。</p>
 */
@FunctionalInterface
public interface CompletionProvider {
    /** @return 异步结果；必须完成，允许在调用线程已完成。 */
    CompletableFuture<CompletionRequest.Result> complete(CompletionRequest.Request request);

    /** 永不提供候选。 */
    CompletionProvider NONE = request -> CompletableFuture.completedFuture(CompletionRequest.Result.of(request, java.util.List.of()));
}
