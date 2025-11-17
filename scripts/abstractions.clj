#!/usr/bin/env bb
;; Extract key abstractions: protocols, multimethods, specs
;; Usage: bb scripts/abstractions.clj

(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(defn extract-abstractions [file]
  (let [content (slurp file)
        path (str file)
        ns-name (-> path
                    (str/replace #"^src/" "")
                    (str/replace #"\.cljc?$" "")
                    (str/replace #"/" "."))
        protocols (re-seq #"\(defprotocol\s+([^\s\n]+)" content)
        multimethods (re-seq #"\(defmulti\s+([^\s\n]+)" content)
        specs (re-seq #"\(s/def\s+([^\s\n]+)" content)
        records (re-seq #"\(defrecord\s+([^\s\n]+)" content)]
    (when (or (seq protocols) (seq multimethods) (seq specs) (seq records))
      {:namespace ns-name
       :file path
       :protocols (map second protocols)
       :multimethods (map second multimethods)
       :specs (map second specs)
       :records (map second records)})))

(defn -main []
  (println "# Key Abstractions")
  (println)

  (let [files (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (filter #(re-matches #".*\.cljc?" (.getName %))))
        abstractions (->> files
                          (map extract-abstractions)
                          (remove nil?))]

    (when (seq abstractions)
      (println "## Protocols")
      (println)
      (doseq [abs (filter #(seq (:protocols %)) abstractions)]
        (println (str "### " (:namespace abs)))
        (doseq [proto (:protocols abs)]
          (println (str "- `" proto "`")))
        (println))

      (println "\n## Multimethods")
      (println)
      (doseq [abs (filter #(seq (:multimethods %)) abstractions)]
        (println (str "### " (:namespace abs)))
        (doseq [mm (:multimethods abs)]
          (println (str "- `" mm "`")))
        (println))

      (println "\n## Specs")
      (println)
      (doseq [abs (filter #(seq (:specs %)) abstractions)]
        (println (str "### " (:namespace abs)))
        (doseq [spec (take 5 (:specs abs))]
          (println (str "- `" spec "`")))
        (println))

      (println "\n## Records")
      (println)
      (doseq [abs (filter #(seq (:records %)) abstractions)]
        (println (str "### " (:namespace abs)))
        (doseq [rec (:records abs)]
          (println (str "- `" rec "`")))
        (println)))

    (when (empty? abstractions)
      (println "*No protocols, multimethods, or records found.*")
      (println)
      (println "This suggests a data-oriented architecture with minimal abstraction."))))

(-main)
