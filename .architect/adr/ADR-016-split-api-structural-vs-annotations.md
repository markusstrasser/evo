# ADR-016: Split API Pattern - Structural vs Annotations

## Status
Accepted (2025-10-23)

## Problem
Should all DB mutations (structural edits AND annotations like selection/highlights) go through the same intent→ops→interpret pipeline, or should annotations use direct DB mutation?

## Context

We have an event-sourced kernel with three core operations:
- `:create-node`
- `:place`
- `:update-node`

Structural edits use: **Intent → Ops → Interpret**

```clojure
;; Intent
{:type :indent :id "a"}

;; Compiled to ops
[{:op :place :id "a" :under "b" :at :last}]

;; Interpreted (validated, derives recomputed)
(interpret db ops)  ;; → DB'
```

Annotations currently use: **Direct Mutation**

```clojure
;; No intents, no ops
(select db "a")  ;; → (assoc db :selection #{:a})
```

**Question:** Should we unify these into one pattern?

## Decision

**Keep the split pattern.** Structural edits use intent→ops→interpret. Annotations use direct DB mutation.

## Rationale

### Architect Evaluation Results

Ran tournament evaluation with 3 LLM providers (gemini, codex, grok):
- **Winner (2/3):** Direct mutation for annotations
- **Runner-up (1/3):** Unified intent→ops for everything

**Winning arguments:**

1. **Simplicity trumps consistency**
   ```clojure
   ;; Direct (simple):
   (select db "a")

   ;; Unified (ceremony):
   (interpret db (compile-intents db [{:type :select :ids ["a"]}]))
   ```

2. **REPL experience matters**
   - Direct: Just call functions
   - Unified: Always wrap in machinery

3. **Performance: No overhead for simple mutations**
   - Direct: One `assoc`
   - Unified: Generate intent → compile to ops → interpret → validate → derive

4. **Debuggability: Immediate state**
   - Changes are immediate and predictable
   - No interpretation layer to trace through

### The Real Distinction

It's not "ephemeral vs persistent" - it's **"structural vs annotation"**:

**Structural changes** (require validation):
- Creating/deleting nodes
- Moving nodes (parent-child relationships)
- Updating node content
- **Need:** Cycle detection, derived index updates, invariant maintenance

**Annotations** (no validation needed):
- Selection (which nodes highlighted)
- Highlights (color annotations)
- Collapsed state (UI state)
- Zoom stack (focus state)
- **Don't need:** Validation, structural derives

### Code Example: The Difference

```clojure
;; STRUCTURAL: Must validate cycles
(interpret db [{:op :place :id "a" :under "b"}])
;; What if this creates a cycle?
;; What if "a" or "b" don't exist?
;; Must recompute :parent-of, :pre, :post, etc.

;; ANNOTATION: No validation needed
(select db "a")  ;; → (assoc db :selection #{:a})
;; Any ID can be selected, even if node doesn't exist
;; No structural derives to update
```

## Alternatives Considered

### Alternative 1: Unified Intent→Ops for Everything

**Approach:**
```clojure
;; Add new :update-db op type
(defmethod compile-intent :select [db {:keys [ids]}]
  [{:op :update-db :path [:selection] :value (set ids)}])

(interpret db (compile-intents db [{:type :select :ids ["a"]}]))
```

**Pros:**
- One API to learn
- Consistent mental model
- All mutations traceable through ops

**Cons:**
- Extra ceremony for simple mutations
- Performance overhead
- Worse REPL experience
- More implementation complexity

**Rejected because:** Simplicity and REPL experience outweigh API consistency for solo development.

### Alternative 2: Annotations Generate Ops But Skip Interpreter

**Approach:**
```clojure
(defmethod compile-intent :select [db {:keys [ids]}]
  ;; Returns DB directly, not ops!
  (assoc db :selection (set ids)))
```

**Pros:**
- "Intent" pattern everywhere
- But fast (no interpretation)

**Cons:**
- Confusing: Same name, different return types
- Why have intents if they don't return ops?

**Rejected because:** This is just direct mutation with extra steps.

## Implementation

### Structural Edits (Intent→Ops→Interpret)

```clojure
(ns plugins.struct.core)

(defmethod compile-intent :indent [db {:keys [id]}]
  [{:op :place :id id :under (prev-sibling db id) :at :last}])

;; Usage:
(interpret db (compile-intents db [{:type :indent :id "a"}]))
```

### Annotations (Direct Mutation)

```clojure
(ns plugins.selection.core)

(defn select [db ids]
  (assoc db :selection (set ids)))

;; Usage:
(select db "a")
```

### Bridge: Multi-Select Structural Ops

Some operations bridge both systems:

```clojure
(defmethod compile-intent :delete-selected [db _]
  (let [selected (selection/get-selected-nodes db)]  ;; Read annotation
    (mapcat #(delete-ops db %) selected)))  ;; Generate structural ops
```

This is fine! Structural ops can read annotation state.

## Guidelines

**Use Intent→Ops→Interpret when:**
- Modifying `:nodes` map
- Modifying `:children-by-parent` map
- Any change that affects structural derived indexes (`:parent-of`, `:pre`, etc.)
- Changes that need validation (cycle detection)

**Use Direct Mutation when:**
- Adding metadata that doesn't affect tree structure
- Annotation/UI state (selection, highlights, collapsed, zoom)
- Even if persistent! (just serialize that namespace separately)
- No validation or structural derives needed

**Rule of thumb:** If it changes the graph structure → intents. If it annotates the graph → direct mutation.

## Consequences

### Positive

- ✅ **Simple:** No ceremony for annotations
- ✅ **Fast:** No interpretation overhead
- ✅ **REPL-friendly:** Threading macros just work
- ✅ **Clear separation:** Structure vs annotation is explicit
- ✅ **Debuggable:** State changes are immediate and visible

### Negative

- ⚠️ **Two APIs:** Developers need to know which pattern to use
- ⚠️ **Mental overhead:** "Should this be intent or direct?"
- ⚠️ **Documentation burden:** Must explain when to use each

### Mitigations

**Clear guidelines in documentation:**
- Structure → Intent→Ops
- Annotation → Direct mutation

**Plugin convention:**
- Structural plugins export intent compilers
- Annotation plugins export pure functions

**Naming convention:**
```clojure
;; Structural (returns ops):
(indent-ops db id)
(delete-ops db id)

;; Annotation (returns DB):
(select db ids)
(highlight db id color)
```

## Future Considerations

**If annotations become complex** (multi-user collaborative editing, operational transform):
- May need to adopt intent→ops for consistency
- Can refactor without changing component API (plugins provide abstraction)

**If performance becomes critical:**
- Direct mutation is already optimal
- Intent→ops would only add overhead

**If traceability becomes important:**
- Can add logging layer to direct mutations
- Or adopt intent pattern selectively

## Related Decisions

- ADR-001: Structural Edits as Lowering (intent→ops for structural)
- ADR-015: UI State as Namespaced DB Root (where annotations live)

## Notes

This decision prioritizes **simplicity and REPL experience** over **API consistency**. For a solo developer building an 80/20 local-first tool, the trade-off is justified. The split pattern reduces cognitive load for simple operations while maintaining rigor for structural changes.

Architect tournament evaluation confirmed this approach, with 2/3 proposals favoring direct mutation despite the user's initial preference for unification.
