# Evo Logseq Parity Overlay

This document ties the canonical Logseq behavior spec (`docs/LOGSEQ_SPEC.md`) to Evo's implementation reality. Read the canonical spec first for user-facing truth, then use this overlay to understand how Evo mirrors those behaviors, what still gaps out, and which test layers guard each promise.

_Last sync:_ 2025‑11‑16 (after auto-overview refresh).

## 1. Reference Stack
- **Ground truth:** `docs/LOGSEQ_SPEC.md`
- **Triad scenarios:** `docs/logseq_behaviors.md`
- **Testing philosophy & commands:** `docs/TESTING_STACK.md`
- **Headless + browser tooling:** `docs/PLAYWRIGHT_MCP_TESTING.md`, `docs/TEXT_SELECTION.md`
- **Auto overview artifacts:** `source-auto-overview*.md` (generated every push; check workspace/CI)

## 2. Implementation Guardrails
Pulled directly from the last refactor of `LOGSEQ_EDITING_SELECTION_PARITY.md`.

1. **Single dispatcher rule** – Editing-context keys (Enter, ArrowUp/Down, Shift+Arrow) are owned by `src/components/block.cljs`. They dispatch Nexus actions containing DOM facts (cursor rows, selection collapse). Never bind these keys in `keymap/bindings_data.cljc` under `:editing`.
2. **Nexus wiring** – DOM handlers route through `shell/nexus.cljs`. Components emit `[:editing/navigate-up payload]`, while the global keymap feeds the same queue. That gives deterministic instrumentation and exactly one action per DOM event.
3. **Cursor guard** – Preserve the `__lastAppliedCursorPos` + mock-text pattern so Replicant doesn't stomp browser selections (see `.architect/RECURRING_PROBLEM_ANALYSIS.md` for historical context).
4. **Selection direction tracking** – `session/selection` stores `:direction`, `:anchor`, and `:focus`. Shift+Arrow extends/contracts incrementally instead of recomputing contiguous ranges. Shift+Click can continue to use contiguous visible ranges via `visible-blocks-in-dom-order`.
5. **Visibility filter** – Navigation/selection helpers must respect the active outline root (current page or zoom root) and skip folded descendants. Reuse `visible-blocks-in-dom-order {:root ... :skip-folded? true}` everywhere.

## 3. Gap Tracker & Regression Focus

| Gap ID | FR IDs | Area | Status | Notes / Tests |
|--------|--------|------|--------|---------------|
| **G-Nav-Visibility** | FR-Scope-01..03 | Visible-outline traversal | ❌ Open | `visible-blocks-in-dom-order` treats `:doc` as default root; must respect current page. Tests: `test/view/block_navigation_view_test.cljc`, `test/e2e/navigation.spec.js::page_scope`. |
| **G-Horizontal-DOM** | FR-NavEdit-02 | Left/Right boundary traversal | ❌ Open (LOGSEQ-PARITY-112) | Replace sibling-only adjacency with DOM-order traversal so ArrowLeft hops to parent at caret 0 and ArrowRight dives into first child. Scenario `NAV-BOUNDARY-LEFT-01` tracks. |
| **G-ShiftClick-Visibility** | FR-NavView-02 | Shift+Click range | ❌ Open | `calc-extend-props` still uses `doc-range`, so folded/out-of-page nodes enter selection. Needs visibility-aware helper. |
| **G-ShiftArrow-Seeding** | FR-NavEdit-04 | Editing boundary seed | ⚠ In progress | Boundary Shift+Arrow exits edit but doesn’t seed selection. Add `:selection/seed` intent before extend. |
| **G-Clipboard-Segments** | FR-Clipboard-02 | Paste semantics | ⚠ In progress | Multi-paragraph paste should split blocks while preserving list markers. See `handler/paste.cljs`. |
| **G-Pointer-Hover** | FR-Pointer-02 | Block ref hover preview | Deferred | Requires popover component + sidebar wiring. |
| **G-Slash / G-QuickSwitch** | FR-Slash-01..05 / FR-QuickSwitch-01 | Slash palette & quick switcher | ⚠ In progress | Inline palette wired via `:slash-menu/*` intents + tests (`plugins.slash-commands-test`). Still missing actual quick switcher UI. |
| **G-Undo-Cursor** | FR-Undo-01 | Undo/redo caret memory | ⚠ In progress | Kernel/history stores hints but cursor restoration still flaky (see `.architect/RECURRING_PROBLEM_ANALYSIS.md`). |

**Resolved gaps:** idle guard, base navigation memory, Shift+Arrow direction tracking, Enter/Shift+Enter parity (see commits referenced in latest `source-auto-overview*.md`).

### 3.1 Regression Notes (2025‑11‑15 verification)
- **Navigation scope isolation:** ensure `visible-blocks-in-dom-order` treats current page as implicit zoom root.
- **Horizontal DOM traversal:** `:navigate-to-adjacent` must use DOM-order traversal instead of sibling-only indexes.
- **Shift+Click visibility:** replace `tree/doc-range` usage with visibility-aware helper.
- **Shift+Arrow seeding:** dispatch selection seed before extend when exiting edit at boundary.

## 4. Triad & Testing Workflow

1. **Triad entries** (`docs/logseq_behaviors.md`) connect Keymap slices → Intent contracts → Scenario ledger rows. Use scenario IDs (e.g., `NAV-BOUNDARY-LEFT-01`) for code comments, tests, and bug reports.
2. **Testing tiers** (`docs/TESTING_STACK.md`):
   - `bb test:view` – hiccup/view-only checks (<1s).
   - `bb test:int` – action extraction + integration loop.
   - `bb test:e2e NAV-…` – Playwright headless scenario runs; use `bb e2e-headed` for debugging.
3. **Quality gates** – `bb lint`, `bb check`, and the scenario-specific `bb scripts/lint_scenarios.clj` enforce that every triad row has matching tests before merges.
4. **Auto overview** – include the latest `source-auto-overview*.md` link in PR descriptions so agents know whether a gap already landed elsewhere.

Use this overlay as the living bridge between Logseq truth and Evo's implementation/testing strategy. When behavior changes upstream, update `docs/LOGSEQ_SPEC.md` first, then refresh the tables and guardrails here so downstream contributors know exactly what remains.
