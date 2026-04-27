(ns kernel.derived-registry
  "Registry for derived-index plugins.

   A plugin is a map:
     {:keys      #{:derived-key ...}                        ; MANDATORY
      :initial   (fn [db] -> {index-key index-value ...})   ; MANDATORY
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
   cross-plugin dependency DAG problem by construction."
  (:require [clojure.set]))

;; ══════════════════════════════════════════════════════════════════════════════
;; Registry
;; ══════════════════════════════════════════════════════════════════════════════

(def recompute
  "Sentinel an :apply-tx returns to signal 'recompute via :initial'."
  ::recompute)

(def core-derived-keys
  "Derived keys owned by kernel.db. Plugins may not emit these."
  #{:parent-of
    :index-of
    :prev-id-of
    :next-id-of
    :pre
    :post
    :id-by-pre
    :doc/pre
    :doc/id-by-pre})

(defonce ^:private *plugins (atom {}))

(defn- plugin-key-owners
  [plugins excluding-plugin]
  (into {}
        (mapcat (fn [[plugin-id {declared-keys :keys}]]
                  (when-not (= plugin-id excluding-plugin)
                    (map (fn [derived-key] [derived-key plugin-id]) declared-keys))))
        plugins))

(defn- validate-spec [k spec]
  (when-not (map? spec)
    (throw (ex-info "Derived plugin spec must be a map with :keys and :initial"
                    {:key k :got spec})))
  (when-not (set? (:keys spec))
    (throw (ex-info "Derived plugin :keys must be a set"
                    {:key k :got (:keys spec)})))
  (when (empty? (:keys spec))
    (throw (ex-info "Derived plugin :keys must not be empty"
                    {:key k})))
  (when-not (ifn? (:initial spec))
    (throw (ex-info "Derived plugin :initial must be a function"
                    {:key k :got (:initial spec)})))
  (when (and (contains? spec :apply-tx)
             (not (ifn? (:apply-tx spec))))
    (throw (ex-info "Derived plugin :apply-tx must be a function (if provided)"
                    {:key k :got (:apply-tx spec)}))))

(defn- validate-key-ownership [plugins k spec]
  (let [declared (:keys spec)
        core-collisions (clojure.set/intersection declared core-derived-keys)
        owners (plugin-key-owners plugins k)
        plugin-collisions (select-keys owners declared)]
    (when (seq core-collisions)
      (throw (ex-info "Derived plugin collides with core derived keys"
                      {:key k :collisions core-collisions})))
    (when (seq plugin-collisions)
      (throw (ex-info "Derived plugin keys collide with another plugin"
                      {:key k :collisions plugin-collisions})))))

(defn register!
  "Register a derived-index plugin under key `k`.

   `spec` is a map with :keys and :initial (mandatory), optionally :apply-tx.
   Example:
     (register! :my-plugin
       {:keys #{:my-data}
        :initial (fn [db] {:my-data (compute db)})})

   Re-registration replaces the previous spec for `k` and releases its old keys."
  [k spec]
  (validate-spec k spec)
  (swap! *plugins
         (fn [plugins]
           (validate-key-ownership plugins k spec)
           (assoc plugins k spec)))
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

(defn- checked-plugin-output [plugin-id declared output]
  (when-not (map? output)
    (throw (ex-info "Derived plugin output must be a map"
                    {:plugin plugin-id :declared declared :emitted output})))
  (let [emitted (set (keys output))
        missing (clojure.set/difference declared emitted)
        extra (clojure.set/difference emitted declared)]
    (when (or (seq missing) (seq extra))
      (throw (ex-info "Derived plugin emitted keys must equal declared keys"
                      {:plugin plugin-id
                       :declared declared
                       :emitted emitted
                       :missing missing
                       :extra extra}))))
  output)

(defn run-all
  "Run :initial on every registered plugin and merge the results.

   Returns a map to be merged into db[:derived].

   Lean first cut always uses :initial — :apply-tx is called through a
   separate future entry point once plugins opt in.

   Plugin invocation order is unspecified, but declared key ownership makes
   merge order irrelevant. Broken plugins fail loudly."
  [db]
  (reduce (fn [acc [plugin-id {declared-keys :keys initial :initial}]]
            (merge acc (checked-plugin-output plugin-id declared-keys (initial db))))
          {}
          @*plugins))

(defn clear!
  "Drop all registered plugins. Used by tests."
  []
  (reset! *plugins {})
  nil)
