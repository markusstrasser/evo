#!/usr/bin/env bb
(ns agent-context
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]))

(defn get-git-history [file-path]
  (let [{:keys [exit out]} (shell/sh "git" "log" "-n" "3" "--pretty=format:%h - %s (%ar)" file-path)]
    (if (zero? exit)
      (str/split-lines out)
      [])))

(defn all-test-files []
  (->> (file-seq (io/file "test"))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (filter #(or (re-find #"\.(clj|cljc|cljs)$" %)
                    (re-find #"\.spec\.js$" %)))))

(defn source-namespace [file-path]
  (try
    (some->> (slurp file-path)
             (re-find #"(?m)^\(ns\s+([^\s\)]+)")
             second)
    (catch Exception _ nil)))

(defn source-info [file-path]
  (let [relative (-> file-path
                     (str/replace #"^src/" "")
                     (str/lower-case))
        no-ext (str/replace relative #"\.[^.]+$" "")
        parts (str/split no-ext #"/")
        stem (last parts)
        parent (when (> (count parts) 1)
                 (nth parts (- (count parts) 2)))]
    {:namespace (source-namespace file-path)
     :stem stem
     :parent parent}))

(defn exact-mirror-candidates [file-path]
  (let [base (-> file-path
                 (str/replace #"^src/" "test/")
                 (str/replace #"\.[^.]+$" "_test"))]
    (for [ext [".clj" ".cljc" ".cljs"]
          :let [candidate (str base ext)]
          :when (.exists (io/file candidate))]
      {:path candidate
       :score 100
       :reason "mirror-path match"})))

(defn namespace-candidates [{:keys [namespace]} test-files]
  (if-not namespace
    []
    (for [test-file test-files
          :let [content (slurp test-file)]
          :when (str/includes? content namespace)]
      {:path test-file
       :score 90
       :reason (str "references namespace " namespace)})))

(defn filename-score [{:keys [stem parent]} test-file]
  (let [filename (-> test-file io/file .getName str/lower-case)
        path-lower (str/lower-case test-file)
        exact-names #{(str stem "_test.clj")
                      (str stem "_test.cljc")
                      (str stem "_test.cljs")
                      (str stem "_test.js")
                      (str stem ".spec.js")}]
    (cond
      (contains? exact-names filename) 80
      (and parent
           (str/includes? path-lower parent)
           (str/includes? filename stem))
      70
      (and (>= (count stem) 4)
           (str/includes? filename stem))
      60
      :else nil)))

(defn filename-candidates [source test-files]
  (for [test-file test-files
        :let [score (filename-score source test-file)]
        :when score]
    {:path test-file
     :score score
     :reason "filename similarity"}))

(defn merge-candidates [candidates]
  (->> candidates
       (reduce (fn [acc {:keys [path score reason]}]
                 (update acc path
                         (fn [existing]
                           (if existing
                             {:path path
                              :score (max score (:score existing))
                              :reasons (-> (:reasons existing)
                                           (conj reason)
                                           distinct
                                           vec)}
                             {:path path
                              :score score
                              :reasons [reason]}))))
               {})
       vals
       (sort-by (juxt (comp - :score) :path))))

(defn get-related-tests [file-path]
  (let [test-files (all-test-files)
        source (source-info file-path)]
    (->> (concat (exact-mirror-candidates file-path)
                 (namespace-candidates source test-files)
                 (filename-candidates source test-files))
         merge-candidates
         vec)))

(defn analyze-complexity [file-path]
  ;; Simplified complexity analysis based on function-complexity.clj
  (try
    (let [lines (str/split (slurp file-path) #"\n")
          functions (atom [])
          current-fn (atom nil)]
      (doseq [line lines]
        (let [trimmed (str/trim line)]
          (when (re-matches #"^\(defn-?.*" trimmed)
            (let [fn-name (second (re-find #"\(defn-?\s+([^\s\[]+)" trimmed))]
              (swap! functions conj {:name fn-name :complexity 1})))))
      @functions)
    (catch Exception e [])))

(defn run-kondo [file-path]
  (let [result (shell/sh "clj-kondo" "--lint" file-path
                         "--config" "{:output {:analysis true :format :edn}}"
                         :env {"CLJ_KONDO_IGNORE_ERRORS" "true"})]
    (if (zero? (:exit result))
      (-> (edn/read-string (:out result))
          :analysis
          :var-usages)
      [])))

(defn summarize-context [file-path]
  (println (str "# Context: " file-path))
  (println)
  
  (when-not (.exists (io/file file-path))
    (println "❌ File not found.")
    (System/exit 1))

  ;; 1. Git History
  (println "## 🕒 Recent History")
  (doseq [log (get-git-history file-path)]
    (println (str "- " log)))
  (println)

  ;; 2. Related Tests
  (println "## 🧪 Testing")
  (let [test-files (get-related-tests file-path)]
    (if (seq test-files)
      (do
        (println (str "✅ Related tests found: " (count test-files)))
        (doseq [{:keys [path reasons]} (take 10 test-files)]
          (println (str "- `" path "` (" (str/join "; " reasons) ")")))
        (when (> (count test-files) 10)
          (println (str "... and " (- (count test-files) 10) " more."))))
      (println "❌ No related tests found via namespace or filename heuristics.")))
  (println)

  ;; 3. Dependencies (via Kondo)
  (println "## 🔗 Dependencies")
  (let [usages (run-kondo file-path)
        deps (->> usages
                  (map :to)
                  (distinct)
                  (sort))]
    (if (seq deps)
      (doseq [dep (take 10 deps)]
        (println (str "- `" dep "`")))
      (println "No external dependencies detected (or analysis failed)."))
    (when (> (count (distinct (map :to usages))) 10)
      (println (str "... and " (- (count (distinct (map :to usages))) 10) " more."))))
  (println)

  ;; 4. Complexity
  (println "## 🧠 Complexity")
  (let [fns (analyze-complexity file-path)]
    (if (seq fns)
      (doseq [f (take 5 fns)]
        (println (str "- `" (:name f) "`")))
      (println "No functions found.")))
  (println))

(let [file-arg (first *command-line-args*)]
  (if file-arg
    (summarize-context file-arg)
    (println "Usage: bb scripts/agent-context.clj <file-path>")))
