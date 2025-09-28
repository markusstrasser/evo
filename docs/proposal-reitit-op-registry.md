# Proposal: A Data-Driven, Extensible Operation Registry

**Inspiration Source**: `/Users/alien/Projects/inspo-clones/reitit/modules/reitit-core/src/reitit/core.cljc`

Reitit's core `router` function is a brilliant example of data-driven architecture. It takes declarative data (routes) and compiles an optimized implementation. We can apply this principle to the kernel's `dispatch` function to make it extensible and more modular.

---

### Before: Hardcoded and Inflexible

The current `dispatch` function uses a rigid, hardcoded `case` statement. This means the set of all possible operations is closed and can only be modified by changing the kernel's source code.

```clojure
;; **COMPLEXITY**: Low (initially), but infinitely rigid.
;; The `case` statement is simple to understand, but it creates a monolithic
;; kernel. There is no way for a user to add their own high-level,
;; domain-specific operations without forking the core.

(defn- dispatch [k]
  (case k
    ;; Core Ops
    :create-node create-node*
    :place place*
    
    ;; Sugar Ops
    :insert insert
    :move move
    
    ;; To add a new op, you MUST edit this file.
    ))
```

### After: Dynamic and Extensible

By replacing the `case` statement with a dynamic registry, the kernel becomes an open, extensible system.

```clojure
;; **COMPLEXITY**: Low (for both kernel and user).
;; The kernel's dispatch logic is simplified to a map lookup. The user can
;; easily add new functionality through a clean, public API, without ever
;; touching the kernel's internals.

;; 1. The registry is just data
(def !op-registry (atom {:insert insert, :move move, ...}))

;; 2. Dispatch is a simple lookup
(defn- dispatch [k]
  (get @!op-registry k))

;; 3. Users can add new operations dynamically
(defn swap-nodes [db {:keys [a b]}]
  (let [parent-a (parent-id-of* db a)
        parent-b (parent-id-of* db b)]
    (-> db
        (place* {:id a :parent-id parent-b})
        (place* {:id b :parent-id parent-a}))))

(register-op! :swap-nodes swap-nodes)

;; 4. The new operation is now a valid part of the kernel's vocabulary
(apply-tx* db {:op :swap-nodes :a "node-1" :b "node-2"})
```

---
### Summary of Improvements

*   **Extensibility**: This is the primary benefit. The kernel is no longer a closed system. It provides a stable set of core primitives and allows users (or LLMs) to build a rich library of domain-specific sugar operations on top.
*   **Modularity**: Sugar operations can be defined in their own namespaces, completely separate from the kernel's core, improving code organization.
*   **Reduced Kernel Surface Area**: The core kernel becomes smaller and more stable, as new functionality is added via registration rather than direct modification.
*   **Empowers the Agent**: An LLM agent can use this to define its own high-level actions, effectively teaching the kernel new tricks tailored to the specific problem it's trying to solve.