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
- Docs entrypoint: open `docs/DX_INDEX.md` → read `docs/STRUCTURAL_EDITING.md` (core editor spec) → skim `docs/LOGSEQ_UI_FEATURES.md` (Logseq-specific UI) → use triads in `docs/LOGSEQ_BEHAVIOR_TRIADS.md` when implementing.

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

**Agent gotcha — `bb` alias**: On this machine `bb` is shell-aliased to `bun build`. When running babashka tasks from Bash, use `\bb` (escapes the alias) or the absolute path. A bare `bb test` will fail with a `bun build` usage error.

### Testing

```bash
# Unit tests (ClojureScript via shadow-cljs)
bb test                    # Compile + run full suite (shadow :test)
bb test:view               # Hiccup/view-only tests (<1s)
bb test:int                # All integration tests under test/integration
bb test:kernel             # Kernel + script tests only
bb test-watch              # Watch entire suite
bb test-watch:view         # Watch view tier only
bb test-watch:int          # Watch integration tier only

# E2E smoke (fast, ~5s) — use during development
npm run test:e2e:smoke
```

See `docs/TESTING.md` for testing philosophy and the buffer vs DB gap.

### Quality Gates

```bash
bb lint                    # Run clj-kondo linter
bb check                   # Lint + architecture verification + compile check
bb check:kernel            # Kernel purity scan + kernel/script tests
bb arch:verify             # Verify runtime/bootstrap docs stay aligned
bb lint:specs              # Validate specs.edn schema (fast, no compile)
bb lint:fr-tests           # Report FR ↔ test coverage (add -- --strict to fail on gaps)
bb fr-audit                # Audit FR coverage (fails if critical FRs uncited)
bb fr-matrix               # Generate FR_MATRIX.md coverage dashboard
bb lint:intents            # Regenerate intent catalog (--update to write)
bb docs:verify             # Verify file references in docs/DX_INDEX.md exist
```

### Cache & Index Management

```bash
bb clean                   # Clear all caches
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
# Get bird's-eye view of codebase with Gemini 3.1 Pro
# Generate full src context and analyze with long-context LLM
bb repomix                 # Creates repomix-output.txt with full codebase
# Then share repomix-output.txt with Gemini 3.1 Pro for architectural analysis
```

**Tip**: Use `bb repomix` + Gemini 3.1 Pro's 1M token context for high-level codebase understanding, architectural decisions, and pattern analysis.

**Auto overview**: Every push generates `dev/overviews/AUTO-*.md` artifacts.
Use them only for rough orientation; canonical repo truth lives in
`README.md`, `docs/DX_INDEX.md`, and `AGENTS.md`.

## Architecture

### Core Layers

```
src/kernel/          # Pure kernel: db, ops, transaction, schema
src/plugins/         # Intent handlers: navigation, editing, selection
src/shell/           # UI adapters: Replicant components
src/keymap/          # Keybinding definitions and dispatch
src/parser/          # Page refs
src/components/      # Replicant UI components
src/spec/registry.cljc  # FR loader + validation
resources/specs.edn  # FR registry (50 FRs with :scenarios keys)
resources/failure_modes.edn # Known bugs/anti-patterns with symptoms and fixes
```

### Transaction Pipeline

All state changes flow through a strict pipeline:

1. **Normalize**: Filter no-ops, resolve position anchors
2. **Validate**: Check schema, invariants (cycles, missing refs)
3. **Apply**: Execute via three primitives (`create-node`, `place`, `update-node`)
4. **Derive**: Recompute indexes (`:parent-of`, `:next-id-of`, `:prev-id-of`, traversal orders)

```clojure
;; Three-op kernel primitives.
;; :create-node and :update-node mutate a node's identity/content.
;; :place mutates tree structure (children-by-parent), not the node.
;; The -node suffix marks node-ops; :place is bare because it's a tree-op.
{:op :create-node :id "a" :type :block :props {:text "Hello"}}
{:op :place       :id "a" :under :doc :at :last}
{:op :update-node :id "a" :props {:text "World"}}
```

### Canonical DB Shape

**Session state lives in a separate atom** (`shell/view_state.cljs`). The DB contains only the persistent document graph.

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

;; View state atom (ephemeral UI state) - see shell/view_state.cljs
{:cursor {:block-id nil :offset 0}
 :selection {:nodes #{} :focus nil :anchor nil}
 :ui {:folded #{}
      :zoom-root nil
      :editing-block-id nil
      :cursor-position nil
      :keep-edit-on-blur false
      :document-view? false}
 :sidebar {:right []}}
```

**Critical**:
- Derived indexes are recomputed automatically. Never mutate them directly.
- View state queries use `kernel.query` functions with view-state parameter: `(q/selection view-state)` not `(q/selection db)`
- View state mutations use `shell.view-state` API functions (e.g., `set-cursor-position!`, `set-current-page!`, `buffer-set!`) - avoid raw `swap-view-state!` outside view_state.cljs

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

### Multi-Step Operations: Script Pattern

For operations where step N depends on results of step N-1, use the **script pattern** (`src/scripts/`):

```clojure
;; Problem: Create and place a block, then tell the caller which ID to focus
;; Single-pass plugins can't see intermediate state

;; Solution: Script simulates on scratch DB
(ns scripts.editing
  (:require [scripts.script :as script]))

(defn insert-block [db {:keys [under at text]}]
  (let [new-id (str (random-uuid))
        result (script/run db
                 [{:op :create-node
                   :id new-id
                   :type :block
                   :props {:text (or text "")}}
                  {:op :place :id new-id :under under :at at}])]
    {:ops (:ops result)
     :new-id new-id}))
```

**How it works:**
1. Run steps on scratch DB (throwaway copy)
2. Each step sees real intermediate state
3. Accumulate normalized ops
4. Return structural facts the outer handler can use for session updates
5. Commit all ops atomically to real DB (one undo entry)

**When to use:**
- Multi-step with dependencies (step 2 needs result of step 1)
- Example: Smart backspace, paste multi-line, batch operations

**When NOT to use:**
- Single-pass logic (one intent → ops)
- No dependency on intermediate state

See `src/scripts/script.cljc` for implementation, `test/scripts/` for examples.

### Replicant (View Layer)

- Treat components as **pure render functions**. All persistent state/governance lives in the kernel.
- Event handlers are simple functions that dispatch intents via `on-intent`. Never mutate DB directly from a component.
- Lifecycle hooks (`:replicant/on-mount`, `:replicant/on-render`, `:replicant/on-unmount`) are the only place you may touch the DOM (focus, selection, mock text). Guard cursor placement with the `__lastAppliedCursorPos` pattern described in `docs/RENDERING_AND_DISPATCH.md`.
- `set-dispatch!` must stay wired so lifecycle hooks fire; leave it alone unless you know what you're doing.

**CRITICAL**: Always use `:replicant/key` (not `:key`) for conditional elements:
```clojure
;; ❌ WRONG - Replicant doesn't check :key
[:span.edit {:key (str id "-edit")} ...]

;; ✅ CORRECT - Replicant uses :replicant/key for element identity
[:span.edit {:replicant/key (str id "-edit")} ...]
```

Without proper `:replicant/key`, Replicant may reuse DOM elements when switching between modes (e.g., edit ↔ view), causing `:on-mount` to not fire. See `docs/CODING_GOTCHAS.md` for details.

**Intent dispatch workflow:**
1. Component receives a DOM event → computes context (cursor offsets, row info).
2. Component calls `(on-intent {:type :navigate-with-cursor-memory :direction :up ...})`.
3. `handle-intent` routes directly through the kernel via `executor/apply-intent!`.

All intents use `{:type ...}` map format. See `docs/RENDERING_AND_DISPATCH.md` + `docs/LOGSEQ_BEHAVIOR_TRIADS.md` before touching keyboard logic.

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

**Session state** lives in a separate atom (see `shell/view_state.cljs`). Query via `kernel.query`.

**CRITICAL: Query function signatures vary!** Not all query functions take a session parameter:
```clojure
;; ❌ WRONG: Passing session to function that doesn't expect it
(let [next-block (q/next-block-dom-order db session block-id)]  ; WRONG!
  ...)

;; ✅ CORRECT: Check signature first
(let [next-block (q/next-block-dom-order db block-id)]  ; Only [db current-id]
  ...)
```

Always check `src/kernel/query.cljc` for the actual signature. ClojureScript won't error on wrong arity - it will silently use wrong values as parameters, causing `null` returns.

### Keyboard & Selection
- `shell.executor/apply-intent!` is the canonical runtime entrypoint. `shell.editor` composes the shell and routes app-global shortcuts through `shell.global-keyboard`.
- `shell.global-keyboard` owns app-global shortcuts and non-editing selection policy. `components.block` owns contenteditable keyboard behavior.
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

See `src/utils/text_selection.cljs` for full API.

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

**Implementation**: `plugins.context-editing/context-aware-enter` handles the `:list-item` context, emitting `:update-node` (to clear marker) + `:create-node` + `:place` operations when content is blank.

### Variable Shadowing

Avoid shadowing core Clojure vars:

```clojure
;; ❌ WRONG: Shadows clojure.core
(fn [db {:keys [char count num key val]}] ...)

;; ✅ CORRECT: Rename parameters
(fn [db {:keys [input-char total-count number k v]}] ...)
```

**Pre-commit hook blocks shadowed vars**. Run `bb lint` to detect before committing.

#### Rename-Escape (bare reference to a renamed core fn)

A sneakier variant of shadowing: you renamed a local to avoid the
clash, but left a bare reference to the core fn on one call site.

```clojure
;; ❌ WRONG: `key-name` was chosen to avoid shadowing, but `key`
;; is still used below — CLJS resolves it to `cljs.core/key`, and
;; `(str "" cljs.core/key)` emits the compiled fn source:
;;   "function cljs$core$key(map_entry){return cljs.core._key(map_entry);}"
(let [key-name (:key event)]
  (printable-key? event key)            ; ← silently wrong
  (handle-intent {:char key}))          ; ← silently wrong

;; ✅ CORRECT: use the rename everywhere.
(let [key-name (:key event)]
  (printable-key? event key-name)
  (handle-intent {:char key-name}))
```

`bb lint:rename-escape` catches this specific class (file binds
`<prefix>-name` + bare `<prefix>` used outside a fn that has `<prefix>`
as a parameter). Runs as part of `bb check`.

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

```bash
# FR scenario coverage report
bb lint:fr-tests           # Reports scenario coverage; add -- --strict to fail on gaps
```

```clojure
;; FR coverage at the REPL
(require '[kernel.intent :as intent])
(intent/full-audit)        ; Show implementation + verification coverage
(intent/coverage-summary)  ; High-level metrics
```

### Explicit Harness Bootstrap

Truthful isolated tiers must load their own fixtures.
- Integration namespaces use `[harness.runtime-fixtures :as runtime-fixtures]` plus `(use-fixtures :once runtime-fixtures/bootstrap-runtime)`.
- Kernel/script tests that inspect intent metadata use `[harness.intent-fixtures :as intent-fixtures]` and register only the minimal test intents they need.
- Never rely on plugin registration or intent metadata leaking in from unrelated namespace load order.

### E2E Tests (Playwright)

- Test actual browser behavior: cursor position, focus, keyboard navigation
- Use accessibility snapshots (not screenshots)
- Verify DOM state, not internal DB structure
- **Smoke tier** (`npm run test:e2e:smoke`): ~15 critical tests, ~5 seconds - use during development
- **Full suite** (`npm run test:e2e`): the full chromium project - use for PR validation
- **Artifact cleanup**: `bb e2e:clean` wipes `test-results/` and `playwright-report/` when they fill disk (Playwright doesn't cap these)

#### Test-mode harness gotchas

`?test=true` triggers `reset-to-empty-db!` in `src/shell/editor.cljs`, which sets **specific** defaults that shield other tests — journals tests especially have to work around them:

- `:journals-view?` is set to **false** (so tests land on `test-page`, not the journals list). Any spec that expects the journals view must enter it explicitly via `window.TEST_HELPERS.openJournalsView()`.
- `process-auto-trash-queue!` is a **no-op** in test mode. This prevents the 100 ms auto-trash queue from silently deleting empty fixture pages and stripping focus through `handle-delete-page`'s session update. If you're writing a test that *wants* to exercise auto-trash, dispatch `:auto-trash-empty-page` directly.

#### Playwright timing gotchas (learned the hard way)

- **Don't combine "first char via global-keyboard" with "multi-char via `keyboard.type`" in one test.** The view→edit transition schedules contenteditable focus via Replicant's on-mount hook; sequential keystrokes race that scheduling and drop chars under parallel worker load. Either press every char with individual `keyboard.press` calls, or enter edit mode first, *then* type the full string.
- **Read block text from the DB, not the DOM.** View-mode renders a ZWSP (`​`, `​`) for empty blocks so they show in the a11y tree. `textContent`-based helpers return that ZWSP and break `.toBe('')` assertions. Use `window.TEST_HELPERS.getBlockText(id)`.
- **`clj->js` emits kebab-string keys.** `session.ui.current_page` is not set — use `session.ui['current-page']`. The existing helpers fall back through both forms, which is a symptom of the trap, not a pattern to copy.

**CRITICAL: Keyboard Events on `contenteditable` Elements**

Playwright's keyboard API **DOES** work on contenteditable, but you must use the keyboard helpers and proper syntax:

```javascript
import { 
  pressKeyOnContentEditable, pressKeyCombo,
  pressHome, pressEnd,  // Cross-platform Home/End
  pressWordLeft, pressWordRight  // Cross-platform word navigation
} from './helpers/keyboard.js';

// ✅ CORRECT: Use keyboard helpers
await pressKeyOnContentEditable(page, 'Enter');
await pressKeyCombo(page, 'ArrowDown', ['Shift']);  // Shift+ArrowDown
await pressHome(page);  // Cmd+Left on Mac, Home on Windows/Linux

// ❌ WRONG: Playwright's modifiers option doesn't work!
await page.keyboard.press('ArrowDown', { modifiers: ['Shift'] });  // Modifiers ignored!

// ❌ WRONG: Home/End don't work on macOS!
await page.keyboard.press('Home');  // Scrolls page on Mac, doesn't move cursor
```

**Why keyboard helpers?**
- Verify contenteditable is focused before pressing keys
- Use Playwright's `'+'` notation internally (which works, unlike `modifiers` array)
- Better error messages if element isn't focused

Use `bb lint:e2e-keyboard` to detect problematic keyboard usage in tests.

```javascript
// Good: Test user-facing behavior
await expect(page.locator('[contenteditable="true"]')).toBeFocused();

// Bad: Couple to implementation
const db = await page.evaluate(() => window.DEBUG.state());
```

**See**: `docs/TESTING.md` for E2E helpers and keyboard API.

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

;; Test operations via transaction pipeline
(require '[kernel.transaction :as tx])
(tx/interpret db [{:op :create-node :id "a" :type :block :props {:text "test"}}
                  {:op :place :id "a" :under :doc :at :last}])
;; => {:db <new-db> :issues [] :trace [...]}
```

## Key Documentation

See `docs/DX_INDEX.md` for the full doc map with task routing. Key files:

- `docs/GOALS.md` - Project mission, strategy, success metrics
- `README.md` - Project quick start and repo structure
- `docs/STRUCTURAL_EDITING.md` - Core editor spec
- `docs/CODING_GOTCHAS.md` - Common pitfalls
- `dev/repl/init.cljc` - REPL utilities

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
   :frontend}  ; Browser REPL build (connects via dev/repl/init.cljc)
```

Use `:blocks-ui` for primary development. `:frontend` is used for browser REPL connections.

## Babashka Tasks

All development tasks available via `bb`:

```bash
bb help                    # Show all available tasks
```

Key task categories:
- Quality Gates: `lint`, `check`, `test`
- E2E Testing: `e2e`, `e2e:watch`, `e2e:debug`, `e2e:a11y`
- Cache Management: `clean`, `index`
- Development: `dev`, `repl-health`

## Constitution

> **Human-protected.** Agent may propose changes but must not modify without explicit approval.

### Generative Principle

> Minimize the spec surface while maximizing kernel power — legible to both humans and LLMs.

Every design decision, refactoring choice, and documentation edit should be evaluated against: "Does this make the kernel smaller, more correct, or more legible?" If none of the three, don't do it.

### Project Mode: Solid Outliner With Clean Extension Surface

*Updated 2026-04-22. Supersedes earlier "Extraction" and "Reference Implementation + Trace Substrate" framings, which were over-claims.*

Evo is a solid outliner with a clean, data-driven extension surface. Kernel stays pure so the code is readable and agents can patch by emitting intents. That's the whole thing. See `docs/GOALS.md` for details.

Agent work should trend toward:
1. **Kernel purity.** Zero imports from `shell/`/`components/`/`keymap/` in `src/kernel/`.
2. **Clean extension surface.** Three registries (intent, derived, render) + session atom. Adding a feature = registering handlers, not editing core.
3. **Deletion.** Remove dead code, consolidate redundant patterns.
4. **Test portability.** Property tests and specs self-contained with the kernel.

Do NOT: add new outliner features, chase Logseq parity, or build speculative infrastructure (trace recording, replayable datasets, library extraction, universal adapters, LLVM-of-UI IRs). Bug fixes and extension-surface cleanup are welcome.

### Principles

1. **Kernel purity over feature breadth.** The kernel must have zero UI dependencies. Every import from `shell/`, `components/`, or `keymap/` in kernel code is a bug.
2. **Three-op invariant.** All state changes reduce to `create-node`, `place`, `update-node`. If a new operation can't be expressed as a composition of these three, the design is wrong.
3. **REPL-verifiable in 30 seconds.** Any kernel behavior must be demonstrable in the REPL with a fixture DB. If it requires a browser to test, it's not kernel — it's shell.
4. **Specs are the product.** `resources/specs.edn`, `docs/STRUCTURAL_EDITING.md`, and the kernel source ARE the publishable artifacts. Keep them precise, correct, and self-contained.
5. **Docs: facts not plans.** Keep documentation that states invariants, specs, and verified behaviors. Delete executed plans, stale proposals, and session artifacts. Git preserves history.
6. **Tests travel with the kernel.** Property tests in `test/kernel/` and `test/scripts/` must work without shell, view, or component dependencies.
7. **Commit freely.** Same auto-commit policy as other projects. Granular semantic commits after every logical change.

### Autonomy Boundaries

**Autonomous (do without asking):**
- Commit after completing a logical change
- Delete dead code, executed plans, stale docs
- Refactor kernel internals for clarity
- Fix bugs in existing code
- Run quality gates (`bb check`, `bb test`)

**Ask first:**
- Adding new features or capabilities
- Changing the three-op kernel API
- Modifying `resources/specs.edn` (the FR registry)
- Modifying this Constitution section
- Modifying `docs/GOALS.md`

### Known Limitations

- Render-registry (Tier 2 of the parser refactor plan) not yet built; per-format rendering currently lives in a `case` in `block.cljs`.
- Library extractability is not measurable — gated on a concrete consumer requesting it.
