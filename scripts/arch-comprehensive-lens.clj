#!/usr/bin/env bb
;; Comprehensive architecture lens generator
;; Target: 860-1720 lines (1/10 to 1/5 of 8603 LOC)
;; Usage: bb scripts/arch-comprehensive-lens.clj > arch-analysis/comprehensive-lens.md

(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(defn read-file-lines [file]
  (try
    (str/split-lines (slurp file))
    (catch Exception e [])))

(defn extract-function-with-body [file fn-name max-lines]
  (let [lines (read-file-lines file)
        start-idx (->> lines
                       (map-indexed vector)
                       (filter (fn [[_ line]] 
                                 (re-find (re-pattern (str "\\(defn-?\\s+" fn-name)) line)))
                       first
                       first)]
    (when start-idx
      (let [body-lines (take max-lines (drop start-idx lines))]
        (str/join "\n" body-lines)))))

(defn extract-all-functions-brief [file]
  (let [lines (read-file-lines file)]
    (->> lines
         (map-indexed vector)
         (filter (fn [[_ line]] (re-find #"\(defn-?\s+" line)))
         (map (fn [[idx line]]
                (let [fn-name (second (re-find #"\(defn-?\s+([^\s\[]+)" line))
                      next-lines (take 10 (drop idx lines))]
                  {:name fn-name
                   :line (inc idx)
                   :preview (str/join "\n" next-lines)})))
         (remove nil?))))

(defn analyze-operation-patterns [files]
  (let [all-content (str/join "\n" (map slurp files))
        create-ops (re-seq #"\{:op :create-node[^}]*\}" all-content)
        place-ops (re-seq #"\{:op :place[^}]*\}" all-content)
        update-ops (re-seq #"\{:op :update-node[^}]*\}" all-content)]
    {:create-count (count create-ops)
     :place-count (count place-ops)
     :update-count (count update-ops)
     :create-examples (take 3 create-ops)
     :place-examples (take 3 place-ops)
     :update-examples (take 3 update-ops)}))

(defn extract-schemas [files]
  (let [all-content (str/join "\n" (map slurp files))
        spec-defs (re-seq #"\(s/def\s+([^\s]+)[\s\S]{0,200}\)" all-content)]
    (->> spec-defs
         (map (fn [[match spec-name]] {:name spec-name :def (subs match 0 (min 150 (count match)))}))
         (take 10))))

(defn -main []
  (println "# Comprehensive Evo Architecture Lens")
  (println)
  (println "Generated:" (java.util.Date.))
  (println)
  (println "This lens provides deep insight into Evo's architecture with:")
  (println "- Function signatures + implementation previews")
  (println "- Full bodies of top complex functions")
  (println "- Operation patterns and flows")
  (println "- Data structures and schemas")
  (println "- Detailed call paths")
  (println)
  (println "**Target:** 860-1720 lines (1/10 to 1/5 of 8,603 LOC)")
  (println)
  (println "---")
  (println)

  (let [files (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (filter #(re-matches #".*\.(clj|cljs|cljc)" (.getName %))))]

    ;; Part 1: Data Structures and Schemas
    (println "## Part 1: Core Data Structures")
    (println)
    (println "### Database Shape")
    (println)
    (println "```clojure")
    (println (slurp "src/kernel/db.cljc"))
    (println "```")
    (println)

    ;; Part 2: Operation Patterns
    (println "## Part 2: Operation Patterns")
    (println)
    (let [patterns (analyze-operation-patterns files)]
      (println (str "- **create-node operations:** " (:create-count patterns) " occurrences"))
      (println (str "- **place operations:** " (:place-count patterns) " occurrences"))
      (println (str "- **update-node operations:** " (:update-count patterns) " occurrences"))
      (println)
      (println "### Example create-node operations:")
      (doseq [ex (:create-examples patterns)]
        (println (str "```clojure\n" ex "\n```")))
      (println)
      (println "### Example place operations:")
      (doseq [ex (:place-examples patterns)]
        (println (str "```clojure\n" ex "\n```")))
      (println)
      (println "### Example update-node operations:")
      (doseq [ex (:update-examples patterns)]
        (println (str "```clojure\n" ex "\n```"))))
    (println)

    ;; Part 3: All Functions with Previews
    (println "## Part 3: Function Catalog (All Functions with Implementation Previews)")
    (println)
    (doseq [file (sort-by str files)]
      (let [ns-name (-> (str file)
                        (str/replace #"^src/" "")
                        (str/replace #"\.(clj|cljs|cljc)$" "")
                        (str/replace #"/" "."))
            functions (extract-all-functions-brief file)]
        (when (seq functions)
          (println (str "### " ns-name))
          (println)
          (doseq [fn functions]
            (println (str "#### " (:name fn) " (line " (:line fn) ")"))
            (println "```clojure")
            (println (:preview fn))
            (println "```")
            (println)))))

    ;; Part 4: Top 10 Most Complex Functions (Full Bodies)
    (println "## Part 4: Top 10 Most Complex Functions (Full Implementation)")
    (println)
    (println "These are the functions that concentrate complexity in the codebase.")
    (println)
    ;; This section will be filled by another script
    
    (println "---")
    (println)
    (println "**End of Comprehensive Lens**")))

(-main)
