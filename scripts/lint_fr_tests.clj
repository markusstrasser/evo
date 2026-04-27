#!/usr/bin/env bb

(ns lint-fr-tests
  "Check FR coverage via specs.edn scenarios.

   FR coverage is now tracked through executable scenarios in specs.edn,
   not through :fr/ids metadata on unit tests.

   Usage:
     bb scripts/lint_fr_tests.clj           # Report coverage
     bb scripts/lint_fr_tests.clj --strict  # Fail if implemented FRs lack scenarios"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def strict? (some #{"--strict"} *command-line-args*))

;; ── Load specs.edn ───────────────────────────────────────────────────────────

(defn load-specs []
  (-> "resources/specs.edn" io/file slurp edn/read-string))

;; ── Extract implemented FRs from intent registrations ────────────────────────

(defn extract-fr-ids-from-file [file]
  "Extract :fr/ids from intent registrations in a source file."
  (let [content (slurp file)
        ;; Match :fr/ids #{...} patterns
        pattern #":fr/ids\s+#\{([^}]+)\}"
        matches (re-seq pattern content)]
    (->> matches
         (mapcat (fn [[_ ids-str]]
                   ;; Parse the keywords from the set
                   (re-seq #":[\w./+-]+" ids-str)))
         (map #(keyword (subs % 1)))  ; Remove leading :
         set)))

(defn find-implemented-frs []
  "Scan src/plugins/*.cljc for :fr/ids in intent registrations."
  (let [plugin-dir (io/file "src/plugins")
        files (->> (file-seq plugin-dir)
                   (filter #(.isFile ^java.io.File %))
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".cljc")))]
    (->> files
         (mapcat extract-fr-ids-from-file)
         set)))

;; ── Check scenario coverage ──────────────────────────────────────────────────

(defn has-executable-scenarios? [fr]
  "Check if an FR has executable scenarios (with :setup :tree)."
  (let [scenarios (:scenarios fr)]
    (and (map? scenarios)
         (some (fn [[_ scenario]]
                 (and (map? scenario)
                      (get-in scenario [:setup :tree])))
               scenarios))))

(defn scenario-count [fr]
  "Count executable scenarios for an FR."
  (let [scenarios (:scenarios fr)]
    (if (map? scenarios)
      (count (filter (fn [[_ s]]
                       (and (map? s) (get-in s [:setup :tree])))
                     scenarios))
      0)))

(defn fixture-coverage-kind-counts []
  (let [f (io/file "resources/fr_fixtures.edn")]
    (if (.exists f)
      (frequencies (map :coverage-kind (:fixtures (edn/read-string (slurp f)))))
      {})))

;; ── Main ─────────────────────────────────────────────────────────────────────

(defn -main [& _args]
  (let [specs (load-specs)
        implemented-frs (find-implemented-frs)

        ;; FRs with scenarios
        frs-with-scenarios (->> specs
                                (filter #(has-executable-scenarios? (val %)))
                                (map key)
                                set)

        ;; Coverage analysis
        implemented-with-scenarios (set/intersection implemented-frs frs-with-scenarios)
        implemented-without-scenarios (set/difference implemented-frs frs-with-scenarios)

        ;; Stats
        total-frs (count specs)
        total-implemented (count implemented-frs)
        total-scenarios (reduce + (map #(scenario-count (val %)) specs))
        fixture-counts (fixture-coverage-kind-counts)
        coverage-pct (if (zero? total-implemented)
                       100
                       (int (* 100 (/ (count implemented-with-scenarios) total-implemented))))]

    (println "📋 Functional Requirement Coverage (Scenario-Based)")
    (println)
    (println "  Total FRs in specs.edn:" total-frs)
    (println "  FRs implemented (cited by intents):" total-implemented)
    (println "  FRs with executable scenarios:" (count frs-with-scenarios))
    (println "  Total executable scenarios:" total-scenarios)
    (when (seq fixture-counts)
      (println "  Deterministic FR fixtures:" fixture-counts)
      (println "    Generated variants are pressure tests, not registry scenario coverage."))
    (println)
    (println "  Implemented FRs with scenarios:" (count implemented-with-scenarios))
    (println "  Implemented FRs missing scenarios:" (count implemented-without-scenarios))
    (println "  Coverage:" (str coverage-pct "%"))

    (when (seq implemented-without-scenarios)
      (println)
      (println "⚠️  Implemented FRs missing executable scenarios:")
      (doseq [fr-id (sort implemented-without-scenarios)]
        (println "   -" fr-id)))

    (when (and strict? (seq implemented-without-scenarios))
      (println)
      (println "❌ Strict mode: failing due to missing scenario coverage")
      (System/exit 1))

    (when (empty? implemented-without-scenarios)
      (println)
      (println "✅ All implemented FRs have scenario coverage!"))

    (System/exit 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
