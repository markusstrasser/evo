#!/usr/bin/env bb
;; Generate complexity metrics per namespace
;; Usage: bb scripts/complexity.clj

(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(defn count-lines [file]
  (with-open [rdr (io/reader file)]
    (count (line-seq rdr))))

(defn analyze-complexity [file]
  (let [content (slurp file)
        lines (count-lines file)
        ;; Count nesting depth as proxy for complexity
        max-depth (->> (str/split content #"\n")
                       (map #(count (filter #{\(} %)))
                       (apply max 0))
        ;; Count function definitions
        fns (count (re-seq #"\(defn" content))
        ;; Count conditionals
        conditionals (+ (count (re-seq #"\(if\s" content))
                        (count (re-seq #"\(when" content))
                        (count (re-seq #"\(cond" content))
                        (count (re-seq #"\(case" content)))
        ;; Count let bindings
        lets (count (re-seq #"\(let\s" content))]
    {:lines lines
     :functions fns
     :max-nesting max-depth
     :conditionals conditionals
     :let-bindings lets
     :complexity-score (+ (* max-depth 2) conditionals lets)}))

(defn -main []
  (println "# Complexity Metrics")
  (println)

  (let [files (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (filter #(re-matches #".*\.cljc?" (.getName %))))
        analyses (map (fn [file]
                        (let [path (str file)
                              ns-name (-> path
                                          (str/replace #"^src/" "")
                                          (str/replace #"\.cljc?$" "")
                                          (str/replace #"/" "."))]
                          (assoc (analyze-complexity file)
                                 :namespace ns-name
                                 :file path)))
                      files)
        sorted (sort-by :complexity-score > analyses)]

    (println "## High Complexity Namespaces")
    (println)
    (println "| Namespace | LOC | Fns | Max Nesting | Conditionals | Complexity |")
    (println "|-----------|-----|-----|-------------|--------------|------------|")

    (doseq [ns (take 15 sorted)]
      (println (str "| " (:namespace ns)
                    " | " (:lines ns)
                    " | " (:functions ns)
                    " | " (:max-nesting ns)
                    " | " (:conditionals ns)
                    " | **" (:complexity-score ns) "** |")))

    (println)
    (println "## Summary")
    (let [total-lines (reduce + (map :lines analyses))
          total-fns (reduce + (map :functions analyses))
          avg-complexity (/ (reduce + (map :complexity-score analyses))
                            (count analyses))]
      (println (str "- Total LOC: " total-lines))
      (println (str "- Total Functions: " total-fns))
      (println (str "- Average Complexity: " (int avg-complexity)))
      (println (str "- High Complexity (>100): "
                    (count (filter #(> (:complexity-score %) 100) analyses)))))))

(-main)
