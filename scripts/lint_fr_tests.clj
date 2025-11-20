#!/usr/bin/env bb

(ns lint-fr-tests
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [edamame.core :as edamame]))

(def strict? (some #{"--strict"} *command-line-args*))

(def watch-config
  (let [file (io/file "resources/fr_watchlist.edn")]
    (when (.exists file)
      (-> file slurp edn/read-string))))

(def watched-namespaces (set (:namespaces watch-config)))
(def watched-test-symbols (set (:tests watch-config)))

(defn- test-files []
  (->> (file-seq (io/file "test"))
       (filter #(.isFile ^java.io.File %))
       (filter (fn [^java.io.File f]
                 (let [name (.getName f)]
                   (or (str/ends-with? name "_test.clj")
                       (str/ends-with? name "_test.cljc")
                       (str/ends-with? name "_test.cljs")))))))

(defn- file->namespace [^java.io.File file]
  (let [rel (-> (.getPath file)
                (str/replace "\\" "/")
                (str/replace-first (re-pattern "^test/") "")
                (str/replace #"\.clj(c|s)?$" ""))]
    (-> rel
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(def edamame-opts {:all true
                   :read-cond :allow
                   :features #{:clj}
                   :auto-resolve (fn [alias]
                                   (if (= :current alias)
                                     'lint-fr-tests
                                     (symbol (str "lint-fr-tests." alias))))})

(defn- parse-tests [file]
  (try
    (let [forms (edamame/parse-string-all (slurp file) edamame-opts)
          ns-sym (file->namespace file)]
      (for [form forms
            :when (and (seq? form)
                       (= 'deftest (first form)))]
        (let [sym (second form)
              metadata (meta sym)
              name (if (symbol? sym) sym (symbol (str sym)))]
          {:name name
           :ns ns-sym
           :fr-ids (:fr/ids metadata #{})})))
    (catch Exception e
      (binding [*out* *err*]
        (println "⚠️  Unable to parse" (.getPath ^java.io.File file) "-" (.getMessage e)))
      [])))

(defn- gather-tests []
  (mapcat parse-tests (test-files)))

(defn- fr-registry-count []
  (-> "resources/specs.edn" io/file slurp edn/read-string count))

(defn -main [& _args]
  (let [tests (gather-tests)
        watched (fn [{:keys [ns name]}]
                   (let [fq (symbol (str ns) (str name))]
                     (cond
                       (and (empty? watched-namespaces)
                            (empty? watched-test-symbols)) true
                       (contains? watched-test-symbols fq) true
                       (contains? watched-namespaces ns) true
                       :else false)))
        watched-tests (filter watched tests)
        grouped (group-by #(boolean (seq (:fr-ids %))) watched-tests)
        with-fr (get grouped true [])
        without-fr (set (map (juxt :ns :name) (get grouped false [])))
        verified-frs (->> with-fr (mapcat :fr-ids) set)
        tests-by-fr (reduce (fn [acc {:keys [name fr-ids ns]}]
                              (reduce (fn [m fr-id]
                                        (update m fr-id (fnil conj #{}) (symbol (str ns) (str name))))
                                      acc
                                      fr-ids))
                            {}
                            with-fr)
        total-frs (fr-registry-count)
        test-count (count with-fr)
        uncited-test-count (count without-fr)]
    (println "📋 Functional Requirement Test Coverage")
    (println "  Total FRs:" total-frs)
    (println "  FRs with verifying tests:" (count verified-frs))
    (println "  Tests citing FRs:" test-count)
    (println "  Tests missing :fr/ids metadata:" uncited-test-count)
    (when (pos? uncited-test-count)
      (println)
      (println "⚠️  Add :fr/ids metadata to the tests below to keep the Spec-as-Database loop healthy:")
      (doseq [[ns name] (sort without-fr)]
        (println "   -" (symbol (str ns) (str name)))))
    (when (and strict? (pos? uncited-test-count))
      (System/exit 1))
    (System/exit 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
