### Architectural Proposal: Modular Indexer Registry with Composable Views

#### 1. Core Idea
The current `derive-indexes` function computes all derived data in a monolithic, threaded pass, tightly coupling the logic for parent-of, index-of, siblings, traversal, and plugins. To decouple this, redesign derived indexes as a registry of independent *indexers*—pure functions that each compute a single, focused index or view from the canonical state. Each indexer declares its dependencies (e.g., parent-of must run after children-by-parent is available, but before traversal). The `derive-indexes` function becomes a coordinator that topologically sorts and executes these indexers in order, threading an intermediate `:derived` map through them. This creates composable "computed views" where indexes are pluggable modules, allowing extensions (like plugins) to register new indexers without modifying core code.

For invalidation and recomputation, stick to full recomputation on demand (as in the current system) for simplicity, but add optional dirty flags per indexer for future optimization. Indexes become more abstract as "views" by defining them as data-driven specs (e.g., a map with `:compute-fn`, `:deps`, and `:validate-fn`), enabling runtime registration and composition. Debuggability is enhanced by making the derivation process traceable, with each indexer outputting an audit log of changes, and allowing partial execution for testing.

#### 2. Key Benefits
- **Simplicity**: Breaks the monolithic `derive-indexes` into small, single-responsibility functions, reducing cognitive load. No more intertwined let-bindings; each indexer is self-contained.
- **Readability**: Dependencies are explicit (e.g., traversal indexer declares it needs `:parent-of`), making the computation graph clear and easier to reason about without scanning threaded code.
- **Debuggability**: Intermediate states can be inspected or logged per indexer, pinpointing failures (e.g., "traversal failed due to missing parent-of"). Audit logs provide a trace like "parent-of added 5 entries."
- **Expressiveness**: Plugins can register custom indexers (e.g., a search index) that compose with core ones, extending the system without forking. This supports "computed view" patterns, like lazy views or cached subsets, by allowing indexers to return functions instead of full maps for on-demand access.

#### 3. Implementation Sketch
Define indexers as data maps in a registry. The `derive-indexers` function sorts and executes them.

```clojure
;; Registry: A map of keyword → indexer spec
(def indexer-registry
  (atom {:parent-of    {:deps []  ; No deps, computes from canonical :children-by-parent
                        :compute-fn (fn [db derived]
                                      (let [parent-of (invert-children-map (:children-by-parent db))]
                                        {:parent-of parent-of}))
                        :validate-fn (fn [derived] (every? string? (keys (:parent-of derived))))}
         :index-of     {:deps [:parent-of]  ; Needs parent-of to know siblings
                        :compute-fn (fn [db derived]
                                      (let [index-of (build-index-map (:children-by-parent db) (:parent-of derived))]
                                        {:index-of index-of}))}
         :siblings     {:deps [:index-of]
                        :compute-fn (fn [db derived] ...)}  ; Similar for prev/next
         :traversal    {:deps [:parent-of :siblings]  ; Recursive walk needs these
                        :compute-fn (fn [db derived]
                                      (let [{:keys [pre post id-by-pre]} (compute-traversal (:roots db) (:parent-of derived) (:children-by-parent db))]
                                        {:pre pre :post post :id-by-pre id-by-pre}))}
         :plugins      {:deps [:traversal]  ; Plugins run last
                        :compute-fn (fn [db derived] (plugins/run-all db derived))}}))

;; Topological sort helper (using e.g., clojure.tools.namespace or simple DFS)
(defn sort-indexers [registry]
  (topo-sort (keys registry) (fn [k] (:deps (get registry k)))))

;; New derive-indexes: Coordinates execution, threads derived map
(defn derive-indexes [db & {:keys [trace?]}]
  (let [ordered-keys (sort-indexers @indexer-registry)
        init-derived {}
        [final-derived trace] (reduce (fn [[derived trace] k]
                                        (let [indexer (get @indexer-registry k)
                                              new-derived ((:compute-fn indexer) db derived)
                                              issues (when-let [v (:validate-fn indexer)] (v new-derived))]
                                          (when (seq issues) (throw (ex-info "Validation failed" {:issues issues})))
                                          [ (merge derived new-derived)
                                            (if trace? (conj trace {:indexer k :added (count new-derived) :sample (take 2 (vals new-derived))}) trace) ]))
                                      [init-derived []]
                                      ordered-keys)]
    (assoc db :derived final-derived :trace (when trace? trace))))

;; Plugin registration example
(defn register-indexer [k spec]
  (swap! indexer-registry assoc k spec))

;; Usage
(register-indexer :custom-search {:deps [:traversal] :compute-fn (fn [db derived] {:search-index (build-search (:nodes db) (:pre derived))})})
(let [db' (derive-indexes db :trace? true)]
  (println (:trace db')))  ; Outputs audit log for debugging
```

- **Invalidation**: Add a `:dirty?` flag to each spec; `derive-indexes` skips clean ones unless forced. For full recompute, ignore flags.
- **Pluggability**: Extensions call `register-indexer` at init, specifying deps to insert into the graph.
- **Testing**: Each `:compute-fn` is pure and isolated, e.g., `(compute-parent-of minimal-db {}) → expected-map`.

#### 4. Tradeoffs and Risks
- **Tradeoffs**: Introduces overhead from topological sorting and registry management, potentially slowing startup or derivation for very large registries (though negligible for core indexes). Full recomputation remains the default, so no performance gains without adding incremental logic, which could complicate purity. More modular code means more functions to navigate, but explicit deps mitigate this.
- **Risks**: Dependency cycles in the registry could cause sort failures—mitigate with validation on registration. If plugins register conflicting keys, it could overwrite core indexers; use namespaces (e.g., `:plugin/search`) or a priority system. Over-pluggability might lead to bloated `:derived` maps if not managed, increasing memory use. Testing complexity rises slightly for integration (ensuring dep order), but per-indexer isolation helps.

#### 5. How It Improves Developer Experience
- **REPL**: Developers can register/test indexers interactively `(register-indexer :test {...})`, then `(derive-indexes db)` and inspect `:derived` or `:trace` piecemeal. Partial execution is easy: `(reduce ... subset-ordered-keys)` to debug a chain.
- **Debugging**: The `:trace` option provides a step-by-step log (e.g., "siblings added 10 entries"), making it simple to bisect issues in tools like CIDER or VS Code. Errors are localized (e.g., throw from a single indexer) rather than buried in a monolithic function.
- **Testing**: Unit tests focus on individual `:compute-fn` with mock `derived` inputs, e.g., `(deftest test-parent-of (is (= expected (compute-parent-of db {}))))`. Integration tests validate the full graph via `sort-indexers` and `derive-indexes`. Property-based testing (e.g., with clojure.spec/test.check) can generate registries and dbs to ensure no cycles or validation failures, improving confidence in extensions. Overall, this shifts from opaque threading to a declarative, inspectable model, encouraging experimentation without fear of breaking the core.
