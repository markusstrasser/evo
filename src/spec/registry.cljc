(ns spec.registry
  "Functional Requirements (FR) registry - Single Source of Truth.

   Design: Spec as Database Pattern
   - Registry stored as EDN data (resources/specs.edn)
   - Validated with Malli schema
   - Queryable at runtime for coverage auditing
   - Intents cite FR IDs; enforcement at registration time

   Philosophy: Requirements as data, not documentation.
   - Markdown generated FROM registry (not vice versa)
   - REPL-first introspection
   - Many-to-many mapping (intent ↔ FRs)
   - Dual coverage: implementation (intents) + verification (tests)

   Usage:
     (require '[spec.registry :as fr])
     (fr/load-registry!)           ; Load and validate specs.edn
     (fr/fr-exists? :fr.nav/vertical-cursor-memory)  ; Check FR
     (fr/get-fr :fr.nav/vertical-cursor-memory)      ; Get FR metadata
     (fr/audit-coverage)           ; Show implementation coverage"
  (:require [malli.core :as m]
            [malli.error :as me]
            #?(:clj [spec.registry-macros :refer [inline-registry]]))
  #?(:cljs (:require-macros [spec.registry-macros :refer [inline-registry]])))

;; ── Malli Schema for FR Registry ─────────────────────────────────────────────

(def fr-schema
  "Schema for a single Functional Requirement entry.
   
   BREAKING: Only executable scenario format supported.
   Legacy vector format [:SCENARIO-01 :SCENARIO-02] removed."
  [:map {:closed true}
   ;; ── Required Fields ───────────────────────────────────────────────────────
   [:desc :string]
   [:priority [:enum :critical :high :medium :low]]
   [:type [:enum :intent-level :invariant :scenario]]
   [:status [:enum :active :deprecated :future]]
   [:version :int]
   [:tags [:vector :keyword]]
   [:spec-ref :string]

   ;; ── Optional: Executable Scenarios ────────────────────────────────────────
   ;; Map of scenario-id → executable scenario with setup/action/expect
   [:scenarios {:optional true} [:map-of :keyword :any]]

   ;; ── Optional: Spec-as-UI Fields ───────────────────────────────────────────

   ;; Level indicates what layer this FR operates at
   [:level {:optional true} [:enum :gesture :intent :kernel]]

   ;; Notes for implementation details or future plans
   [:notes {:optional true} :string]

   ;; Behaviors: Human-readable behavior table (for docs generation)
   [:behaviors {:optional true}
    [:vector
     [:map
      [:context :string]
      [:behavior :string]
      [:scenario {:optional true} :keyword]
      [:level {:optional true} [:enum :gesture :intent :kernel]]]]]

   ;; Invariants: Conditions that must hold pre/post action
   [:invariants {:optional true}
    [:map
     [:pre {:optional true} [:vector :keyword]]
     [:post {:optional true} [:vector :keyword]]]]

   ;; Properties: Generative property tests
   [:properties {:optional true}
    [:vector
     [:map
      [:name :keyword]
      [:desc :string]
      [:generator {:optional true} :keyword]]]]])

(def scenario-schema
  "Schema for an executable scenario within an FR.
   
   Tree DSL format: [:doc [:a \"text\"] [:b \"text\" {:cursor 0} [:c \"child\"]]]
   - First element is parent (keyword for roots, string for block ids)
   - Following elements are [id text ?attrs ?children...]"
  [:map
   [:name :string]
   [:tags {:optional true} [:set :keyword]]

   ;; Setup: Initial state before action
   [:setup
    [:map
     [:tree :any] ;; Tree DSL, validated by tree-dsl namespace
     [:session {:optional true}
      [:map
       [:editing-block-id {:optional true} [:maybe :string]]
       [:cursor-position {:optional true} :int]
       [:selection {:optional true}
        [:map
         [:nodes {:optional true} [:set :string]]
         [:anchor {:optional true} [:maybe :string]]
         [:focus {:optional true} [:maybe :string]]]]]]]]

   ;; Action: Intent or sequence of intents
   [:action [:or
             [:map [:type :keyword]] ;; Single intent
             [:vector [:map [:type :keyword]]]]] ;; Action sequence (steps)

   ;; Expect: What should be true after action
   [:expect
    [:map
     [:tree {:optional true} :any] ;; Expected tree structure
     [:ops {:optional true}
      [:map
       [:includes {:optional true} [:vector :map]]
       [:excludes {:optional true} [:vector :map]]
       [:count {:optional true} :int]]]
     [:session {:optional true} :map]
     [:invariants-hold? {:optional true} :boolean]]]])

(def registry-schema
  "Schema for the entire specs.edn registry.
   Map of namespaced keyword → FR metadata."
  [:map-of :keyword fr-schema])

;; ── Registry State ────────────────────────────────────────────────────────────

;; Embedded registry (loaded from resources/specs.edn at compile time)
(def embedded-registry (inline-registry))

(defonce !registry
  (atom embedded-registry))

;; ── Loading & Validation ──────────────────────────────────────────────────────

(defn validate-scenarios!
  "Validate all executable scenarios against scenario-schema.
   Returns vector of validation errors, empty if all valid."
  [registry]
  (let [scenario-validator (m/validator scenario-schema)]
    (vec
     (for [[fr-id fr] registry
           :when (map? (:scenarios fr))
           [scenario-id scenario] (:scenarios fr)
           :when (and (map? scenario)
                      (contains? scenario :setup)
                      (contains? scenario :action)
                      (contains? scenario :expect))
           :when (not (scenario-validator scenario))]
       {:fr-id fr-id
        :scenario-id scenario-id
        :errors (me/humanize (m/explain scenario-schema scenario))}))))

(defn validate-registry!
  "Validate embedded registry against Malli schema.
   
   Two-phase validation:
   1. Validate overall registry structure (fr-schema for each FR)
   2. Validate each executable scenario against scenario-schema
   
   Throws if schema validation fails.
   Called automatically on namespace load."
  []
  ;; Phase 1: Overall registry structure
  (let [validator (m/validator registry-schema)]
    (when-not (validator embedded-registry)
      (let [explanation (m/explain registry-schema embedded-registry)
            humanized (me/humanize explanation)]
        (throw (ex-info "Invalid FR registry (embedded data)"
                        {:errors humanized
                         :explanation explanation
                         :hint "Check spec.registry embedded-registry definition"})))))

  ;; Phase 2: Validate each executable scenario
  (let [scenario-errors (validate-scenarios! embedded-registry)]
    (when (seq scenario-errors)
      (throw (ex-info "Invalid scenarios in FR registry"
                      {:scenario-errors scenario-errors
                       :count (count scenario-errors)
                       :hint "Check scenario structure: requires :name, :setup {:tree ...}, :action {:type ...}, :expect {:tree ...}"}))))

  embedded-registry)

(defn load-registry!
  "Validate and load embedded registry.
   Returns: Loaded registry map."
  []
  (let [validated (validate-registry!)]
    (reset! !registry validated)
    validated))

;; ── Query API ─────────────────────────────────────────────────────────────────

(defn fr-exists?
  "Check if an FR ID exists in the registry.

   Example:
     (fr-exists? :fr.nav/vertical-cursor-memory)  ;=> true
     (fr-exists? :fr.unknown/fake)                ;=> false"
  [fr-id]
  (contains? @!registry fr-id))

(defn get-fr
  "Get metadata for a specific FR.

   Returns: {:desc ... :priority :critical ...} or nil if not found.

   Example:
     (get-fr :fr.nav/vertical-cursor-memory)
     ;=> {:desc \"Vertical navigation preserves...\" :priority :critical ...}"
  [fr-id]
  (get @!registry fr-id))

(defn list-frs
  "List all registered FR IDs.

   Optional filters:
   - :priority  - Filter by priority (:critical, :high, :medium, :low)
   - :type      - Filter by type (:intent-level, :invariant, :scenario)
   - :status    - Filter by status (:active, :deprecated)
   - :tag       - Filter by tag presence (keyword)

   Examples:
     (list-frs)                        ; All FRs
     (list-frs {:priority :critical})  ; Critical FRs only
     (list-frs {:type :invariant})     ; Cross-cutting invariants
     (list-frs {:tag :navigation})     ; Navigation-related FRs"
  ([] (keys @!registry))
  ([{:keys [priority type status tag]}]
   (cond->> @!registry
     priority (filter (fn [[_ fr]] (= (:priority fr) priority)))
     type (filter (fn [[_ fr]] (= (:type fr) type)))
     status (filter (fn [[_ fr]] (= (:status fr) status)))
     tag (filter (fn [[_ fr]] (contains? (set (:tags fr)) tag)))
     true (map first)
     true (into []))))

(defn critical-frs
  "Shortcut: List all critical FRs.

   Example:
     (critical-frs)
     ;=> [:fr.nav/vertical-cursor-memory :fr.selection/edit-view-exclusive ...]"
  []
  (list-frs {:priority :critical}))

;; ── Validation Helpers ────────────────────────────────────────────────────────

(defn validate-fr-ids!
  "Validate a set of FR IDs against the registry.
   Throws if any ID is unknown.

   Used by intent registration to enforce FR linkage.

   Example:
     (validate-fr-ids! #{:fr.nav/vertical-cursor-memory})  ; OK
     (validate-fr-ids! #{:fr.unknown/fake})                ; Throws"
  [fr-ids]
  (doseq [id fr-ids]
    (when-not (fr-exists? id)
      (throw (ex-info "Unknown FR ID"
                      {:fr-id id
                       :available-frs (keys @!registry)
                       :hint "Check resources/specs.edn for valid FR IDs"})))))

;; ── Scenario Query API ────────────────────────────────────────────────────────

(defn executable-scenario?
  "Check if a scenario entry is executable (has setup/action/expect).
   
   Legacy scenarios are just keyword IDs: [:SCENARIO-01 :SCENARIO-02]
   Executable scenarios are maps: {:SCENARIO-01 {:setup ... :action ... :expect ...}}"
  [scenario-entry]
  (and (map? scenario-entry)
       (contains? scenario-entry :setup)
       (contains? scenario-entry :action)
       (contains? scenario-entry :expect)))

(defn get-scenario
  "Get a scenario by FR ID and scenario ID.
   
   Returns: The scenario map if executable, nil otherwise.
   
   Example:
     (get-scenario :fr.edit/backspace-merge :BACKSPACE-MERGE-01)
     ;=> {:name \"...\" :setup {...} :action {...} :expect {...}}"
  [fr-id scenario-id]
  (when-let [fr (get-fr fr-id)]
    (when-let [scenarios (:scenarios fr)]
      (when (map? scenarios)
        (get scenarios scenario-id)))))

(defn all-executable-scenarios
  "Get all executable scenarios across all FRs.
   
   Returns: Sequence of [fr-id scenario-id scenario-map] tuples.
   
   Example:
     (all-executable-scenarios)
     ;=> [[:fr.edit/backspace-merge :MERGE-01 {:setup ...}] ...]"
  []
  (for [[fr-id fr] @!registry
        :when (map? (:scenarios fr))
        [scenario-id scenario] (:scenarios fr)
        :when (executable-scenario? scenario)]
    [fr-id scenario-id scenario]))

(defn scenarios-by-fr
  "Get all executable scenarios grouped by FR ID.
   
   Returns: Map of fr-id → scenario-map
   
   Example:
     (scenarios-by-fr)
     ;=> {:fr.edit/backspace-merge {:MERGE-01 {...} :MERGE-02 {...}}}"
  []
  (into {}
        (for [[fr-id fr] @!registry
              :let [scenarios (:scenarios fr)]
              :when (and scenarios (map? scenarios))]
          [fr-id scenarios])))

(defn scenario-count
  "Count total executable scenarios.
   
   Returns: Number of executable scenarios across all FRs."
  []
  (reduce + (for [[_ scenarios] (scenarios-by-fr)]
              (count scenarios))))

(defn has-executable-scenarios?
  "Check if an FR has any executable scenarios (with setup/action/expect)."
  [fr-id]
  (let [fr (get-fr fr-id)
        scenarios (:scenarios fr)]
    (and (map? scenarios)
         (some executable-scenario? (vals scenarios)))))

;; ── Auto-Load on Namespace Require ────────────────────────────────────────────

;; Load registry when namespace is required (dev/test workflow)
;; Catches errors gracefully for hot reload
(try
  (load-registry!)
  (catch #?(:clj Exception :cljs js/Error) e
    (println "⚠️ WARNING: Failed to load FR registry:" (ex-message e))
    (println "   Run (spec.registry/load-registry!) to retry")))
