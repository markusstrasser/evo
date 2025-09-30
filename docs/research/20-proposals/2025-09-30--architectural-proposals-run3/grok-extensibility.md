### Architectural Proposal: Interceptor Chain for Intent Compilation

#### 1. Core Idea
To enhance extensibility and composability, redesign the intent compilation model using an **interceptor chain** inspired by frameworks like Pedestal or Reitit in Clojure. Each high-level intent (e.g., `:indent`, `:delete`) is handled by a chain of interceptors—small, composable functions that process a shared context map. The context includes the current database (`:db`), the intent data (`:intent`), an accumulating vector of core operations (`:ops`), and optional metadata like `:trace` for debugging. Interceptors can read from the db's derived indexes to make decisions, append ops to `:ops`, or even modify the intent to delegate to sub-intents (enabling composition). 

A central registry maps intent types (e.g., `:indent`) to predefined chains of interceptors, making extension points discoverable via a public API function like `(register-intent-chain :my-intent [interceptor1 interceptor2])`. This replaces the current multimethod with a more flexible, chainable model where interceptors can be reused across intents (e.g., a "cycle-check" interceptor shared between `:indent` and `:move`). For compilation, a top-level function like `(compile-intent db intent)` executes the chain for that intent type, threading the context through each interceptor and returning the final `:ops`. This promotes expressiveness by allowing recursive composition (e.g., an `:outdent` interceptor could enqueue a sub-intent for `:place`), while ensuring testability through isolated interceptor functions.

#### 2. Key Benefits
- **Simplicity**: Interceptors are pure functions with a uniform signature `(context -> context)`, reducing boilerplate compared to multimethods that require dispatching and error handling per method. Defining a new intent is just composing existing interceptors, avoiding the need to write full multimethod bodies.
- **Readability**: The chain explicitly shows the flow of processing (e.g., validate → compute-target → emit-ops), making it easy to follow how an intent compiles to ops. Context keys are standardized, so developers can quickly understand what data is available or modified.
- **Debuggability**: Built-in tracing (e.g., appending to `:trace` in each interceptor) allows logging the context at every step, helping diagnose issues like why an op was emitted or skipped. Short-circuiting (via `:halt?` in context) provides clear failure points without deep stack traces.
- **Expressiveness**: Composition is natural—interceptors can enqueue sub-intents (e.g., `:indent` calls a "find-prev-sibling" interceptor, then enqueues a `:place` sub-intent). This enables hierarchical features, like a `:batch-edit` intent that composes multiple `:indent` and `:delete` chains, far beyond the current flat multimethod.

#### 3. Implementation Sketch
Define core structures in a new namespace `core.intent` (building on `core.struct`).

```clojure
(ns core.intent
  (:require [core.db :as db] [core.ops :as ops]))

;; Context: A map threaded through the chain
;; {:db Db, :intent {:type :keyword, ...}, :ops [Op], :trace [String], :halt? boolean, :meta {}}

;; Interceptor: A map with handlers (enter/leave similar to Pedestal)
(def example-interceptor
  {:name "find-prev-sibling"  ;; For discoverability and tracing
   :enter (fn [ctx]
            (let [id (get-in ctx [:intent :id])
                  prev (get-in ctx [:db :derived :prev-id-of id])]
              (if prev
                (assoc ctx :meta {:prev-id prev})  ;; Store temp data
                (assoc ctx :halt? true :trace (conj (:trace ctx) "No prev sibling found")))))})

;; Registry: Atom or Var holding {intent-type -> [interceptor]}
(def intent-chains (atom {:indent [validate-id-interceptor
                                   find-prev-sibling-interceptor
                                   emit-place-op-interceptor]
                          :delete [validate-id-interceptor
                                   emit-trash-place-interceptor]}))

(defn register-intent-chain [type interceptors]
  (swap! intent-chains assoc type interceptors))

;; Core compilation function
(defn compile-intent [initial-db intent]
  (let [chain (get @intent-chains (:type intent) [])  ;; Lookup chain
        initial-ctx {:db (db/derive-indexes initial-db)  ;; Ensure fresh derived data
                     :intent intent
                     :ops []
                     :trace []
                     :halt? false
                     :meta {}}]
    (reduce (fn [ctx {:keys [enter leave]}]  ;; Execute chain (enter -> leave for two-phase if needed)
              (if (:halt? ctx)
                (reduced ctx)  ;; Short-circuit
                (let [ctx' (if enter (enter ctx) ctx)]
                  (if leave (leave ctx') ctx'))))
            initial-ctx
            chain)))

;; Example usage (replaces core.struct/compile-intents)
(defn compile-intents [db intents]
  (mapcat (partial compile-intent db) intents))  ;; Returns flat [Op]

;; Example interceptor for composition (enqueues sub-intent)
(def emit-place-op-interceptor
  {:name "emit-place-op"
   :enter (fn [ctx]
            (let [prev (:prev-id (:meta ctx))
                  sub-intent {:type :place  ;; Or directly emit op; this shows composition
                              :id (get-in ctx [:intent :id])
                              :under prev
                              :at :last}
                  sub-ctx (compile-intent (:db ctx) sub-intent)]  ;; Recursive compile
              (update ctx :ops into (:ops sub-ctx))))})

;; Public discovery API
(defn list-intent-chains [] @intent-chains)  ;; Returns map for inspection
```

This sketch keeps the kernel unchanged; extensions register chains and use `compile-intents` before calling `interpret`.

#### 4. Tradeoffs and Risks
- **Tradeoffs**: Introduces a layer of indirection (context management) over direct multimethods, which might feel heavier for simple intents—e.g., a one-line multimethod becomes a chain of one interceptor. Performance isn't a focus, but large recursive chains could lead to stack overflows if not managed (mitigated by flattening ops early). Discoverability relies on the registry being well-documented; without it, developers might miss how to extend.
- **Risks**: Over-composition could create brittle chains where order matters implicitly (e.g., interceptor A assumes B ran first), leading to subtle bugs. Testing requires mocking contexts, which is more setup than testing a pure multimethod. If chains grow complex, it might obscure the "flat" elegance of the original 3-op kernel, risking violation of the "closed instruction set" principle by hiding too much logic in interceptors.

#### 5. How It Improves Developer Experience
- **REPL**: Developers can interactively build and test chains at the REPL, e.g., `(compile-intent db {:type :indent :id "b"})` to see emitted ops and trace immediately. Threading a context map allows easy inspection/modification, like `(-> initial-ctx find-prev-sibling-interceptor :enter)` for stepwise evaluation.
- **Debugging**: The `:trace` key auto-logs decisions (e.g., "Prev sibling: nil, halting"), making it simple to `println` or integrate with tools like `clojure.tools.trace`. Errors are localized to specific interceptors, with short-circuiting preventing cascading failures.
- **Testing**: Interceptors are pure and isolated, so unit tests can assert on `(interceptor-enter {:db mock-db :intent mock-intent}) => expected-ctx`. Composition tests can mock the registry and run full chains, e.g., using `with-redefs` on `intent-chains`. Property-based testing (via `test.check`) becomes easier by generating intents and verifying compiled ops against db invariants, ensuring extensions don't break kernel guarantees. Overall, this shifts from opaque multimethods to modular, inspectable pieces, encouraging experimentation without kernel changes.
