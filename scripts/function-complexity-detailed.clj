#!/usr/bin/env bb
;; Comprehensive per-function complexity analysis
;; Usage: bb scripts/function-complexity-detailed.clj

(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(defn find-function-boundaries [lines]
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
          (let [fn-name (second (re-find #"\(defn-?\s+([^\s\[]+)" trimmed))]
            (recur (inc i) functions
                   {:name fn-name :start i :lines [line]}
                   (count (filter #{\\(} line))))

          ;; Inside function - track depth
          (and current-fn (pos? paren-depth))
          (let [opens (count (filter #{\\(} line))
                closes (count (filter #{\\)} line))
                new-depth (+ paren-depth opens (- closes))]
            (if (<= new-depth 0)
              (recur (inc i)
                     (conj functions (assoc current-fn
                                            :end i
                                            :lines (conj (:lines current-fn) line)))
                     nil 0)
              (recur (inc i) functions
                     (update current-fn :lines conj line)
                     new-depth)))

          :else
          (recur (inc i) functions current-fn paren-depth))))))

(defn analyze-function [fn-map]
  (let [lines (:lines fn-map)
        body (str/join "\\n" lines)
        loc (count lines)
        max-nesting (->> lines
                         (map #(count (filter #{\\(} %)))
                         (apply max 0))
        conditionals (+ (count (re-seq #"\\(if\\s" body))
                        (count (re-seq #"\\(when" body))
                        (count (re-seq #"\\(cond" body))
                        (count (re-seq #"\\(case" body)))
        loops (+ (count (re-seq #"\\(loop\\s" body))
                 (count (re-seq #"\\(recur" body)))
        lets (count (re-seq #"\\(let\\s" body))
        ;; Extract what the function does
        doc-line (second lines)
        has-doc? (and doc-line (str/includes? doc-line "\""))
        ;; Look for key operations
        creates-node? (str/includes? body ":create-node")
        places-node? (str/includes? body ":place")
        updates-node? (str/includes? body ":update-node")
        handles-intent? (str/includes? body "handle-intent")
        validates? (or (str/includes? body "valid?")
                       (str/includes? body "validate"))
        derives? (str/includes? body "derive")]
    (assoc fn-map
           :loc loc
           :max-nesting max-nesting
           :conditionals conditionals
           :loops loops
           :let-bindings lets
           :complexity (+ (* max-nesting 2) conditionals (* loops 3) lets)
           :operations {:creates? creates-node?
                       :places? places-node?
                       :updates? updates-node?
                       :intents? handles-intent?
                       :validates? validates?
                       :derives? derives?})))

(defn analyze-file [file]
  (try
    (let [lines (str/split (slurp file) #"\\n")
          path (str file)
          ns-name (-> path
                      (str/replace #"^src/" "")
                      (str/replace #"\\.cljc?s?$" "")
                      (str/replace #"/" "."))
          functions (find-function-boundaries lines)
          analyzed (map analyze-function functions)]
      {:namespace ns-name
       :file path
       :functions analyzed})
    (catch Exception e
      (println (str "Error analyzing " file ": " (.getMessage e)))
      nil)))

(defn complexity-band [complexity]
  (cond
    (< complexity 5) :trivial
    (< complexity 10) :simple
    (< complexity 20) :moderate
    (< complexity 30) :complex
    :else :very-complex))

(defn -main []
  (println "# Detailed Function-Level Complexity")
  (println)

  (let [files (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (filter #(re-matches #".*\\.(clj|cljs|cljc)" (.getName %))))
        analyses (->> files
                      (map analyze-file)
                      (remove nil?))
        all-functions (->> analyses
                           (mapcat (fn [analysis]
                                     (map #(assoc % :ns (:namespace analysis))
                                          (:functions analysis)))))
        sorted (sort-by :complexity > all-functions)
        by-band (group-by (comp complexity-band :complexity) all-functions)]

    ;; Complexity distribution
    (println "## Complexity Distribution")
    (println)
    (doseq [[band fns] (sort-by key by-band)]
      (println (str "- **" (name band) "** (<" 
                   (case band
                     :trivial "5"
                     :simple "10"
                     :moderate "20"
                     :complex "30"
                     :very-complex "∞")
                   "): " (count fns) " functions")))
    (println)

    ;; Top complex functions with details
    (println "## Top 30 Most Complex Functions")
    (println)
    (println "| Function | Namespace | LOC | Nesting | Cond | Loops | Complexity | Operations |")
    (println "|----------|-----------|-----|---------|------|-------|------------|------------|")

    (doseq [fn (take 30 sorted)]
      (let [ops (:operations fn)
            op-str (->> [(when (:creates? ops) "C")
                         (when (:places? ops) "P")
                         (when (:updates? ops) "U")
                         (when (:intents? ops) "I")
                         (when (:validates? ops) "V")
                         (when (:derives? ops) "D")]
                        (remove nil?)
                        (str/join ","))]
        (println (str "| `" (:name fn) "`"
                     " | " (or (:ns fn) "unknown")
                     " | " (:loc fn)
                     " | " (:max-nesting fn)
                     " | " (:conditionals fn)
                     " | " (:loops fn)
                     " | **" (:complexity fn) "**"
                     " | " op-str " |"))))

    (println)
    (println "**Legend:** C=Creates, P=Places, U=Updates, I=Intents, V=Validates, D=Derives")
    (println)

    ;; Functions by size
    (println "## Largest Functions (by LOC)")
    (println)
    (let [by-size (sort-by :loc > all-functions)]
      (doseq [fn (take 25 by-size)]
        (println (str "- `" (:name fn) "` (" (:ns fn) ") - " 
                     (:loc fn) " lines, complexity " (:complexity fn)))))

    ;; Deep nesting
    (println)
    (println "## Functions with Deep Nesting (>8)")
    (println)
    (let [deep-nesting (filter #(> (:max-nesting %) 8) all-functions)]
      (if (seq deep-nesting)
        (doseq [fn (take 15 deep-nesting)]
          (println (str "- `" (:name fn) "` (" (:ns fn) ") - depth " 
                       (:max-nesting fn) ", " (:loc fn) " LOC")))
        (println "*No functions with excessive nesting found.*")))

    ;; Functions by operation type
    (println)
    (println "## Functions by Operation Type")
    (println)
    (let [intent-handlers (filter #(get-in % [:operations :intents?]) all-functions)
          validators (filter #(get-in % [:operations :validates?]) all-functions)
          derivers (filter #(get-in % [:operations :derives?]) all-functions)
          creators (filter #(get-in % [:operations :creates?]) all-functions)]
      (println (str "- **Intent Handlers:** " (count intent-handlers) " functions"))
      (when (seq intent-handlers)
        (doseq [fn (take 5 (sort-by :complexity > intent-handlers))]
          (println (str "  - `" (:name fn) "` (" (:ns fn) ") - complexity " 
                       (:complexity fn)))))
      (println)
      (println (str "- **Validators:** " (count validators) " functions"))
      (println (str "- **Derivers:** " (count derivers) " functions"))
      (println (str "- **Node Creators:** " (count creators) " functions")))

    ;; Summary stats
    (println)
    (println "## Summary Statistics")
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
