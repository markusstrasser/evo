#!/usr/bin/env bb
;; Trace data flow through the system
;; Usage: bb scripts/data-flow.clj

(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(defn find-data-definitions [content]
  ;; Find schema definitions, constants, and key data structures
  (let [schemas (re-seq #"\((?:s/def|def)\s+:([^\s]+)" content)
        constants (re-seq #"\(def\s+([a-z][a-z0-9-]*-id)\s+" content)
        ops (re-seq #":op\s+:([^\s\}]+)" content)]
    {:schemas (map second schemas)
     :constants (map second constants)
     :operations (map second ops)}))

(defn analyze-data-flow []
  (let [files (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (filter #(re-matches #".*\.cljc?" (.getName %))))
        analyses (for [file files]
                   (let [content (slurp file)
                         path (str file)]
                     {:file path
                      :data (find-data-definitions content)}))]
    analyses))

(defn -main []
  (println "# Data Flow Analysis")
  (println)

  (let [analyses (analyze-data-flow)
        all-schemas (mapcat #(get-in % [:data :schemas]) analyses)
        all-constants (mapcat #(get-in % [:data :constants]) analyses)
        all-ops (mapcat #(get-in % [:data :operations]) analyses)]

    (println "## Key Data Structures")
    (println)
    (println "### Schema Definitions")
    (doseq [schema (distinct all-schemas)]
      (println (str "- `:" schema "`")))

    (println "\n### Constants & IDs")
    (doseq [const (distinct all-constants)]
      (println (str "- `" const "`")))

    (println "\n### Operations")
    (doseq [op (distinct all-ops)]
      (println (str "- `:op :" op "`")))

    (println "\n## Data Flow Patterns")
    (println)
    (println "```")
    (println "User Event")
    (println "  ↓")
    (println "Shell adapter (components.block / shell.global-keyboard)")
    (println "  ↓ dispatch intent map")
    (println "Executor (shell.executor)")
    (println "  ↓ api/dispatch")
    (println "Plugin Handler (plugins.*)")
    (println "  ↓ return ops + session-updates")
    (println "Kernel Transaction (kernel.transaction)")
    (println "  ↓ apply + derive")
    (println "DB Update (kernel.db)")
    (println "  ↓ re-render")
    (println "Replicant Component")
    (println "```")))

(-main)
