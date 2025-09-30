# Clojure Style Guide: Inspired Virtuosity

*Living examples from real refactorings in this codebase.*

## Core Philosophy

- **Eliminate ceremony**: Remove intermediate bindings that exist only to be assembled
- **Leverage idioms**: Use standard patterns the community recognizes instantly
- **Extract reusability**: Pull out helpers when they prevent duplication or improve testability
- **Inline simplicity**: Don't name things that are used exactly once and are self-evident

---

## Pattern: Eliminate Intermediate Bindings

**Before** - Extracting, then reassembling:
```clojure
(defn derive-indexes [db]
  (let [{:keys [children-by-parent roots]} db
        parent-of (compute-parent-of children-by-parent)
        index-of (compute-index-of children-by-parent)
        {:keys [prev-id-of next-id-of]} (compute-siblings children-by-parent)
        {:keys [pre post id-by-pre]} (compute-traversal children-by-parent roots)
        core-derived {:parent-of parent-of
                      :index-of index-of
                      :prev-id-of prev-id-of
                      :next-id-of next-id-of
                      :pre pre
                      :post post
                      :id-by-pre id-by-pre}]
    ...))
```

**After** - Direct construction with merge:
```clojure
(defn derive-indexes [db]
  (let [{:keys [children-by-parent roots]} db
        core-derived (merge {:parent-of (compute-parent-of children-by-parent)
                             :index-of (compute-index-of children-by-parent)}
                            (compute-siblings children-by-parent)
                            (compute-traversal children-by-parent roots))]
    ...))
```

**Why**: 7 intermediate bindings eliminated. Functions that return maps can be merged directly. Shorter, clearer data flow.

---

## Pattern: Idiomatic Flattening

**Before**:
```clojure
(let [errors (->> [(validate-children-exist ...)
                   (validate-no-duplicates ...)
                   (validate-single-parent ...)]
                  (apply concat)
                  vec)]
  ...)
```

**After**:
```clojure
(let [errors (->> [(validate-children-exist ...)
                   (validate-no-duplicates ...)
                   (validate-single-parent ...)]
                  (mapcat identity)
                  (remove nil?)
                  vec)]
  ...)
```

**Why**: `mapcat identity` is the standard Clojure idiom for flattening one level. More recognizable than `apply concat`. Adding `remove nil?` makes nil-handling explicit.

---

## Pattern: Extract Helpers to DRY Error Throwing

**Before** - Duplicated error construction:
```clojure
(defn ->index [db parent-id anchor]
  (let [kids (children db parent-id)]
    (cond
      (contains? anchor :before)
      (let [i (.indexOf kids (:before anchor))]
        (when (neg? i)
          (throw (ex-info "Anchor :before references unknown sibling"
                         {:reason ::missing-target
                          :anchor-type :before
                          :target-id (:before anchor)
                          :parent-id parent-id
                          :available-siblings kids})))
        {:idx i :normalized-anchor {:before (:before anchor)}})

      (contains? anchor :after)
      (let [i (.indexOf kids (:after anchor))]
        (when (neg? i)
          (throw (ex-info "Anchor :after references unknown sibling"  ;; DUPLICATE!
                         {:reason ::missing-target
                          :anchor-type :after
                          :target-id (:after anchor)
                          :parent-id parent-id
                          :available-siblings kids})))
        {:idx (inc i) :normalized-anchor {:after (:after anchor)}})
      ...)))
```

**After** - Extracted error helpers:
```clojure
(defn- throw-missing-target
  "Throw consistent error for missing sibling target."
  [anchor-type target-id parent-id kids]
  (throw (ex-info (str "Anchor " anchor-type " references unknown sibling")
                  {:reason ::missing-target
                   :anchor-type anchor-type
                   :target-id target-id
                   :parent-id parent-id
                   :available-siblings kids
                   :suggest {:replace-anchor :at-end}})))

(defn- resolve-before
  "Resolve {:before id} anchor."
  [kids parent-id target-id]
  (let [i (.indexOf kids target-id)]
    (when (neg? i)
      (throw-missing-target :before target-id parent-id kids))
    {:idx i :normalized-anchor {:before target-id}}))

(defn- resolve-after
  "Resolve {:after id} anchor."
  [kids parent-id target-id]
  (let [i (.indexOf kids target-id)]
    (when (neg? i)
      (throw-missing-target :after target-id parent-id kids))
    {:idx (inc i) :normalized-anchor {:after target-id}}))

(defn ->index [db parent-id anchor]
  (let [kids (children db parent-id)
        n (count kids)]
    (cond
      (or (= anchor :first) (= anchor :at-start))
      {:idx 0 :normalized-anchor :first}

      (map? anchor)
      (cond
        (contains? anchor :before) (resolve-before kids parent-id (:before anchor))
        (contains? anchor :after) (resolve-after kids parent-id (:after anchor))
        ...)
      ...)))
```

**Why**: Eliminated 7 duplicate error throws. Main function reduced from 92→34 lines. Each handler is testable independently. Error messages guaranteed consistent.

---

## Pattern: Extract Reusable Math/Algorithm Helpers

**Before** - Inline definitions:
```clojure
(defn order [p]
  (if (empty? p)
    1
    (let [cycles (loop [...] ...)  ; complex cycle detection
          gcd (fn gcd [a b]        ; inline GCD
                (if (zero? b) a (recur b (mod a b))))
          lcm (fn [a b]            ; inline LCM
                (quot (* a b) (gcd a b)))]
      (reduce lcm 1 cycles))))
```

**After** - Top-level helpers:
```clojure
(defn- gcd
  "Greatest common divisor using Euclidean algorithm."
  [a b]
  (if (zero? b) a (recur b (mod a b))))

(defn- lcm
  "Least common multiple of two integers."
  [a b]
  (quot (* a b) (gcd a b)))

(defn- find-cycle-lengths
  "Extract all cycle lengths from permutation p."
  [p]
  (loop [...]))

(defn order [p]
  (if (empty? p)
    1
    (->> p
         find-cycle-lengths
         (reduce lcm 1))))
```

**Why**: `gcd` and `lcm` are reusable. Cycle detection logic is testable independently. Main function becomes a clean pipeline.

---

## Pattern: Inline Simple Reducers

**Before** - Named single-use function:
```clojure
(defn- validate-ops [db ops]
  (let [indexed-ops (m/indexed ops)
        step-fn (fn [[current-db all-issues] [op-index op]]
                  (let [op-issues (validate-op current-db op op-index)]
                    (if (seq op-issues)
                      (reduced [current-db (into all-issues op-issues)])
                      [(apply-op current-db op) all-issues])))]
    (reduce step-fn [db []] indexed-ops)))
```

**After** - Standard reduce pattern:
```clojure
(defn- validate-ops [db ops]
  (reduce
   (fn [[current-db all-issues] [op-index op]]
     (let [op-issues (validate-op current-db op op-index)]
       (if (seq op-issues)
         ;; Stop on first error
         (reduced [current-db (into all-issues op-issues)])
         ;; Apply valid operation and continue
         [(apply-op current-db op) all-issues])))
   [db []]
   (m/indexed ops)))
```

**Why**: The reducer is simple enough to inline. Standard `(reduce fn init coll)` pattern is immediately recognizable. Eliminates intermediate binding for `indexed-ops` and `step-fn`.

**Trade-off**: Lose named function in stack traces. Acceptable when reducer logic is straightforward and well-commented.

---

## Pattern: Extract Pure Logic to Testable Helpers

**Before** - Complex nested conditionals:
```clojure
(defn- is-noop-place? [db op]
  (let [{:keys [id under at]} op]
    (cond
      (not= (:op op) :place) nil
      (not= under (get-in db [:derived :parent-of id])) nil
      :else
      (let [current-siblings (get-in db [:children-by-parent under] [])
            current-idx (find-index current-siblings id)
            siblings-without-node (vec (remove #(= % id) current-siblings))
            target-idx (resolve-anchor-position siblings-without-node at current-idx)]
        (= current-idx target-idx)))))
```

**After** - Extracted simulation helper:
```clojure
(defn- same-position-after-place?
  "Check if node would end up at the same index after place operation.
   Simulates remove → resolve anchor → insert to find final position."
  [siblings id at]
  (let [current-idx (find-index siblings id)
        siblings-without-node (vec (remove #(= % id) siblings))
        target-idx (resolve-anchor-position siblings-without-node at current-idx)]
    (= current-idx target-idx)))

(defn- is-noop-place? [db {:keys [op id under at]}]
  (when (= op :place)
    (let [current-parent (get-in db [:derived :parent-of id])]
      (and (= under current-parent)
           (same-position-after-place?
            (get-in db [:children-by-parent under] [])
            id
            at)))))
```

**Why**: The "remove → resolve → compare" logic is now testable without a full database. Guard clauses (`when`, `and`) replace nested `cond`. Single responsibility: `same-position-after-place?` handles simulation, `is-noop-place?` handles guards.

---

## Anti-Patterns to Avoid

### Over-naming
```clojure
;; BAD - needless intermediate binding
(let [x (+ 1 2)
      y (* x 3)]
  y)

;; GOOD - direct composition
(* (+ 1 2) 3)
```

### Under-naming
```clojure
;; BAD - duplicated complex logic
(if (and (pos? x) (< x 100) (even? x))
  ...
  (if (and (pos? y) (< y 100) (even? y))
    ...))

;; GOOD - extracted predicate
(defn valid-score? [n]
  (and (pos? n) (< n 100) (even? n)))

(if (valid-score? x) ... (if (valid-score? y) ...))
```

---

## Summary: The Refactoring Tests

Ask yourself:
1. **Can I eliminate bindings?** - If a value is used once to construct a map, merge directly
2. **Is there a standard idiom?** - `mapcat identity`, `(reduce f init coll)`, threading macros
3. **Am I repeating logic?** - Extract helpers for DRY
4. **Can this be tested independently?** - Extract pure functions
5. **Is this reusable elsewhere?** - Pull to top-level (gcd, lcm)
6. **Am I naming single-use things?** - Inline if simple and clear

**Goal**: Code that a Clojure expert would write. Clean, idiomatic, beautiful.
