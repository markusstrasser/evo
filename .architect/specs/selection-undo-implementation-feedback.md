# Selection & Undo/Redo Implementation Feedback

**Date**: 2025-10-23
**Implemented**: Selection plugin (ADR-015), Undo/Redo infrastructure, Multi-select structural ops

---

## What Was Implemented

### 1. Selection Plugin (`src/plugins/selection/core.cljc`)

New ADR-015 pattern: `:selection #{}` at DB root (replaces ADR-012's boolean property pattern)

**API:**
```clojure
;; State access
(get-selection DB)          ;; → #{id1 id2}
(selected? DB id)           ;; → true/false
(selection-count DB)        ;; → integer
(has-selection? DB)         ;; → true/false

;; Modification
(select DB ids)             ;; Replace selection
(extend-selection DB ids)   ;; Add to selection
(deselect DB ids)           ;; Remove from selection
(clear DB)                  ;; Empty selection
(toggle DB id)              ;; Toggle single node

;; Navigation helpers
(select-next-sibling DB)
(select-prev-sibling DB)
(select-parent DB)
(select-all-siblings DB)
```

### 2. History Infrastructure (`src/core/history.cljc`)

Operates on entire DB snapshots (including `:selection` and other ephemeral state)

**API:**
```clojure
;; History queries
(can-undo? DB)              ;; → true/false
(can-redo? DB)              ;; → true/false
(undo-count DB)             ;; → integer
(redo-count DB)             ;; → integer

;; History modification
(record DB)                 ;; Save current state to undo stack
(undo DB)                   ;; → restored DB or nil
(redo DB)                   ;; → restored DB or nil
(set-limit DB n)            ;; Set max undo stack size
(clear-history DB)          ;; Empty all history
```

### 3. Multi-select Structural Ops (`src/plugins/struct/core.cljc`)

New intent types that operate on current selection:

```clojure
{:type :delete-selected}
{:type :indent-selected}
{:type :outdent-selected}
```

### 4. Tests

- **Selection tests**: 12 tests covering state management and navigation (ALL PASS ✅)
- **History tests**: 10 tests covering undo/redo and selection preservation (ALL PASS ✅)
- **Struct tests**: Existing tests still pass (✅)

---

## What Worked Well

### 1. Pure Function Pattern is Excellent

**The Good:**
```clojure
;; Threading selection transformations is beautiful:
(-> db
    (S/select "a")
    (S/extend-selection "c")
    (S/select-parent))
```

This is MUCH cleaner than the old pattern:
```clojure
;; Old (ADR-012): Had to interpret ops
(let [op1 (sel/select db "a")
      db1 (interpret db [op1])
      op2 (sel/select db1 "c")
      db2 (interpret db1 [op2])]
  ...)
```

**Why it works:**
- No ops means no interpretation overhead for UI state
- REPL-friendly: just call functions
- Composable via threading macros
- Fast (no transaction log pollution)

### 2. Undo/Redo "Just Works"

The insight that undo restores the ENTIRE DB (including `:selection`) is brilliant:

```clojure
;; Undo a structural edit
(-> db
    (S/select "a")              ;; Select node
    (H/record)                  ;; Save state
    (interpret [{:op :delete ...}]) ;; Structural change
    (H/undo))                   ;; Restores EVERYTHING (structure + selection)
```

No special-casing needed. Selection automatically correct after undo.

### 3. Namespace Pattern is Consistent

```clojure
{:nodes {}              ;; Core data
 :derived {...}         ;; Computed state (from core data)
 :selection #{...}      ;; Ephemeral state (managed by plugin)
 :history {...}}        ;; Undo/redo metadata
```

Everything in one place, clear responsibilities.

---

## What Was Weird / Bad API Design

### 1. Selection Navigation: `first` vs "Focus" Concept

**The Problem:**
```clojure
(defn select-next-sibling [DB]
  (let [selection (get-selection DB)]
    (if-let [current (first selection)]  ;; 🚨 WRONG!
      ...)))
```

**Why it's weird:**
- Sets are unordered! `(first #{:a :b})` is non-deterministic
- Need a "focus" concept (which node is the "current" one in multi-select)
- TODO comments everywhere: `;;TODO: should use "last" or "focus" concept`

**Better API:**
```clojure
;; Need to track focus separately:
{:selection {:nodes #{:a :b :c}
             :focus :b}}  ;; Which node has "focus" for navigation

(select-next-sibling DB)  ;; Uses :focus, not (first selection)
```

### 2. Multi-select Structural Ops: Order Matters!

**The Problem:**
```clojure
(defmethod compile-intent :indent-selected [DB _]
  (let [selected (selection/get-selected-nodes DB)]
    (mapcat #(indent-ops DB %) selected)))  ;; 🚨 Wrong order!
```

**Why it's broken:**
- Sets are unordered
- Indenting in wrong order creates invalid states
- Need to indent in DOCUMENT ORDER (top to bottom)

**TODO comments:**
```clojure
;; TODO: Should we sort by document order first?
```

**Better approach:**
```clojure
(defn indent-selected [DB]
  (let [selected (selection/get-selected-nodes DB)
        ;; Sort by pre-order traversal index
        ordered (sort-by #(get-in DB [:derived :pre %]) selected)]
    (mapcat #(indent-ops DB %) ordered)))
```

### 3. Selection + History: Snapshot Behavior Surprising?

**The Issue:**

```clojure
(-> db
    (S/select "a")          ;; Select node a
    (H/record)              ;; Save snapshot
    (interpret [...])       ;; Structural edit
    (S/select "b")          ;; Change selection to b
    (H/undo))               ;; What's selected? "a" (from snapshot!)
```

Undo restores the OLD selection, not the current one.

**Is this good or bad?**
- **Good**: Consistent (entire DB is restored)
- **Bad**: User expects undo to affect structure, not selection
- **Weird**: Selection changes aren't "recordable actions" but get recorded anyway

**Possible fix:**
```clojure
;; Strip ephemeral state before recording?
(defn record [DB]
  (let [snapshot (dissoc DB :selection)]  ;; Don't save selection?
    ...))
```

But then selection doesn't undo/redo. Is that what we want?

### 4. `select` Takes Single ID or Collection (Inconsistent)

**Current API:**
```clojure
(select DB "a")         ;; Single ID
(select DB ["a" "b"])   ;; Collection
```

**Why it's weird:**
- Overloaded semantics
- Hard to tell what type to pass
- Error-prone (forget to wrap in vector)

**Better:**
```clojure
(select-one DB id)
(select-many DB ids)
;; OR
(select DB id-or-ids)  ;; but document clearly
```

### 5. Breaking Change from ADR-012

**The Big One:**

Existing tests fail because they use the OLD selection API:
```clojure
;; Old (ADR-012):
(sel/select db "a")  ;; → Returns an :update-node OP
(interpret db [(sel/select db "a")])  ;; Must interpret

;; New (ADR-015):
(sel/select db "a")  ;; → Returns updated DB
```

**Same function name, completely different semantics!**

This broke existing integration tests:
- `plugins.integration-edge-cases-test` (5 failures, 1 error)

**Fix options:**
1. Update failing tests to use new API ✅ (rename old fns first)
2. Namespace the new plugin differently (`plugins.selection.v2`)
3. Add migration guide

---

## Test Results

```
Ran 157 tests containing 649 assertions.
5 failures, 1 errors.
```

**Failures:**
- All in `plugins/integration_edge_cases_test.cljc`
- All due to old ADR-012 API usage
- Easy to fix (just update to new API)

**New tests (all pass):**
- `core.history-test`: 10/10 ✅
- `plugins.selection.core-test`: 12/12 ✅

---

## Recommendations

### P0 - Must Fix

1. **Add focus tracking to selection**
   ```clojure
   {:selection {:nodes #{:a :b :c}
                :focus :b
                :anchor :a}}  ;; For range selection
   ```

2. **Sort multi-select ops by document order**
   ```clojure
   (defn sort-by-doc-order [DB ids]
     (sort-by #(get-in DB [:derived :pre %]) ids))
   ```

3. **Fix failing integration tests**
   - Update to new selection API
   - Or preserve old API under different names

### P1 - Should Fix

4. **Decide: Should undo restore selection?**
   - Current: Yes (entire DB snapshot)
   - Alternative: No (strip `:selection` from snapshots)
   - Document the choice in ADR-015

5. **Clarify single vs multi select API**
   ```clojure
   ;; Option A: Keep flexible
   (select DB id-or-ids)  ;; Document: "Takes single ID or collection"

   ;; Option B: Split
   (select-one DB id)
   (select-many DB ids)
   ```

### P2 - Nice to Have

6. **Add keyboard-friendly selection extensions**
   ```clojure
   (extend-to-next-sibling DB)   ;; Like Shift+Down
   (extend-to-prev-sibling DB)   ;; Like Shift+Up
   (select-range DB from to)     ;; Click + Shift+Click
   ```

7. **Add selection predicates**
   ```clojure
   (all-siblings-selected? DB id)
   (contiguous-selection? DB)
   (same-parent-selection? DB)
   ```

---

## Architecture Validation

### ADR-015 Pattern: ✅ APPROVED

The decision to store selection as `:selection #{}` at DB root (not as node properties) is **superior** to ADR-012:

**Wins:**
- ✅ Pure functions (no op interpretation overhead)
- ✅ Threading-friendly (`->` macro just works)
- ✅ REPL-friendly (call functions directly)
- ✅ Fast (no transaction log pollution)
- ✅ Undo/redo "just works" (entire DB snapshot)
- ✅ Consistent with `:derived` pattern

**Costs:**
- ⚠️ Breaking change from ADR-012 (need migration)
- ⚠️ Need "focus" concept for navigation (sets are unordered)
- ⚠️ Multi-select ops need document-order sorting

**Verdict:** The pattern is sound. The issues are **implementation details**, not architectural flaws.

---

## Next Steps

1. Add `{:focus :id}` to selection state
2. Add `sort-by-doc-order` helper
3. Fix failing integration tests
4. Document undo/selection interaction in ADR-015
5. Consider deprecation path for ADR-012 API

---

## Summary

**What worked:** Pure function selection API, undo/redo infrastructure, namespace pattern
**What was weird:** Unordered sets for ordered operations, breaking API change
**What to fix:** Focus tracking, document-order sorting, failing tests

The core architectural insight (ADR-015: ephemeral state at DB root) is **excellent**. The API needs refinement but the foundation is solid.
