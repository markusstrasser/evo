#!/usr/bin/env bb
;; Extract call graph and critical paths from codebase
;; Usage: bb scripts/call-graph.clj [--detailed]
;;
;; Default: Uses clj-kondo static analysis
;; --detailed: Adds hot paths documentation and cross-namespace call analysis

(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[clojure.java.shell :as shell])

(def detailed? (some #{"--detailed" "-d"} *command-line-args*))

;; ── clj-kondo Analysis (Default Mode) ───────────────────────────────────────

(defn run-kondo-analysis []
  (let [result (shell/sh "clj-kondo" "--lint" "src"
                         "--config" "{:output {:analysis true :format :edn}}"
                         :env {"CLJ_KONDO_IGNORE_ERRORS" "true"})]
    (if (zero? (:exit result))
      (edn/read-string (:out result))
      (do
        (println "Warning: clj-kondo failed, using empty analysis")
        {:analysis {}}))))

(defn extract-calls [analysis]
  (let [var-definitions (get-in analysis [:analysis :var-definitions] [])
        var-usages (get-in analysis [:analysis :var-usages] [])]
    {:definitions (group-by :ns var-definitions)
     :usages (group-by :to var-usages)}))

(defn find-entry-points-kondo [definitions]
  (->> definitions
       (mapcat val)
       (filter #(or (str/includes? (str (:name %)) "handle")
                    (str/includes? (str (:name %)) "main")
                    (str/includes? (str (:name %)) "dispatch")))
       (map #(select-keys % [:ns :name]))
       (take 10)))

(defn trace-calls [fn-key usages depth]
  (when (<= depth 3)
    (let [calls (get usages fn-key [])]
      (for [call (take 5 calls)]
        {:from fn-key
         :to (:to call)
         :name (:name call)
         :children (trace-calls (:to call) usages (inc depth))}))))

;; ── Regex-based Analysis (Detailed Mode) ────────────────────────────────────

(defn extract-function-calls [file]
  (try
    (let [content (slurp file)
          ns-name (-> (str file)
                      (str/replace #"^src/" "")
                      (str/replace #"\\.cljc?s?$" "")
                      (str/replace #"/" "."))
          calls (->> (re-seq #"\(([a-z\-]+/[a-z\-]+)" content)
                     (map second)
                     frequencies)]
      {:namespace ns-name
       :file (str file)
       :calls calls})
    (catch Exception _ nil)))

(defn find-entry-points-documented []
  [{:point "shell.editor/handle-intent"
    :desc "Main intent dispatcher"
    :triggers "All user interactions"}
   {:point "shell.executor/apply-intent!"
    :desc "Canonical runtime entrypoint"
    :triggers "Intent dispatch from keyboard, mouse, and startup flows"}
   {:point "kernel.transaction/interpret"
    :desc "Operation interpreter"
    :triggers "All state changes"}
   {:point "kernel.db/derive-indexes"
    :desc "Index derivation"
    :triggers "After every transaction"}
   {:point "components.block/Block"
    :desc "Main block component"
    :triggers "Every block render"}])

(defn analyze-hot-paths []
  [{:path "User keystroke → Block component → Executor → API dispatch → Plugin → Transaction → Derive → Re-render"
    :frequency "Every keystroke in edit mode"
    :components ["components.block" "shell.executor" "kernel.api" "plugins.*" "kernel.transaction" "kernel.db"]}
   {:path "Selection change → Navigation plugin → Query → Update session → Re-render"
    :frequency "Every arrow key / click"
    :components ["plugins.navigation" "plugins.selection" "kernel.query" "shell.editor"]}
   {:path "Structure change → Struct plugin → Create/Place ops → Transaction → Derive indexes"
    :frequency "Indent/outdent/move operations"
    :components ["plugins.structural" "kernel.ops" "kernel.transaction" "kernel.db"]}
   {:path "Enter key → Smart editing → Context detection → Split/merge ops → Transaction"
    :frequency "Every Enter in edit mode"
    :components ["plugins.context-editing" "plugins.context" "kernel.transaction"]}])

;; ── Output Functions ────────────────────────────────────────────────────────

(defn print-kondo-analysis []
  (println "Analyzing codebase with clj-kondo...")
  (let [analysis (run-kondo-analysis)
        {:keys [definitions usages]} (extract-calls analysis)
        entry-points (find-entry-points-kondo definitions)]

    (println (str "\n## Entry Points (" (count entry-points) " found)"))
    (println)

    (doseq [ep entry-points]
      (println (str "### " (:ns ep) "/" (:name ep)))
      (println)
      (let [traces (trace-calls (:ns ep) usages 0)]
        (doseq [trace (take 5 traces)]
          (println (str "- calls `" (:name trace) "` in `" (:to trace) "`"))))
      (println))

    (println "\n## Most Called Functions")
    (println)
    (let [call-counts (->> usages
                           vals
                           (apply concat)
                           (map :to)
                           frequencies
                           (sort-by val >)
                           (take 10))]
      (doseq [[ns cnt] call-counts]
        (println (str "- `" ns "` - called " cnt " times"))))))

(defn print-detailed-analysis []
  ;; Documented entry points
  (println "## System Entry Points")
  (println)
  (doseq [entry (find-entry-points-documented)]
    (println (str "### `" (:point entry) "`"))
    (println (str "- **Description:** " (:desc entry)))
    (println (str "- **Triggered by:** " (:triggers entry)))
    (println))

  ;; Hot paths
  (println "## Critical Execution Paths")
  (println)
  (println "These are the most frequently executed code paths:")
  (println)
  (doseq [path (analyze-hot-paths)]
    (println (str "### " (:frequency path)))
    (println)
    (println (str "**Flow:** `" (:path path) "`"))
    (println)
    (println "**Components:**")
    (doseq [comp (:components path)]
      (println (str "- `" comp "`")))
    (println))

  ;; Regex-based call frequency
  (println "## Most-Called Functions (Static Analysis)")
  (println)
  (let [files (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (filter #(re-matches #".*\.(clj|cljs|cljc)" (.getName %))))
        analyses (->> files
                      (map extract-function-calls)
                      (remove nil?))
        all-calls (->> analyses
                       (mapcat :calls)
                       (reduce (fn [acc [fn-name cnt]]
                                 (update acc fn-name (fnil + 0) cnt))
                               {}))]

    (println "| Function | Call Sites |")
    (println "|----------|------------|")
    (doseq [[fn-name cnt] (take 30 (sort-by second > all-calls))]
      (println (str "| `" fn-name "` | " cnt " |"))))

  ;; Dependency clusters
  (println)
  (println "## Key Dependency Clusters")
  (println)
  (println "### Kernel Core")
  (println "```")
  (println "kernel.db ← kernel.ops ← kernel.transaction")
  (println "    ↓           ↓              ↓")
  (println "kernel.query  kernel.position  kernel.intent")
  (println "```")
  (println)
  (println "### Plugin Layer")
  (println "```")
  (println "kernel.derived-registry → plugins.* → kernel.api")
  (println "                      ↓")
  (println "               kernel.query")
  (println "```")
  (println)
  (println "### View Layer")
  (println "```")
  (println "shell.editor → components.* → shell.executor")
  (println "       ↓                              ↓")
  (println "  kernel.api                   kernel.derived-registry")
  (println "```"))

(defn -main []
  (println "# Critical Call Paths")
  (println)

  (if detailed?
    (print-detailed-analysis)
    (print-kondo-analysis)))

(-main)
