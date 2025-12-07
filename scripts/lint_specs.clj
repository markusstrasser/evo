#!/usr/bin/env bb

(ns lint-specs
  "Lint specs.edn for schema validity without compiling tests.

   Usage:
     bb scripts/lint_specs.clj

   Exit codes:
     0 - All specs valid
     1 - Schema validation errors found

   This is a fast lint pass that catches:
   - Missing required FR fields
   - Invalid priority/type/status enums
   - Malformed scenario structures
   - Scenario actions using unregistered intent types"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]))

;; ── Registered Intent Discovery ───────────────────────────────────────────────

(defn extract-registered-intents
  "Scan src/plugins/*.cljc and src/kernel/*.cljc for (intent/register-intent! :keyword ...)
   Returns a set of registered intent keywords."
  []
  (let [plugin-files (fs/glob "src/plugins" "*.cljc")
        kernel-files (fs/glob "src/kernel" "*.cljc")
        all-files (concat plugin-files kernel-files)
        pattern #"\(intent/register-intent!\s+:([a-z][a-z0-9-]*)"
        extract-from-file (fn [f]
                            (let [content (slurp (str f))
                                  matches (re-seq pattern content)]
                              (map (fn [[_ kw]] (keyword kw)) matches)))]
    (->> all-files
         (mapcat extract-from-file)
         set)))

;; Inline Malli for Babashka (subset needed for validation)
;; Full Malli isn't available in bb, so we do structural checks

(defn validate-fr
  "Validate a single FR entry. Returns nil if valid, error map if invalid."
  [fr-id fr]
  (let [errors (cond-> []
                 ;; Required fields
                 (not (string? (:desc fr)))
                 (conj "Missing or invalid :desc (must be string)")

                 (not (#{:critical :high :medium :low} (:priority fr)))
                 (conj (str "Invalid :priority " (:priority fr) " (must be :critical/:high/:medium/:low)"))

                 (not (#{:intent-level :invariant :scenario} (:type fr)))
                 (conj (str "Invalid :type " (:type fr)))

                 (not (#{:active :deprecated :future} (:status fr)))
                 (conj (str "Invalid :status " (:status fr)))

                 (not (int? (:version fr)))
                 (conj "Missing or invalid :version (must be int)")

                 (not (vector? (:tags fr)))
                 (conj "Missing or invalid :tags (must be vector)")

                 (not (string? (:spec-ref fr)))
                 (conj "Missing or invalid :spec-ref (must be string)"))]
    (when (seq errors)
      {:fr-id fr-id :errors errors})))

(defn validate-action-intent
  "Validate that an action's :type is a registered intent.
   Returns error string or nil if valid."
  [action registered-intents]
  (when (map? action)
    (let [intent-type (:type action)]
      (when (and (keyword? intent-type)
                 (not (contains? registered-intents intent-type)))
        (str "Unknown intent type :" (name intent-type)
             " - not found in registered intents")))))

(defn validate-scenario
  "Validate an executable scenario. Returns nil if valid, error map if invalid."
  [fr-id scenario-id scenario registered-intents]
  (when (and (map? scenario)
             (or (contains? scenario :setup)
                 (contains? scenario :action)
                 (contains? scenario :expect)))
    ;; This looks like an executable scenario, validate it
    (let [action (:action scenario)
          ;; Handle both single action and action sequences
          actions (if (vector? action) action [action])
          intent-errors (keep #(validate-action-intent % registered-intents) actions)

          errors (cond-> []
                   (not (string? (:name scenario)))
                   (conj "Missing :name (must be string)")

                   (not (map? (:setup scenario)))
                   (conj "Missing :setup (must be map)")

                   (and (map? (:setup scenario))
                        (not (contains? (:setup scenario) :tree)))
                   (conj "Missing :setup :tree")

                   (not (or (map? (:action scenario))
                            (vector? (:action scenario))))
                   (conj "Missing or invalid :action (must be map or vector)")

                   (and (map? (:action scenario))
                        (not (keyword? (:type (:action scenario)))))
                   (conj "Missing :action :type (must be keyword)")

                   (not (map? (:expect scenario)))
                   (conj "Missing :expect (must be map)")

                   ;; Add intent validation errors
                   (seq intent-errors)
                   (into intent-errors))]
      (when (seq errors)
        {:fr-id fr-id :scenario-id scenario-id :errors errors}))))

(defn validate-registry
  "Validate entire registry. Returns {:valid? bool :errors [...]}"
  [registry registered-intents]
  (let [fr-errors (keep (fn [[fr-id fr]] (validate-fr fr-id fr)) registry)
        scenario-errors (for [[fr-id fr] registry
                              :when (map? (:scenarios fr))
                              [scenario-id scenario] (:scenarios fr)
                              :let [err (validate-scenario fr-id scenario-id scenario registered-intents)]
                              :when err]
                          err)
        all-errors (concat fr-errors scenario-errors)]
    {:valid? (empty? all-errors)
     :fr-errors (vec fr-errors)
     :scenario-errors (vec scenario-errors)
     :registered-intents-count (count registered-intents)
     :total-frs (count registry)
     :total-scenarios (reduce + (for [[_ fr] registry
                                      :when (map? (:scenarios fr))]
                                  (count (:scenarios fr))))}))

(defn -main []
  (println "🔍 Linting specs.edn...")
  (println)

  (let [specs-file (io/file "resources/specs.edn")]
    (if-not (.exists specs-file)
      (do
        (println "❌ ERROR: resources/specs.edn not found")
        (System/exit 1))

      (try
        (let [registered-intents (extract-registered-intents)
              registry (edn/read-string (slurp specs-file))
              {:keys [valid? fr-errors scenario-errors total-frs total-scenarios registered-intents-count]}
              (validate-registry registry registered-intents)]

          (println "📊 Registry Stats:")
          (println "   Total FRs:" total-frs)
          (println "   Total Scenarios:" total-scenarios)
          (println "   Registered Intents:" registered-intents-count)
          (println)

          (if valid?
            (do
              (println "✅ All specs valid!")
              (System/exit 0))
            (do
              (println "❌ Validation errors found:")
              (println)

              (when (seq fr-errors)
                (println "FR Errors:")
                (doseq [{:keys [fr-id errors]} fr-errors]
                  (println (str "  " fr-id ":"))
                  (doseq [err errors]
                    (println (str "    - " err)))))

              (when (seq scenario-errors)
                (println)
                (println "Scenario Errors:")
                (doseq [{:keys [fr-id scenario-id errors]} scenario-errors]
                  (println (str "  " fr-id "/" scenario-id ":"))
                  (doseq [err errors]
                    (println (str "    - " err)))))

              (println)
              (println (str "Total: " (+ (count fr-errors) (count scenario-errors)) " errors"))
              (System/exit 1))))

        (catch Exception e
          (println "❌ ERROR: Failed to parse specs.edn")
          (println (str "   " (.getMessage e)))
          (System/exit 1))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
