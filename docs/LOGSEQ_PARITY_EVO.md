# Evo Logseq Parity Overlay

This document ties the canonical Logseq behavior spec (`docs/LOGSEQ_SPEC.md`) to Evo's implementation reality. Read the canonical spec first for user-facing truth, then use this overlay to understand how Evo mirrors those behaviors, what still gaps out, and which test layers guard each promise.

_Last sync:_ 2025‑12‑04 (Navigation scope and Right→first child now working).

## 1. Reference Stack
- **Ground truth:** `docs/LOGSEQ_SPEC.md`
- **Triad scenarios:** `docs/logseq_behaviors.md`
- **Testing philosophy & commands:** `docs/TESTING_STACK.md`
- **Headless + browser tooling:** `docs/PLAYWRIGHT_MCP_TESTING.md`, `docs/TEXT_SELECTION.md`
- **Auto overview artifacts:** `source-auto-overview*.md` (generated every push; check workspace/CI)

## 2. Implementation Guardrails

1. **Single dispatcher rule** – Editing-context keys (Enter, ArrowUp/Down, Shift+Arrow) are owned by `src/components/block.cljs`. They dispatch Nexus actions containing DOM facts (cursor rows, selection collapse). Never bind these keys in `keymap/bindings_data.cljc` under `:editing`.
2. **Nexus wiring** – DOM handlers route through `shell/nexus.cljs`. Components emit `[:editing/navigate-up payload]`, while the global keymap feeds the same queue. That gives deterministic instrumentation and exactly one action per DOM event.
3. **Cursor guard** – Preserve the `__lastAppliedCursorPos` + mock-text pattern so Replicant doesn't stomp browser selections (see `.architect/RECURRING_PROBLEM_ANALYSIS.md` for historical context).
4. **Selection direction tracking** – `session/selection` stores `:direction`, `:anchor`, and `:focus`. Shift+Arrow extends/contracts incrementally instead of recomputing contiguous ranges. Shift+Click can continue to use contiguous visible ranges via `visible-blocks-in-dom-order`.
5. **Visibility filter** – Navigation/selection helpers must respect the active outline root (current page or zoom root) and skip folded descendants. Reuse `visible-blocks-in-dom-order {:root ... :skip-folded? true}` everywhere.

## 3. Block Selection Gaps

### 3.1 Critical Behavior Differences (2025‑12‑04)

| Gap | What Logseq Does | What Evo Does | Priority |
|-----|------------------|---------------|----------|
| **Shift+Click with folded blocks** | Only selects visible blocks, skips folded descendants | Selects folded descendants (uses `doc-range` without visibility check) | **MEDIUM** - Breaks selection boundary expectations |

### 3.2 What Works (No Gap)

✅ **Incremental selection extension** - Shift+Arrow extends in same direction, contracts in opposite direction
✅ **Selection anchor/focus tracking** - Session stores `:anchor`, `:focus`, `:direction`
✅ **Block selection at boundaries** - Shift+Arrow at first/last row exits edit and extends block selection
✅ **Undo cursor restore** - Undo/redo restores cursor position
✅ **Shift+Arrow in multi-line blocks** - Text selection line-by-line when NOT at boundary; block selection only at first/last row
✅ **Left at block start → parent** - Left arrow at start navigates to parent block at end position
✅ **Right at block end → first child** - Right arrow at end navigates to first child at start position (DOM order)
✅ **Navigation scope (page boundaries)** - Arrow navigation respects current page, won't jump to adjacent pages

### 3.3 Implementation Focus Areas

**Remaining work for block selection feel parity:**

1. **Visibility-aware selection** (`plugins.selection/calc-extend-props`)
   - Shift+Click should filter out folded blocks
   - Replace `doc-range` with visibility check

## 4. Triad & Testing Workflow

1. **Triad entries** (`docs/logseq_behaviors.md`) connect Keymap slices → Intent contracts → Scenario ledger rows. Use scenario IDs (e.g., `NAV-BOUNDARY-LEFT-01`) for code comments, tests, and bug reports.
2. **Testing tiers** (`docs/TESTING_STACK.md`):
   - `bb test:view` – hiccup/view-only checks (<1s).
   - `bb test:int` – action extraction + integration loop.
   - `bb test:e2e NAV-…` – Playwright headless scenario runs; use `bb e2e-headed` for debugging.
3. **Quality gates** – `bb lint`, `bb check`, and the scenario-specific `bb scripts/lint_scenarios.clj` enforce that every triad row has matching tests before merges.
4. **Auto overview** – include the latest `source-auto-overview*.md` link in PR descriptions so agents know whether a gap already landed elsewhere.

Use this overlay as the living bridge between Logseq truth and Evo's implementation/testing strategy. When behavior changes upstream, update `docs/LOGSEQ_SPEC.md` first, then refresh the tables and guardrails here so downstream contributors know exactly what remains.
