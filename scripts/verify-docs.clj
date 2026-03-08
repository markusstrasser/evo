#!/usr/bin/env bb

(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.fs :as fs])

(defn parse-dx-index
  "Parse DX_INDEX.md and extract all file references"
  [dx-index-path]
  (let [content (slurp dx-index-path)
        ;; Match markdown links: [text](path)
        md-link-pattern #"\[.*?\]\((.*?)\)"
        ;; Match EDN string literals: "path"
        edn-string-pattern #"\"([^\"]+\.(?:md|clj|cljs|cljc|edn))\""

        md-matches (re-seq md-link-pattern content)
        edn-matches (re-seq edn-string-pattern content)

        md-paths (map second md-matches)
        edn-paths (map second edn-matches)

        all-paths (concat md-paths edn-paths)]

    ;; Filter to only file paths (not URLs)
    (filter #(and (not (str/starts-with? % "http"))
                  (or (str/ends-with? % ".md")
                      (str/ends-with? % ".clj")
                      (str/ends-with? % ".cljs")
                      (str/ends-with? % ".cljc")
                      (str/ends-with? % ".edn")))
            all-paths)))

(defn verify-file-exists
  "Check if a file path exists, return {:path path :exists? boolean}"
  [dx-dir path]
  (let [full-path (if (str/starts-with? path "/")
                    path
                    (str (fs/normalize (fs/path dx-dir path))))
        exists? (fs/exists? full-path)]
    {:path path
     :full-path full-path
     :exists? exists?}))

(defn main []
  (let [base-dir (System/getProperty "user.dir")
        dx-index-path (str base-dir "/docs/DX_INDEX.md")
        dx-dir (str (fs/parent dx-index-path))

        _ (when-not (fs/exists? dx-index-path)
            (println "ERROR: docs/DX_INDEX.md not found")
            (System/exit 1))

        referenced-files (parse-dx-index dx-index-path)
        verification-results (map #(verify-file-exists dx-dir %) referenced-files)
        missing-files (filter #(not (:exists? %)) verification-results)]

    (println "Verifying documentation references in docs/DX_INDEX.md...\n")
    (println (format "Total references found: %d" (count referenced-files)))

    (if (empty? missing-files)
      (do
        (println "✅ All referenced files exist!")
        (System/exit 0))
      (do
        (println (format "\n❌ %d missing files found:\n" (count missing-files)))
        (doseq [{:keys [path]} missing-files]
          (println (format "  - %s" path)))
        (println "\nPlease either:")
        (println "  1. Create the missing files, or")
        (println "  2. Remove references to them from docs/DX_INDEX.md")
        (System/exit 1)))))

(main)
