#!/usr/bin/env bb
;; Detailed call graph analysis with critical paths
;; Usage: bb scripts/call-graph-detailed.clj

(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn])

(defn extract-function-calls [file]
  "Extract function calls from a file"
  (try
    (let [content (slurp file)
          ns-name (-> (str file)
                      (str/replace #"^src/" "")
                      (str/replace #"\\.cljc?s?$" "")
                      (str/replace #"/" "."))
          ;; Find all function calls
          calls (->> (re-seq #"\\(([a-z\\-]+/[a-z\\-]+)" content)
                     (map second)
                     frequencies)]
      {:namespace ns-name
       :file (str file)
       :calls calls})
    (catch Exception e nil)))

(defn find-entry-points []
  "Identify system entry points"
  [{:point "shell.blocks-ui/handle-intent"
    :desc "Main intent dispatcher"
    :triggers "All user interactions"}
   {:point "shell.nexus/dispatch-intent"
    :desc "Nexus dispatcher"
    :triggers "Keyboard, mouse, commands"}
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
  "Document known critical execution paths"
  [{:path "User keystroke → Block component → Nexus → Plugin → Transaction → Derive → Re-render"
    :frequency "Every keystroke in edit mode"
    :components ["components.block" "shell.nexus" "plugins.*" "kernel.transaction" "kernel.db"]}
   {:path "Selection change → Navigation plugin → Query → Update session → Re-render"
    :frequency "Every arrow key / click"
    :components ["plugins.navigation" "plugins.selection" "kernel.query" "shell.blocks-ui"]}
   {:path "Structure change → Struct plugin → Create/Place ops → Transaction → Derive indexes"
    :frequency "Indent/outdent/move operations"
    :components ["plugins.struct" "kernel.ops" "kernel.transaction" "kernel.db"]}
   {:path "Enter key → Smart editing → Context detection → Split/merge ops → Transaction"
    :frequency "Every Enter in edit mode"
    :components ["plugins.smart-editing" "plugins.context" "kernel.transaction"]}])

(defn -main []
  (println "# Detailed Call Graph Analysis")
  (println)

  ;; Entry points
  (println "## System Entry Points")
  (println)
  (doseq [entry (find-entry-points)]
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

  ;; Call frequency analysis
  (println "## Most-Called Functions")
  (println)
  (println "Based on static analysis of call sites:")
  (println)

  (let [files (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (filter #(re-matches #".*\\.(clj|cljs|cljc)" (.getName %))))
        analyses (->> files
                      (map extract-function-calls)
                      (remove nil?))
        all-calls (->> analyses
                       (mapcat :calls)
                       (reduce (fn [acc [fn-name count]]
                                 (update acc fn-name (fnil + 0) count))
                               {}))]

    (println "| Function | Call Sites |")
    (println "|----------|------------|")
    (doseq [[fn-name count] (take 30 (sort-by second > all-calls))]
      (println (str "| `" fn-name "` | " count " |"))))

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
  (println "plugins.registry → plugins.* → kernel.api")
  (println "                      ↓")
  (println "               kernel.query")
  (println "```")
  (println)
  (println "### View Layer")
  (println "```")
  (println "shell.blocks-ui → components.* → shell.nexus")
  (println "       ↓                              ↓")
  (println "  kernel.api                   plugins.registry")
  (println "```"))

(-main)
