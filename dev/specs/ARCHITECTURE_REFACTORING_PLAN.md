# Architecture Refactoring Plan - Round 2 Analysis

**Status:** Approved by 3 AI architects (Gemini 2.5 Pro, GPT-5.1, Grok-4)
**Date:** 2025-01-17
**Context:** Round 2 architectural review with matcher-combinators + editscript integration

## Executive Summary

Three AI architects reviewed Evo's architecture in two rounds:

- **Round 1:** Analyzed codebase metrics (47 namespaces, 8,603 LOC, 247 functions) and identified core improvements
- **Round 2:** Integrated new library capabilities (matcher-combinators, editscript) and refined proposals

**Key Finding:** The new tools don't change WHAT to do—they make it SAFE to do it.

### Consensus Recommendations

All three models strongly agree:

1. **Keep 3-op kernel** - editscript reinforces this decision, doesn't replace it
2. **matcher-combinators becomes default testing approach** - enables safe refactoring
3. **editscript is for tooling/dev/testing** - NOT core runtime semantics
4. **Tier 1 priorities remain:** visible-order index, session operations, intent dispatch

---

## Current Architecture Context

### Metrics (from arch-lens)

- **47 namespaces**, 8,603 LOC, 247 functions
- **Plugins:** 3,497 LOC (40% of codebase)
- **Complexity hotspot:** `plugins.editing` 263-line anonymous function (complexity 49-55)
- **Query API:** 32 functions in `kernel.query` (suggests duplication)
- **Zero protocols/multimethods** (pure data-oriented)

### 3-Op Kernel (Current)

```clojure
;; Three primitives for ALL state changes
{:op :create   :id "a" :type :block :props {:text "Hello"}}
{:op :place    :id "a" :under :doc :at :last}
{:op :update   :id "a" :props {:text "World"}}
```

**Rationale:** Domain semantics > generic patches. Operations encode WHAT happened (create-node, place, update-node), not HOW data changed (path edits).

### Transaction Pipeline

```
User Action
    ↓
Component (Replicant)      # Dispatch intent
    ↓
Plugin (Intent Handler)    # Calculate operations
    ↓
Kernel (Transaction)       # Normalize → Validate → Apply → Derive
    ↓
Component Re-renders       # Replicant diffs DOM
```

**Derived Indexes (Automatic):**
- `:parent-of` - Child → parent lookup
- `:next-id-of`, `:prev-id-of` - Sibling navigation
- `:index-of` - Position within siblings
- `:pre`, `:post` - Traversal orders
- `:id-by-pre` - Reverse index for order queries

---

## Available Libraries

### matcher-combinators (Nubank)

**Purpose:** Flexible structural testing for nested data structures

**Key Features:**
- Partial matching (maps default to `embeds`, not exact equality)
- Composable matchers (`equals`, `in-any-order`, `prefix`, `via`)
- Beautiful diffs showing exact mismatches
- Predicates in assertions: `{:props {:count odd?}}`

**Example:**
```clojure
;; Instead of brittle exact equality:
(is (= {:derived {:parent-of {"b1" :doc "b2" :doc}
                  :next-id-of {"b1" "b2"}
                  ...}}
       result))

;; Flexible structural assertion:
(is (match? {:derived {:parent-of {"b1" :doc}}}  ; Only care about b1's parent
            result))
```

### editscript (Juji)

**Purpose:** Minimal diffs between nested data structures

**Key Features:**
- A* algorithm for optimal diffs (structure-preserving)
- Quick algorithm for speed (100x faster than A*)
- Serializable edits (plain vectors)
- `patch` to apply diffs

**Example:**
```clojure
(def before-db {:nodes {"b1" {:text "a"}}})
(def after-db  {:nodes {"b1" {:text "b"} "b2" {:text "c"}}})

(def diff (e/diff before-db after-db))
(e/get-edits diff)
;; => [[[nodes "b1" :props :text] :r "b"]
;;     [[nodes "b2"] :+ {:text "c"}]]

(= after-db (e/patch before-db diff)) ;; true
```

---

## Integration Patterns

### 1. matcher-combinators for Testing (Tier 0 - IMMEDIATE)

**Implementation:** Add as test dependency, use `match?` in all new tests.

#### Pattern A: Test Derived Indexes Without Brittleness

```clojure
(ns evo.kernel.derived-test
  (:require [clojure.test :refer [deftest is]]
            [matcher-combinators.clj-test :refer [match?]]
            [matcher-combinators.matchers :as m]))

(deftest visible-order-index-basic
  (let [db (setup-db-with-folded-nodes)]
    ;; Assert order STARTS with these nodes, ignore rest
    (is (match? {:derived {:visible-order (m/prefix ["b1" "b3" "b5"])}}
                db))

    ;; Assert ONLY b5's parent, ignore all other relationships
    (is (match? {:derived {:parent-of {"b5" "b3"}}}
                db))))
```

**Benefits:**
- Tests won't break when adding unrelated nodes
- Focus on semantic contracts, not exact output
- Reduced test maintenance overhead

#### Pattern B: Test Operation Shape and Intent Semantics

```clojure
(deftest indent-selection-emits-structural-ops
  (let [session {:selection {:nodes ["b1" "b2"]}}
        ops (plugins.editing/handle-intent
              {:intent :selection/indent
               :session session
               :db some-db})]
    ;; Assert critical fields, ignore metadata
    (is (match?
          [[:place {:id "b1" :parent "prev-sibling"}]
           [:place {:id "b2" :parent "prev-sibling"}]]
          ops))

    ;; Use in-any-order when order doesn't matter
    (is (match?
          (m/in-any-order
            [[:update-node (m/embeds {:id "b1" :props (m/embeds {:folded? true})})]
             [:update-node (m/embeds {:id "b2"})]])
          ops))))
```

#### Pattern C: Property-Based + Structural Testing

```clojure
(require '[clojure.test.check.generators :as gen]
         '[clojure.test.check.properties :as prop])

(defspec topo-order-respects-parent 50
  (prop/for-all [ops (gen/ops-sequence)]
    (let [[db _] (k/transact (k/empty-db) ops)
          ix (d/build-indexes db)]
      ;; Validate topsort with predicate
      (match? {:visible-order {:by-parent (m/via validate-topsort anything)}}
              ix))))
```

### 2. editscript for Undo/Redo (Tier 2)

**Implementation:** Diff-based history instead of inverse operations.

#### Core Pattern: Store Diffs, Not Inverse Ops

```clojure
;; In kernel.history namespace
(defonce history-stack (atom {:undo [] :redo []}))

(defn record-transaction! [before-db after-db]
  (let [forward-diff (e/diff before-db after-db)
        backward-diff (e/diff after-db before-db)]
    (swap! history-stack #(-> %
                              (update :undo conj {:forward forward-diff
                                                 :backward backward-diff})
                              (assoc :redo [])))))  ; Clear redo on new op

(defn undo! [current-db]
  (when-let [entry (peek (:undo @history-stack))]
    (swap! history-stack #(-> % (update :undo pop) (update :redo conj entry)))
    (e/patch current-db (:backward entry))))

(defn redo! [current-db]
  (when-let [entry (peek (:redo @history-stack))]
    (swap! history-stack #(-> % (update :redo pop) (update :undo conj entry)))
    (e/patch current-db (:forward entry))))
```

**Integration Point:** `kernel.api/transact!` captures before/after and calls `record-transaction!`.

**Simplification:**
- Deletes all manual `(invert-op op)` functions
- Undo reduces to patching with pre-computed diff
- Perfectly captures FR-Undo-01 (restore caret/selection) IF session state is in db

**Prerequisites:** Formalize session operations (move all UI state to `:session` key in db).

### 3. editscript for Dev Tooling (Tier 0)

**Implementation:** Auto-generate operations from state diffs for prototyping and testing.

#### diff→ops Compiler

```clojure
;; In a dev or test namespace
(defn diff->ops [diff]
  (->> (e/get-edits diff)
       (mapv (fn [[path op-type value]]
               (condp = op-type
                 :+ (if (= 2 (count path))  ; [:nodes "id"]
                      [:create-node (second path) value]
                      [:place ...])  ; More complex placement logic
                 :- [:delete-node (second path)]
                 :r (let [[_ node-id & props-path] path]
                      [:update-node node-id (assoc-in {} props-path value)]))))))

;; Usage in REPL
(def before-db {:nodes {"b1" {:text "a"}}})
(def after-db  {:nodes {"b1" {:text "b"} "b2" {:text "c"}}})
(def diff (e/diff before-db after-db))

(diff->ops diff)
;; => [[:update-node "b1" {:props {:text "b"}}]
;;     [:create-node "b2" {:text "c"}]]
```

**Usage in Tests:**
```clojure
(let [db0 ...
      db-desired (-> db0
                     (assoc-in [:nodes "b1" :props :text] "New")
                     (update :nodes dissoc "b3"))  ; Deletion
      ops (diff->ops db0 db-desired)
      [db1 _] (k/transact db0 ops)]
  (is (= db-desired db1)))
```

**Benefits:**
- Demystifies the kernel for developers
- Generate test fixtures from examples
- Verify 3-op kernel is expressive enough
- Prototype complex behaviors using pure db transformations

### 4. editscript for Runtime (Tier 3 - Optional)

#### Pattern A: Audit/Sync Storage

```clojure
(defn step-with-delta [db ops]
  (let [before db
        [after effects] (k/transact db ops)
        diff (e/diff before after)]
    {:db after
     :effects effects
     :delta (e/get-edits diff)}))

;; For network sync on single-writer model:
;; - Keep Evo ops as CRDT-ish event log semantics
;; - Also expose "state channel" with editscript deltas for efficiency
```

**Important:** Do NOT replace Evo ops with editscript edits as core API. They're too low-level and lose domain semantics.

---

## Refactoring Opportunities

### 1. Decouple Session State for Undo (Tier 1)

**Problem:** Ephemeral UI state (selection, cursor, editing-block-id) is scattered or not part of transactional history.

**Solution:** Move all session state into `:session` key in main db map.

```clojure
;; Current structure
{:nodes {...}
 :derived {...}
 ;; Session state scattered in separate atoms
}

;; Proposed structure
{:nodes {...}
 :derived {...}
 :session {:ui {:editing-block-id "b1"
                :cursor-memory {...}}
           :selection {:nodes ["b2" "b3"]
                      :anchor "b2"
                      :focus "b3"
                      :direction :down}}}
```

**Benefits:**
- editscript can track session state transactionally
- Clean undo/redo of selection + cursor position
- Single source of truth for all application state

**Implementation:**
1. Create `session` namespace with explicit operations:
   - `enter-edit`, `exit-edit`
   - `set-selection`, `clear-selection`, `extend-selection`
   - `update-cursor-memory`
2. Implement as `:update-node` on session nodes
3. Use matcher-combinators to test state machine behaviors

### 2. Purify Semantic Operations (Tier 3)

**Problem:** Complex semantic operations have imperative logic with multiple transact! calls.

**Solution:** Refactor to pure functions that transform db map, then use editscript to extract ops.

```clojure
;; Before: Imperative function
(defn indent-selection! [db selection]
  (let [ops (calculate-indent-ops db selection)]
    (kernel/transact! db ops)))

;; After: Pure function + diffing
(defn indent-selection-transform [db selection]
  ;; Pure function returning new state
  (let [target-parent ...]
    (-> db
        (move-in-ordering ...)
        (update-derived-indexes ...))))

;; In plugin/intent handler:
(let [before-db @app-db
      after-db (indent-selection-transform before-db selection)
      diff (e/diff before-db after-db)
      ops (diff->ops diff)]
  (kernel/transact! before-db ops))
```

**Benefits:**
- Core logic trivial to test without mocking kernel
- Easier to reason about state transformations
- Separates "what should happen" from "how to encode it"

### 3. Solidify :visible-order Index with Matcher Safety Nets (Tier 1)

**Problem:** Current navigation helpers (32 functions in `kernel.query`) suggest duplication. No single index for "visible outline" filtering by zoom + folding.

**Solution:** Add `:visible-order` derived index, collapse query functions.

```clojure
;; Proposed derived index structure
{:derived {:visible-order {:by-parent {:doc ["b1" "b3" "b4"]
                                       "b1" ["b2"]}}
           :parent-of {...}
           :next-id-of {...}}}
```

**Test with matcher-combinators:**
```clojure
(deftest logseq-spec-FR-Move-01-indent
  (let [before-db {:nodes {"b1" {:text "parent"}
                           "b2" {:text "child 1"}
                           "b3" {:text "to be indented"}}
                   :ordering {:doc ["b1" "b3"]
                              "b1" ["b2"]}}
        ops (intent->ops :indent-selection {:selection #{"b3"}})
        after-db (kernel/transact! before-db ops)]

    ;; Assert structural facts with matchers
    (is (match? {:ordering {"b1" (m/in-any-order ["b2" "b3"])}}
                after-db))

    (is (match? {:derived {:parent-of {"b3" "b1"}
                           :prev-sibling-of {"b3" "b2"}}}
                after-db))))
```

**Implementation:**
1. Add `:visible-order` index computation in `kernel.derived`
2. Rewrite navigation helpers (`prev-visible`, `next-visible`, `ancestors-of`) on precomputed index
3. Lock down semantics with matcher-based tests
4. Simplify plugin code to "walk visible-order" calls

### 4. Intent Multimethod Dispatch (Tier 1)

**Problem:** 263-line anonymous function in `plugins.editing` (complexity 49-55) handles all intents imperatively.

**Solution:** Use multimethod dispatch with one handler per intent.

```clojure
;; Current: 263-line monolith
(fn [ctx]
  (case (:intent ctx)
    :selection/extend-prev (...)
    :editor/smart-split (...)
    ;; ... 50+ more cases
    ))

;; Proposed: Multimethod dispatch
(defmulti handle-intent (fn [ctx] (:intent ctx)))

(defmethod handle-intent :selection/extend-prev [{:keys [db session]}]
  ;; Small, focused handler
  ...)

(defmethod handle-intent :editor/smart-split [{:keys [db session editing-block-id]}]
  ;; Another focused handler
  ...)
```

**Test each with matchers:**
```clojure
(deftest shift-enter-selection-op
  (let [ctx {:session {:selection {:nodes ["b1" "b2"]}}
             :db db}
        ops (handle-intent (assoc ctx :intent :selection/open-in-sidebar))]
    (is (match?
          [[:update-node (m/embeds {:id :session/sidebar
                                    :props (m/embeds {:tabs (m/in-any-order ["b1" "b2"])})})]]
          ops))))
```

**Benefits:**
- Breaks up monolith into testable units
- Each intent has clear contract (ctx → ops)
- matcher-combinators allows changing session representation without breaking tests

### 5. Regression Fixtures with editscript (Tier 2)

**Problem:** Complex refactorings risk breaking subtle behaviors.

**Solution:** Record golden snapshots, use editscript diffs to verify behavior preservation.

```clojure
;; Workflow:
;; 1. Current implementation → run scenario → snapshot state → store
;; 2. Refactor implementation
;; 3. Re-run scenario, compute diff vs snapshot

(deftest shift-down-from-last-line-extends-selection
  (let [initial-state fixture/three-blocks-with-middle-editing
        final-state (simulate-keys initial-state [:shift :down])]

    ;; Assert semantic constraints with matchers
    (is (match?
          {:session {:selection {:nodes ["b2" "b3"]
                                 :anchor "b2"
                                 :focus "b3"
                                 :direction :down}
                     :ui {:editing-block-id nil}}}
          final-state))

    ;; Optional: Inspect diff when test fails
    (when-not (match? expected final-state)
      (prn (e/get-edits (e/diff initial-state final-state))))))
```

**Use for:** High-risk areas (selection, move/indent, paste, undo).

---

## Does editscript Change the 3-Op Kernel Assessment?

### Answer: NO - It REINFORCES It

**Gemini 2.5 Pro:**
> "The tension was never about the number of ops, but about the lack of layers. `editscript` clarifies the roles:
>
> 1. **Application State** (db map) - What editscript diffs
> 2. **Kernel API** (3 ops) - The ONLY sanctioned, transactional gateway
> 3. **Semantic Operations** - Compile down to 3 primitives
> 4. **Intents** - User actions mapping to semantic ops
>
> editscript operates BETWEEN these layers. It doesn't replace the kernel; it serves it."

**GPT-5.1:**
> "editscript is value-agnostic: it only knows paths, not domain meaning. Evo ops encode SEMANTICS (create-node, place, update-node) which:
>
> - Are the right abstraction for plugins
> - Map cleanly to Logseq behaviors
> - Are better basis for history, intent, cross-client compatibility
>
> Keep 3-op kernel as external contract. editscript helps generate/validate ops, not signal the kernel should become a generic 'patch' engine."

### Architectural Layers (Clarified)

```
┌─────────────────────────────────────────────────┐
│ Layer 4: Intents                                │
│ User actions (e.g., :press-enter, :indent)     │
└─────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────┐
│ Layer 3: Semantic Operations                    │
│ indent-blocks!, split-block!, move-selection!   │
│ (compile to Layer 2 primitives)                 │
└─────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────┐
│ Layer 2: Kernel API (3 ops)                     │
│ create-node, place, update-node                 │
│ Stable, auditable gateway with derived indexes  │
└─────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────┐
│ Layer 1: Application State (db map)             │
│ Canonical nested data structure                 │
└─────────────────────────────────────────────────┘

editscript operates BETWEEN layers:
- Generate Layer 2 ops from desired Layer 1 state (dev tooling)
- Record net effect of transaction for undo/redo
- Does NOT replace the kernel
```

---

## Testing Strategy

### Layered Testing with New Tools

```
Event → Nexus → Plugin → Ops → Kernel → DB + Derived + Session
```

**Layer 1: Kernel Tests (ops → db & derived)**
- Deterministic, pure functions
- Heavy use of matcher-combinators
- Example:
  ```clojure
  (deftest merge-blocks-backspace-at-start
    (let [[db0 _] (k/transact (k/empty-db) create-ops)
          [db1 _] (k/transact db0 (plugins.editing/backspace-at-start {...}))]
      (is (match? {:nodes {"b1" (m/embeds {:props {:text "foobar"}})}
                   :derived {:visible-order {:by-parent {:doc ["b1"]}}}}
                  db1))))
  ```

**Layer 2: Intent/Plugin Tests (intent + state → ops)**
- Use matchers to assert op shape and semantics
- Example:
  ```clojure
  (deftest shift-enter-selection-op
    (let [ctx {:session {:selection {:nodes ["b1" "b2"]}} :db db}
          ops (plugins.navigation/handle-intent
                (assoc ctx :intent :selection/open-in-sidebar))]
      (is (match?
            [[:update-node (m/embeds {:id :session/sidebar
                                      :props (m/embeds {:tabs (m/in-any-order ["b1" "b2"])})})]]
            ops))))
  ```

**Layer 3: Behavior Tests (Logseq scenarios)**
- Use editscript + matchers to validate full transitions
- Example:
  ```clojure
  (deftest shift-down-from-last-line-extends-selection
    (let [initial fixture/three-blocks-editing
          final (simulate-keys initial [:shift :down])]
      (is (match?
            {:session {:selection {:nodes ["b2" "b3"]
                                   :direction :down}
                       :ui {:editing-block-id nil}}}
            final))
      ;; Diagnostic: inspect diff when fails
      (let [diff (e/diff initial final)]
        (when-not (match? expected final)
          (prn (e/get-edits diff))))))
  ```

---

## Implementation Priorities

### Tier 0: Enable Tooling (IMMEDIATE - Do This NOW)

**1. Add matcher-combinators**
- **Action:** Add `[nubank/matcher-combinators "3.9.1"]` to `deps.edn`
- **Scope:** Test dependency only
- **Usage:** All new tests MUST use `match?` instead of `=`
- **File:** Update test namespaces to require `[matcher-combinators.clj-test :refer [match?]]`

**2. Add editscript**
- **Action:** Add `[juji/editscript "0.6.3"]` to `deps.edn`
- **Scope:** Dev/test dependency (optionally runtime for undo later)
- **Usage:** Create `dev/diff_ops.cljc` with diff→ops compiler for REPL use

**Why Immediate:** These tools enable SAFE execution of all Tier 1-3 work. They're the foundation.

### Tier 1: High-Impact Foundation (Next Sprint)

**3. Add :visible-order index**
- **Complexity:** Medium
- **LOC:** ~200 lines (kernel.derived changes, new query helpers)
- **Tests:** Lock down with matcher-combinators
- **Dependencies:** None
- **Impact:** Critical path for Logseq parity, simplifies 32-function query API
- **Files:**
  - `src/kernel/derived.cljc` - Add index computation
  - `src/kernel/query.cljc` - Refactor navigation helpers
  - `test/kernel/derived_test.cljc` - matcher-based tests

**4. Formalize Session Operations**
- **Complexity:** Medium-High
- **LOC:** ~300 lines (new session namespace, migrate state)
- **Tests:** State machine behaviors with matchers
- **Dependencies:** Prerequisite for clean undo (Tier 2)
- **Impact:** Single source of truth, enables transactional UI state
- **Files:**
  - `src/kernel/session.cljc` - New namespace with operations
  - `src/plugins/editing.cljc` - Migrate to session ops
  - `src/shell/blocks_ui.cljs` - Update to query session state

**5. Intent Multimethod Dispatch**
- **Complexity:** High (careful migration required)
- **LOC:** Break 263-line function into ~30-50 multimethods
- **Tests:** One test per intent handler (matcher-based)
- **Dependencies:** matcher-combinators (Tier 0)
- **Impact:** Maintainability, testability, breaks up complexity hotspot
- **Files:**
  - `src/plugins/editing.cljc` - Convert to multimethod
  - `test/plugins/editing_test.cljc` - Add per-intent tests

### Tier 2: Major Simplification (Following Sprint)

**6. Refactor Undo/Redo with editscript**
- **Complexity:** Medium
- **LOC:** ~150 lines (new history namespace, integrate with kernel)
- **Tests:** Undo/redo behaviors with matcher assertions
- **Dependencies:** Session operations (Tier 1 #4)
- **Impact:** Delete complex inverse-op logic, capture caret/selection
- **Files:**
  - `src/kernel/history.cljc` - New namespace with diff-based undo
  - `src/kernel/api.cljc` - Integrate record-transaction!
  - `test/kernel/history_test.cljc` - Undo/redo scenarios

**7. Consolidate Query API**
- **Complexity:** Medium
- **LOC:** Reduce 32 functions to ~10 core helpers
- **Tests:** matcher-based structural assertions
- **Dependencies:** :visible-order index (Tier 1 #3)
- **Impact:** Simplify plugins, reduce duplication
- **Files:**
  - `src/kernel/query.cljc` - Consolidate to core helpers
  - `test/kernel/query_test.cljc` - Update tests

### Tier 3: Long-Term Architectural Purity (Future)

**8. Implement Semantic Ops as Pure Transforms**
- **Complexity:** High
- **LOC:** Refactor ~500 lines across plugin namespaces
- **Tests:** Pure function tests + diff validation
- **Dependencies:** All Tier 1-2 work
- **Impact:** Final step toward truly layered architecture
- **Files:**
  - `src/plugins/*.cljc` - Convert imperative handlers to pure transforms
  - `dev/diff_ops.cljc` - Enhance diff→ops compiler

**9. editscript for History/Sync (Optional)**
- **Complexity:** Medium
- **LOC:** ~200 lines (network sync abstraction)
- **Tests:** Sync scenarios with matcher assertions
- **Dependencies:** Undo refactor (Tier 2 #6)
- **Impact:** Future-proof for collaborative editing
- **Files:**
  - `src/sync/delta.cljc` - New namespace for state sync
  - `test/sync/delta_test.cljc` - Sync scenarios

---

## Migration Guide

### Phase 1: Integrate Testing Libraries (Week 1)

**Day 1-2: Add dependencies**
```clojure
;; deps.edn
{:deps {...}
 :aliases
 {:test {:extra-deps {nubank/matcher-combinators {:mvn/version "3.9.1"}
                      juji/editscript {:mvn/version "0.6.3"}}}}}
```

**Day 3-5: Create initial examples**
1. Convert 3 existing kernel tests to use `match?`
2. Create `dev/diff_ops.cljc` with basic diff→ops compiler
3. Document patterns in `docs/TESTING_PATTERNS.md`

### Phase 2: Visible-Order Index (Week 2-3)

**Implementation checklist:**
- [ ] Add `:visible-order` computation to `kernel.derived/build-indexes`
- [ ] Add test fixtures with folded nodes
- [ ] Write matcher-based tests for index structure
- [ ] Refactor `prev-visible`, `next-visible` to use index
- [ ] Update plugins to use new helpers
- [ ] Run full test suite, verify no regressions

### Phase 3: Session Operations (Week 3-4)

**Implementation checklist:**
- [ ] Create `kernel.session` namespace
- [ ] Define session operation functions
- [ ] Add session state to db under `:session` key
- [ ] Migrate `plugins.editing` to use session ops
- [ ] Write matcher-based state machine tests
- [ ] Update UI components to query session state

### Phase 4: Intent Dispatch Refactor (Week 4-6)

**Strategy:** Migrate incrementally, one intent at a time.

**Priority order:**
1. Navigation intents (arrow keys, selection) - Most critical
2. Editing intents (enter, backspace, delete)
3. Structural intents (indent, outdent, move)
4. Low-frequency intents (paste, copy, etc.)

**Per-intent checklist:**
- [ ] Extract intent logic into multimethod
- [ ] Write matcher-based test for intent
- [ ] Verify in REPL with fixtures
- [ ] Run E2E tests for affected scenarios
- [ ] Commit with semantic message

---

## Success Metrics

### Code Metrics (Target by End of Tier 2)

- **Reduce plugins.editing complexity:** 263 lines → <50 lines per multimethod
- **Consolidate query API:** 32 functions → ~10 core helpers
- **Test coverage:** 80%+ with matcher-combinators
- **Complexity score:** Reduce average from 12 to <8

### Qualitative Metrics

- **REPL development velocity:** Faster prototyping with diff→ops compiler
- **Test maintenance:** Fewer brittle tests breaking on unrelated changes
- **Onboarding time:** New contributors understand intent dispatch pattern
- **Bug resolution:** Easier to debug with layered architecture

---

## Risks and Mitigations

### Risk 1: Test Suite Rewrite Overhead

**Risk:** Converting all tests to matcher-combinators is time-consuming.

**Mitigation:**
- Incremental adoption: New tests use matchers, old tests unchanged
- Focus on high-value areas: derived indexes, intent handlers
- Create patterns document with copy-paste examples

### Risk 2: editscript diff→ops Incompleteness

**Risk:** Diff-based approach might not capture all semantic operations correctly.

**Mitigation:**
- Use ONLY for dev/testing initially, not production
- Keep 3-op kernel as source of truth
- Validate diff→ops output against manual ops

### Risk 3: Session State Migration Breaks UI

**Risk:** Moving session state to `:session` key breaks existing component queries.

**Mitigation:**
- Comprehensive test coverage before migration
- Feature flag to toggle between old/new session state
- Migrate one component at a time with rollback plan

### Risk 4: Multimethod Dispatch Performance

**Risk:** Multimethod dispatch slower than case statement.

**Mitigation:**
- Profile after migration, optimize only if needed
- Consider protocol dispatch if performance critical
- Remember: Correctness > Performance (project philosophy)

---

## References

### Round 1 Consensus (All 3 Models)

1. **Add :visible-order index** - Critical for navigation
2. **Formalize session operations** - Separate UI from document
3. **Intent multimethod dispatch** - Break up monolith
4. **Enrich selection schema** - Add anchor, focus, direction
5. **Consolidate Query API** - Reduce duplication
6. **Semantic structural ops** - Domain ops compiling to primitives

### Round 2 Key Insight

**"The new tools don't change WHAT to do—they make it SAFE to do it."**

- matcher-combinators: Safety nets for aggressive refactoring
- editscript: Developer ergonomics + future-proof undo/sync

### External Documentation

- **matcher-combinators:** https://github.com/nubank/matcher-combinators
- **editscript:** https://github.com/juji-io/editscript
- **Logseq Spec:** `dev/specs/LOGSEQ_SPEC.md`
- **Architecture Lens:** Generated via `bb arch-lens`

---

## Appendix A: Code Examples

### Example 1: Matcher-Based Derived Index Test

```clojure
(ns evo.kernel.derived-test
  (:require [clojure.test :refer [deftest is testing]]
            [matcher-combinators.clj-test :refer [match?]]
            [matcher-combinators.matchers :as m]
            [evo.kernel.api :as k]
            [evo.kernel.derived :as d]))

(deftest parent-and-visible-order-basic
  (let [db0 (k/empty-db)
        [db1 _] (k/transact db0
                 [[:create-node {:id :root :type :doc}]
                  [:create-node {:id "b1" :type :block}]
                  [:place {:id "b1" :parent :root :before nil}]
                  [:create-node {:id "b2" :type :block}]
                  [:place {:id "b2" :parent :root :before nil}]])
        derived (d/build-indexes db1)]

    ;; Only assert what matters
    (is (match?
          {:parent-of {"b1" :root
                       "b2" :root}
           :visible-order {:by-parent {:root ["b1" "b2"]}}}
          derived))))

(deftest visible-order-with-folding
  (let [db0 (k/empty-db)
        [db1 _] (k/transact db0
                 [[:create-node {:id "parent" :type :block :props {:folded? true}}]
                  [:place {:id "parent" :parent :root :before nil}]
                  [:create-node {:id "hidden-child" :type :block}]
                  [:place {:id "hidden-child" :parent "parent" :before nil}]
                  [:create-node {:id "visible-sibling" :type :block}]
                  [:place {:id "visible-sibling" :parent :root :before nil}]])
        derived (d/build-indexes db1)]

    ;; Parent is folded, child should not appear in visible-order
    (is (match?
          {:visible-order {:by-parent {:root (m/equals ["parent" "visible-sibling"])
                                       "parent" []}}}
          derived))))
```

### Example 2: Intent Handler with Matcher Test

```clojure
(ns evo.plugins.editing
  (:require [clojure.test :refer [deftest is]]
            [matcher-combinators.clj-test :refer [match?]]
            [matcher-combinators.matchers :as m]))

(defmulti handle-intent
  "Dispatch intent to appropriate handler based on :intent key."
  (fn [ctx] (:intent ctx)))

(defmethod handle-intent :selection/indent [{:keys [db session]}]
  (let [selected-ids (get-in session [:selection :nodes])
        target-parent (calculate-indent-target db selected-ids)]
    (for [id selected-ids]
      [:place {:id id :parent target-parent :at :last}])))

;; Test
(deftest indent-selection-places-under-prev-sibling
  (let [db (fixture/simple-outline)
        session {:selection {:nodes ["b2" "b3"]}}
        ops (handle-intent {:intent :selection/indent
                           :db db
                           :session session})]

    ;; Assert ops structure, ignore exact parent ID calculation
    (is (match?
          [[:place (m/embeds {:id "b2" :parent string?})]
           [:place (m/embeds {:id "b3" :parent string?})]]
          ops))

    ;; Can also assert specific parent if known
    (is (match?
          [[:place {:id "b2" :parent "b1" :at :last}]
           [:place {:id "b3" :parent "b1" :at :last}]]
          ops))))
```

### Example 3: diff→ops REPL Usage

```clojure
(require '[editscript.core :as e])
(require '[dev.diff-ops :as diff-ops])

;; Prototype a complex operation by showing desired end state
(def before-db
  {:nodes {"b1" {:type :block :props {:text "Hello"}}
           "b2" {:type :block :props {:text "World"}}}
   :children-by-parent {:doc ["b1" "b2"]}
   :derived {:parent-of {"b1" :doc "b2" :doc}}})

;; Model the desired change (merge b2 into b1)
(def after-db
  {:nodes {"b1" {:type :block :props {:text "HelloWorld"}}}
   :children-by-parent {:doc ["b1"]}
   :derived {:parent-of {"b1" :doc}}})

;; Extract the operations
(def ops (diff-ops/diff->ops (e/diff before-db after-db)))

;; => [[:update-node "b1" {:props {:text "HelloWorld"}}]
;;     [:delete-node "b2"]]

;; Verify round-trip
(def [result _] (k/transact before-db ops))
(= after-db result) ;; => true
```

---

## Appendix B: Glossary

**3-op kernel:** The three primitive operations (create-node, place, update-node) that encode all state changes.

**Derived indexes:** Automatically computed lookups (`:parent-of`, `:next-id-of`, etc.) rebuilt after every transaction.

**Intent:** High-level user action (`:selection/indent`, `:editor/smart-split`) dispatched by UI components.

**Semantic operation:** Domain-specific operation (indent-blocks!, split-block!) that compiles to primitive ops.

**Session state:** Ephemeral UI state (selection, cursor position, editing mode) that should be part of transactional history.

**matcher-combinators:** Testing library for flexible structural assertions on nested data.

**editscript:** Library for computing minimal diffs between nested data structures.

**diff→ops compiler:** Dev tool that generates kernel operations from desired state changes using editscript.

**visible-order index:** Proposed derived index showing outline structure filtered by zoom level and folding state.

---

## Document History

- **2025-01-17:** Initial Round 2 analysis with library integration
- **Authors:** Synthesis of Gemini 2.5 Pro, GPT-5.1, and Grok-4 proposals
- **Status:** Approved architectural direction
