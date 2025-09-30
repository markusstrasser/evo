Here is an architectural proposal for redesigning the extensibility model.

### **Architectural Proposal: The Interceptor Chain Model**

#### 1. Core Idea

Instead of compiling intents via a simple `mapcat` over a multimethod, we introduce an **Interceptor Chain** model, inspired by frameworks like Pedestal and Re-frame. In this model, compiling an intent is not a single transformation step but a process where a `context` map flows through a pipeline of interceptors. Each interceptor is a small, reusable piece of middleware that can inspect, modify, or augment the context before passing it along.

The `context` map is a rich object containing not just the initial database and intent, but also accumulating lists of `:ops`, `:effects` (for side-effects like logging), and a queue of remaining interceptors. The core `compile-intent` logic becomes the final "handler" in the chain. This transforms intent compilation from a stateless `A -> B` function into a stateful, composable workflow, allowing for powerful cross-cutting concerns (like validation, auditing, or conditional logic) to be applied declaratively.

#### 2. Key Benefits

*   **Expressiveness & Composability:** Interceptors are first-class values that can be mixed, matched, and reused. A complex workflow, like a "safe delete" that first checks for children and then archives them, can be composed from a `[:check-children :archive :audit]` chain instead of being written as one monolithic function. This makes the logic more granular and declarative.
*   **Readability & Simplicity:** Each interceptor has a single, well-defined responsibility (e.g., logging, authorization, validation). This separation of concerns makes the codebase easier to understand than a single large function with tangled logic. The business logic (the handler) is cleanly separated from the boilerplate.
*   **Debuggability:** The `context` map provides a complete, inspectable snapshot of the compilation process at every step. By adding a simple `:debug` interceptor, a developer can print the entire state of the world before and after any other interceptor, making it trivial to trace how data is being transformed.
*   **Discoverability:** Instead of searching the codebase for multimethod definitions, extensions become explicit. A central registry can map intent types to their default interceptor chains, providing a clear, documented entry point for developers to understand and extend system behavior.

#### 3. Implementation Sketch

We would replace the current `core.struct/compile-intents` with a new namespace, `evo.intent`, that manages the interceptor model.

**1. Define the `Context` and `Interceptor` Shapes:**

```clojure
;; The data that flows through the chain
(defrecord Context [db intent ops effects issues stack])

;; An interceptor is a map with :before and/or :after functions
(defrecord Interceptor [id before after])

;; Helper to create an interceptor
(defn interceptor [{:keys [id before after]}]
  (->Interceptor id
                 (or before identity)
                 (or after identity)))
```

**2. The Chain Executor:**

This function is the heart of the model. It processes the stack of interceptors in the context.

```clojure
(defn execute-chain [context]
  (loop [ctx context]
    (if-let [current-interceptor (first (:stack ctx))]
      ;; There are more interceptors to process
      (let [rest-of-stack (rest (:stack ctx))
            ;; 1. Run the :before function
            ctx-before    (-> ctx
                              (assoc :stack rest-of-stack)
                              ((:before current-interceptor)))]
        (if (reduced? ctx-before)
          ;; Short-circuit if :before returns a reduced value
          @ctx-before
          ;; 2. Recur with the rest of the stack
          (let [ctx-after-recursion (recur ctx-before)]
            ;; 3. Run the :after function on the result
            ((:after current-interceptor) ctx-after-recursion))))
      ;; Base case: the stack is empty
      ctx)))
```

**3. Define Handlers and Interceptors:**

Handlers are just interceptors that do the main work of converting an intent to ops.

```clojure
;; The core logic, now as a handler interceptor
(def delete-handler
  (interceptor
    {:id :delete-handler
     :before (fn [context]
               (let [{:keys [id]} (:intent context)]
                 (update context :ops conj {:op :place :id id :under :trash :at :last})))}))

;; A reusable middleware interceptor for auditing
(def audit-interceptor
  (interceptor
    {:id :audit
     :after (fn [context]
              (let [{:keys [user]} context
                    op-count (count (:ops context))]
                (update context :effects conj {:effect :log-audit
                                               :user user
                                               :intent (:intent context)
                                               :ops-generated op-count})))}))
```

**4. The Public API:**

The new `compile-intents` function assembles and runs the chain.

```clojure
;; A registry maps intents to their default chains
(def intent-registry
  {:delete [audit-interceptor delete-handler]
   ;; Other intents would be registered here
   })

(defn compile-intents [db intents]
  ;; The compilation now becomes a reduction, threading the DB state
  (let [initial-context {:db db, :ops [], :effects [], :issues []}]
    (reduce
      (fn [acc-ctx intent]
        (let [chain (get intent-registry (:type intent))]
          (if-not chain
            (update acc-ctx :issues conj {:issue :unknown-intent :intent intent})
            (let [run-ctx (assoc acc-ctx :intent intent :stack chain)
                  result-ctx (execute-chain run-ctx)]
              ;; Thread the new DB and ops forward for the next intent
              (-> acc-ctx
                  (assoc :db (:db result-ctx))
                  (update :ops into (:ops result-ctx))
                  ;; ... merge effects, issues, etc.
                  )))))
      initial-context
      intents)))
```

#### 4. Tradeoffs and Risks

*   **Increased Complexity:** The primary tradeoff is conceptual overhead. A developer must now understand the interceptor model (context, chains, before/after) instead of a simple function call. This is more powerful but has a steeper learning curve than `mapcat`.
*   **Performance:** There is a minor performance cost due to the additional function calls and map manipulations for each interceptor versus a direct multimethod dispatch. However, given the focus on architectural elegance, this is an acceptable tradeoff.
*   **Debugging Flow Control:** While the context is easy to inspect, debugging the *flow* of a deeply nested or complex chain can sometimes be challenging, similar to debugging promise chains or "callback hell" in other ecosystems.

#### 5. How It Improves Developer Experience

*   **REPL-Driven Development:** This model is exceptionally friendly to interactive development. A developer can construct a `context` map in the REPL and manually apply a single interceptor or an entire chain to it, immediately seeing the result. `(execute-chain test-context [audit-interceptor])`.
*   **Unit Testing:** Testing becomes significantly easier and more granular. Each interceptor can be unit-tested in isolation by providing it with a mock `context` and asserting on the transformed output context. There is no need to set up a full database state to test a piece of cross-cutting logic.
*   **Clear Extension Points:** To add a new feature, a developer can either create a new handler and register it, or write a new interceptor and prepend/append it to an existing chain. This is more explicit and discoverable than searching for all `defmethod` implementations for a given protocol. The `intent-registry` acts as a central, readable catalog of the system's capabilities.
