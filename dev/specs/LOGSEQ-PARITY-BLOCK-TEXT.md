# Logseq Parity: Block/Text Editing, Navigation, Selection

**Audience:** Evo contributors and AI agents working in this repo  
**Goal:** Deliver end-user parity with Logseq (macOS, `:editor/logical-outdenting? true`) for block editing, cursor travel, and selection affordances.

---

## 1. Background & Context

Logseq users expect a “continuous document” feel: arrow keys traverse visible blocks seamlessly, cursor columns persist, outdenting obeys logical positioning, and deselection is effortless. Evo’s architecture already mirrors much of that UX through intent → op pipelines, but several subtleties differ. Recent audits (`dev/specs/claude-logseq-parity-deep-dive.md`, `dev/specs/CRITICAL_BEHAVIOR_GAPS.md`, direct source comparisons with `~/Projects/best/logseq`) surfaced the remaining gaps; this document centralises them so agents can implement fixes without re-investigating.

Definitions:
- **Direct outdenting** – block becomes a sibling right after its parent (Logseq default when `:editor/logical-outdenting? false`).
- **Logical outdenting** – block moves to the bottom of the grandparent’s children list (Logseq when `:editor/logical-outdenting? true`; this is the user’s current setting).
- **Cursor column memory** – horizontal offset retained when moving between blocks.
- **Background deselection** – Escape or clicking empty canvas clears selection when not editing.

---

## 2. User Stories

1. **Continuous editing**  
   *As a writer*, when I press `↓` at the bottom of a block, I land on the next visible block with the caret in the same column, even if the target is folded, zoomed, multi-line, or emoji-rich.

2. **Code & markup awareness**  
   *As a developer using code fences*, pressing Enter inserts a newline inside the fence instead of spawning a new block.

3. **Logical outdenting**  
   *As a Logseq user with logical outdenting enabled*, Shift+Tab should move the current block to the bottom of the grandparent’s children without kidnapping its right siblings.

4. **Quick deselection**  
   *As someone organising blocks*, pressing Escape (while not editing) or clicking empty canvas should clear the block selection immediately.

5. **Predictable boundary navigation**  
   *As a keyboard user*, hitting `↑`/`↓` at document edges should keep me in place rather than throwing an exception.

6. **Reference following**  
   *As a reader*, `Cmd+O` should follow the page/block reference under the caret just like Logseq.

7. **Safe merges**  
   *As someone restructuring outlines*, backspacing to merge blocks must keep every child visible under the merged parent—no descendants lost to the trash.

---

## 3. Gap Analysis (grounded in Logseq/Evo sources)

| Area | Logseq implementation | Evo current state | Impact |
|------|-----------------------|-------------------|--------|
| Cursor column memory | `state.cljs:set-editor-last-pos!` + `restore-last-saved-cursor!` applied whenever crossing blocks | `:cursor-memory` stored (plugins/navigation.cljc) but never consumed; `components/block.cljs` only reads ad-hoc cursor hints | Column resets to start/end when returning to previous block |
| Multi-line landing | `handler/block.cljs:text-range-by-lst-fst-line` → up hits last line, down hits first | `get-target-cursor-pos` picks first line for up, last for down | Caret appears on wrong visual row |
| Grapheme counting | `util.cljs:get-line-pos` uses `GraphemeSplitter` (emoji-aware) | `navigation.cljc:get-line-pos` uses simple `count` with TODO | Emoji/CJK drift column memory |
| Fold/zoom navigation | `util/get-prev-block-non-collapsed` walks DOM respecting fold + zoom root | Raw `:prev-id-of` / `:next-id-of` lookups | Caret jumps into folded children or outside zoom context |
| Boundary handling | No visible sibling → caret stays put (`cursor/move-cursor-to`) | Missing sibling → `(throw (ex-info …))` (`navigate-no-sibling-throws-test`) | Exceptions bubble to UI |
| Enter key | Context-aware; code fences insert newline; refs trigger navigation | `handle-enter` dispatches `:smart-split` only | Code fences spawn new blocks; refs ignored |
| Horizontal boundary memory | `move-cross-boundary-*` reuses stored column | `:navigate-to-adjacent` always `:start`/`:max` | Right-boundary round-trips jump to start |
| Shift selection | DOM-derived row detection keeps anchor | `detect-cursor-row-position` naive; anchor lost after first hop | Shift+Arrow range breaks on multi-line/emoji |
| Outdenting | Direct/logical modes leave right siblings untouched | `struct.cljc:outdent-ops` re-parents right siblings under outdented block | Hierarchy diverges from Logseq + user setting |
| Deselection | Escape (non-editing) + background click dispatch `:selection :clear` | No Escape/background handler (`shell/blocks_ui.cljs`) | Selection sticks until toggled |
| Backspace merge | Children re-parented to previous block (`outliner-core/move-blocks!`) | `:merge-with-prev` sends merged block to trash with children attached | Child blocks “disappear” with trashed node |
| Follow link (Cmd+O) | `Cmd+O` navigates to page/block; `Cmd+Shift+O` opens sidebar | No equivalent intent/binding | Users must manually open links |

---

## 4. Detailed Requirements

### 4.1 Cursor Memory
- Persist `{block-id {:column n :direction dir}}` under `session/ui`.
- Expose via `kernel.query` and consume in `components/block.cljs` whenever a block enters edit.
- Clear entry when the user explicitly moves the caret (mouse click, DOM selection). 

### 4.2 Navigation Intents
- Update `get-target-cursor-pos` to mirror Logseq’s first/last-line rule.
- Wrap sibling lookup in fold/zoom filters (respect `q/folded?`, zoom root).
- Replace exceptions with no-op ops that keep `editing-block-id` unchanged but set cursor to `0` (`:up`) or `(count text)` (`:down`).
- Horizontal navigation (`:navigate-to-adjacent`) should accept stored column fallback.

### 4.3 Grapheme Support
- CLJS: use `js/Intl.Segmenter` for grapheme counts.
- CLJ tests: use `BreakIterator` (or similar) to keep assertions consistent.
- Apply to `get-line-pos` and any delete/merge helpers relying on spans.

### 4.4 Context-aware Enter
- Route `handle-enter` through a richer intent that:
  - Inserts newline inside code fences instead of splitting.
  - Intercepts block/page refs (`((id))`, `[[Page]]`) and populates session state for navigation/preview.
  - Falls back to existing list/checkbox continuation and plain split behaviour.

### 4.5 Outdenting (logical and direct)
- Refactor `outdent-ops` so right siblings remain untouched.
- Logical mode (`:editor/logical-outdenting? true`): `{:op :place :id id :under gp :at :last}`.
- Direct mode (`false`): `{:op :place :id id :under gp :at {:after parent}}`.
- Wire to user config (default logical=true until preference plumbing exists).

### 4.6 Deselection
- Extend `handle-global-keydown` to dispatch `{:type :selection :mode :clear}` on Escape when not editing.
- Add background click handler on `.app` / `.main-content` to clear selection unless the target is within `.block` (respect `.stopPropagation`).

### 4.7 Backspace Merge Children
- Before trashing the merged block, move each child under the previous block (preserve order).
- Mirror the child migration logic used in `:merge-with-next` so forward delete and backspace stay symmetric.
- Keep cursor positioning at the end of the merged text.

### 4.8 Follow Link Under Cursor (Cmd+O)
- Introduce a `:follow-link-under-cursor` intent leveraging existing context detection (`context/context-at-cursor`).
- For `:page-ref` → switch current page; for `:block-ref` → scroll/highlight target block (sidebar support can be stubbed for `Cmd+Shift+O`).
- Register key binding `[{:key "o" :mod true} {:type :follow-link-under-cursor}]` (reserve `Cmd+Shift+O`).

---

## 5. Test Plan

### Unit
- `test/plugins/navigation_test.cljc`
  - Adjust multi-line expectations.
  - Add fold/zoom-aware navigation and emoji column persistence cases.
  - Verify boundary navigation no longer throws.
- `test/plugins/smart_editing_test.cljc`
  - Enter inside code fence stays within block.
  - Assert block/page ref branches set expected ops.
- `test/plugins/editing_test.cljc`
  - Ensure merge/delete preserve stored columns.
  - Add `merge-with-prev-migrates-children-test` mirroring the existing `merge-with-next` coverage.
- `test/plugins/struct_test.cljc`
  - Outdenting leaves right siblings untouched; placement matches logical/direct expectations.
- `test/plugins/selection_test.cljc`
  - Escape/background click dispatch `:selection :clear` when not editing.
- `test/plugins/link_actions_test.cljc` (new)
  - `:follow-link-under-cursor` moves to page or block; no-op for plain text.

### Integration / Browser
- Update `test/e2e/critical-fixes.spec.js` (or add new Playwright spec) to cover:
  - Logical outdenting scenario (matching Logseq).
  - Boundary navigation without exceptions.
  - Deselection via Escape and background click.
  - Backspace merge retains child visibility.
  - `Cmd+O` follows references under the caret (page + block cases).

---

## 6. Acceptance Criteria

1. Arrow navigation (vertical & horizontal) matches Logseq across folded/zoomed, multi-line, and emoji-heavy blocks.
2. Enter key respects context: code fence newline, list continuation, reference navigation.
3. Shift+Tab implements logical outdenting without reparenting siblings; direct mode available once config wiring lands.
4. Cursor column memory survives round-trips and merges.
5. Escape/background click clears selection outside edit mode.
6. Updated unit + integration tests pass and guard against regressions.
7. Backspace merges never orphan children; descendants remain visible under the merged block immediately.
8. `Cmd+O` follows the reference under the caret just like Logseq (page or block).

---

## 7. Rollout Notes

- Implement in stages: cursor memory + navigation → outdenting/backspace fixes → deselection → Enter/link handling.
- Keep intent interfaces backward-compatible; introduce optional fields rather than breaking existing ops.
- After each stage, verify parity manually in Logseq (macOS) to confirm the feel matches expectations.
