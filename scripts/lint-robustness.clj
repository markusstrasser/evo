#!/usr/bin/env bb
(ns lint-robustness
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(defn find-cljs-files [dir]
  (->> (file-seq (io/file dir))
       (filter #(str/ends-with? (.getName %) ".cljs"))))

(def rules
  [{:id :shadowing-val
    :regex #"\s\[.*:keys\s+\[.*val\s.*\]"
    :message "Avoid shadowing clojure.core/val. Rename to 'v' or 'value'."}
   {:id :shadowing-key
    :regex #"\s\[.*:keys\s+\[.*key\s.*\]"
    :message "Avoid shadowing clojure.core/key. Rename to 'k'."}
   {:id :root-id-string
    :regex #":under\s+:doc"
    :message "Use constants for root IDs (e.g., const/doc-id) instead of keywords if possible, or ensure consistent usage."}
   {:id :direct-atom-mutation
    :regex #"\(swap!\s+!state"
    :message "Direct atom mutation in UI components. Prefer dispatching intents via on-intent / shell.executor."}
   {:id :missing-map-key
    :regex #"\(for\s+\[.*\]\s+\["
    :message "Potential missing :key in list comprehension. Verify Replicant/React key usage."}])

(defn check-file [file]
  (let [content (slurp file)
        lines (str/split-lines content)
        rel-path (str file)]
    (doseq [[line-num line] (map-indexed vector lines)]
      (doseq [{:keys [regex message id]} rules]
        (when (re-find regex line)
          (println (str rel-path ":" (inc line-num) " [" (name id) "] " message)))))))

(defn -main []
  (println "Linting for robustness patterns...")
  (doseq [file (find-cljs-files "src")]
    (check-file file))
  (println "Done."))

(-main)
