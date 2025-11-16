#!/usr/bin/env bb
(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(def spec-file "docs/specs/logseq_behaviors.md")
(def scenario-pattern #"Scenario ID[^\n]*?([A-Z0-9\-]+)")

(defn ensure-file [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (binding [*out* *err*]
        (println "File not found:" path))
      (System/exit 1))
    f))

(def scenario-ids (->> (slurp (ensure-file spec-file))
                       (re-seq scenario-pattern)
                       (map second)
                       set))

(when (empty? scenario-ids)
  (binding [*out* *err*]
    (println "No scenario IDs found in" spec-file))
  (System/exit 1))

(def test-files (->> (file-seq (ensure-file "test"))
                     (filter #(.isFile ^java.io.File %))
                     (map #(.getPath ^java.io.File %))
                     (filter #(re-find #"\.(clj|cljc|cljs)$" %))))

(def file->content (vec (map (fn [path] [path (slurp path)]) test-files)))

(defn covered? [scenario-id]
  (some (fn [[_ content]]
          (str/includes? content scenario-id))
        file->content))

(def missing (sort (remove covered? scenario-ids)))

(if (seq missing)
  (do
    (binding [*out* *err*]
      (println "Missing scenario coverage for:" (str/join ", " missing)))
    (System/exit 1))
  (println "✓ Scenario lint passed"))
