# Final Recommendation: Intent Router Design

**Date:** 2025-10-24
**Run ID:** `7fd9ac2a-7bfe-4e1a-9037-39b6d177f888`
**Evaluators:** 2x Codex, 2x Gemini, 1x Grok
**Winner:** Gemini-1 (100% confidence)

## Universal Approval

All 5 agents understood the proposal correctly and **unanimously endorsed the dual-multimethod pattern**. No major objections, all praised the design.

## Key Validations

### 1. Two Multimethods is Clean (Universal Agreement)

**Gemini-1:**
> "Two multimethods will dispatch on the intent type... This separation allows for modularity and testability."

**Codex-1:**
> "Two parallel multimethods keep effectful operations and pure state updates explicit and independently testable."

**Grok:**
> "Separates concerns (state vs. effects), promoting pure functions where possible."

**Consensus:** Two multimethods (not one with tagged returns) is the right choice.

### 2. Naming is Good

**No agent suggested better names.** All used `intent->ops` and `intent->db` without comment, suggesting the names are intuitive.

**Gemini-1:** Used the names naturally in examples
**Codex-1:** Noted "dispatch remains simple" - validated the clarity
**Grok:** Emphasized "explicit data flow" - names support this

**Validation:** Keep `intent->ops` and `intent->db`.

### 3. Implicit Dispatch is Better Than Explicit Category

**None of the agents suggested** adding `:category` to intents. They all assumed the try-structural-first approach.

**Codex-1:**
> "Dispatcher shell... calling both multimethods in order"

**Grok:**
> "Dispatches to two separate multimethods"

**Implication:** Implicit dispatch (try `intent->ops`, fall back to `intent->db`) is acceptable and simpler than requiring `:category` in every intent.

### 4. Plugins Doing Both is Natural

**Gemini-1** provided example showing both:
> "`intent->ops` to determine which operations to execute, and `intent->db` to determine how to read/write"

**Codex-1:**
> "Maintaining two handlers per intent may feel repetitive; requires discipline"

**Interpretation:** It's normal for plugins to implement both, but needs documentation to avoid forgetting one.

### 5. Plugin Manifests: Optional Documentation

**None required manifests,** all assumed documentation would be via docstrings or comments.

**Grok:**
> "Minimal deps (just Clojure core)"

**Codex-1:**
> "Optional `describe-intent` helper returning metadata for debugging"

**Recommendation:** Keep manifests optional. Start with docstrings, add manifest infrastructure later if needed.

## Critical Insights

### 1. Error Handling: Clear Messages Essential

**All agents emphasized** good error messages for unknown intents:

**Codex-1:**
> "Short-circuiting on unknown types... missing handlers are reported clearly"

**Grok:**
> "Clear errors on unknown types"

**Implementation:**
```clojure
(defn apply-intent [db intent]
  (cond
    (get-method intent->ops (:type intent))
    (interpret db (intent->ops db intent))

    (get-method intent->db (:type intent))
    (intent->db db intent)

    :else
    (throw (ex-info "Unknown intent type"
                    {:type (:type intent)
                     :available-structural (keys (methods intent->ops))
                     :available-view (keys (methods intent->db))
                     :suggestion "Did you forget to implement the method?"}))))
```

### 2. REPL Testing: Core Design Goal

**Every agent mentioned REPL testability:**

**Gemini-1:**
> "Multimethods are REPL-friendly and allow for easy inspection"

**Codex-1:**
> "Enabling direct REPL evaluation of each branch"

**Grok:**
> "Inspecting intermediate results at REPL"

**Validation:** The design meets the primary constraint (REPL-driven development).

### 3. Discipline Required

**Codex-1** was most explicit:
> "Maintaining two handlers per intent may feel repetitive; requires discipline to stay consistent... lint/checklist needed"

**Risk:** Plugin authors might forget to implement both methods.

**Mitigation:**
```clojure
;; REPL helper to check completeness
(defn check-plugin-intents [plugin-ns]
  (let [ops-intents (keys (methods intent->ops))
        db-intents (keys (methods intent->db))
        all-intents (set/union ops-intents db-intents)]
    {:total (count all-intents)
     :ops-only (set/difference ops-intents db-intents)
     :db-only (set/difference db-intents ops-intents)
     :both (set/intersection ops-intents db-intents)}))
```

### 4. Performance: Not a Concern

**Codex-1:**
> "Multimethod cost: Dispatch is slower than simple case statements; usually acceptable"

**Grok:**
> "Multimethod dispatch adds slight indirection... negligible for UI scale"

**Consensus:** Performance is fine for UI-scale tree editor.

## What They DIDN'T Mention

Interesting omissions:

1. **No prior art comparisons** - None mentioned Redux, VSCode, Emacs
2. **No alternative patterns suggested** - All accepted the proposal as-is
3. **No fundamental objections** - Only minor cons about verbosity/discipline

**Interpretation:** The design is sound and follows established patterns naturally.

## Answering Our Original Questions

### Q1: Is dual-multimethod pattern clear and maintainable?

**Answer: YES**

All agents praised clarity and modularity. No confusion about two dispatch points.

### Q2: Are names `intent->ops` and `intent->db` good?

**Answer: YES**

No alternative names suggested. Names convey return types clearly.

### Q3: Implicit or explicit dispatch?

**Answer: IMPLICIT (try structural first)**

None suggested requiring `:category` in intents. Simpler without it.

### Q4: Is "plugin doing both" a good pattern?

**Answer: YES, WITH DISCIPLINE**

Natural to have both, but needs documentation/tooling to avoid forgetting methods.

### Q5: What are we missing?

**Codex-1 identified:**
- Need lint/checklist for completeness
- Optional `describe-intent` helper
- Clear error messages for unknown types

**Grok identified:**
- Testing friction if global atom used (ensure functions take db as arg)
- Watch for async creep (keep synchronous)

### Q6: Comparison to prior art?

**None provided,** suggesting the pattern is standard enough not to need comparison.

## Final Design (Approved)

```clojure
;; plugins/intents.cljc

(defmulti intent->ops
  "Compile intent to tree operations.
   Returns: vector of ops"
  (fn [_db intent] (:type intent)))

(defmulti intent->db
  "Apply intent directly to database.
   Returns: updated db"
  (fn [_db intent] (:type intent)))

(defn apply-intent
  "Unified entry point for all intents."
  [db intent]
  (cond
    (get-method intent->ops (:type intent))
    (interpret db (intent->ops db intent))

    (get-method intent->db (:type intent))
    (intent->db db intent)

    :else
    (throw (ex-info "Unknown intent type"
                    {:type (:type intent)
                     :available {:structural (keys (methods intent->ops))
                                 :view (keys (methods intent->db))}}))))

;; REPL helpers
(defn list-intents []
  {:structural (keys (methods intent->ops))
   :view (keys (methods intent->db))})

(defn intent-category [intent-type]
  (cond
    (get-method intent->ops intent-type) :structural
    (get-method intent->db intent-type) :view
    :else :unknown))
```

## Implementation Checklist

1. **Create `plugins/intents.cljc`** with multimethods and helpers
2. **Migrate `compile-intent`** from `plugins/struct/core.cljc` to `intent->ops`
3. **Add `intent->db` methods** to `plugins/selection/core.cljc`
4. **Update event handlers** to use `apply-intent`
5. **Add REPL helpers** (`list-intents`, `intent-category`, `check-plugin-intents`)
6. **Document in ADR** with rationale and examples
7. **Add tests** for both multimethod types

## Red Flags to Watch (From Agents)

**Codex-1:**
- Maintaining two handlers requires discipline (add lint/checklist)
- No orchestration for complex flows (document explicitly)
- Learning curve for new plugin authors (good onboarding docs)

**Grok:**
- Async creep (keep synchronous unless justified)
- Testing friction (ensure functions take db as arg, not global state)
- Over-extension (too many intent types → refactor into hierarchies)

**Gemini-1:**
- Over-complicated operation functions (keep small and focused)
- Implicit state dependencies (avoid global state in methods)
- Lack of documentation (document each intent type)

## Recommendation: IMPLEMENT IT

All 5 architects approved the design with only minor cons (verbosity, discipline). The dual-multimethod pattern is:

✅ Clear and maintainable
✅ Well-named (intent->ops / intent->db)
✅ Implicit dispatch is simpler
✅ Supports plugins doing both naturally
✅ REPL-friendly (primary constraint met)
✅ Follows established patterns

**Next steps:**
1. Implement `plugins/intents.cljc`
2. Migrate existing code
3. Write ADR documenting the decision
4. Create plugin authoring guide

The architects have spoken: this design is solid. Let's build it.
