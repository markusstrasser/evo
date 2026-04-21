(ns kernel.derived-registry
  "Registry for derived-index plugins.

   A plugin is a map:
     {:initial   (fn [db] -> {index-key index-value ...})   ; MANDATORY
      :apply-tx  (fn [prev-index db-before tx-ops db-after]
                    -> {index-key ...} | :kernel.derived-registry/recompute)}

   :initial is the *oracle*: it computes the plugin's index contribution
   from scratch. It must be a pure function of the db.

   :apply-tx is an OPTIONAL incremental-maintenance path. When implemented
   it must satisfy the contract
     (apply-tx (initial db-before) db-before tx-ops db-after)
     = (initial db-after)
   i.e. it's a checkable optimization — never a second source of truth.
   Returning `::recompute` (or being absent entirely) tells the kernel to
   fall back to `(initial db-after)`.

   Lean first cut (Phase D of the kernel refactor): no plugin implements
   :apply-tx yet. The protocol surface is in place so profile-driven
   per-plugin migration can happen incrementally without kernel churn.
   The kernel does not use protocols — plugins are plain data to keep the
   kernel free of dispatch machinery (see CLAUDE.md kernel invariants).

   Isolation rule (Phase D design §4): a plugin's :initial / :apply-tx
   reads ONLY:
     - canonical db (:nodes, :children-by-parent, :roots)
     - kernel-maintained core indexes (:parent-of, :prev-id-of, etc.)
     - its own previous index value (for :apply-tx)
   Plugins MUST NOT read other plugins' indexes. This eliminates the
   cross-plugin dependency DAG problem by construction.")

;; ══════════════════════════════════════════════════════════════════════════════
;; Registry
;; ══════════════════════════════════════════════════════════════════════════════

(def recompute
  "Sentinel an :apply-tx returns to signal 'recompute via :initial'."
  ::recompute)

(defonce ^:private *plugins (atom {}))

(defn- validate-spec [k spec]
  (when-not (map? spec)
    (throw (ex-info "Derived plugin spec must be a map with :initial"
                    {:key k :got spec})))
  (when-not (ifn? (:initial spec))
    (throw (ex-info "Derived plugin :initial must be a function"
                    {:key k :got (:initial spec)})))
  (when (and (contains? spec :apply-tx)
             (not (ifn? (:apply-tx spec))))
    (throw (ex-info "Derived plugin :apply-tx must be a function (if provided)"
                    {:key k :got (:apply-tx spec)}))))

(defn register!
  "Register a derived-index plugin under key `k`.

   `spec` is a map with :initial (mandatory) and optionally :apply-tx.
   Example:
     (register! :my-plugin {:initial (fn [db] {:my-data (compute db)})})

   Re-registration replaces the previous spec for `k`."
  [k spec]
  (validate-spec k spec)
  (swap! *plugins assoc k spec)
  nil)

(defn register-derived!
  "Alias for register!. Communicates at call-sites that this plugin only
   contributes to db[:derived]."
  [k spec]
  (register! k spec))

(defn unregister!
  "Remove a plugin from the registry. Used by tests / hot-reload."
  [k]
  (swap! *plugins dissoc k)
  nil)

(defn registered
  "Return the full registry map {k -> spec}."
  []
  @*plugins)

(defn run-all
  "Run :initial on every registered plugin and merge the results.

   Returns a map to be merged into db[:derived].

   Lean first cut always uses :initial — :apply-tx is called through a
   separate future entry point once plugins opt in.

   Plugin invocation order is unspecified (iteration over a hash-map).
   Collisions between plugins emitting the same derived key are therefore
   UNDEFINED BEHAVIOR — one will silently win, but which one is not
   guaranteed to be stable across runs. The Phase D isolation rule
   requires plugins to partition the derived-key namespace; collisions
   indicate a plugin bug, not a merge policy."
  [db]
  (reduce (fn [acc [_k {:keys [initial]}]]
            (try
              (merge acc (initial db))
              (catch #?(:clj Exception :cljs js/Error) e
                #?(:clj (println "Plugin error:" (.getMessage e))
                   :cljs (.error js/console "Plugin error:" (.-message e)))
                acc)))
          {}
          @*plugins))

(defn clear!
  "Drop all registered plugins. Used by tests."
  []
  (reset! *plugins {})
  nil)
