# Tiptap Integration Proposal - Architectural Evaluation Context

## Executive Summary

**Proposal**: Replace the current contenteditable + mock-text cursor detection system with Tiptap (ProseMirror-based editor).

**Claimed Benefits**:
1. Eliminate brittle cursor instrumentation (mock-text scaffolding)
2. CRDT infrastructure via @tiptap/extension-collaboration + Yjs
3. Server rendering for AI agents via static renderer
4. Better cursor preservation across operations

## Current Architecture

### Event-Sourced Kernel (Pure Data)

```clojure
;; Three-op primitives
{:op :create :id "a" :type :block :props {:text "Hello"}}
{:op :place  :id "a" :under :doc :at :last}
{:op :update :id "a" :props {:text "World"}}

;; Transaction pipeline: Normalize → Validate → Apply → Derive
;; All state changes flow through kernel.api/dispatch
```

**Key Characteristics**:
- Immutable EDN operations (not DOM mutations)
- REPL-first development (pure functions)
- Property-based testing with random op sequences
- Canonical DB shape: `{:nodes {id {:type :props}} :children-by-parent ...}`
- Framework-agnostic core (thin UI adapters)

### View Layer (Replicant + Nexus)

```clojure
;; Replicant: Pure hiccup → DOM diffing (NOT React)
[:span {:contentEditable true
        :replicant/on-render (fn [{:replicant/keys [node]}]
                               (.focus node)
                               (set-cursor-position! node cursor-pos)
                               (update-mock-text! node text))}]

;; Nexus: Data-driven action dispatch
(on-intent [[:editing/navigate-up {:block-id "a" :cursor-offset 5}]])
```

**Current Pain Points** (from `docs/CONTENTEDITABLE_DEBUGGING.md` + `components/block.cljs`):
1. **Mock-text scaffolding**: 200 LOC to detect cursor row position for navigation
2. **Cursor preservation**: `__lastAppliedCursorPos` guard pattern to avoid reapply loops
3. **Focus management**: Manual `.focus()` calls in `:replicant/on-render`
4. **Selection synchronization**: Browser selection ↔ kernel selection state
5. **E2E testing fragility**: Playwright `pressKeyOnContentEditable()` helper required

### Philosophy (from `VISION.md`)

> Build → Learn → Extract → Generalize
> **Not: Theorize → Propose → Analyze → Repeat**

- "AI-native ops" - LLMs generate operations, not DOM
- "Kernel OS" metaphor - progressive lowering (Intent → Core Algebra → View Diff)
- Framework-agnostic core: swap React/Replicant for anything
- Protocols at edges, NOT in kernel
- "If you can swap the renderer and your intents stay identical... you didn't build Yet Another UI Framework; you built the LLVM of UI"

## Tiptap Proposal Details

### What Tiptap Provides

1. **ProseMirror Core**:
   - Document model: hierarchical node tree (similar to our kernel)
   - Transactions: immutable operations (like our EDN ops)
   - State management: single source of truth
   - Schema validation: typed nodes/marks

2. **Cursor & Selection**:
   - Built-in selection tracking (no mock-text needed)
   - TextSelection, NodeSelection, AllSelection primitives
   - Decorations for remote cursors (CRDT-ready)

3. **CRDT Infrastructure**:
   - `@tiptap/extension-collaboration` + Yjs awareness
   - Remote cursor overlays out of the box
   - Per-client undo/redo

4. **Server Rendering**:
   - Static renderer for HTML/Markdown snapshots
   - No hidden browser needed for AI agents

### Integration Sketch

```javascript
// Mount Tiptap inside Replicant component
[:div#tiptap-editor
 {:replicant/on-mount
  (fn [{:replicant/keys [node remember]}]
    (let [editor (new Editor
                   {:element node
                    :content initial-content
                    :onTransaction (fn [txn]
                                     ;; Map ProseMirror transaction → EDN ops
                                     (on-intent (prosemirror->edn txn)))})]
      (remember editor)))}]
```

**Bridge Strategy**:
- Tiptap transactions → EDN operations (one-way adapter)
- Kernel state changes → `editor.commands.setContent()` (reverse adapter)
- Preserve event-sourced semantics (Tiptap as view layer only)

## Evaluation Criteria

### 1. Architectural Alignment

**Current constraints** (from `CLAUDE.md` + `VISION.md`):
- ✅ Pure kernel: no protocols, no async
- ✅ Canonical DB shape owned by kernel
- ✅ Framework-agnostic core (adapters at edges)
- ✅ Event sourcing: immutable EDN ops
- ✅ REPL-first: property-based testing

**Questions**:
- Does Tiptap integration preserve framework-agnostic core?
- Can ProseMirror transactions map cleanly to EDN ops?
- What happens to REPL-first testing if editor state lives in Tiptap?
- Does this violate "adapters at edges only"?

### 2. Complexity Trade-offs

**Current system**:
- Mock-text: ~200 LOC, brittle but debuggable
- Pure ClojureScript: no external dependencies
- Full control over cursor logic

**Tiptap system**:
- ProseMirror: ~40KB (minified), large API surface
- TypeScript/JavaScript interop overhead
- Commercial extensions (some paid)
- Debugging: ProseMirror devtools + Replicant devtools

**Questions**:
- Is the mock-text pain worth avoiding?
- What's the total dependency weight?
- How do we debug Tiptap ↔ kernel boundary?

### 3. Migration Risk

**Current state**:
- 44 functional requirements (FRs) in `resources/specs.edn`
- E2E tests for cursor/selection behaviors (`test/e2e/navigation-selection-parity.spec.js`)
- Logseq parity specs (`docs/specs/LOGSEQ_PARITY_EVO.md`)

**Migration path**:
1. Prototype bridge (single block editor)
2. Verify cursor-preservation E2Es (NAV-BOUNDARY-LEFT-01, etc.)
3. Incremental feature migration
4. Regression testing full FR matrix

**Questions**:
- Can we incrementally adopt or is it all-or-nothing?
- What breaks during transition?
- How do we test the bridge layer?

### 4. AI-Native Alignment

**Current workflow** (from `VISION.md`):
- LLMs generate EDN operations (not DOM)
- Operations are inspectable/replayable
- AI agents patch events via REPL

**Tiptap workflow**:
- Static renderer: HTML/Markdown snapshots for agents
- ProseMirror transactions: JSON (not EDN)
- Schema: TypeScript types (not Clojure specs)

**Questions**:
- Does Tiptap improve AI-native tooling or complicate it?
- Can we preserve EDN operation ledger?
- How does static renderer integrate with kernel snapshots?

### 5. Alternative Approaches

**Option A: Refine mock-text**
- Extract boundary detection to utility namespace
- Add property-based tests for cursor logic
- Simplify with Range API improvements

**Option B: Minimal ProseMirror**
- Use ProseMirror core directly (no Tiptap)
- Custom schema matching kernel model
- Smaller API surface, more control

**Option C: Custom cursor tracking**
- Replace mock-text with Range.getBoundingClientRect()
- Keep contenteditable + Replicant
- Minimal dependencies

**Option D: Tiptap (as proposed)**
- Full framework swap
- Accept ProseMirror dependency
- Leverage ecosystem extensions

## Code Snippets

### Current Mock-Text Implementation

```clojure
;; src/components/block.cljs:18-49
(defn- update-mock-text!
  "Update hidden mock-text element with current contenteditable content and position.
   This enables cursor row position detection (Logseq technique)."
  [elem text]
  (when (and elem (.-getBoundingClientRect elem))
    (when-let [mock-elem (js/document.getElementById "mock-text")]
      ;; Position mock-text to match the editing element
      (let [rect (.getBoundingClientRect elem)
            top (.-top rect)
            left (.-left rect)
            width (.-width rect)]
        (set! (.. mock-elem -style -top) (str top "px"))
        (set! (.. mock-elem -style -left) (str left "px"))
        (set! (.. mock-elem -style -width) (str width "px")))
      ;; Update content with character spans
      (let [content (str text "0")
            chars (seq content)]
        (set! (.-innerHTML mock-elem) "")
        (doseq [[idx c] (map-indexed vector chars)]
          (let [span (.createElement js/document "span")]
            (.setAttribute span "id" (str "mock-text_" idx))
            (if (= c \newline)
              (do (set! (.-textContent span) "0")
                  (.appendChild span (.createElement js/document "br")))
              (set! (.-textContent span) (str c)))
            (.appendChild mock-elem span)))))))

(defn- detect-cursor-row-position
  "Detect if cursor is on first/last row of contenteditable.
   Returns {:first-row? bool :last-row? bool}
   Uses character position in mock-text instead of range rect for accuracy
   with wrapped text."
  [elem]
  (when elem
    (let [selection (.getSelection js/window)]
      (when (and selection (> (.-rangeCount selection) 0))
        (let [char-index (loop [node (.createTreeWalker js/document elem 4 nil)
                                index 0]
                           (if-let [text-node (.nextNode node)]
                             (if (= text-node (.-focusNode selection))
                               (+ index (.-focusOffset selection))
                               (recur node (+ index (.-length text-node))))
                             index))
              mock-elem (js/document.getElementById "mock-text")
              mock-span-before (when (and mock-elem (pos? char-index))
                                 (aget (.-children mock-elem) (dec char-index)))
              tops (get-mock-text-tops)
              cursor-top (if mock-span-before
                           (.-top (.getBoundingClientRect mock-span-before))
                           (first tops))]
          {:first-row? (and (seq tops) (= (first tops) cursor-top))
           :last-row? (and (seq tops) (= (last tops) cursor-top))})))))
```

### Current Cursor Preservation Guard

```clojure
;; Pattern described in docs/RENDERING_AND_DISPATCH.md:330
;; Guard cursor placement with __lastAppliedCursorPos pattern

:replicant/on-render
(fn [{:replicant/keys [node]}]
  (.focus node)
  (when-let [cursor-pos (q/cursor-position db)]
    ;; Only apply if different from last applied
    (when (not= cursor-pos (.-__lastAppliedCursorPos node))
      (set-cursor-position! node cursor-pos)
      (set! (.-__lastAppliedCursorPos node) cursor-pos)))
  (update-mock-text! node (.-textContent node)))
```

### Kernel Transaction Pipeline

```clojure
;; src/kernel/api.cljc:110-147
(defn dispatch*
  "Dispatch an intent with full trace output (for REPL/agents).
   Returns {:db :issues :trace} for debugging and introspection."
  [db intent {:keys [history/enabled?]}]
  (let [{:keys [ops]} (intent/apply-intent db intent)
        all-ephemeral? (every? ephemeral-op? ops)
        record? (and (not (false? enabled?))
                     (not all-ephemeral?))
        db0 (if record? (H/record db) db)]
    ;; Fast path: skip derive for fully ephemeral transactions
    (tx/interpret db0 ops {:tx/skip-derived? all-ephemeral?})))

;; Transaction pipeline (kernel.transaction/interpret)
;; 1. Normalize: Filter no-ops, resolve position anchors
;; 2. Validate: Check schema, invariants (cycles, missing refs)
;; 3. Apply: Execute via three primitives (create, place, update)
;; 4. Derive: Recompute indexes (:parent-of, :next-id-of, :prev-id-of)
```

## Questions for Evaluation

### Strategic Questions

1. **Does Tiptap preserve the "LLVM of UI" vision?**
   - Can we still swap renderers if we depend on ProseMirror?
   - Is Tiptap an adapter at the edge or does it invade the core?

2. **Is the mock-text complexity the *real* problem?**
   - Are we solving cursor detection or architectural misfit?
   - Would refining contenteditable be simpler?

3. **What's the true migration cost?**
   - How many of our 44 FRs break during transition?
   - Can we run old and new systems in parallel?

4. **CRDT: Do we need it?**
   - Is collaboration a near-term goal or distant feature?
   - Are we paying for features we won't use?

### Tactical Questions

1. **Tiptap → EDN mapping:**
   - Can ProseMirror transactions map 1:1 to our ops?
   - What about non-text operations (indent, move, delete)?

2. **Testing impact:**
   - How do we test the bridge layer?
   - Do E2E tests become simpler or more complex?

3. **REPL workflow:**
   - Can we still test operations in isolation?
   - Does Tiptap state interfere with time-travel debugging?

4. **Performance:**
   - What's the bundle size impact?
   - Does ProseMirror diffing conflict with Replicant diffing?

## Success Metrics

**If adopting Tiptap, we should see**:
1. ✅ Mock-text code deleted (~200 LOC)
2. ✅ E2E cursor tests simplified (no `pressKeyOnContentEditable` helper)
3. ✅ Cursor preservation bugs reduced (quantified via E2E flakiness)
4. ✅ Framework-agnostic core preserved (can still swap view layer)
5. ✅ REPL-first workflow intact (operations testable in isolation)

**Red flags**:
1. 🚨 Bundle size > 100KB added
2. 🚨 Kernel operations leak ProseMirror types
3. 🚨 E2E test complexity increases
4. 🚨 REPL workflow disrupted (can't test ops without Tiptap instance)
5. 🚨 Migration breaks > 10 existing FRs

## Recommendation Request

**Evaluate this proposal against**:
1. **Correctness first** (CLAUDE.md: "this is user study data")
2. **Easy to debug** (event log must be inspectable)
3. **Simple to reason about** (avoid clever tricks)
4. **Acceptable tradeoffs**: Slower performance for clearer code
5. **Red flags**: Mutation of shared state, complex abstractions without clear benefit

**Judge which approach better fits these priorities**:
- **Option A**: Adopt Tiptap (as proposed)
- **Option B**: Refine mock-text (incremental improvement)
- **Option C**: Use ProseMirror directly (minimal dependency)
- **Option D**: Custom cursor tracking (contenteditable + Range API)

Focus on:
- Long-term architectural alignment
- Migration risk vs. benefit
- Debuggability and maintainability
- Fit with "Build → Learn → Extract → Generalize" philosophy
