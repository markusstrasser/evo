#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def live-runtime-files
  ["README.md"
   "scripts/data-flow.clj"
   "scripts/call-graph.clj"
   "scripts/lint-robustness.clj"])

(def stale-runtime-patterns
  [{:label "shell.nexus"
    :pattern #"shell\.nexus"}
   {:label "Nexus dispatcher"
    :pattern #"Nexus dispatcher"}
   {:label "Replicant/Nexus"
    :pattern #"Replicant/Nexus"}
   {:label "via 'nexus'"
    :pattern #"via 'nexus'"}
   {:label "→ Nexus →"
    :pattern #"→ Nexus →"}])

(defn file-lines [path]
  (-> path slurp str/split-lines))

(defn runtime-drift-issues []
  (for [path live-runtime-files
        :let [lines (file-lines path)]
        [line-num line] (map-indexed vector lines)
        {:keys [label pattern]} stale-runtime-patterns
        :when (re-find pattern line)]
    {:path path
     :line (inc line-num)
     :issue (str "stale runtime reference: " label)}))

(defn markdown-links [content]
  (map second (re-seq #"\[.*?\]\((.*?)\)" content)))

(defn indexed-doc-paths []
  (let [dx-dir (fs/path "docs")
        dx-content (slurp (str (fs/path dx-dir "DX_INDEX.md")))]
    (->> (markdown-links dx-content)
         (remove #(or (str/starts-with? % "http")
                      (str/starts-with? % "#")))
         (map #(fs/normalize (fs/path dx-dir %)))
         (filter #(and (fs/exists? %)
                       (str/ends-with? (str %) ".md")))
         (map str)
         distinct)))

(defn executed-doc-issues []
  (for [path (indexed-doc-paths)
        :when (re-find #"(?m)^Status:\s+Executed" (slurp path))]
    {:path path
     :line nil
     :issue "executed plan indexed as canonical documentation"}))

(defn editor-runtime-issues []
  (let [editor (slurp "src/shell/editor.cljs")]
    (concat
     (when-not (str/includes? editor "(executor/apply-intent! !db intent-map \"DIRECT\")")
       [{:path "src/shell/editor.cljs"
         :line nil
         :issue "missing canonical executor dispatch in handle-intent"}])
     (when-not (str/includes? editor "(plugins/init!)")
       [{:path "src/shell/editor.cljs"
         :line nil
         :issue "missing explicit plugins/init! bootstrap"}]))))

(defn print-issue! [{:keys [path line issue]}]
  (println (str path
                (when line (str ":" line))
                " - " issue)))

(defn main []
  (println "Verifying live architecture/runtime surfaces...")
  (let [issues (concat (runtime-drift-issues)
                       (executed-doc-issues)
                       (editor-runtime-issues))]
    (if (seq issues)
      (do
        (println)
        (doseq [issue issues]
          (print-issue! issue))
        (System/exit 1))
      (println "✓ Live runtime/bootstrap docs are aligned"))))

(main)
