#!/usr/bin/env bb
;; Generate architecture summary
;; Usage: bb scripts/arch-summary.clj [--detailed]

(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(def detailed? (some #{"--detailed" "-d"} *command-line-args*))

(defn count-lines [file]
  (with-open [rdr (io/reader file)]
    (count (line-seq rdr))))

(defn extract-top-level-forms [file]
  (try
    (let [content (slurp file)
          defn-forms (re-seq #"\(defn-?\s+([^\s\[]+)" content)
          def-forms (re-seq #"\(def\s+([^\s\[]+)" content)
          defmulti-forms (re-seq #"\(defmulti\s+([^\s\[]+)" content)
          defmethod-forms (re-seq #"\(defmethod\s+([^\s\[]+)" content)]
      {:defns (map second defn-forms)
       :defs (map second def-forms)
       :multimethods (map second defmulti-forms)
       :methods (map second defmethod-forms)})
    (catch Exception _
      {:defns [] :defs [] :multimethods [] :methods []})))

(defn extract-requires [file]
  (try
    (let [content (slurp file)
          require-forms (re-seq #"\[([^\s\]]+)" content)]
      (->> require-forms
           (map second)
           (filter #(or (str/includes? % "kernel")
                        (str/includes? % "plugins")
                        (str/includes? % "shell")
                        (str/includes? % "components")))
           (take 10)
           vec))
    (catch Exception _ [])))

(defn extract-function-signatures [file]
  (try
    (let [content (slurp file)
          lines (str/split content #"\n")
          fn-sigs (->> lines
                       (map-indexed vector)
                       (filter (fn [[_ line]]
                                 (re-find #"\(defn-?\s+" line)))
                       (map (fn [[idx line]]
                              (let [fn-name (second (re-find #"\(defn-?\s+([^\s\[]+)" line))
                                    next-lines (take 5 (drop (inc idx) lines))
                                    docstring (when (re-find #"^\s*\"" (first next-lines))
                                                (first next-lines))
                                    args-line (if docstring
                                                (second next-lines)
                                                (first next-lines))
                                    args (second (re-find #"\[([^\]]*)\]" args-line))]
                                {:name fn-name
                                 :args args
                                 :docstring (when docstring
                                              (str/trim (str/replace docstring #"\"" "")))})))
                       (remove nil?))]
      fn-sigs)
    (catch Exception _ [])))

(defn analyze-namespace [file]
  (let [path (str file)
        ns-name (-> path
                    (str/replace #"^src/" "")
                    (str/replace #"\\.cljc?s?$" "")
                    (str/replace #"/" ".")
                    (str/replace #"_" "-"))
        forms (extract-top-level-forms file)
        lines (count-lines file)
        fn-count (+ (count (:defns forms))
                    (count (:multimethods forms)))]
    (cond-> {:namespace ns-name
             :file path
             :lines lines
             :functions fn-count
             :top-functions (take 5 (:defns forms))}
      detailed? (assoc :forms forms
                       :requires (extract-requires file)
                       :signatures (take 10 (extract-function-signatures file))))))

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

(defn print-namespace-summary [ns]
  (println (str "- **" (:namespace ns) "** "
                "(" (:lines ns) " LOC, " (:functions ns) " fns)"))
  (when (seq (:top-functions ns))
    (println (str "  - Key: " (str/join ", " (take 3 (:top-functions ns)))))))

(defn print-namespace-detailed [ns]
  (println (str "### " (:namespace ns)))
  (println (str "- **File:** " (:file ns)))
  (println (str "- **Size:** " (:lines ns) " LOC, " (:functions ns) " functions"))

  ;; Show key functions with signatures
  (when (seq (:signatures ns))
    (println "- **Key Functions:**")
    (doseq [sig (take 5 (:signatures ns))]
      (println (str "  - `" (:name sig)
                    (when (:args sig) (str " [" (:args sig) "]"))
                    "`"))
      (when (:docstring sig)
        (println (str "    > " (str/trim (:docstring sig)))))))

  ;; Show dependencies
  (when (seq (:requires ns))
    (println (str "- **Dependencies:** "
                  (str/join ", " (map #(str "`" % "`")
                                      (take 5 (:requires ns)))))))

  ;; Show form breakdown
  (let [forms (:forms ns)]
    (when (or (seq (:defs forms))
              (seq (:multimethods forms))
              (seq (:methods forms)))
      (println "- **Forms:**")
      (when (seq (:defs forms))
        (println (str "  - " (count (:defs forms)) " defs: "
                      (str/join ", " (map #(str "`" % "`")
                                          (take 5 (:defs forms)))))))
      (when (seq (:multimethods forms))
        (println (str "  - " (count (:multimethods forms)) " multimethods: "
                      (str/join ", " (map #(str "`" % "`")
                                          (:multimethods forms))))))
      (when (seq (:methods forms))
        (println (str "  - " (count (:methods forms)) " methods")))))
  (println))

(defn -main []
  (let [src-files (->> (file-seq (io/file "src"))
                       (filter #(.isFile %))
                       (filter #(re-matches #".*\.(clj|cljs|cljc)" (.getName %))))
        analyses (map analyze-namespace src-files)
        by-category (group-by (comp categorize-namespace :namespace) analyses)
        total-lines (reduce + (map :lines analyses))
        total-fns (reduce + (map :functions analyses))]

    (println (str "# " (if detailed? "Detailed " "") "Architecture Summary"))
    (println)
    (println (str "**Total:** " (count analyses) " namespaces, "
                  total-lines " LOC, " total-fns " functions"))
    (println)

    (doseq [[category nss] (sort-by key by-category)]
      (let [sorted-ns (sort-by :lines > nss)
            cat-lines (reduce + (map :lines sorted-ns))
            cat-fns (reduce + (map :functions sorted-ns))]
        (println (str "\n## " (name category) " (" (count sorted-ns) " namespaces, "
                      cat-lines " LOC, " cat-fns " functions)"))
        (println)

        (if detailed?
          (doseq [ns sorted-ns]
            (print-namespace-detailed ns))
          (do
            (doseq [ns sorted-ns]
              (print-namespace-summary ns))
            (println)))))

    ;; Cross-category dependencies (detailed only)
    (when detailed?
      (println)
      (println "## Cross-Category Dependencies")
      (println)
      (println "```")
      (println "kernel    ← plugins ← shell ← components")
      (println "  ↓")
      (println "parser")
      (println "  ↓")
      (println "plugins")
      (println "```")
      (println))))

(-main)
