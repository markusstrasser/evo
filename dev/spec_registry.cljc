
(ns spec-registry
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
     (require '[spec-registry :as fr])
     (fr/load-registry!)           ; Load and validate specs.edn
     (fr/fr-exists? :fr.nav/vertical-cursor-memory)  ; Check FR
     (fr/get-fr :fr.nav/vertical-cursor-memory)      ; Get FR metadata
     (fr/audit-coverage)           ; Show implementation coverage"
  (:require [malli.core :as m]
            [malli.error :as me]
            #?(:clj [spec-registry-macros :refer [inline-registry]]))
  #?(:cljs (:require-macros [spec-registry-macros :refer [inline-registry]])))

;; ── Malli Schema for FR Registry ─────────────────────────────────────────────

(def fr-schema
  "Schema for a single Functional Requirement entry."
  [:map {:closed true}
   [:desc :string]
   [:priority [:enum :critical :high :medium :low]]
   [:type [:enum :intent-level :invariant :scenario]]
   [:status [:enum :active :deprecated]]
   [:version :int]
   [:tags [:vector :keyword]]
   [:spec-ref :string]])

(def registry-schema
  "Schema for the entire specs.edn registry.
   Map of namespaced keyword → FR metadata."
  [:map-of :keyword fr-schema])

;; ── Registry State ────────────────────────────────────────────────────────────

;; Embedded registry (loaded from resources/specs.edn at compile/compile time)
(def embedded-registry (inline-registry))

(defonce !registry
  (atom embedded-registry))

;; ── Loading & Validation ──────────────────────────────────────────────────────

(defn validate-registry!
  "Validate embedded registry against Malli schema.
   Throws if schema validation fails.

   Called automatically on namespace load."
  []
  (let [validator (m/validator registry-schema)]
    (when-not (validator embedded-registry)
      (let [explanation (m/explain registry-schema embedded-registry)
            humanized (me/humanize explanation)]
        (throw (ex-info "Invalid FR registry (embedded data)"
                        {:errors humanized
                         :explanation explanation
                         :hint "Check spec-registry.cljc embedded-registry definition"}))))
    embedded-registry))

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
     type     (filter (fn [[_ fr]] (= (:type fr) type)))
     status   (filter (fn [[_ fr]] (= (:status fr) status)))
     tag      (filter (fn [[_ fr]] (contains? (set (:tags fr)) tag)))
     true     (map first)
     true     (into []))))

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

;; ── Auto-Load on Namespace Require ────────────────────────────────────────────

;; Load registry when namespace is required (dev/test workflow)
;; Catches errors gracefully for hot reload
(try
  (load-registry!)
  (catch #?(:clj Exception :cljs js/Error) e
    (println "⚠️ WARNING: Failed to load FR registry:" (ex-message e))
    (println "   Run (spec-registry/load-registry!) to retry")))
