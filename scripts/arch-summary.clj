#!/usr/bin/env bb
;; Generate token-efficient architecture summary
;; Usage: bb scripts/arch-summary.clj

(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn])

(defn count-lines [file]
  (with-open [rdr (io/reader file)]
    (count (line-seq rdr))))

(defn extract-top-level-forms [file]
  (try
    (let [content (slurp file)
          ;; Simple regex to find defn, def, defmulti, defmethod
          forms (re-seq #"\((?:defn|def|defmulti|defmethod|defprotocol)\s+([^\s\)]+)" content)]
      (map second forms))
    (catch Exception _ [])))

(defn analyze-namespace [file]
  (let [path (str file)
        ns-name (-> path
                    (str/replace #"^src/" "")
                    (str/replace #"\.cljc?$" "")
                    (str/replace #"/" ".")
                    (str/replace #"_" "-"))
        lines (count-lines file)
        forms (extract-top-level-forms file)]
    {:namespace ns-name
     :file path
     :lines lines
     :functions (count forms)
     :top-functions (take 5 forms)}))

(defn categorize-namespace [ns-name]
  (cond
    (str/starts-with? ns-name "kernel.") :kernel
    (str/starts-with? ns-name "plugins.") :plugins
    (str/starts-with? ns-name "shell.") :shell
    (str/starts-with? ns-name "components.") :components
    (str/starts-with? ns-name "keymap.") :keymap
    (str/starts-with? ns-name "parser.") :parser
    (str/starts-with? ns-name "utils.") :utils
    :else :other))

(defn -main []
  (let [src-files (->> (file-seq (io/file "src"))
                       (filter #(.isFile %))
                       (filter #(or (.endsWith (.getName %) ".clj")
                                    (.endsWith (.getName %) ".cljs")
                                    (.endsWith (.getName %) ".cljc"))))
        analyses (map analyze-namespace src-files)
        by-category (group-by (comp categorize-namespace :namespace) analyses)
        total-lines (reduce + (map :lines analyses))
        total-fns (reduce + (map :functions analyses))]

    (println "# Evo Architecture Summary")
    (println)
    (println (str "**Total:** " (count analyses) " namespaces, "
                  total-lines " LOC, " total-fns " functions"))
    (println)

    (doseq [[category nss] (sort-by key by-category)]
      (let [cat-lines (reduce + (map :lines nss))
            cat-fns (reduce + (map :functions nss))]
        (println (str "\n## " (name category) " (" (count nss) " namespaces, "
                      cat-lines " LOC, " cat-fns " functions)"))
        (println)
        (doseq [ns (sort-by :lines > nss)]
          (println (str "- **" (:namespace ns) "** "
                        "(" (:lines ns) " LOC, " (:functions ns) " fns)"))
          (when (seq (:top-functions ns))
            (println (str "  - Key: " (str/join ", " (take 3 (:top-functions ns)))))))
        (println)))))

(-main)
