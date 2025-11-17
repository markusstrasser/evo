#!/usr/bin/env bb
;; Per-function complexity analysis
;; Usage: bb scripts/function-complexity.clj

(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn])

(defn find-function-boundaries [lines]
  ;; Find (defn ...) and track matching parens to get function body
  (loop [i 0
         functions []
         current-fn nil
         paren-depth 0]
    (if (>= i (count lines))
      (if current-fn (conj functions current-fn) functions)
      (let [line (nth lines i)
            trimmed (str/trim line)]
        (cond
          ;; Start of new function
          (and (not current-fn) (re-matches #"^\(defn.*" trimmed))
          (let [fn-name (second (re-find #"\(defn\s+([^\s\[]+)" trimmed))]
            (recur (inc i) functions
                   {:name fn-name :start i :lines [line]}
                   (count (filter #{\(} line))))

          ;; Inside function - track depth
          (and current-fn (pos? paren-depth))
          (let [opens (count (filter #{\(} line))
                closes (count (filter #{\)} line))
                new-depth (+ paren-depth opens (- closes))]
            (if (<= new-depth 0)
              ;; Function ended
              (recur (inc i)
                     (conj functions (assoc current-fn
                                            :end i
                                            :lines (conj (:lines current-fn) line)))
                     nil 0)
              ;; Continue function
              (recur (inc i) functions
                     (update current-fn :lines conj line)
                     new-depth)))

          :else
          (recur (inc i) functions current-fn paren-depth))))))

(defn analyze-function [fn-map]
  (let [lines (:lines fn-map)
        body (str/join "\n" lines)
        loc (count lines)
        max-nesting (->> lines
                         (map #(count (filter #{\(} %)))
                         (apply max 0))
        conditionals (+ (count (re-seq #"\(if\s" body))
                        (count (re-seq #"\(when" body))
                        (count (re-seq #"\(cond" body))
                        (count (re-seq #"\(case" body)))
        loops (+ (count (re-seq #"\(loop\s" body))
                 (count (re-seq #"\(recur" body)))
        lets (count (re-seq #"\(let\s" body))]
    (assoc fn-map
           :loc loc
           :max-nesting max-nesting
           :conditionals conditionals
           :loops loops
           :let-bindings lets
           :complexity (+ (* max-nesting 2) conditionals (* loops 3) lets))))

(defn analyze-file [file]
  (try
    (let [lines (str/split (slurp file) #"\n")
          path (str file)
          ns-name (-> path
                      (str/replace #"^src/" "")
                      (str/replace #"\.cljc?$" "")
                      (str/replace #"/" "."))
          functions (find-function-boundaries lines)
          analyzed (map analyze-function functions)]
      {:namespace ns-name
       :file path
       :functions analyzed})
    (catch Exception e
      (println (str "Error analyzing " file ": " (.getMessage e)))
      nil)))

(defn -main []
  (println "# Function-Level Complexity")
  (println)

  (let [files (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (filter #(re-matches #".*\.cljc?" (.getName %))))
        analyses (->> files
                      (map analyze-file)
                      (remove nil?))
        all-functions (->> analyses
                           (mapcat :functions)
                           (map #(assoc % :ns (:namespace (first analyses)))))
        sorted (sort-by :complexity > all-functions)]

    (println "## Top 20 Most Complex Functions")
    (println)
    (println "| Function | Namespace | LOC | Nesting | Conditionals | Loops | Complexity |")
    (println "|----------|-----------|-----|---------|--------------|-------|------------|")

    (doseq [fn (take 20 sorted)]
      (println (str "| `" (:name fn) "`"
                    " | " (or (:ns fn) "unknown")
                    " | " (:loc fn)
                    " | " (:max-nesting fn)
                    " | " (:conditionals fn)
                    " | " (:loops fn)
                    " | **" (:complexity fn) "** |")))

    (println)
    (println "## Largest Functions (by LOC)")
    (println)
    (let [by-size (sort-by :loc > all-functions)]
      (doseq [fn (take 15 by-size)]
        (println (str "- `" (:name fn) "` - " (:loc fn) " lines"))))

    (println)
    (println "## Functions with Deep Nesting (>10)")
    (println)
    (let [deep-nesting (filter #(> (:max-nesting %) 10) all-functions)]
      (if (seq deep-nesting)
        (doseq [fn (take 10 deep-nesting)]
          (println (str "- `" (:name fn) "` - nesting depth " (:max-nesting fn))))
        (println "*No functions with excessive nesting found.*")))

    (println)
    (println "## Summary")
    (let [total-fns (count all-functions)
          avg-loc (/ (reduce + (map :loc all-functions)) total-fns)
          avg-complexity (/ (reduce + (map :complexity all-functions)) total-fns)
          large-fns (count (filter #(> (:loc %) 50) all-functions))
          complex-fns (count (filter #(> (:complexity %) 30) all-functions))]
      (println (str "- Total functions: " total-fns))
      (println (str "- Average LOC: " (int avg-loc)))
      (println (str "- Average complexity: " (int avg-complexity)))
      (println (str "- Large functions (>50 LOC): " large-fns))
      (println (str "- High complexity (>30): " complex-fns)))))

(-main)
