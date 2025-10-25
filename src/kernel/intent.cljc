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

;; ── Intent → Operations (Structural) ──────────────────────────────────────────

(defmulti intent->ops
  "Compile a structural intent into a vector of core operations.

   Returns: vector of operation maps for interpretation
   Dispatch: :type key of intent map

   Structural intents affect the document tree and must go through
   the interpret pipeline for validation and derived state updates.

   Example:
     (defmethod intent->ops :indent [db {:keys [id]}]
       [{:op :place :id id :under (prev-sibling db id) :at :last}])"
  (fn [_db intent] (:type intent)))

(defmethod intent->ops :default
  [_db _intent]
  nil)  ;; Return nil to signal no ops handler

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
  (let [ops (intent->ops db intent)]
    {:db db :ops (vec (or ops []))}))

;; ── Convenience helpers ───────────────────────────────────────────────────────

(defn has-handler?
  "Returns true if intent type has an intent->ops implementation."
  [intent-type]
  (some? (get-method intent->ops intent-type)))

;; ── Intent Registry ───────────────────────────────────────────────────────────

(defonce intent-registry (atom {}))

(defn get-intent-meta
  "Get metadata for an intent type (doc, spec, etc.)."
  [intent-type]
  (get @intent-registry intent-type))

(defn list-intents
  "List all registered intent types with their metadata."
  []
  @intent-registry)

;; ── defintent Macro ───────────────────────────────────────────────────────────

#?(:clj
   (defmacro defintent
     "Define an intent with handler and metadata in one place.

      Usage:
        (defintent :select
          {:sig [db {:keys [ids]}]
           :doc \"Set selection; last id is focus.\"
           :spec [:map [:type [:= :select]] [:ids [:vector :string]]]
           :ops [{:op :update-node :id \"session/selection\" ...}]})

      This expands to:
        - defmethod for intent->ops
        - registry entry with :doc and :spec"
     [intent-kw {:keys [sig doc ops spec]}]
     (let [[db-sym intent-destructure] sig
           registry-sym 'kernel.intent/intent-registry
           multimethod-sym 'kernel.intent/intent->ops]
       `(do
          ~(when ops
             `(defmethod ~multimethod-sym ~intent-kw
                [~db-sym ~intent-destructure]
                ~ops))
          (swap! ~registry-sym assoc ~intent-kw
                 {:doc ~doc
                  :spec ~spec})
          ~intent-kw))))
