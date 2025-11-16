# Logseq Behavior Specs

Logseq parity behaviors serve as end-to-end tests for the Evo kernel’s plugin surface. This spec consolidates the previous parity tables, spec gaps, and navigation notes into the triad format (Keymap slice → Intent contract → Scenario ledger). Each trio captures the *input trigger*, the *system contract*, and the *user-visible outcome* so we preserve the exact “feel” of Logseq without locking in implementation details.

## Parity Snapshot (2025-11-14)

### Editing Behaviors
| Feature | Logseq | Evo | Status |
|---------|--------|-----|--------|
| **Enter** (plain) | Create block below, cursor at start | ✅ Same | ✅ |
| **Shift+Enter** | Insert `\n` in current block | ✅ Same | ✅ |
| **Enter at position 0** | Create block ABOVE | ✅ Same | ✅ |
| **Enter in list** | Continue list pattern | ✅ Same | ✅ |
| **Enter in empty list** | Unformat + create peer | ✅ Same | ✅ |
| **Backspace at start** | Merge with previous | ✅ Same | ✅ |
| **Backspace in empty** | Delete block | ✅ Same | ✅ |
| **Delete at end** | Merge with next (child-first) | ✅ Same | ✅ |

### Navigation Behaviors
| Feature | Logseq | Evo | Status |
|---------|--------|-----|--------|
| **Arrow Up/Down** | Cursor memory across blocks | ✅ Same | ✅ |
| **Arrow Left/Right** | Navigate to adjacent at boundary | ✅ Same | ✅ |
| **Arrows with selection** | Collapse selection first | ✅ Same | ✅ |
| **Ctrl+P/N** | Emacs up/down | ✅ Same | ✅ |
| **Ctrl+A** | Beginning of line | ✅ Same | ✅ **NEW** |
| **Ctrl+E** | End of line | ✅ Same | ✅ **NEW** |
| **Alt+A** | Beginning of block | ✅ Same | ✅ **NEW** |
| **Alt+E** | End of block | ✅ Same | ✅ **NEW** |

### Selection Behaviors
| Feature | Logseq | Evo | Status |
|---------|--------|-----|--------|
| **Shift+Arrow (editing)** | Text OR block at boundary | ✅ Same | ✅ |
| **Shift+Arrow (view)** | Incremental extend/contract | ✅ Same | ✅ **NEW** |
| **Plain arrow (view)** | Replace with adjacent | ✅ Same | ✅ |
| **Direction tracking** | Preserves expand/contract mode | ✅ Same | ✅ **NEW** |
| **Contraction** | Removes trailing block | ✅ Same | ✅ **NEW** |

Edge cases validated: empty/single/long text blocks, Unicode + RTL, caret position 0/end, folded parents, zoomed outlines, and selection direction flips.

## Implementation Artifacts
- **Key files**: `src/components/block.cljs`, `src/plugins/selection.cljc`, Playwright suites under `test/e2e/`.
- **Testing**: 23 Playwright specs (`npm run e2e -- foundational-editing-parity`) + headless CLJC tests summarized in `docs/TESTING_STACK.md`.
- **Manual checklist** (kept for quick smoke validation):
  - Type text, press Shift+Enter → newline inline, no new block.
  - Multi-line block: Ctrl+A/E stay within line, Alt+A/E jump to block boundaries.
  - Select 3 blocks with Shift+Down → Shift+Up contracts incrementally.
  - Type emoji 🔥🚀 → arrows navigate without corruption.
  - Empty block focuses immediately and accepts typing.

## Triad Entries

### Behavior: Arrow Left parent hop (NAV-BLOCK-ARROWLEFT-PARENT)
```
feature_id: NAV-BLOCK-ARROWLEFT-PARENT
owners: Editing & Navigation squad
status: ❌ open (LOGSEQ-PARITY-112)
source_refs:
  - keymap: keymap/bindings_data.cljc
  - component: src/components/block.cljs
  - intent: src/shell/navigation.cljs
  - docs: docs/RENDERING_AND_DISPATCH.md#editing-keys
```

#### 1. Keymap Coverage Slice
| Key | Context | Handler (Logseq ref) | Handler File | Evo Intent | Status | Notes |
|-----|---------|----------------------|--------------|------------|--------|-------|
| ArrowLeft | Editing | `keydown-move-caret-left` | `logseq/editor.cljs:3245` | `:navigate-to-adjacent` | ◐ | Needs parent hop when caret=0 |

#### 2. Intent Contract Sheet
- **Intent**: `:navigate-to-adjacent`
- **Triggers**: ArrowLeft (caret position = 0), ArrowRight (caret = block end)
- **Preconditions**: `:editing? true`; `visible-blocks-in-dom-order` respects fold + zoom scope; active block not root when moving left
- **Inputs**: `{:block-id :caret-pos :direction :outline-root-id}`
- **Behavior**:
  1. Calculate DOM-ordered block list filtered to visible outline root.
  2. When `direction = :left` and caret at 0, select previous visible block (parent or sibling above).
  3. Emit ops:
     - `[:ui/set :editing-block target-id]`
     - `[:ui/set :cursor-position (if parent? :max 0)]`
     - `[:ui/clear-selection]`
- **Side effects**: Exit child edit mode, enter parent at text end, preserve outline root, clear selection.
- **Parity reference**: `logseq/editor.cljs:3245-3320`
- **Tests owed**: covered by `test/view/block_navigation_view_test.cljc`, `test/integration/navigation_scenarios_test.cljc`, and `test/e2e/navigation.spec.js :: NAV-BOUNDARY-LEFT-01`
- **Open issues**: Share DOM-order helper with vertical navigation so page scope stays consistent.

- **Scenario ID**: NAV-BOUNDARY-LEFT-01  
  **Given** Folded block A with first child B; page = Journals 2025-11-16; B in edit mode at caret 0; no selection.  
  **When** User presses ArrowLeft.  
  **Then** Edit focus moves to A, caret lands at end of A, selection stays empty, outline root unchanged.  
  **User feel** Flow continues upward with no flicker; user never sees the caret jump to the top of B or collapse the fold.  
  **Edge cases** Folded parents, zoomed views, parent with long text, child preceded by sibling selection.  
  **Tests** `test/view/block_navigation_view_test.cljc :: scenario-nav-boundary-left-01`; `test/integration/navigation_scenarios_test.cljc :: scenario-nav-boundary-left-01`; `test/e2e/navigation.spec.js :: NAV-BOUNDARY-LEFT-01 (expected-fail until LOGSEQ-PARITY-112)`  
  **Status** ❌ (cursor remains in child B).

_Add additional behaviors below using `docs/templates/TRIAD.md` as more parity items are documented._
