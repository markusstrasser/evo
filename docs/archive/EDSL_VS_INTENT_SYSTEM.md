# eDSL vs Intent System: Design Comparison

**TL;DR**: Both use "operations as data" to solve Hexagonal Architecture's coupling problem. They chose monadic composition (Free monad), we chose flat event sourcing. Both work; ours is simpler for our domain.

## The Problem

Hexagonal Architecture couples business logic to implementation:

```clojure
;; ❌ Business logic calls protocol methods directly
(defn indent-block [db block-id]
  (let [parent (get-parent db block-id)
        new-parent (get-next-sibling db parent)]
    (update-parent! db block-id new-parent)))
```

**Problems**:
- Tests require mocks for all protocols
- Easy to bypass abstractions
- Business logic mixed with side effects

## Two Solutions: eDSL vs Intent System

### Their eDSL Approach (Biotz/Free Monad)

```clojure
;; Operations as Hiccup
(defn indent-block [block-id]
  [:get-parent block-id
   (fn [parent]
     [:get-next-sibling parent
      (fn [new-parent]
        [:update-parent block-id new-parent])])])

;; Pluggable interpreter
(def test-impl
  {:get-parent (fn [id] "mock-parent")
   :get-next-sibling (fn [id] "mock-sibling")
   :update-parent (fn [id p] {:updated true})})

(interpreter test-impl (indent-block "a"))
```

**Pros**:
- Composable (monadic)
- Pluggable interpreters (easy test doubles)
- Solid FP theory

**Cons**:
- Complex (nested callbacks)
- Requires monad understanding
- Not event-sourceable (operations in closures)

### Our Intent System (Event Sourcing)

```clojure
;; Intent → operations multimethod
(defmethod intent->ops :indent-selected [{:keys [db]}]
  (let [selected (sel/get-selection db)
        focus (sel/get-focus db)
        parent (get-in db [:derived :parent-of focus])
        prev-id (get-in db [:derived :prev-id-of focus])]
    (when prev-id
      [{:op :update-node
        :id focus
        :updates {:parent prev-id}}])))

;; Kernel interprets operations
(interpret db [{:op :update-node :id "a" :updates {:parent "b"}}])
;; => {:db <updated-db> :issues []}
```

**Pros**:
- Simple (flat operations)
- Event-sourceable (ops = events)
- Debuggable (inspect operation log)
- No monad theory required

**Cons**:
- Less composable than monads
- Interpreter baked into kernel
- Testing edge cases requires real DB setup

## Making Our Interpreter Pluggable

### Current Limitation

```clojure
;; Testing edge cases requires setting up real DB
(let [db (-> (DB/empty-db)
             (I/interpret [{:op :create-node :id "a" ...}])
             :db
             (I/interpret [{:op :create-node :id "b" ...}])
             :db)]
  ;; Now we can test indent behavior
  (is (= expected (intent->ops {:type :indent-selected} db))))
```

**Problem**: Setting up complex DB state for edge cases is tedious.

### With Pluggable Operations

```clojure
;; Make ops/apply accept optional implementation map
(defn apply-op
  "Apply a single operation to db. Optionally use custom impl map."
  ([db op] (apply-op db op nil))
  ([db op impl]
   (let [op-type (:op op)
         op-fn (or (get impl op-type)
                   (case op-type
                     :create-node ops/create-node
                     :update-node ops/update-node
                     :delete-node ops/delete-node
                     :place ops/place))]
     (op-fn db op))))
```

### Example 1: Test Error Paths

```clojure
;; Test what happens when update-node fails
(def error-impl
  {:update-node (fn [db op]
                  (throw (ex-info "Network error" {:op op})))})

(testing "handles update failures gracefully"
  (let [result (interpret db [{:op :update-node :id "a" :updates {:foo "bar"}}]
                          :impl error-impl)]
    (is (seq (:issues result)))
    (is (= "Network error" (-> result :issues first :message)))))
```

### Example 2: Test Race Conditions

```clojure
;; Simulate concurrent modifications
(def stale-read-impl
  {:update-node (fn [db {:keys [id updates]}]
                  ;; Return stale version
                  (if (= id "a")
                    (throw (ex-info "Stale read" {:id id}))
                    (ops/update-node db {:id id :updates updates})))})

(testing "detects stale reads"
  (let [result (interpret db [{:op :update-node :id "a" :updates {:text "new"}}]
                          :impl stale-read-impl)]
    (is (seq (:issues result)))))
```

### Example 3: Trace Operations

```clojure
;; Log all operations for debugging
(def trace-impl
  {:create-node (fn [db op]
                  (println "CREATE:" op)
                  (ops/create-node db op))
   :update-node (fn [db op]
                  (println "UPDATE:" op)
                  (ops/update-node db op))
   :delete-node (fn [db op]
                  (println "DELETE:" op)
                  (ops/delete-node db op))})

;; Now all operations print to console
(interpret db [{:op :create-node :id "a" :type :block :props {:text "foo"}}]
           :impl trace-impl)
;; => CREATE: {:op :create-node :id "a" ...}
```

### Example 4: Test Performance

```clojure
;; Measure operation timing
(def perf-impl
  {:update-node (fn [db op]
                  (let [start (js/performance.now)
                        result (ops/update-node db op)
                        end (js/performance.now)]
                    (println (str "update-node took " (- end start) "ms"))
                    result))})
```

## Implementation

```clojure
;; core/interpret.cljc
(defn interpret
  "Interpret operations against db.

  Options:
  - :impl - Map of {:op-type op-fn} for custom operation implementations.
            Useful for testing edge cases, tracing, or performance monitoring."
  [db ops & {:keys [impl]}]
  (reduce
   (fn [acc op]
     (if (seq (:issues acc))
       acc  ; Stop on first error
       (try
         (let [db' (if impl
                     (apply-op (:db acc) op impl)
                     (apply-op (:db acc) op))]
           (assoc acc :db db'))
         (catch #?(:clj Exception :cljs js/Error) e
           (update acc :issues conj
                   {:op op
                    :message (ex-message e)
                    :data (ex-data e)})))))
   {:db db :issues []}
   ops))
```

## Payoff

### Before (Current)

```clojure
;; Setting up complex edge case = lots of boilerplate
(testing "handles deeply nested structure"
  (let [db (-> (DB/empty-db)
               (I/interpret [{:op :create-node :id "page" :type :page :props {}}])
               :db
               (I/interpret [{:op :place :id "page" :under :doc :at :last}])
               :db
               (I/interpret [{:op :create-node :id "a" :type :block :props {:text "a"}}])
               :db
               (I/interpret [{:op :place :id "a" :under "page" :at :last}])
               :db
               ;; ... 20 more setup operations
               )]
    (is (= expected (intent->ops {:type :indent-selected} db)))))
```

### After (With Pluggable Impl)

```clojure
;; Test edge case directly
(testing "handles deeply nested structure"
  (let [mock-impl {:get-parent (constantly "deeply-nested-parent")
                   :get-prev-sibling (constantly nil)}]
    (is (= expected (interpret db ops :impl mock-impl)))))

;; Or trace what's happening
(testing "debug complex workflow"
  (interpret db ops :impl trace-impl)
  ;; Console shows exact operation sequence
  )

;; Or test error paths
(testing "handles network failures"
  (let [result (interpret db ops :impl error-impl)]
    (is (seq (:issues result)))))
```

## When to Use Each

### Use eDSL (Biotz approach) when:
- Complex composition (nested conditionals, loops)
- Need multiple interpreters (test/dev/prod/trace)
- FP team comfortable with monads
- Domain has complex control flow

### Use Intent System (our approach) when:
- Event sourcing is a requirement
- Operations should be appendable to log
- Simple workflows (CRUD + relationships)
- Team prefers straightforward data flow
- 80/20: Simple enough for flat operations

## Our Verdict

**Keep the intent system**, but steal their pluggable interpreter idea:

```clojure
;; Add optional :impl parameter to interpret
(interpret db ops :impl test-impl)  ; Use custom implementations
(interpret db ops)                  ; Use kernel's built-in ops
```

**Payoff**:
1. Test edge cases without DB setup ceremony
2. Trace operations for debugging
3. Inject failures to test error handling
4. Measure performance of specific operations
5. All without changing kernel code

**Cost**: Minimal - just thread `impl` through `interpret` and `apply-op`.

## References

- **Biotz article**: [Domain Driven Design in Clojure with generalized Hiccup](https://biotz.io/posts/domain-driven-design-in-clojure-with-generalized-hiccup)
- **Free monad**: Haskell's `FFree` construction for composable effects
- **Our kernel**: `src/kernel/interpret.cljc` - flat event sourcing pipeline
- **Event sourcing**: Operations as append-only log (inherently testable)
