# Clojure Style Guide: Clean, Inspired, Beautiful Code

> Extracted from refactoring sessions. These patterns represent our preferences and philosophy.

## Core Principles

**Build → Learn → Extract → Generalize**
Not: Theorize → Propose → Analyze → Repeat

**Philosophy:**
- Prefer pure functions over stateful operations
- Favor composition over complexity
- Make data flow explicit
- Prioritize debuggability

---

## 1. Eliminate Atoms in Pure Functions

### ❌ Before: Imperative with Atoms
```clojure
(defn- compute-traversal [children-by-parent roots]
  (let [pre-order (atom [])
        post-order (atom [])
        pre-counter (atom 0)
        post-counter (atom 0)]
    (letfn [(visit [id]
              (let [pre-idx @pre-counter]
                (swap! pre-order conj id)
                (swap! pre-counter inc)
                (doseq [child (get children-by-parent id [])]
                  (visit child))
                (swap! post-order conj id)
                (swap! post-counter inc)))]
      (doseq [root roots]
        (when (contains? children-by-parent root)
          (visit root)))
      @pre-order)))
```

**Problems:**
- Side effects obscure data flow
- Hard to debug intermediate states
- Atoms are unnecessary for pure computation
- `doseq` used for accumulation (wrong tool)

### ✅ After: Pure Functional with State Threading
```clojure
(defn- compute-traversal [children-by-parent roots]
  (letfn [(visit-node [state id]
            ;; Record pre-order visit before children
            (let [state-with-pre (update state :pre-order conj id)
                  children (get children-by-parent id [])
                  ;; Visit all children, threading state through
                  state-after-children (reduce visit-node state-with-pre children)]
              ;; Record post-order visit after children
              (update state-after-children :post-order conj id)))

          (visit-root [state root]
            (if (contains? children-by-parent root)
              (visit-node state root)
              state))]

    ;; Visit all roots, threading state through reduce
    (let [initial-state {:pre-order [] :post-order []}
          final-state (reduce visit-root initial-state roots)]
      final-state)))
```

**Why Better:**
- ✅ No side effects - data flow is explicit
- ✅ `reduce` for accumulation (correct tool)
- ✅ Named intermediate values aid debugging
- ✅ Pure functions are trivially testable
- ✅ Can add `tap>` at any binding for inspection

**Pattern:** Use `reduce` with state threading instead of atoms + doseq

---

## 2. Extract Helper Functions for Complex Logic

### ❌ Before: Nested Conditionals
```clojure
(defn- resolve-at-position [siblings at]
  (let [max-idx (count siblings)]
    (cond
      (integer? at) (max 0 (min at max-idx))
      (= at :first) 0
      (= at :last) max-idx
      (map? at)
      (cond
        (:before at)
        (let [target-id (:before at)
              target-idx (.indexOf siblings target-id)]
          (if (>= target-idx 0)
            target-idx
            max-idx))
        (:after at)
        (let [target-id (:after at)
              target-idx (.indexOf siblings target-id)]
          (if (>= target-idx 0)
            (inc target-idx)
            max-idx))
        :else max-idx)
      :else max-idx)))
```

**Problems:**
- 3 levels of nesting
- Duplicated logic for `:before` and `:after`
- Can't test map-handling in isolation
- Complex mental model

### ✅ After: Extracted Helpers
```clojure
(defn- find-anchor-index
  "Find index of target-id in siblings, returning -1 if not found."
  [siblings target-id]
  (.indexOf siblings target-id))

(defn- resolve-relative-anchor
  "Resolve {:before id} or {:after id} to concrete index."
  [siblings anchor-map append-position]
  (let [[relation target-id] (or (find anchor-map :before)
                                  (find anchor-map :after))
        target-idx (find-anchor-index siblings target-id)]
    (if (neg? target-idx)
      append-position
      (cond-> target-idx
        (= relation :after) inc))))

(defn- resolve-at-position
  "Resolve :at anchor to concrete index within siblings list."
  [siblings at]
  (let [append-position (count siblings)]
    (cond
      (integer? at)     (max 0 (min at append-position))
      (= at :first)     0
      (= at :last)      append-position
      (map? at)         (resolve-relative-anchor siblings at append-position)
      :else             append-position)))
```

**Why Better:**
- ✅ Flattened from 3 levels → 1 level nesting
- ✅ Each function has single responsibility
- ✅ Idiomatic patterns: `find`, `or`, `cond->`
- ✅ Named helpers create REPL breakpoints
- ✅ Better naming reveals intent (`append-position` vs `max-idx`)

**Pattern:** Extract complex branches into focused helper functions

---

## 3. Break Monoliths into Composable Validators

### ❌ Before: 63-Line Monolith
```clojure
(defn- validate-op [db op op-index]
  (let [issue (fn [issue-kw hint] {:issue issue-kw :op op :at op-index :hint hint})
        op-issues (case (:op op)
                    :place
                    (let [{:keys [id under at]} op]
                      (concat
                       (when-not (contains? (:nodes db) id)
                         [(issue :node-not-found ...)])
                       (when (map? at)
                         (let [siblings ...]
                           (cond
                             (:before at) ...
                             (:after at) ...)))
                       (when (contains? (:nodes db) id)
                         (let [is-descendant? (fn is-descendant? [...]
                                                (loop [...] ...))]
                           (when (or (= id under) ...)
                             [(issue :cycle-detected ...)]))))))])
    (vec (concat schema-issue op-issues))))
```

**Problems:**
- 63 lines, 7 levels of nesting
- Mixed concerns (validation + logic)
- Inline recursive function
- Hard to test individual validations
- Cognitive overload

### ✅ After: Composable Validators
```clojure
;; Helpers
(defn- make-issue [op op-index issue-kw hint]
  {:issue issue-kw :op op :at op-index :hint hint})

(defn- node-exists? [db id]
  (contains? (:nodes db) id))

(defn- descendant-of? [db ancestor-id descendant-id]
  (loop [current descendant-id
         visited #{}]
    (cond
      (contains? visited current) false
      (= current ancestor-id) true
      (contains? (:roots db) current) false
      :else (recur (get-in db [:derived :parent-of current]) (conj visited current)))))

;; Operation-specific validators
(defn- validate-anchor [db under at issue-fn]
  (when (map? at)
    (let [siblings (get-in db [:children-by-parent under] [])
          {:keys [before after]} at]
      (cond
        before (when-not (some #(= % before) siblings)
                 [(issue-fn :anchor-not-sibling ...)])
        after (when-not (some #(= % after) siblings)
                [(issue-fn :anchor-not-sibling ...)])))))

(defn- validate-place [db {:keys [id under at]} issue-fn]
  (->> [(when-not (node-exists? db id)
          [(issue-fn :node-not-found ...)])
        (when-not (parent-exists? db under)
          [(issue-fn :parent-not-found ...)])
        (validate-anchor db under at issue-fn)
        (validate-cycle db id under issue-fn)]
       (remove nil?)
       (mapcat identity)))

;; Main orchestrator
(defn- validate-op [db op op-index]
  (let [issue-fn (partial make-issue op op-index)
        schema-issues (when-not (schema/valid-op? op) [...])
        op-issues (case (:op op)
                    :create-node (validate-create db op issue-fn)
                    :place (validate-place db op issue-fn)
                    :update-node (validate-update db op issue-fn)
                    [(issue-fn :unknown-op ...)])]
    (vec (concat schema-issues op-issues))))
```

**Why Better:**
- ✅ 10 focused functions vs 1 monolith
- ✅ Each validator independently testable
- ✅ Threading macro shows clear pipeline
- ✅ `partial` for cleaner issue creation
- ✅ Extracted `descendant-of?` as reusable utility
- ✅ Max nesting reduced 7→3 levels

**Pattern:** Large functions → small orchestrator + focused helpers

---

## 4. Eliminate Cross-Platform Duplication

### ❌ Before: Repeated Reader Conditionals
```clojure
(for [[parent children] children-by-parent
      child children
      :when (not (contains? nodes child))]
  #?(:clj (format "Child %s of parent %s does not exist" child parent)
     :cljs (gstr/format "Child %s of parent %s does not exist" child parent)))

(for [[parent children] children-by-parent
      :when (not= (count children) (count (set children)))]
  #?(:clj (format "Parent %s has duplicate children: %s" parent children)
     :cljs (gstr/format "Parent %s has duplicate children: %s" parent children)))

;; ... 5 more times ...
```

**Problems:**
- 12+ lines of `#?(:clj/:cljs)` duplication
- Inconsistent if someone misses one
- Noisy, hard to read

### ✅ After: Helper Function
```clojure
(defn- fmt
  "Cross-platform format helper."
  [template & args]
  (apply #?(:clj format :cljs gstr/format) template args))

(for [[parent children] children-by-parent
      child children
      :when (not (contains? nodes child))]
  (fmt "Child %s of parent %s does not exist" child parent))

(for [[parent children] children-by-parent
      :when (not= (count children) (count (set children)))]
  (fmt "Parent %s has duplicate children: %s" parent children))
```

**Why Better:**
- ✅ Single source of truth for formatting
- ✅ Eliminates 12+ lines of duplication
- ✅ More readable
- ✅ Easier to change platform logic

**Pattern:** Extract reader conditionals into helper functions

---

## 5. Use Idiomatic Clojure Functions

### ❌ Before: Manual Implementations
```clojure
;; Manual index finding
(.indexOf siblings target-id)

;; Manual conditional increment
(if (>= target-idx 0)
  (inc target-idx)
  max-idx)

;; Verbose map filtering
(for [[parent children] child->parents
      :when (> (count children) 1)]
  ...)
```

### ✅ After: Idiomatic Patterns
```clojure
;; Medley's indexed for idiomatic pairing
(m/indexed siblings)  ; => [[0 :a] [1 :b] [2 :c]]

;; Use `neg?` instead of `(>= idx 0)`
(if (neg? target-idx)
  fallback-idx
  target-idx)

;; Conditional threading
(cond-> target-idx
  (= relation :after) inc)

;; find returns [key value] or nil
(let [[relation target-id] (or (find anchor-map :before)
                                (find anchor-map :after))]
  ...)

;; Medley's filter-vals for map filtering
(m/filter-vals #(> % 1) (frequencies children))

;; Threading macro for pipeline clarity
(->> validators
     (remove nil?)
     (mapcat identity)
     vec)
```

**Why Better:**
- ✅ More expressive intent
- ✅ Leverages Clojure stdlib + medley
- ✅ Less verbose
- ✅ Standard patterns easier to recognize

**Pattern:** Prefer idiomatic Clojure over manual implementations

---

## 6. Name Reveals Intent

### ❌ Before: Generic Names
```clojure
(let [max-idx (count siblings)]      ; What does max mean?
  ...)

(let [target-idx (.indexOf ...)]     ; Java interop, unclear
  ...)
```

### ✅ After: Intention-Revealing Names
```clojure
(let [append-position (count siblings)]  ; Ah, where new items go!
  ...)

(let [target-idx (find-anchor-index siblings before)]  ; Named function
  ...)
```

**Why Better:**
- ✅ `append-position` explains the "why"
- ✅ Named functions > Java interop
- ✅ Self-documenting code

**Pattern:** Names should explain purpose, not just what

---

## 7. Strategic Comments Explain "Why", Not "What"

### ❌ Before: No Context
```clojure
(let [state-with-pre (update state :pre-order conj id)
      children (get children-by-parent id [])
      state-after-children (reduce visit-node state-with-pre children)]
  (update state-after-children :post-order conj id))
```

### ✅ After: Explains Algorithm
```clojure
;; Record pre-order visit before children
(let [state-with-pre (update state :pre-order conj id)
      children (get children-by-parent id [])

      ;; Visit all children, threading state through
      state-after-children (reduce visit-node state-with-pre children)]

  ;; Record post-order visit after children
  (update state-after-children :post-order conj id))
```

**Why Better:**
- ✅ Comments explain the traversal algorithm
- ✅ Helps reader understand the "why"
- ✅ Documents invariants

**Pattern:** Comment the algorithm/intent, not the syntax

---

## 8. Threading Macros for Data Pipelines

### ❌ Before: Nested Function Calls
```clojure
(vec (concat (remove nil? validators)))
```

### ✅ After: Threading Shows Flow
```clojure
(->> validators
     (remove nil?)
     (mapcat identity)
     vec)
```

### ✅ Another Example
```clojure
(let [child->parents (->> children-by-parent
                          (mapcat (fn [[parent children]]
                                    (map #(vector % parent) children)))
                          (group-by first)
                          (m/filter-vals #(> (count %) 1)))]
  ...)
```

**Why Better:**
- ✅ Reads top-to-bottom like a pipeline
- ✅ Easy to add intermediate `tap>` for debugging
- ✅ Clear data transformation steps

**Pattern:** Use `->>` for data transformations, `->` for method chains

---

## 9. Docstrings Document Contracts

### ❌ Before: Minimal Documentation
```clojure
(defn- resolve-at-position
  "Resolve :at anchor to concrete index within siblings list."
  [siblings at]
  ...)
```

### ✅ After: Comprehensive Documentation
```clojure
(defn- resolve-at-position
  "Resolve :at anchor to concrete index within siblings list.

   Anchor types:
     - integer: direct index (clamped to [0, count])
     - :first/:last: start or end position
     - {:before id}: insert before target (or end if not found)
     - {:after id}: insert after target (or end if not found)

   Args:
     siblings - vector of sibling IDs
     at - anchor specification

   Returns:
     Concrete index (clamped to valid range)"
  [siblings at]
  ...)
```

**Why Better:**
- ✅ Documents all anchor types
- ✅ Specifies edge case behavior
- ✅ Clear arg/return contracts
- ✅ Serves as inline reference

**Pattern:** Docstrings should be mini-specs

---

## Summary: Quality Checklist

Before committing, ask:

- [ ] Are atoms necessary, or can I use pure functions?
- [ ] Is nesting depth ≤3 levels?
- [ ] Can complex branches be extracted into helpers?
- [ ] Are helper functions independently testable?
- [ ] Do names reveal intent, not just types?
- [ ] Are cross-platform conditionals extracted?
- [ ] Am I using idiomatic Clojure (medley, `neg?`, `cond->`, threading)?
- [ ] Do docstrings document all cases and edge behaviors?
- [ ] Are comments explaining "why", not "what"?
- [ ] Is data flow explicit (no hidden mutation)?

**Philosophy:** Code should be a joy to debug at 2am. Make it obvious.