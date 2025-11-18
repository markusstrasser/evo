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

(defn get-related-tests [file-path]
  (let [test-path (str/replace file-path #"^src/" "test/")
        test-path (str/replace test-path #"\.clj[sc]?$" "_test.cljc")]
    (if (.exists (io/file test-path))
      test-path
      nil)))

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
  (if-let [test-file (get-related-tests file-path)]
    (println (str "✅ Test file found: `" test-file "`"))
    (println "❌ No direct test file found (checked mirror path in `test/`)."))
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
