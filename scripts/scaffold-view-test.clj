#!/usr/bin/env bb
(ns scaffold-view-test
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(defn usage []
  (println "Usage: bb scaffold:view-test <src/path/to/component.cljs>"))

(defn component-name? [name]
  (and name
       (re-matches #"^[A-Z].*" name))) ; Simple heuristic: Starts with uppercase

(defn extract-components [file-path]
  (let [content (slurp file-path)
        matches (re-seq #"\(defn\s+([A-Z][a-zA-Z0-9\-\?]+)" content)]
    (map second matches)))

(defn infer-test-path [src-path]
  (-> src-path
      (str/replace #"^src/" "test/view/")
      (str/replace #"\.cljs$" "_test.cljc")))

(defn generate-test-content [src-ns components]
  (let [test-ns (str "view." src-ns "-test")]
    (str "(ns " test-ns "\n"
         "  (:require [clojure.test :refer [deftest testing is]]\n"
         "            [view.util :as vu]\n"
         "            [" src-ns " :as sut]))\n\n"
         (str/join "\n"
                   (for [comp components]
                     (str "(deftest " (str/lower-case comp) "-rendering-test\n"
                          "  (testing \"" comp " renders correctly\"\n"
                          "    (let [props {}\n"
                          "          view (sut/" comp " props)]\n"
                          "      (is (vector? view) \"Returns hiccup vector\")\n"
                          "      ;; TODO: Add specific assertions\n"
                          "      ;; (is (vu/find-element view :.some-class))\n"
                          "      )))
" ))))))

(defn -main [& args]
  (let [src-path (first args)]
    (when-not src-path
      (usage)
      (System/exit 1))

    (when-not (.exists (io/file src-path))
      (println "Error: Source file not found:" src-path)
      (System/exit 1))

    (let [src-ns (-> src-path
                     (str/replace #"^src/" "")
                     (str/replace #"\.cljs$" "")
                     (str/replace #"/" "."))
          components (extract-components src-path)
          test-path (infer-test-path src-path)]

      (if (empty? components)
        (println "No TitleCase components found in" src-path)
        (do
          (if (.exists (io/file test-path))
            (println "Test file already exists:" test-path)
            (do
              (io/make-parents test-path)
              (spit test-path (generate-test-content src-ns components))
              (println "✅ Created view test scaffold:" test-path)
              (println "   Target components:" (str/join ", " components)))))))))

(apply -main *command-line-args*)
