# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Event-sourced UI kernel with declarative operations and generative AI tooling. Pure data transformation library for UI state management using ClojureScript.

**Philosophy**: Build → Learn → Extract → Generalize (not: Theorize → Propose → Analyze → Repeat)

Key characteristics:
- Event sourcing: All changes as immutable EDN operations
- REPL-first development workflow
- AI-native design: LLMs generate operations, not DOM
- Framework-agnostic core with thin UI adapters
- Correctness over performance, simple over clever
- Docs entrypoint: open `docs/DX_INDEX.md` → read `docs/LOGSEQ_SPEC.md` (ground truth) → skim `docs/LOGSEQ_PARITY_EVO.md` (Evo guardrails) → use triads in `docs/logseq_behaviors.md` when implementing.

## Essential Commands

### Development

```bash
# Start development (ALWAYS use this)
npm start                  # Clean + watch CLJS + watch CSS (prevents stale errors)

# Fast restart (skip clean - only when cache is fresh)
npm run dev:fast           # Watch CLJS + watch CSS

# Manual cache clear
npm run clean              # Clear shadow-cljs + bb caches

# Production build
npm run build              # Clean + release blocks-ui + minified CSS
```

**Important**: Always use `npm start` for development. It runs **watch mode** which prevents "stale output" errors. Never use `npx shadow-cljs compile` directly.

### Testing

```bash
# Unit tests (ClojureScript via shadow-cljs)
bb test                    # Compile + run full suite (shadow :test)
bb test:view               # Hiccup/view-only tests (<1s)
bb test:int                # Render→action integration tests
bb test-watch              # Watch entire suite
bb test-watch:view         # Watch view tier only
bb test-watch:int          # Watch integration tier only
bb test:e2e NAV-...        # Filter Playwright specs by scenario ID

# E2E tests (Playwright)
bb e2e                     # Run all E2E tests
bb e2e-watch               # Watch mode with UI
bb e2e-debug               # Run with Playwright debugger
bb e2e-headed              # Run with visible browser
bb e2e-report              # Open last test report
bb e2e-a11y                # Accessibility tests only
bb e2e-visual              # Visual regression tests (Percy)
```

### Quality Gates

```bash
bb lint                    # Run clj-kondo linter
bb check                   # Lint + compile check (full quality gate)
bb check-deps-sync         # Verify deps.edn and shadow-cljs.edn match
bb lint:e2e-keyboard       # Check E2E tests for problematic keyboard usage
bb lint:fr-tests           # Report FR ↔ test coverage (add -- --strict to fail on gaps)
bb fr-audit                # Audit FR coverage (fails if critical FRs uncited)
bb fr-matrix               # Generate FR_MATRIX.md coverage dashboard
```

### Cache & Index Management

```bash
bb clean                   # Clear all caches + semantic search index
bb index                   # Rebuild ck semantic embeddings index
```

### REPL

```bash
# Start shadow-cljs and connect via your editor
# Then load REPL utilities:
(require '[repl :as repl])
(repl/init!)               # Load core namespaces + clojure+ enhancements

# Quick health check
bb repl-health             # Run diagnostics (requires running REPL)
```

### AI-Assisted Development

```bash
# Get bird's-eye view of codebase with Gemini 2.5 Pro
# Generate full src context and analyze with long-context LLM
bb repomix                 # Creates repomix-output.txt with full codebase
# Then share repomix-output.txt with Gemini 2.5 Pro for architectural analysis
```

**Tip**: Use `bb repomix` + Gemini 2.5 Pro's 2M token context for high-level codebase understanding, architectural decisions, and pattern analysis.

**Auto overview**: Every push generates a `source-auto-overview*.md` artifact (check the workspace or CI run). Grab the latest version for a quick, human-readable summary before diving in.

## Architecture

### Core Layers

```
src/kernel/          # Pure kernel: db, ops, transaction, schema, errors
src/plugins/         # Intent handlers: navigation, editing, selection, refs
src/shell/           # UI adapters: Replicant components
src/keymap/          # Keybinding definitions and dispatch
src/parser/          # Block refs, page refs, embeds
src/components/      # Replicant UI components
resources/specs.edn  # FR registry (44 functional requirements)
resources/plugins.edn   # Declarative plugin manifest consumed by shell.plugin-manifest
resources/demo-pages.edn# Blocks UI seed data (load via shell.demo-data)
dev/spec_registry.cljc  # FR loader + validation
dev/test_scanner.cljc   # Test verification coverage scanner
```

### Transaction Pipeline

All state changes flow through a strict pipeline:

1. **Normalize**: Filter no-ops, resolve position anchors
2. **Validate**: Check schema, invariants (cycles, missing refs)
3. **Apply**: Execute via three primitives (create, place, update)
4. **Derive**: Recompute indexes (`:parent-of`, `:next-id-of`, `:prev-id-of`, traversal orders)

```clojure
;; Three-op kernel primitives
{:op :create   :id "a" :type :block :props {:text "Hello"}}
{:op :place    :id "a" :under :doc :at :last}
{:op :update   :id "a" :props {:text "World"}}
```

### Canonical DB Shape

**IMPORTANT (2025-11-21)**: Session state moved to separate atom. DB now contains only persistent document graph.

```clojure
;; Database (persistent document only)
{:nodes {"id" {:type :block :props {...}}}
 :children-by-parent {:doc ["a" "b"] "a" ["c"]}
 :roots [:doc :trash]  ; :session removed - now separate atom
 :derived {:parent-of {"a" :doc}
           :next-id-of {"a" "b"}
           :prev-id-of {"b" "a"}
           :index-of {"a" 0 "b" 1}
           :pre {"a" 0 "b" 1}  ; pre-order traversal
           :post {"a" 2 "b" 1}
           :id-by-pre {0 "a" 1 "b"}}}

;; Session atom (ephemeral UI state) - see shell/session.cljs
{:cursor {:block-id nil :offset 0}
 :selection {:nodes #{} :focus nil :anchor nil}
 :buffer {:block-id nil :text "" :dirty? false}
 :ui {:folded #{}
      :zoom-root nil
      :editing-block-id nil
      :cursor-position nil}
 :sidebar {:right []}}
```

**Critical**:
- Derived indexes are recomputed automatically. Never mutate them directly.
- Session state queries use `kernel.query` functions with session parameter: `(q/selection session)` not `(q/selection db)`
- Buffer updates go directly to session via `session/swap-session!` (no intent)

### Intent → Operations Pattern

UI components dispatch intents, plugins calculate operations:

```
User Action
    ↓
Component (Replicant)      # Dispatch intent
    ↓
Plugin (Intent Handler)    # Calculate operations
    ↓
Kernel (Transaction)       # Apply + derive
    ↓
Component Re-renders       # Replicant diffs DOM
```

Components never mutate state directly. They describe what happened via intents.

### Multi-Step Operations: Macro Pattern

For operations where step N depends on results of step N-1, use the **macro pattern** (`src/macros/`):

```clojure
;; Problem: Delete block, then select previous block
;; Single-pass plugins can't see intermediate state

;; Solution: Macro simulates on scratch DB
(ns macros.editing
  (:require [macros.script :as script]))

(defn smart-backspace [db {:keys [id]}]
  (:ops
    (script/run db
      [;; Step 1: Delete block
       {:type :delete :id id}

       ;; Step 2: Function sees result of step 1
       (fn [db-after-delete]
         (when-let [prev (get-in db-after-delete [:derived :prev-id-of id])]
           [{:type :select :id prev}]))])))
```

**How it works:**
1. Run steps on scratch DB (throwaway copy)
2. Each step sees real intermediate state
3. Accumulate normalized ops
4. Commit all ops atomically to real DB (one undo entry)

**When to use:**
- Multi-step with dependencies (step 2 needs result of step 1)
- Example: Smart backspace, paste multi-line, batch operations

**When NOT to use:**
- Single-pass logic (one intent → ops)
- No dependency on intermediate state

See `src/macros/script.cljc` for implementation, `test/macros/` for examples.

### Replicant (View Layer)

- Treat components as **pure render functions**. All persistent state/governance lives in the kernel.
- Event handlers are simple functions that dispatch Nexus actions. Never call `handle-intent` (or mutate DB) directly from a component.
- Lifecycle hooks (`:replicant/on-mount`, `:replicant/on-render`, `:replicant/on-unmount`) are the only place you may touch the DOM (focus, selection, mock text). Guard cursor placement with the `__lastAppliedCursorPos` pattern described in `docs/RENDERING_AND_DISPATCH.md`.
- `set-dispatch!` must stay wired so lifecycle hooks fire; leave it alone unless you know what you're doing.

**CRITICAL**: Always use `:replicant/key` (not `:key`) for conditional elements:
```clojure
;; ❌ WRONG - Replicant doesn't check :key
[:span.edit {:key (str id "-edit")} ...]

;; ✅ CORRECT - Replicant uses :replicant/key for element identity
[:span.edit {:replicant/key (str id "-edit")} ...]
```

Without proper `:replicant/key`, Replicant may reuse DOM elements when switching between modes (e.g., edit ↔ view), causing `:on-mount` to not fire. See `docs/REPLICANT_KEY_FIX.md` for details.

**Nexus workflow:**
1. Component receives a DOM event → computes context (cursor offsets, row info).
2. Component calls `(nexus/dispatch! [:editing/navigate-up payload])`.
3. Nexus handler translates the action into an intent and routes it through the kernel.

This guarantees one dispatch per event and keeps DOM-only facts close to the component. See `docs/RENDERING_AND_DISPATCH.md` + `docs/logseq_behaviors.md` before touching keyboard logic.

## Common Gotchas

### Constants & IDs

```clojure
;; ❌ WRONG: Root IDs are NOT prefixed
{:op :place :id "a" :under :root-trash :at :last}

;; ✅ CORRECT: Use bare keywords
{:op :place :id "a" :under :trash :at :last}

;; ❌ WRONG (outdated): Session state no longer lives in DB!
(get-in db [:nodes "session/ui" :props :editing-block-id])

;; ✅ CORRECT: Use kernel.query with session atom
(require '[kernel.query :as q])
(q/editing-block-id session)
(q/selection session)
(q/folded? session "block-id")
```

**Root constants**: `:doc`, `:trash` (defined in `src/kernel/constants.cljc`)

**Session state** lives in a separate atom (see `shell/session.cljs`). Query via `kernel.query`.

### Keyboard & Selection
- Use the Nexus dispatcher for **all** keyboard actions. Do not add new handlers directly to `handle-global-keydown` or components without routing through Nexus.
- Editing-mode arrow keys live exclusively in `components/block.cljs`. Extending selection requires the mock-text boundary helpers—duplicate work elsewhere will cause cursor jumps.
- For new behaviors, add Playwright coverage that asserts both DOM selection (`window.getSelection()`) and kernel selection state. Pure DB tests are not enough for cursor/selection bugs.

### Text Selection Utilities

Use `util.text-selection` for contenteditable DOM operations:

```clojure
;; ❌ WRONG: Using element->text for input/paste handlers
(let [new-text (text-sel/element->text target)] ...)  ;; Adds trailing \n!

;; ✅ CORRECT: Use textContent for simple text extraction
(let [new-text (.-textContent target)] ...)

;; ✅ CORRECT: Use element->text only for cursor positioning with make-range
(text-sel/set-current-range! (text-sel/make-range node pos pos))
```

**Key functions**:
- `element->text`: Converts contenteditable to plain text with trailing newline (for DOM traversal)
- `get-position`: Returns cursor position info (`:position`, `:extent`, `:line`)
- `make-range`: Creates DOM Range for cursor positioning
- Use **`textContent`** for input handlers, **`element->text`** only with `make-range`

See `src/util/text_selection.cljs` for full API.

### Move "Climb" Semantics (Logseq Parity)

**Mod+Shift+Up/Down** implements boundary-aware "climb" behavior:

- **Climb Out**: When pressing `Mod+Shift+Up` on a first child (no previous sibling), the block "climbs out" to become a sibling of its parent, positioned immediately before the parent.
- **Descend Into**: When pressing `Mod+Shift+Down` on a last child (no next sibling), the block descends into the parent's next sibling as its first child.
- **Boundary Respect**: Blocks at doc level (top-level) cannot climb further; operations become no-ops.

```clojure
;; Example: Climb out behavior
;; Before: Parent → [Child A (first), Child B]
;; After Mod+Shift+Up on Child A: [Child A, Parent → [Child B]]

;; Example: Descend behavior
;; Before: [Parent → [Child C (last)], Uncle]
;; After Mod+Shift+Down on Child C: [Parent, Uncle → [Child C]]
```

**Implementation**: `plugins.struct/move-selected-up-ops` and `move-selected-down-ops` detect boundary conditions and re-parent blocks accordingly. Multi-selection is fully supported.

### Empty List Item Enter (Logseq Parity)

Pressing **Enter** on an empty list item (e.g., `- ` with no content) performs two operations in a single keystroke:

1. **Unformats** the current block (removes the list marker, leaving empty text)
2. **Creates a peer block** at the parent's level, positioned after the parent

This matches Logseq's one-step workflow for exiting nested list contexts.

```clojure
;; Example: Empty list Enter
;; Before: Parent → [Child with content, "- " (empty list marker)]
;; After Enter: Parent → [Child with content, (empty unformatted)], New Peer (cursor here)
```

**Implementation**: `plugins.smart_editing/context-aware-enter` handles the `:list-item` context, emitting `:update-node` (to clear marker) + `:create-node` + `:place` operations when content is blank.

### Replicant Keys

**Always use `:replicant/key`, never `:key`** for elements that conditionally render:

```clojure
;; ❌ WRONG - Replicant ignores :key
(if editing?
  [:span.edit {:key (str id "-edit")} ...]
  [:span.view {:key (str id "-view")} ...])

;; ✅ CORRECT - Replicant checks :replicant/key
(if editing?
  [:span.edit {:replicant/key (str id "-edit")} ...]
  [:span.view {:replicant/key (str id "-view")} ...])
```

**Why**: Replicant's `reusable?` function only checks `:replicant/key` (not `:key`). Without proper keys, elements with the same tag name get reused instead of recreated, preventing lifecycle hooks from firing. See `docs/REPLICANT_KEY_FIX.md`.

### Variable Shadowing

Avoid shadowing core Clojure vars:

```clojure
;; ❌ WRONG: Shadows clojure.core
(fn [db {:keys [char count num key val]}] ...)

;; ✅ CORRECT: Rename parameters
(fn [db {:keys [input-char total-count number k v]}] ...)
```

**Pre-commit hook blocks shadowed vars**. Run `bb lint` to detect before committing.

### Multi-Character Text Markers

Bold/italic use multi-char markers (`**`, `__`). Need substring matching:

```clojure
;; ❌ WRONG: Only handles single chars
(when (= (nth text cursor-pos) "*") ...)

;; ✅ CORRECT: Check substrings, sort by length
(some (fn [[open close]]
        (when (= (subs text start (+ start (count open))) open) ...))
      (sort-by (comp - count key) marker-pairs))
```

### Test ID Format

DB uses string IDs, not keywords:

```clojure
;; ❌ WRONG
(is (= :trash (get-in db [:derived :parent-of :block-a])))

;; ✅ CORRECT
(is (= :trash (get-in db [:derived :parent-of "block-a"])))
```

**See**: `docs/CODING_GOTCHAS.md` for complete list.

## Testing Strategy

### Unit Tests (Property-Based)

- Generate random operations, verify invariants hold
- Test with multiple random seeds for coverage
- Located in `test/` directory (`.cljc` files)
- Tag tests with FR metadata: `(deftest ^{:fr/ids #{:fr.xxx/yyy}} ...)`

```clojure
;; Reproduce property test failure
bb lint:scenarios          # Ensure docs/specs scenario IDs have tests

;; FR coverage tracking
(require '[kernel.intent :as intent])
(intent/full-audit)        # Show implementation + verification coverage
(intent/coverage-summary)  # High-level metrics
```

### E2E Tests (Playwright)

- Test actual browser behavior: cursor position, focus, keyboard navigation
- Use accessibility snapshots (not screenshots)
- Verify DOM state, not internal DB structure

**CRITICAL: Keyboard Events on `contenteditable` Elements**

Playwright's `page.keyboard.press()` does **NOT** reliably trigger keyboard event handlers on `contenteditable` elements. Always use the `pressKeyOnContentEditable()` helper from `test/e2e/helpers/keyboard.js`:

```javascript
// ❌ WRONG: May silently fail to trigger handlers
await page.keyboard.press('ArrowLeft');

// ✅ CORRECT: Guaranteed to dispatch events properly
import { pressKeyOnContentEditable } from './helpers/keyboard.js';
await pressKeyOnContentEditable(page, 'ArrowLeft');
```

Use `bb lint:e2e-keyboard` to detect problematic keyboard usage in tests.

```javascript
// Good: Test user-facing behavior
await expect(page.locator('[contenteditable="true"]')).toBeFocused();

// Bad: Couple to implementation
const db = await page.evaluate(() => window.DEBUG.state());
```

**See**: `docs/PLAYWRIGHT_MCP_TESTING.md` for complete E2E testing guide and keyboard helper API.

### Full Testing Stack Reference

For the complete philosophy (headless tiers, redundancy analysis, browser-only gaps) read `docs/TESTING_STACK.md`.

### REPL-First Debugging

**Preferred workflow** (30s vs 5min):

1. Reproduce issue in REPL
2. Test fix in REPL
3. Apply to code
4. Verify in REPL
5. Run test suite

```clojure
(require '[repl :as repl])
(repl/init!)

;; Load fixtures
(require '[fixtures :as fix])
(def db (fix/sample-db))

;; Test operations
(require '[kernel.api :as api])
(api/transact! db [{:op :create :id "a" :type :block :props {:text "test"}}])
```

## Key Documentation

- `docs/DX_INDEX.md` - Canonical doc map for humans + agents
- `VISION.md` - Project philosophy and architectural ideas
- `docs/RENDERING_AND_DISPATCH.md` - Replicant + Nexus reference (event handlers, lifecycle, dispatch data)
- `docs/logseq_behaviors.md` - Behavior triads (keymap slice, intent contract, scenario ledger)
- `docs/TESTING_STACK.md` - Unified testing guide (view/integration tiers, redundancy analysis, UX gap appendix)
- `docs/E2E_TESTING.md` - E2E testing patterns, helpers reference, troubleshooting
- `docs/STATE_MACHINE.md` - UI state machine (idle/selection/editing), intent validation
- `docs/CODING_GOTCHAS.md` - Common pitfalls (constants, shadowing, IDs)
- `docs/PLAYWRIGHT_MCP_TESTING.md` - E2E testing with MCP
- `dev/repl/init.cljc` - REPL utilities and initialization

## Design Constraints

- No protocols in kernel (just pure functions)
- No async in core (event sourcing is synchronous)
- Canonical DB shape owned by kernel
- Adapters at edges only (normalize/denormalize)
- Framework-agnostic core (swap React/Replicant for anything)

## Shadow-cljs Builds

```clojure
;; shadow-cljs.edn
:builds
  {:blocks-ui  ; Block editor UI (primary development build)
   :test       ; Unit tests (node-test)
   :frontend}  ; Legacy build
```

Use `:blocks-ui` build for primary development. REPL connects to `:frontend` by default (see `dev/repl/init.cljc`).

## Babashka Tasks

All development tasks available via `bb`:

```bash
bb help                    # Show all available tasks
```

Key task categories:
- Quality Gates: `lint`, `check`, `test`
- E2E Testing: `e2e`, `e2e-watch`, `e2e-debug`, `e2e-a11y`
- Cache Management: `clean`, `index`
- Development: `dev`, `repl-health`
