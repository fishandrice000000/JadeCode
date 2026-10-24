# Vendored Pi TUI patches (P01–P07)

Upstream: `/home/fishandrice/Backend/pi`, commit `4954d760504c11df9b2cbdc040346107b6a8153d`, package `packages/tui` (version 1.3.0, MIT).
Vendored copy: `frontend/vendor/pi-tui/`. Original and current SHA-256 per file: `provenance.json`.

Six source files are modified: `src/terminal.ts`, `src/stdin-buffer.ts`, `src/tui.ts`, `src/tui-alt-screen.ts`, `src/keybindings.ts`, `src/components/editor.ts`.
Everything else is byte-identical to the pinned commit. `provenance.json` lists P01–P07 and per-file original/current hashes. The upstream working tree is
never touched; `scripts/verify-vendor.mjs` re-checks these hashes and fails the build when a vendored file drifts without a
recorded patch.

## P01 — raw stdin source watermark (`src/terminal.ts`)

- **Why:** Jade approvals must reject a key press whose bytes started arriving *before* the approval frame was committed to
  stdout. Pi splits stdin through `StdinBuffer`, so an `ESC` that arrived in an older chunk and its tail in a newer chunk must
  not look like fresh input.
- **Change:** `ProcessTerminal` gains `setRawInputTap(tap)` and calls it inside `stdinDataHandler` with the raw chunk, before
  `StdinBuffer.process`. Nothing is parsed or reordered in the vendor; the tap is a pure observation hook.
- **Impact:** approval freshness only. Normal Pi key handling, Kitty negotiation and paste handling are unchanged.
- **Regression:** `test/terminal/input-evidence.test.ts` (split ESC across chunks is stale), plus the Java-side
  `BridgeApprovalTest` which refuses a stale receipt watermark.

## P02 — priority input routing (`src/tui.ts`, `src/tui-alt-screen.ts`)

- **Why:** `TuiAltScreen` registers its viewport listener in its own constructor, so an application listener registered later
  never sees mouse/key input that the viewport already consumed. The approval page must be able to route a fresh `Enter`/`Esc`
  and a left-button press to itself first.
- **Change:** `TuiBase.addPriorityInputListener` / `removePriorityInputListener`; priority listeners run before regular
  listeners, overlays and the focused component. `TuiAltScreen.mousePriorityHandler` receives the already-decoded
  `TuiMouseEvent` before Pi gestures (selection, scrollbar, wheel routing).
- **Impact:** the Jade approval controller can intercept only while an approval is pending; SDD normal keys and mouse
  gestures keep using Pi. No second CSI/Kitty/SGR parser is introduced.
- **Regression:** `test/terminal/approval-input-priority.test.ts`; Node approval tests drive press/release/wheel/paste and
  assert only a fresh left press on the same-frame rect answers.

## P03 — render gate and output-commit fence (`src/tui.ts`)

- **Why:** Pi's `Terminal.write` returns `void`; a generated frame is not a committed frame. Jade must (a) stop generating
  whole-screen diffs while stdout is backed up and (b) publish approval geometry only after the frame's writes were accepted.
- **Change:** `TuiBase.renderGate` is consulted before `doRender` in all three render paths; a blocked frame stays pending
  until `notifyRenderReady()`. `afterFrameRendered()` runs synchronously after a frame queued its writes; the Jade terminal
  adapter attaches its stdout-completion fence there.
- **Impact:** scheduling only. `previousScreen` still advances exactly when Pi writes, so a suppressed frame can never be
  half-applied. The fence proves stdout accepted the bytes, not that a human saw pixels.
- **Regression:** `test/terminal/presented-evidence.test.ts` (write failure publishes no evidence, drained render wakes once,
  no polling) and the acceptance report V05/P03 note.

## P04 — configured escape reassembly timeout (`src/terminal.ts`)

- **Why:** Jade exposes `escapeTimeoutMillis`; Pi reads `PI_TUI_ESC_TIMEOUT` from the process environment only, and Jade must
  not mutate machine-wide environment for one setting.
- **Change:** `ProcessTerminal.setEscapeTimeoutMs(ms)` overrides the `StdinBuffer` window for the next `start()`; `undefined`
  keeps Pi's own environment/SSH heuristic (Jade's `0` maps to `undefined`).
- **Impact:** key reassembly latency only; the value is read at startup, matching the Jade settings contract.
- **Regression:** `test/terminal/escape-timeout.test.ts` (override is passed through, `undefined` keeps the default).

## P05 — scrollbar setting on the fullscreen view (`src/tui-alt-screen.ts`)

- **Why:** the approved `fullscreenScrollbar` setting (auto/always/hidden) had no public route to the implicit primary
  scroll view; the upstream constructor keeps it private. Without this the setting would be stored but never consumed.
- **Change:** `TuiAltScreen.setScrollbar(mode)` forwards to `ScrollView.setScrollbar` on the primary view and requests a
  render. No scrollbar algorithm is reimplemented.
- **Impact:** display only; scrolling/selection behavior unchanged. This patch was present in the first checkpoint but was
  missing from the audit files; both files are corrected here instead of rewriting history.
- **Regression:** Node acceptance settings test (scrollbar mode reaches the host) and manual fullscreen scrollbar checks.

## P06 — sequence start chunk provenance (`src/stdin-buffer.ts`, `src/terminal.ts`)

- **Why:** review evidence E08 showed that a raw chunk containing a complete key plus the first half of an `ESC` sequence
  could make the later-completed sequence look fresh. P01 recorded only the raw chunk watermark; it did not retain which
  chunk the first byte of each emitted sequence came from.
- **Change:** `StdinBuffer.process(data, chunkId)` remembers the chunk of the first byte still buffered and emits
  `data`/`paste` with that chunk. `ProcessTerminal` forwards it via `currentInputChunk()`; the Jade terminal adapter passes
  it into `InputEvidence.beginDispatch(chunk)`.
- **Impact:** approval freshness only; sequence parsing, paste handling and timers are unchanged.
- **Regression:** `test/terminal/input-evidence.test.ts` drives the real vendored `StdinBuffer` with a split CSI-u Enter and
  asserts the sequence starts at chunk 1, plus `BridgeApprovalTest` rejecting an old watermark.

## P07 — LF/Ctrl+J reassignment priority (`src/keybindings.ts`, `src/components/editor.ts`)

- **Why:** Pi's Editor hard-codes LF/Ctrl+J as a newline and also lets the default `enter` binding match LF for submit.
  When Jade users rebind `tui.input.newLine` (or bind LF to a cursor/delete action), those fallbacks must not steal the key.
- **Change:** `KeybindingsManager.matchesAny/matchesAnyExcept`; Editor skips the hard-coded newline block when LF is bound
  to another action, and skips the submit fallback when LF is bound to a non-submit action. No parser/rendering change.
- **Impact:** only explicit user keybinding overrides. Defaults keep Pi behavior. Jade Application consumes an unbound LF
  when newLine is overridden so it cannot fall back to newline.
- **Regression:** `test/decision/keymap-consumers.test.ts` (LF rebound to cursorLeft moves the cursor, no newline; old
  default blocked) and the hidden-action Editor test.

## Not patched (deliberately)

- No Pi Agent, model, session or provider code is vendored. Only `packages/tui`.
- No syntax highlighting, chat viewport or Mermaid code is copied from `packages/coding-agent`; Jade's own modules wrap the
  public Pi Markdown/theme/selection APIs instead.
- `packages/pi-tui/test/**` is vendored verbatim as upstream reference material and is **not** executed by Jade's test suite.


## P08 — Resume rendering after stdout drain

- File: `src/tui.ts` (`notifyRenderReady`).
- 原因：P03 挡住一帧后保留 `renderRequested=true`，drain 回调再调用 `requestRender()` 会被该标志提前返回，界面永久停止重绘。
- 修改：消费 gateBlocked 时先清理没有排程的合并标志，再按普通 16ms 策略排一帧最新状态。
- 验证：`terminal-adapter.test.ts` 使用真实 JadeTerminal 的 false write/延迟 callback/drain 与真实 Pi TuiAltScreen；阻塞期间 100 次更新不渲染，drain 后只绘最新值一次。未改变上游调度间隔或输入抢占规则。
