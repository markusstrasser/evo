# Editing UX Stabilization Proposal

**Date:** 2025-11-11  
**Author:** Codex (GPT-5)  
**Scope:** Blocks editing experience parity with Logseq

---

## Context

Editors report that block editing feels clunky and occasionally breaks. A quick trace through the hot path confirms several systemic inefficiencies in how we stream keystrokes into the event-sourced kernel:

- `components/block.cljs` dispatches `:update-content` on every `input`, which writes directly to the canonical tree (`src/components/block.cljs:557-563`, `src/plugins/editing.cljc:58-62`).
- `api/dispatch` records a full history snapshot and re-runs `db/derive-indexes` for each of those writes (`src/kernel/api.cljc:137-147`, `src/kernel/transaction.cljc:280-302`, `src/kernel/db.cljc:108-125`).
- `update-mock-text!` rebuilds a span per character and reads layout metrics on every render (`src/components/block.cljs:17-47`, `src/components/block.cljs:520-556`).
- Instrumentation still logs every intent and keeps before/after DB snapshots even in non-dev contexts (`src/shell/blocks_ui.cljs:86-101`, `src/dev/tooling.cljs:29-40`).
- Cursor restoration forces focus each render, leading to flickers when rapid navigation occurs (`src/components/block.cljs:520-552`).

Together these leave typing performance far behind Logseq and amplify intermittent race conditions.

---

## Proposal

### 1. Introduce an Editing Buffer + Checkpoints
- Store in-flight text under `session/ui` via a new intent (e.g. `:buffer-edit`) so keystrokes produce ephemeral ops.
- Persist to the canonical tree on blur, structural commands (split/merge), or a debounced checkpoint timer. We can implement the `:checkpoint` flow already outlined in `docs/TEXT_EDITING_BEHAVIORS_SPEC.md:1043-1274`.
- When dispatching buffer intents, call `api/dispatch` with `{:history/enabled? false}` to skip history snapshots and `derive-indexes`. Commit intents alone will trigger full persistence and history.
- Add regression tests to ensure undo/redo reverts meaningful units (whole blocks/phrases) rather than per-character noise.

### 2. Batch Structural Transactions
- Allow `handle-intent` to coalesce consecutive structural intents during the same animation frame so `tx/interpret` runs once per batch.
- Keep existing invariants (validation, derive) but avoid redundant passes when a command triggers multiple ops (e.g. smart split + cursor positioning).

### 3. Rework the Mock-Text Mirror
- Cache the last text/layout fingerprint; skip updates when nothing changed.
- Defer DOM writes to `requestAnimationFrame` and reuse a single text node with `white-space: pre-wrap` instead of O(n) span creation.
- Clone computed styles from the active editable on mount so wrapping stays in lockstep without manual `top/left/width` thrash.

### 4. Harden Focus & Keyboard Flow
- Only call `.focus` when the editable isn’t already the active element; gate cursor restoration so we don’t reapply selections mid-typing.
- Audit `handle-keydown` interaction with the keymap resolver to ensure `preventDefault` happens exactly once, especially for arrows at block boundaries and folded/zoomed contexts.
- Extend existing navigation tests to cover merged/folded cases and ensure cursor memory survives buffer commits.

### 5. Strip Dev Instrumentation from Production Paths
- Wrap console logging and `dev/log-dispatch!` behind a `goog.DEBUG` (or equivalent) guard.
- Provide a lightweight telemetry hook (counts per intent) to retain observability without holding onto large DB snapshots.

---

## Expected Outcomes

- Typing latency comparable to Logseq (no O(n) derive per keystroke).
- Reliable cursor behavior during rapid navigation and edits.
- Cleaner undo history (per sentence/block checkpoints).
- Reduced layout thrash from the mock-text helper.
- Production builds free from dev-only logging overhead.

---

## Next Steps

1. Prototype the editing buffer and checkpoint intents with unit tests for undo/redo and multi-block editing.
2. Refactor `update-mock-text!` with cached metrics and profile long-block typing in the browser.
3. Guard instrumentation, confirm silent console in production mode, and document the dev toggle.
4. Validate the whole flow with E2E editing parity tests (Playwright suites under `test/e2e/editing-parity.spec.js`).

