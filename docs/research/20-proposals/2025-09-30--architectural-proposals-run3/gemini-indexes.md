Here is an architectural proposal for decoupling the derived index system.

### 1. Core Idea

The proposal is to replace the monolithic `derive-indexes` function with a declarative, pluggable **Derived View Engine**. Instead of a single, hardcoded function that computes all indexes in one pass, each derived index (or "view") is defined as a self-contained, registered component. These components declare their own data dependencies and provide a pure function for their computation.

A central engine is responsible for resolving the dependency graph between these views and the canonical state, executing the computations in the correct order. This turns index computation from an implicit, procedural operation into an explicit, data-driven one. Developers can add, remove, or modify views without touching the core engine, and the system can compute any subset of views on demand, making the entire process more modular, transparent, and debuggable.

### 2. Key Benefits

*   **Simplicity & Readability:** Each view's logic is isolated in its own definition (e.g., a `parent-of.clj` file could contain the spec and compute function). This is much easier to understand than tracing logic through a single, large `derive-indexes` function with multiple `let` bindings.
*   **Debuggability:** You can ask the engine to compute a single view or a specific subset of views. This allows you to isolate and inspect a problematic index without the noise of computing all other indexes. The explicit dependency graph also makes it trivial to see what data a view depends on.
*   **Expressiveness & Composability:** Views can depend not only on the canonical state but also on other views. For example, a new `:siblings` view could declare a dependency on `:parent-of` and `:children-by-parent`. The engine's dependency resolver would automatically compute them in the correct order. This makes complex, layered computations clean and declarative.
*   **Pluggable Architecture:** Adding a new derived index becomes a matter of defining a new view spec and registering it. No modification to the core `core.db` or `core.interpret` namespaces is needed, adhering to the Open/Closed Principle. This is ideal for plugins or extensions that need to build their own specialized indexes on the core data structure.

### 3. Implementation Sketch

We introduce a `ViewRegistry` and a new `compute-views` function that replaces `derive-indexes`.

**(1) Define the View Spec Structure:**

Each view is a map with three keys:
*   `:key`: The keyword identifying the view in the `:derived` map (e.g., `:parent-of`).
*   `:deps`: A set of keys this view depends on. These can be from the canonical state (e.g., `:children-by-parent`) or other derived views (e.g., `:parent-of`).
*   `:compute-fn`: A pure function `(fn [db] -> result-map)` that computes the view. It receives the full `db` containing its already-computed dependencies and returns a map of its results (e.g., `{:parent-of {...}}`).

**(2) Create a Registry:**

```clojure
;; In a new namespace, e.g., `core.views`
(defonce view-registry (atom {}))

(defn register-view [view-spec]
  (swap! view-registry assoc (:key view-spec) view-spec))
```

**(3) Example View Definitions:**

```clojure
;; core/views/parent_of.clj
(def parent-of-view
  {:key :parent-of
   :deps #{:children-by-parent}
   :compute-fn
   (fn [{:keys [children-by-parent]}]
     (let [parent-map (reduce-kv
                        (fn [acc parent children]
                          (reduce #(assoc %1 %2 parent) acc children))
                        {}
                        children-by-parent)]
       {:parent-of parent-map}))})

;; core/views/siblings.clj
(def siblings-view
  {:key :siblings ; A new, combined view for prev/next
   :deps #{:children-by-parent :parent-of} ; Depends on another view!
   :compute-fn
   (fn [{:keys [children-by-parent] :as db}]
     ;; ... logic to compute :prev-id-of and :next-id-of ...
     ;; This logic is now completely isolated.
     {:prev-id-of {...}
      :next-id-of {...}})})

;; Register them at startup
(register-view parent-of-view)
(register-view siblings-view)
```

**(4) The View Engine:**

The `compute-views` function resolves dependencies and executes the computations.

```clojure
;; In core.views, replacing core.db/derive-indexes
(defn compute-views
  "Computes derived views for the db.
  Can compute all views or a specified subset."
  ([db]
   (compute-views db (keys @view-registry)))
  ([db view-keys]
   (let [;; 1. Build a dependency graph for the requested keys
         graph (build-dependency-graph @view-registry view-keys)
         ;; 2. Topologically sort the graph to get computation order
         compute-order (topological-sort graph)]
     (loop [;; 3. Reduce over the sorted views, threading the db
            [key & more] compute-order
            current-db db]
       (if-not key
         current-db ; Return the final db when done
         (let [view-spec (get @view-registry key)
               ;; 4. Execute the compute function
               new-derived-data ((:compute-fn view-spec) current-db)]
           (recur more (update-in current-db [:derived] merge new-derived-data))))))))
```

### 4. Tradeoffs and Risks

*   **Performance:** The primary tradeoff is performance for clarity. The original single-pass `derive-indexes` is likely faster as it can compute multiple indexes during a single traversal of the tree. This new model may perform multiple traversals (e.g., `parent-of` traverses, then `siblings` traverses). For many applications, this is a worthy tradeoff, and optimizations like memoization or combining compatible views into a single pass could be added to the engine later if needed.
*   **Increased Indirection:** The logic is now spread across more files and a central registry. This is a net-win for modularity but requires developers to understand the registry pattern instead of just reading a single function.
*   **Dependency Cycle Detection:** The `build-dependency-graph` step must include robust cycle detection. A programming error could introduce a circular dependency between views (e.g., A depends on B, and B depends on A), which would hang the computation.

### 5. How It Improves Developer Experience

*   **REPL-Driven Development:** This architecture is vastly superior for interactive development. A developer can instantly debug or inspect a single part of the derived state without re-running the entire monolithic function:
    ```clojure
    ;; In the REPL, with a database `db`
    ;; Just compute and inspect the parent-of index
    (compute-views db [:parent-of])

    ;; See how the traversal indexes look in isolation
    (compute-views db [:pre :post])
    ```
*   **Focused Testing:** Unit testing becomes trivial. Each view's `:compute-fn` is a pure function that can be tested in complete isolation by providing it with a mock `db` containing only its declared dependencies.
    ```clojure
    (deftest parent-of-test
      (let [mock-db {:children-by-parent {:root ["a"] "a" ["b"]}}
            result ((:compute-fn parent-of-view) mock-db)]
        (is (= {:parent-of {"a" :root "b" "a"}} result))))
    ```
*   **Confident Extensibility:** When a developer needs a new index, they no longer face the daunting task of modifying a critical, complex core function. They can confidently add a new, isolated view file, write its logic and tests, and register it, knowing they are unlikely to break any existing index computations.
