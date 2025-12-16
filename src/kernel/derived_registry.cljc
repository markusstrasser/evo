(ns kernel.derived-registry
  "Registry for derived index extensions.

  Plugins are pure functions Db → {keyword any} that compute additional
  derived views. They cannot mutate canonical data.

  Plugins are registered once at startup and run after every transaction.

  NOTE: Renamed from plugins.registry to clarify that this is kernel
  infrastructure for derived indexes, not intent handlers.")

;; ══════════════════════════════════════════════════════════════════════════════
;; Registry
;; ══════════════════════════════════════════════════════════════════════════════

;; Atom holding registered plugins: {keyword (fn [db] → map)}
(defonce ^:private *plugins (atom {}))

;; ══════════════════════════════════════════════════════════════════════════════
;; Public API
;; ══════════════════════════════════════════════════════════════════════════════

(defn register!
  "Register a plugin with the given key and function.

   k: keyword identifier for the plugin
   f: pure function Db → {keyword any}

   The plugin function should return a map of derived data to be merged
   into db[:derived].

   Example:
     (register! :my-plugin
                (fn [db]
                  {:my-data (compute-something db)}))"
  [k f]
  (swap! *plugins assoc k f)
  nil)

(defn register-derived!
  "Alias for register! that clarifies intent for derived index plugins.

   Useful when scanning call-sites: `register-derived!` communicates that the
   plugin only contributes to db[:derived] and never mutates canonical data."
  [k f]
  (register! k f))

(defn unregister!
  "Remove a plugin from the registry.

   Useful for testing or hot-reloading."
  [k]
  (swap! *plugins dissoc k)
  nil)

(defn registered
  "Return a map of all registered plugins."
  []
  @*plugins)

(defn run-all
  "Run all registered plugins on db and merge their outputs.

   Returns a map to be deep-merged into db[:derived].

   Plugins are run in an unspecified order. If multiple plugins
   return the same key, later plugins will override earlier ones."
  [db]
  (reduce (fn [acc [_k f]]
            (try
              (merge acc (f db))
              (catch #?(:clj Exception :cljs js/Error) e
                ;; Log error but don't fail the whole derive phase
                #?(:clj (println "Plugin error:" (.getMessage e))
                   :cljs (.error js/console "Plugin error:" (.-message e)))
                acc)))
          {}
          @*plugins))

(defn clear!
  "Clear all registered plugins.

   Useful for testing."
  []
  (reset! *plugins {})
  nil)
