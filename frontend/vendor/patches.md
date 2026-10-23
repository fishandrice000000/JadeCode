# Vendored Pi TUI patches (P01–P04)

Upstream: `/home/fishandrice/Backend/pi`, commit `4954d760504c11df9b2cbdc040346107b6a8153d`, package `packages/tui` (version 1.3.0, MIT).
Vendored copy: `frontend/vendor/pi-tui/`. Original and current SHA-256 per file: `provenance.json`.

Only three source files are modified. Everything else is byte-identical to the pinned commit. The upstream working tree is
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

## Not patched (deliberately)

- No Pi Agent, model, session or provider code is vendored. Only `packages/tui`.
- No syntax highlighting, chat viewport or Mermaid code is copied from `packages/coding-agent`; Jade's own modules wrap the
  public Pi Markdown/theme/selection APIs instead.
- `packages/pi-tui/test/**` is vendored verbatim as upstream reference material and is **not** executed by Jade's test suite.
