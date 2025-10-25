(ns kernel.intent
  "Intent router - all intents compile to core operations.

   Design: Unified Intent-to-Ops Pattern
   - intent->ops: Compiles all intents (structural + session) to core operations
   - apply-intent: Returns ops for caller to interpret
   - Session state (selection, edit, cursor) stored as nodes, changed via ops

   This unifies the event handling pipeline through the 3-op kernel.
   All state changes go through validate/derive, enabling full undo/redo.

   Example usage:
     ;; Structural intent (compiles to ops)
     (apply-intent db {:type :indent :id \"a\"})

     ;; Session intent (compiles to ops on session nodes)
     (apply-intent db {:type :select :ids [\"a\" \"b\"]})")

;; ── Intent Registry (Data, No Macros) ────────────────────────────────────────

(defonce !intents (atom {}))

;; ── Registration API ──────────────────────────────────────────────────────────

(defn register-intent!
  "Register an intent handler with metadata.

   Args:
   - kw: Intent type keyword (e.g. :select, :indent)
   - config: Map with :doc, :spec, :handler keys

   Example:
     (register-intent! :select
       {:doc \"Set selection\"
        :spec [:map [:type [:= :select]] [:ids [:vector :string]]]
        :handler (fn [db {:keys [ids]}]
                   [{:op :update-node :id \"session/selection\" :props {...}}])})"
  [kw {:keys [doc spec handler]}]
  (swap! !intents assoc kw {:doc doc :spec spec :handler handler})
  nil)

;; ── Unified Entry Point ───────────────────────────────────────────────────────

(defn apply-intent
  "Apply an intent by compiling it to operations.

   Returns: {:db unchanged-db :ops [operations]}

   The caller is responsible for interpreting the ops through tx/interpret.
   All intents (structural + session) now go through the ops pipeline.

   Example:
     (apply-intent db {:type :indent :id \"a\"})
     ;=> {:db db :ops [{:op :place ...}]}

     (apply-intent db {:type :select :ids [\"a\"]})
     ;=> {:db db :ops [{:op :update-node :id \"session/selection\" ...}]}"
  [db intent]
  (if-let [handler (get-in @!intents [(:type intent) :handler])]
    {:db db :ops (vec (or (handler db intent) []))}
    {:db db :ops []}))

;; ── Convenience helpers ───────────────────────────────────────────────────────

(defn has-handler?
  "Returns true if intent type has a registered handler."
  [intent-type]
  (contains? @!intents intent-type))

(defn list-intents
  "List all registered intent types with their metadata."
  []
  (into {} (map (fn [[k v]] [k (dissoc v :handler)]) @!intents)))

(defn intent->ops
  "DEPRECATED: Use apply-intent instead. Kept for backward compatibility."
  [db intent]
  (:ops (apply-intent db intent)))
