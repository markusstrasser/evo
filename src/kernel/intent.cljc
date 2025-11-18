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
     (apply-intent db {:type :select :ids [\"a\" \"b\"]})"
  (:require [malli.core :as m]
            [malli.error :as me]))

;; ── Intent Registry (Data, No Macros) ────────────────────────────────────────

(defonce !intents (atom {}))

;; ── Registration API ──────────────────────────────────────────────────────────

(defn register-intent!
  "Register an intent handler with Malli spec validation.

   Args:
   - kw: Intent type keyword (e.g. :select, :indent)
   - config: Map with :doc, :spec, :handler keys

   Implementation Notes (GPT-5 Spec Link Pattern):
   - Caches compiled Malli validator for performance
   - Tracks spec version (hash) for hot reload detection
   - Replaces by key (idempotent) to avoid stale entries
   - Specs should use {:closed true} to prevent extra keys
   - Keep DB-dependent checks OUT of Malli spec (use separate validation)

   Example:
     (register-intent! :select
       {:doc \"Set selection\"
        :spec [:map {:closed true}
               [:type [:= :select]]
               [:ids [:vector :string]]]
        :handler (fn [db {:keys [ids]}]
                   [{:op :update-node :id \"session/selection\" :props {...}}])})"
  [kw {:keys [doc spec handler]}]
  (let [;; Compile Malli validator (cached for performance)
        validator (when spec (m/validator spec))
        ;; Track spec version for hot reload detection
        version (when spec (hash spec))]
    (swap! !intents assoc kw
           {:doc doc
            :spec spec
            :validator validator
            :handler handler
            :version version})
    kw))

;; ── Validation ─────────────────────────────────────────────────────────────────

(defn validate-intent!
  "Validate intent against its registered Malli spec.

   Throws ex-info with humanized errors on validation failure.
   Returns intent unchanged if valid or no spec registered.

   Implementation Note:
   - Uses cached validator from registry (no runtime compilation)
   - Distinguishes unknown intent vs invalid intent
   - Includes spec-id and humanized errors in ex-data"
  [{:keys [type] :as intent}]
  (when-not type
    (throw (ex-info "Intent missing :type"
                    {:intent intent
                     :hint "All intents must have a :type key"})))

  (if-let [{:keys [validator spec]} (get @!intents type)]
    (when validator
      (when-not (validator intent)
        (let [explanation (m/explain spec intent)
              humanized (me/humanize explanation)]
          (throw (ex-info "Invalid intent"
                          {:type type
                           :intent intent
                           :spec spec
                           :errors humanized
                           :explanation explanation})))))
    ;; Unknown intent - no handler registered
    (throw (ex-info "Unknown intent type"
                    {:type type
                     :intent intent
                     :hint "Register this intent with register-intent!"})))
  intent)

;; ── Unified Entry Point (with Validation Interceptor) ─────────────────────────

(def ^:dynamic *validate-intents*
  "Enable/disable runtime intent validation.

   Default: false (opt-in) until all intents have registered specs.
   Set to true to enable validation globally.

   Override in dev/test:
     (binding [intent/*validate-intents* true]
       (apply-intent db intent))"
  false)

(defn apply-intent
  "Apply an intent by compiling it to operations.

   Dispatch Pipeline (Interceptor Pattern):
   1. Validate intent (if *validate-intents* enabled)
   2. Lookup handler
   3. Execute handler → ops
   4. Return {:db db :ops ops}

   Returns: {:db unchanged-db :ops [operations]}

   The caller is responsible for interpreting the ops through tx/interpret.
   All intents (structural + session) now go through the ops pipeline.

   Example:
     (apply-intent db {:type :indent :id \"a\"})
     ;=> {:db db :ops [{:op :place ...}]}

     (apply-intent db {:type :select :ids [\"a\"]})
     ;=> {:db db :ops [{:op :update-node :id \"session/selection\" ...}]}"
  [db intent]
  ;; Validation interceptor (when enabled)
  (when *validate-intents*
    (validate-intent! intent))

  ;; Lookup and execute handler
  (if-let [handler (get-in @!intents [(:type intent) :handler])]
    {:db db :ops (vec (or (handler db intent) []))}
    {:db db :ops []}))

;; ── REPL Introspection Helpers ───────────────────────────────────────────────

(defn has-handler?
  "Returns true if intent type has a registered handler."
  [intent-type]
  (contains? @!intents intent-type))

(defn list-intents
  "List all registered intent types with their metadata (excludes handlers/validators).

   Returns map of intent-type → {:doc, :spec, :version}

   Example:
     (list-intents)
     ;=> {:select {:doc \"Set selection\" :spec [...] :version 123456}
     ;    :indent {:doc \"Indent blocks\" :spec [...] :version 789012}}"
  []
  (into {} (map (fn [[k v]] [k (dissoc v :handler :validator)]) @!intents)))

(defn intent-spec
  "Get the Malli spec for a specific intent type.

   Example:
     (intent-spec :select)
     ;=> [:map {:closed true} [:type [:= :select]] [:ids [:vector :string]]]"
  [intent-type]
  (get-in @!intents [intent-type :spec]))

(defn intent-doc
  "Get documentation for a specific intent type.

   Example:
     (intent-doc :select)
     ;=> \"Set selection to one or more blocks\""
  [intent-type]
  (get-in @!intents [intent-type :doc]))

(defn intent-version
  "Get spec version (hash) for a specific intent type.

   Useful for detecting hot reload changes.

   Example:
     (intent-version :select)
     ;=> 123456789"
  [intent-type]
  (get-in @!intents [intent-type :version]))

(defn validate-intent-repl
  "Validate an intent and return humanized errors (REPL helper, doesn't throw).

   Returns:
   - nil if valid
   - {:errors humanized-errors :explanation malli-explanation} if invalid

   Example:
     (validate-intent-repl {:type :select :ids \"not-a-vector\"})
     ;=> {:errors {:ids [\"should be a vector\"]} :explanation {...}}"
  [{:keys [type] :as intent}]
  (if-let [{:keys [validator spec]} (get @!intents type)]
    (when (and validator (not (validator intent)))
      (let [explanation (m/explain spec intent)]
        {:errors (me/humanize explanation)
         :explanation explanation}))
    {:errors {:type [(str "Unknown intent type: " type)]}}))

(defn intent->ops
  "DEPRECATED: Use apply-intent instead. Kept for backward compatibility."
  [db intent]
  (:ops (apply-intent db intent)))
