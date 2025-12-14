(ns kernel.intent
  "Intent router - all intents compile to core operations.

   Design: Unified Intent-to-Ops Pattern
   - intent->ops: Compiles all intents to core operations
   - apply-intent: Returns ops (and optional session-updates) for caller to interpret
   - Session state lives in a separate view-state atom, updated via :session-updates

   This unifies the event handling pipeline through the 3-op kernel.
   All DB state changes go through validate/derive, enabling full undo/redo.

   Spec-as-Database Pattern:
   - Intents cite Functional Requirements (FRs) via :fr/ids
   - Registration validates FR IDs against resources/specs.edn
   - REPL audit shows implementation coverage

   Example usage:
     ;; Structural intent (compiles to ops)
     (apply-intent db session {:type :indent :id \"a\"})

     ;; Intent with session updates (selection, edit mode, etc.)
     (apply-intent db session {:type :select :ids [\"a\" \"b\"]})"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [spec.registry :as fr]
            #_{:clj-kondo/ignore [:unused-namespace]} ; db/ used in CLJS reader conditional
            [kernel.db :as db]))

;; ── Intent Registry (Data, No Macros) ────────────────────────────────────────

(defonce !intents (atom {}))

;; Track intents without FR citations for grouped warning at startup
(defonce !uncited-intents (atom #{}))

;; ── Registration API ──────────────────────────────────────────────────────────

(defn register-intent!
  "Register an intent handler with Malli spec validation, FR linkage, and state requirements.

   Args:
   - kw: Intent type keyword (e.g. :select, :indent)
   - config: Map with keys:
     - :doc           - Documentation string
     - :spec          - Malli spec for validation
     - :handler       - Handler function (fn [db session intent] -> ops)
     - :fr/ids        - (optional) Set of FR keywords this intent implements
     - :allowed-states - (optional) Set of states where this intent is valid
                        nil = allowed in any state (universal)
                        #{:editing} = only in editing state
                        #{:editing :selection} = in editing or selection

   Spec-as-Database Enforcement:
   - :fr/ids (optional): Set of FR keywords this intent implements
   - Validates FR IDs against resources/specs.edn registry
   - Soft warnings for uncited intents (breaking changes deferred)
   - Throws on unknown FR IDs (hard enforcement)

   State Requirements:
   - :allowed-states collocates state constraints with intent definition
   - High cohesion: intent definition + constraints in one place
   - Supports plugin development without touching kernel files

   Implementation Notes (GPT-5 Spec Link Pattern):
   - Caches compiled Malli validator for performance
   - Tracks spec version (hash) for hot reload detection
   - Replaces by key (idempotent) to avoid stale entries
   - Specs should use {:closed true} to prevent extra keys
   - Keep DB-dependent checks OUT of Malli spec (use separate validation)

   Example:
     (register-intent! :smart-split
       {:doc \"Context-aware block splitting\"
        :spec [:map {:closed true}
               [:type [:= :smart-split]]
               [:block-id :string]
               [:cursor-pos :int]]
        :fr/ids #{:fr.edit/smart-split}
        :allowed-states #{:editing}  ;; Only valid in editing state
        :handler (fn [db session {:keys [block-id cursor-pos]}]
                   [{:op :update-node ...}])})"
  [kw {:keys [doc spec handler] :as config}]
  (let [ids (get config :fr/ids)]
    ;; Track uncited intents for grouped warning (instead of spamming console)
    (when (empty? ids)
      (swap! !uncited-intents conj kw))

    ;; Hard enforcement: validate FR IDs
    (when (seq ids)
      (try
        (fr/validate-fr-ids! ids)
        (catch #?(:clj Exception :cljs js/Error) e
          (throw (ex-info "Intent registration failed: unknown FR ID(s)"
                          {:intent kw
                           :fr/ids ids
                           :error (ex-message e)
                           :cause e})))))

    (let [;; Compile Malli validator (cached for performance)
          validator (when spec (m/validator spec))
          ;; Track spec version for hot reload detection
          version (when spec (hash spec))
          ;; Base config - always present
          base-config {:doc doc
                       :spec spec
                       :validator validator
                       :handler handler
                       :fr/ids (or ids #{})
                       :version version}
          ;; Only include :allowed-states if explicitly provided in config
          ;; (distinguishes "not specified" from "explicitly nil = any state")
          final-config (if (contains? config :allowed-states)
                         (assoc base-config :allowed-states (:allowed-states config))
                         base-config)]
      (swap! !intents assoc kw final-config)
      kw)))

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
   2. Auto-commit pending buffer (if :pending-buffer present)
   3. Lookup handler
   4. Execute handler → result
   5. Return {:db db :ops ops :session-updates {...}}

   Buffer Auto-Commit:
   If intent contains :pending-buffer {:block-id ... :text ...}, an :update-node
   op is prepended to commit the buffer content. This ensures typed content isn't
   lost when intents exit edit mode or modify the editing block.

   Returns: {:db unchanged-db :ops [operations] :session-updates {...}}

   The caller is responsible for:
   - Interpreting :ops through tx/interpret (DB changes)
   - Applying :session-updates to session atom (ephemeral state)

   Args:
     db      - Current database (persistent document graph)
     session - Current session state (ephemeral UI state: cursor, selection, fold, zoom)
     intent  - Intent map (e.g., {:type :indent :id \"a\"})

   Handler Signature (Phase 6):
     (fn [db session intent] -> [ops] | {:ops [...] :session-updates {...}})

   Handlers can return:
   - Vector of ops (backward compatible, no session updates)
   - Map with :ops and/or :session-updates keys

   Example:
     (apply-intent db session {:type :indent :id \"a\"})
     ;=> {:db db :ops [{:op :place ...}] :session-updates nil}

     (apply-intent db session {:type :selection :mode :extend-next})
     ;=> {:db db :ops [] :session-updates {:selection {:nodes #{...} :focus \"a\"}}}"
  [db session intent]
  ;; DEBUG: Check for stale derived indexes before processing intent
  ;; This helps detect the root cause of :anchor-not-sibling validation errors
  #?(:cljs
     (when ^boolean goog.DEBUG
       (when-let [inconsistency (db/check-parent-of-consistency db)]
         (js/console.error "🚨🚨🚨 STALE DERIVED INDEXES detected BEFORE intent processing! 🚨🚨🚨"
                           "\nIntent:" (pr-str (:type intent))
                           "\nFull intent:" (pr-str intent)
                           "\nInconsistency:" (pr-str inconsistency)
                           "\nDB hash:" (hash db))
         (js/console.trace "Stack trace - DB was already corrupted when intent started"))))

  ;; Validation interceptor (when enabled)
  (when *validate-intents*
    (validate-intent! intent))

  ;; Build buffer-commit op if pending-buffer present
  ;; This auto-commits typed content before any intent that might lose it
  (let [buffer-op (when-let [{:keys [block-id text]} (:pending-buffer intent)]
                    {:op :update-node :id block-id :props {:text text}})]

    ;; Lookup and execute handler
    (if-let [handler (get-in @!intents [(:type intent) :handler])]
      (let [result (handler db session intent)]
        (if (map? result)
          ;; Handler returned map with :ops and/or :session-updates
          {:db db
           :ops (vec (concat (when buffer-op [buffer-op])
                             (or (:ops result) [])))
           :session-updates (:session-updates result)}
          ;; Handler returned vector of ops (backward compatible)
          {:db db
           :ops (vec (concat (when buffer-op [buffer-op])
                             (or result [])))
           :session-updates nil}))
      {:db db
       :ops (vec (when buffer-op [buffer-op]))
       :session-updates nil})))

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

(defn intent-allowed-states
  "Get the allowed states for a specific intent type.

   Returns:
   - :not-registered if intent type is not registered
   - :not-specified if intent is registered but :allowed-states not set
   - nil if intent explicitly allows any state (universal)
   - Set of allowed state keywords (e.g., #{:editing :selection})

   Example:
     (intent-allowed-states :smart-split)
     ;=> #{:editing}

     (intent-allowed-states :undo)
     ;=> nil  ;; explicitly allows any state

     (intent-allowed-states :some-old-intent)
     ;=> :not-specified  ;; registered but no :allowed-states key

     (intent-allowed-states :unknown-intent)
     ;=> :not-registered"
  [intent-type]
  (if-let [config (get @!intents intent-type)]
    ;; Intent is registered - check if :allowed-states key exists
    (if (contains? config :allowed-states)
      (:allowed-states config) ;; May be nil (any state) or a set
      :not-specified) ;; Key not present, fall back to centralized map
    :not-registered))

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
  "DEPRECATED: Use apply-intent instead. Kept for backward compatibility.

   Note: Passes nil for session. Handlers that need session will not work correctly."
  [db intent]
  (:ops (apply-intent db nil intent)))

;; ── FR Coverage Audit (REPL Introspection) ───────────────────────────────────

(defn audit-coverage
  "Audit FR implementation coverage across registered intents.

   Returns map with:
   - :total-frs          - Total FRs in registry
   - :cited-frs          - FRs cited by at least one intent
   - :uncited-frs        - FRs with no intent implementation
   - :critical-uncited   - Critical FRs with no implementation (RED FLAG!)
   - :implementation-pct - Percentage of FRs with intent coverage
   - :intent-citations   - Map of intent → FR set

   Example:
     (audit-coverage)
     ;=> {:total-frs 12
     ;    :cited-frs [:fr.nav/vertical-cursor-memory :fr.selection/extend-boundary]
     ;    :uncited-frs [:fr.struct/climb-descend :fr.kernel/undo-restores-all]
     ;    :critical-uncited [:fr.kernel/undo-restores-all]
     ;    :implementation-pct 83
     ;    :intent-citations {:select #{:fr.selection/edit-view-exclusive}
     ;                       :indent #{:fr.struct/indent-outdent}}}"
  []
  (let [;; All FRs from registry
        all-frs (set (fr/list-frs))
        critical-frs (set (fr/critical-frs))

        ;; FR citations from intents
        intent-citations (into {}
                               (map (fn [[kw config]]
                                      [kw (:fr/ids config)])
                                    @!intents))
        cited-frs (set (mapcat val intent-citations))

        ;; Uncited FRs
        uncited-frs (set/difference all-frs cited-frs)
        critical-uncited (set/intersection critical-frs uncited-frs)

        ;; Coverage percentage
        implementation-pct (if (zero? (count all-frs))
                             100
                             (int (* 100 (/ (count cited-frs) (count all-frs)))))]

    {:total-frs (count all-frs)
     :cited-frs (vec (sort cited-frs))
     :uncited-frs (vec (sort uncited-frs))
     :critical-uncited (vec (sort critical-uncited))
     :implementation-pct implementation-pct
     :intent-citations intent-citations}))

(defn implemented-frs
  "Return set of FR IDs that have been implemented (cited by at least one intent).
   
   Example:
     (implemented-frs)
     ;=> #{:fr.nav/vertical-cursor-memory :fr.struct/indent-outdent ...}"
  []
  (set (mapcat :fr/ids (vals @!intents))))

(defn fr-implemented?
  "Check if an FR has been implemented (cited by at least one intent).
   
   Example:
     (fr-implemented? :fr.nav/vertical-cursor-memory) ;=> true
     (fr-implemented? :fr.ui/slash-palette)           ;=> false"
  [fr-id]
  (contains? (implemented-frs) fr-id))

(defn implemented-fr-list
  "Return sorted list of implemented FR IDs.
   For use in UI filtering."
  []
  (vec (sort (implemented-frs))))

(defn full-audit
  "Complete FR audit showing both implementation and verification coverage.

   Returns vector of maps, one per FR:
   - :id               - FR keyword
   - :desc             - FR description
   - :priority         - :critical | :high | :medium | :low
   - :implemented?     - Boolean (has intent citation)
   - :verified?        - Boolean (has test citation)
   - :status           - :complete | :implemented-untested | :verified-unimplemented | :missing
   - :implementing-intents - Set of intent keywords citing this FR
   - :verifying-tests  - Set of test names citing this FR

   Status meanings:
   - :complete                  - 🟢 Has both intent and test coverage
   - :implemented-untested      - 🟡 Has intent but no test
   - :verified-unimplemented    - 🟠 Has test but no intent (rare, possible for invariants)
   - :missing                   - 🔴 No intent or test coverage

   Example:
     (full-audit)
     ;=> [{:id :fr.nav/vertical-cursor-memory
     ;     :desc \"Vertical navigation preserves column\"
     ;     :priority :critical
     ;     :implemented? true
     ;     :verified? true
     ;     :status :complete
     ;     :implementing-intents #{:navigate-with-cursor-memory}
     ;     :verifying-tests #{cursor-memory-test}}
     ;    {:id :fr.edit/smart-split
     ;     :desc \"Enter splits at cursor\"
     ;     :priority :high
     ;     :implemented? true
     ;     :verified? false
     ;     :status :implemented-untested
     ;     :implementing-intents #{:context-aware-enter}
     ;     :verifying-tests #{}}]"
  []
  (let [;; Get implementation coverage from intents
        impl-audit (audit-coverage)
        impl-citations (:intent-citations impl-audit)
        cited-frs (set (:cited-frs impl-audit))

        ;; Get verification coverage from tests
        ;; Note: Test scanner is only available in dev/test environments
        ;; Production builds or non-test contexts will show zero verification coverage
        test-scan #?(:clj (try
                           ;; Try to resolve dev.test-scanner/scan-tests-for-frs function
                           ;; This will only work if the namespace is already loaded (dev/test context)
                            (if-let [scan-fn (resolve 'dev.test-scanner/scan-tests-for-frs)]
                              (scan-fn)
                              {:verified-frs #{}
                               :tests-by-fr {}})
                            (catch Exception _
                              {:verified-frs #{}
                               :tests-by-fr {}}))
                     :cljs {:verified-frs #{}
                            :tests-by-fr {}})
        verified-frs (:verified-frs test-scan)
        tests-by-fr (:tests-by-fr test-scan)

        ;; Invert intent-citations to get intents-by-fr
        intents-by-fr (reduce
                       (fn [acc [intent-kw fr-ids]]
                         (reduce (fn [m fr-id]
                                   (update m fr-id (fnil conj #{}) intent-kw))
                                 acc
                                 fr-ids))
                       {}
                       impl-citations)]

    ;; Build report for each FR
    (vec
     (for [fr-id (fr/list-frs)]
       (let [fr-meta (fr/get-fr fr-id)
             impl? (contains? cited-frs fr-id)
             verif? (contains? verified-frs fr-id)
             status (cond
                      (and impl? verif?) :complete
                      impl? :implemented-untested
                      verif? :verified-unimplemented
                      :else :missing)]
         {:id fr-id
          :desc (:desc fr-meta)
          :priority (:priority fr-meta)
          :implemented? impl?
          :verified? verif?
          :status status
          :implementing-intents (get intents-by-fr fr-id #{})
          :verifying-tests (get tests-by-fr fr-id #{})})))))

(defn coverage-summary
  "High-level summary of coverage metrics.

   Returns:
   - :total-frs         - Total FRs in registry
   - :complete          - FRs with both intent and test
   - :implemented       - FRs with intent (subset: with test)
   - :verified          - FRs with test (subset: with intent)
   - :missing           - FRs with neither
   - :complete-pct      - % with both
   - :implementation-pct - % with intent
   - :verification-pct   - % with test

   Example:
     (coverage-summary)
     ;=> {:total-frs 12
     ;    :complete 5
     ;    :implemented 9
     ;    :verified 5
     ;    :missing 3
     ;    :complete-pct 41
     ;    :implementation-pct 75
     ;    :verification-pct 41}"
  []
  (let [audit (full-audit)
        total (count audit)
        by-status (group-by :status audit)
        complete-count (count (:complete by-status))
        impl-count (count (filter :implemented? audit))
        verif-count (count (filter :verified? audit))
        missing-count (count (:missing by-status))]
    {:total-frs total
     :complete complete-count
     :implemented impl-count
     :verified verif-count
     :missing missing-count
     :complete-pct (if (zero? total) 100 (int (* 100 (/ complete-count total))))
     :implementation-pct (if (zero? total) 100 (int (* 100 (/ impl-count total))))
     :verification-pct (if (zero? total) 100 (int (* 100 (/ verif-count total))))}))

(defn log-uncited-intents!
  "Log a grouped warning for all intents without FR citations.
   Call once after all plugins are loaded (not on each registration).
   
   Returns the set of uncited intent keywords for programmatic use."
  []
  (let [uncited @!uncited-intents]
    (when (seq uncited)
      #?(:cljs
         (js/console.warn
          (str "📋 " (count uncited) " intents without FR citations:\n   "
               (str/join "\n   " (sort (map name uncited)))
               "\n\n   Add :fr/ids to link intents to specs.edn requirements."))
         :clj
         (println
          (str "📋 " (count uncited) " intents without FR citations:\n   "
               (str/join "\n   " (sort (map name uncited)))
               "\n\n   Add :fr/ids to link intents to specs.edn requirements."))))
    uncited))
