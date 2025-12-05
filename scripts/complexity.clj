#!/usr/bin/env bb
;; Unified complexity analysis tool
;;
;; Usage:
;;   bb scripts/complexity.clj                    # namespace-level summary
;;   bb scripts/complexity.clj --functions        # function-level analysis
;;   bb scripts/complexity.clj --functions --detailed  # with operation types
;;   bb scripts/complexity.clj --help
;;
;; Replaces: complexity.clj, function-complexity.clj, function-complexity-detailed.clj

(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(def cli-opts
  {:functions (some #{"--functions" "-f"} *command-line-args*)
   :detailed  (some #{"--detailed" "-d"} *command-line-args*)
   :help      (some #{"--help" "-h"} *command-line-args*)})

;;; Shared utilities

(defn count-lines [file]
  (with-open [rdr (io/reader file)]
    (count (line-seq rdr))))

(defn source-files []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile %))
       (filter #(re-matches #".*\.(clj|cljs|cljc)" (.getName %)))))

(defn file->namespace [path]
  (-> (str path)
      (str/replace #"^src/" "")
      (str/replace #"\.(clj|cljs|cljc)$" "")
      (str/replace #"/" ".")))

;;; Function-level analysis

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
                   (count (filter #{\(} line))))

          ;; Inside function - track depth
          (and current-fn (pos? paren-depth))
          (let [opens (count (filter #{\(} line))
                closes (count (filter #{\)} line))
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

(defn analyze-function [fn-map detailed?]
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
        lets (count (re-seq #"\(let\s" body))
        base {:name (:name fn-map)
              :loc loc
              :max-nesting max-nesting
              :conditionals conditionals
              :loops loops
              :let-bindings lets
              :complexity (+ (* max-nesting 2) conditionals (* loops 3) lets)}]
    (if detailed?
      (assoc base :operations
             {:creates? (str/includes? body ":create-node")
              :places? (str/includes? body ":place")
              :updates? (str/includes? body ":update-node")
              :intents? (str/includes? body "handle-intent")
              :validates? (or (str/includes? body "valid?")
                              (str/includes? body "validate"))
              :derives? (str/includes? body "derive")})
      base)))

(defn analyze-file-functions [file detailed?]
  (try
    (let [lines (str/split (slurp file) #"\n")
          ns-name (file->namespace file)
          functions (find-function-boundaries lines)
          analyzed (map #(analyze-function % detailed?) functions)]
      {:namespace ns-name
       :file (str file)
       :functions analyzed})
    (catch Exception e
      (println (str "Error analyzing " file ": " (.getMessage e)))
      nil)))

;;; Namespace-level analysis

(defn analyze-namespace-complexity [file]
  (let [content (slurp file)
        lines (count-lines file)
        max-depth (->> (str/split content #"\n")
                       (map #(count (filter #{\(} %)))
                       (apply max 0))
        fns (count (re-seq #"\(defn" content))
        conditionals (+ (count (re-seq #"\(if\s" content))
                        (count (re-seq #"\(when" content))
                        (count (re-seq #"\(cond" content))
                        (count (re-seq #"\(case" content)))
        lets (count (re-seq #"\(let\s" content))]
    {:namespace (file->namespace file)
     :file (str file)
     :lines lines
     :functions fns
     :max-nesting max-depth
     :conditionals conditionals
     :let-bindings lets
     :complexity-score (+ (* max-depth 2) conditionals lets)}))

;;; Output formatters

(defn complexity-band [complexity]
  (cond
    (< complexity 5) :trivial
    (< complexity 10) :simple
    (< complexity 20) :moderate
    (< complexity 30) :complex
    :else :very-complex))

(defn print-namespace-report [analyses]
  (println "# Complexity Metrics (by Namespace)")
  (println)
  (let [sorted (sort-by :complexity-score > analyses)]
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

(defn print-function-report [analyses detailed?]
  (let [all-functions (->> analyses
                           (mapcat (fn [a] (map #(assoc % :ns (:namespace a)) (:functions a)))))
        sorted (sort-by :complexity > all-functions)]

    (if detailed?
      (do
        (println "# Detailed Function-Level Complexity")
        (println)
        ;; Complexity distribution
        (let [by-band (group-by (comp complexity-band :complexity) all-functions)]
          (println "## Complexity Distribution")
          (println)
          (doseq [[band fns] (sort-by key by-band)]
            (println (str "- **" (name band) "**: " (count fns) " functions")))
          (println))

        ;; Table with operations
        (println "## Top 30 Most Complex Functions")
        (println)
        (println "| Function | Namespace | LOC | Nesting | Cond | Loops | Complexity | Ops |")
        (println "|----------|-----------|-----|---------|------|-------|------------|-----|")
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

        ;; Functions by operation type
        (println "## Functions by Operation Type")
        (println)
        (let [intent-handlers (filter #(get-in % [:operations :intents?]) all-functions)
              validators (filter #(get-in % [:operations :validates?]) all-functions)
              derivers (filter #(get-in % [:operations :derives?]) all-functions)
              creators (filter #(get-in % [:operations :creates?]) all-functions)]
          (println (str "- **Intent Handlers:** " (count intent-handlers)))
          (println (str "- **Validators:** " (count validators)))
          (println (str "- **Derivers:** " (count derivers)))
          (println (str "- **Node Creators:** " (count creators)))))

      ;; Simple function report
      (do
        (println "# Function-Level Complexity")
        (println)
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
                        " | **" (:complexity fn) "** |")))))

    ;; Common sections for both modes
    (println)
    (println "## Largest Functions (by LOC)")
    (println)
    (let [by-size (sort-by :loc > all-functions)]
      (doseq [fn (take 15 by-size)]
        (println (str "- `" (:name fn) "` (" (:ns fn) ") - " (:loc fn) " lines"))))

    (println)
    (println "## Functions with Deep Nesting (>8)")
    (println)
    (let [deep-nesting (filter #(> (:max-nesting %) 8) all-functions)]
      (if (seq deep-nesting)
        (doseq [fn (take 10 deep-nesting)]
          (println (str "- `" (:name fn) "` (" (:ns fn) ") - depth " (:max-nesting fn))))
        (println "*No functions with excessive nesting found.*")))

    ;; Summary
    (println)
    (println "## Summary")
    (let [total-fns (count all-functions)
          avg-loc (/ (reduce + (map :loc all-functions)) (max 1 total-fns))
          avg-complexity (/ (reduce + (map :complexity all-functions)) (max 1 total-fns))
          large-fns (count (filter #(> (:loc %) 50) all-functions))
          complex-fns (count (filter #(> (:complexity %) 30) all-functions))]
      (println (str "- Total functions: " total-fns))
      (println (str "- Average LOC: " (int avg-loc)))
      (println (str "- Average complexity: " (int avg-complexity)))
      (println (str "- Large functions (>50 LOC): " large-fns))
      (println (str "- High complexity (>30): " complex-fns)))))

(defn print-help []
  (println "complexity.clj - Unified complexity analysis tool")
  (println)
  (println "Usage:")
  (println "  bb scripts/complexity.clj                         # namespace-level")
  (println "  bb scripts/complexity.clj --functions             # function-level")
  (println "  bb scripts/complexity.clj --functions --detailed  # with operations")
  (println)
  (println "Options:")
  (println "  -f, --functions  Analyze at function level (default: namespace)")
  (println "  -d, --detailed   Include operation type analysis (requires --functions)")
  (println "  -h, --help       Show this help"))

;;; Main

(defn -main []
  (cond
    (:help cli-opts)
    (print-help)

    (:functions cli-opts)
    (let [analyses (->> (source-files)
                        (map #(analyze-file-functions % (:detailed cli-opts)))
                        (remove nil?))]
      (print-function-report analyses (:detailed cli-opts)))

    :else
    (let [analyses (map analyze-namespace-complexity (source-files))]
      (print-namespace-report analyses))))

(-main)
