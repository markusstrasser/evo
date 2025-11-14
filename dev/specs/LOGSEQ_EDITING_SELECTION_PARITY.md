# Logseq Editing + Selection Parity Spec

**Status:** Draft for implementation — replaces `MULTI_BLOCK_SELECTION_DIRECTION_TRACKING.md` and folds in Replicant event-handling fixes

**Date:** 2025-11-14

**Scope:**
- Multi-block selection behavior (plain arrows, Shift+arrows, direction tracking)
- Editing-mode arrow handling and cursor boundary detection
- Replicant lifecycle + event dispatch contract (single source of truth for keyboard handling)
- Fold/zoom visibility guarantees for selection/navigation
- Updated documentation & testing obligations

---

## 1. Executive Summary

Recent debugging uncovered two intertwined issues:
1. **Input dispatch split-brain:** both the global keymap and the block component dispatch intents for the same arrow keys while editing, so cursor/selection state oscillates.
2. **Selection drift:** keyboard range extension still relies on `doc-range`, ignores zoom roots, and cannot contract like Logseq because direction is not tracked.

This spec defines the target end-state: Logseq-compatible keyboard semantics with a single event pathway, guarded by updated Replicant guidance and tests.

---

## 2. Event Dispatch & Replicant Integration

### 2.1 Goals
- Exactly one handler fires per DOM key event.
- Block component owns DOM-derived facts (cursor row, selection collapse) and dispatches intents with full context.
- Global keymap continues to power non-editing shortcuts, but editing-context bindings that duplicate component logic are removed.

### 2.2 Requirements
1. **Adopt Nexus for action dispatch**
   - Add `no.cjohansen/nexus` to `deps.edn` (done) and initialize a Nexus dispatcher inside `shell/blocks_ui.cljs`.
   - Pipe DOM keyboard events into Nexus actions (`[:key/arrow-up {:editing? true ...}]`) so both global shortcuts and block-local handlers flow through the same queue.
   - Provide adapters so components call `(nexus/dispatch! [:editing/navigate-up payload])` instead of invoking `handle-intent` directly. This becomes the single choke point for instrumentation/tests.
2. **Editing keypath ownership**
   - Remove `Shift+Arrow`, `ArrowUp/Down`, and `Enter` bindings from `keymap/bindings_data.cljc`’s `:editing` context when those behaviors are handled inside `components/block.cljs`.
   - `handle-global-keydown` must ignore those keys if `q/editing?` is true; Nexus still receives the event from the component.
3. **Replicant lifecycle usage**
   - `:replicant/on-render` guards for text updates must keep the `__lastAppliedCursorPos` check (documented in §4 below).
   - Mock-text updates use both element and text (`(update-mock-text! node text)`) to maintain coordinate parity.
4. **Docs alignment**
   - Update `docs/REPLICANT.md`, `CLAUDE.md`, and team onboarding docs to describe the Nexus-based dispatch pipeline and forbid the old dual-handler pattern.

### 2.3 Deliverables
- Nexus bootstrap module (`src/shell/nexus.cljs` or similar) plus wiring from `shell/blocks_ui.cljs`.
- Code change removing duplicate keymap bindings and enriching block handlers if any payload fields are missing.
- Instrumented action log (behind `dev?` flag) so Playwright tests can assert number + shape of dispatched actions.
- Documentation update (see §7) explaining the single-dispatch rule and the cursor-position guard.

---

## 3. Multi-Block Selection Direction Tracking (Non-Editing Mode)

### 3.1 Behavior Overview
| Interaction | Logseq | Current Evo | Target |
|-------------|--------|-------------|--------|
| Plain Arrow with multi-selection | Replace entire selection with adjacent block | ✅ | ✅ |
| Shift+Arrow extension | Incremental add/remove based on direction | Uses `doc-range`, fills gaps | Implement incremental logic |
| Shift+Arrow contraction | Removes blocks from selection edge without flipping direction | Recomputes contiguous range | Implement incremental logic |
| Non-contiguous selections | Supported (preserves gaps) | Forced contiguous | Preserve gaps |

### 3.2 State Additions
Extend `session/selection` props:
```clojure
{:nodes #{"a" "b"}
 :focus "b"
 :anchor "a"
 :direction :down} ; NEW
```

### 3.3 Algorithms
- `:extend-next` / `:extend-prev` operate in two modes:
  1. **No direction yet:** treat first Shift+Arrow as both anchor + direction.
  2. **Same direction:** add the next visible block to selection.
  3. **Opposite direction:** remove the trailing block (contract) until only one block remains; only then flip `:direction` and start extending the other way.
- Selection helpers must accept a `:source` flag (keyboard vs mouse) so Shift+Click may continue to use contiguous ranges while Shift+Arrow uses incremental adds.

### 3.4 Visibility Filters
- Reuse `plugins.navigation/visible-in-context?` for both extension and contraction so folded/zoomed-out nodes are skipped.
- Replace the stale `[:nodes session/ui :props :zoom-id]` reads with `q/zoom-root`.

---

## 4. Shift+Arrow in Editing Mode

### 4.1 Cursor Boundary Detection
- Keep the mock-text technique from `components/block.cljs` (line ~500) to compute first/last visual row.
- When `detect-cursor-row-position` reports `:first-row?` (for Shift+Up) or `:last-row?` (Shift+Down):
  1. Collapse any in-block selection (`.collapseToStart`/`.collapseToEnd`).
  2. Dispatch `{:type :selection :mode :extend-prev}` or `:extend-next` exactly once.
  3. Prevent default so the browser does not create a text selection.
- Otherwise, allow the event to bubble for native text selection.

### 4.2 Single Dispatcher Rule
- Editing-mode Shift+Arrow must never be bound in the keymap: the component already owns it.
- `docs/REPLICANT.md` must call out this rule so future contributors do not reintroduce double-dispatch.

---

## 5. Fold/Zoom Guarantees

1. **Visibility predicate**: both selection and navigation must call a shared helper that enforces:
   - Block lies under the current zoom root (or root when nil)
   - No folded ancestors
2. **Edge navigation**: when there is no previous/next visible block, intents must no-op and keep the cursor where it is (current behavior is correct; document it).

---

## 6. Testing Plan

| Layer | Tests to add/update |
|-------|---------------------|
| Unit (Clojure) | New suite `test/plugins/selection_direction_tracking_test.cljc` covering incremental add/remove, direction persistence, zoom root filtering. |
| Integration (Playwright) | Scenarios for: Shift+Arrow extension/contraction, boundary handoff while editing, folded block skipping, zoomed view (selection stays within zoom). |
| Regression harness | Re-enable `clojure -M:test` in CI once dependency download issue is solved; until then run targeted namespaces locally. |

### Test Scenarios (Playwright)
1. `Shift+ArrowDown` repeatedly extends selection; `Shift+ArrowUp` contracts without losing direction.
2. Non-contiguous selection via Shift+Arrow preserves gaps.
3. Editing at first row: Shift+ArrowUp extends selection beyond block instead of creating a text highlight.
4. Zoom into block X; Shift+ArrowDown never escapes that subtree.

---

## 7. Documentation Updates

1. **`docs/REPLICANT.md`**
   - Lead with the function-based event handlers we actually use.
   - Explain the single-dispatch rule and the `__lastAppliedCursorPos` guard.
   - Show the updated `update-mock-text!` signature.
   - Move the `mounting?` discussion to a “Resolved Bugs” appendix so readers do not try to “fix” it again.
2. **`LOGSEQ_SPEC.md`**
   - Reference this document for Shift+Arrow behavior and call out that Shift+Click still uses contiguous ranges.
3. **Archive old spec files** once this document is approved (see below).

---

## 8. Implementation Checklist

1. **State + helpers**
   - Add `:direction` to selection state, migrate existing code to preserve backwards compatibility (default to `nil`).
   - Split `calc-extend-props` into keyboard vs mouse variants.
2. **Event handling / Nexus**
   - Initialize a Nexus dispatcher (single atom) and expose helpers so components emit actions rather than calling `handle-intent` directly.
   - Remove redundant keymap bindings, consolidate editing key handling inside the block component.
   - Ensure `handle-global-keydown` short-circuits Shift+Arrow when editing while still routing all other keys through Nexus.
3. **Visibility filtering**
   - Replace `:zoom-id` lookups with `q/zoom-root` or pass `visible-in-context?` to selection helpers.
4. **Docs/tests**
   - Update `docs/REPLICANT.md` and `LOGSEQ_SPEC.md` according to §7.
   - Land new unit + e2e tests from §6.

---

## 9. Archival Plan

Once this spec is approved, archive the following superseded documents into `dev/specs/archive/`:
1. `dev/specs/MULTI_BLOCK_SELECTION_DIRECTION_TRACKING.md` (replaced by this file)
2. `dev/specs/archive/SHIFT_ARROW_TEXT_SELECTION_SPEC.md` (content merged here)
3. Any prior “LOGSEQ_FEEL” snippets covering cursor memory that conflict with the single-dispatch rule (list to be confirmed during implementation)

---

## 10. References
- Logseq source: `handler/editor.cljs`, `state.cljs`, `util/cursor.cljs`
- Evo source: `components/block.cljs`, `plugins/selection.cljc`, `plugins/navigation.cljc`, `shell/blocks_ui.cljs`, `keymap/bindings_data.cljc`
