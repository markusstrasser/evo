(ns scripts.research.export-proposals-index
  (:require [scripts.utils.files :as files]
            [scripts.utils.json :as json]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn project-root []
  (let [script-dir (str (fs/parent (fs/real-path *file*)))]
    (str (fs/parent script-dir))))

(defn latest-proposals-dir []
  (let [proposals-dirs (sort
                        (comp - compare)
                        (files/list-files (str (project-root) "/docs/research/proposals") {:glob "*"}))]
    (first (filter #(fs/directory? %) proposals-dirs))))

(defn parse-ranking-file [ranking-file]
  (let [content (files/read-file ranking-file)
        lines (str/split-lines content)]
    (loop [remaining lines
           current-tier nil
           proposals {}]
      (if (empty? remaining)
        proposals
        (let [line (first remaining)
              rest-lines (rest remaining)]
          (cond
            ;; Capture tier (### Tier A (7-8): Strong)
            (re-matches #"^###\s*Tier\s+([A-Z])+.*" line)
            (let [[_ tier] (re-matches #"^###\s*Tier\s+([A-Z])+.*" line)]
              (recur rest-lines tier proposals))

            ;; Capture proposal header (**Proposal 1: gemini-kernel**)
            (re-matches #"^\*\*Proposal\s+\d+:\s+([^-]+)-([^\s\*]+).*" line)
            (let [[_ provider question] (re-matches #"^\*\*Proposal\s+\d+:\s+([^-]+)-([^\s\*]+).*" line)]
              ;; Parse next lines for metadata
              (loop [meta-lines rest-lines
                     score nil
                     focus nil
                     insight nil]
                (if (or (empty? meta-lines)
                        (str/starts-with? (first meta-lines) "**"))
                  (let [key (str provider "-" question)
                        existing (get proposals key)
                        new-score (parse-long (or score "0"))]
                    (if (or (nil? existing)
                            (> new-score (:score existing 0)))
                      (recur (drop (- (count rest-lines) (count meta-lines)) rest-lines)
                             current-tier
                             (assoc proposals key {:provider provider
                                                   :question question
                                                   :score new-score
                                                   :tier (or current-tier "N/A")
                                                   :insight (or insight "")}))
                      (recur (drop (- (count rest-lines) (count meta-lines)) rest-lines)
                             current-tier
                             proposals)))
                  (let [meta-line (first meta-lines)
                        next-score (when (re-matches #"^-\s*Score:\s*(\d+)/10.*" meta-line)
                                     (second (re-matches #"^-\s*Score:\s*(\d+)/10.*" meta-line)))
                        next-focus (when (re-matches #"^-\s*Focus\s+Area:\s*(\S+).*" meta-line)
                                     (second (re-matches #"^-\s*Focus\s+Area:\s*(\S+).*" meta-line)))
                        next-insight (when (re-matches #"^-\s*Core\s+Insight:\s*(.*)" meta-line)
                                       (second (re-matches #"^-\s*Core\s+Insight:\s*(.*)" meta-line)))]
                    (recur (rest meta-lines)
                           (or next-score score)
                           (or next-focus focus)
                           (or next-insight insight))))))

            :else
            (recur rest-lines current-tier proposals)))))))

(defn collect-rankings [proposals-dir]
  (let [rankings-dir (str proposals-dir "/rankings")
        ranking-files (files/list-files rankings-dir {:glob "*.md"})]

    (reduce
     (fn [acc ranking-file]
       (merge-with
        (fn [old new]
          (if (> (:score new 0) (:score old 0))
            new
            old))
        acc
        (parse-ranking-file ranking-file)))
     {}
     ranking-files)))

(defn process-proposals [proposals-dir rankings]
  (let [proposals-files (files/list-files (str proposals-dir "/proposals") {:glob "*.md"})]
    (for [proposal-file proposals-files]
      (let [filename (fs/file-name proposal-file)
            base-name (str/replace filename #"\.md$" "")
            [provider question] (str/split base-name #"-" 2)
            key (str provider "-" question)
            ranking (get rankings key {})

            ;; Get file stats
            size-bytes (files/file-size proposal-file)
            size-kb (quot size-bytes 1024)

            ;; Extract title from file
            content (files/read-file proposal-file {:limit 50})
            title-match (re-find #"(?m)^#{1,2}\s+(.{1,80})" content)
            title (if title-match
                    (str/replace (second title-match) #"\*\*" "")
                    "No title")

            ;; File link
            abs-path (str (fs/real-path proposal-file))
            file-link (str "file://" abs-path)]

        {:score (get ranking :score "N/A")
         :tier (get ranking :tier "N/A")
         :provider (or provider "unknown")
         :question (or question base-name)
         :title title
         :insight (subs (get ranking :insight "") 0 (min 200 (count (get ranking :insight ""))))
         :file-link file-link
         :size-kb size-kb}))))

(defn write-tsv [rows output-file]
  (let [sep "\t"
        header (str "Score" sep "Tier" sep "Provider" sep "Question" sep "Title" sep "Core_Insight" sep "File_Link" sep "Size_KB\n")
        lines (map (fn [row]
                     (str/join sep
                               [(str (:score row))
                                (:tier row)
                                (:provider row)
                                (:question row)
                                (str/replace (:title row) #"\t" " ")
                                (str/replace (:insight row) #"\t" " ")
                                (:file-link row)
                                (str (:size-kb row))]))
                   rows)
        sorted-lines (sort-by
                      (fn [line]
                        (let [score-str (first (str/split line #"\t"))]
                          (- (if (= score-str "N/A")
                               -1
                               (parse-long score-str)))))
                      lines)]
    (files/write-file output-file
                      (str header (str/join "\n" sorted-lines) "\n"))))

(defn write-csv [rows output-file]
  (let [sep ","
        header (str "Score" sep "Tier" sep "Provider" sep "Question" sep "Title" sep "Core_Insight" sep "File_Link" sep "Size_KB\n")
        lines (map (fn [row]
                     (str/join sep
                               [(str (:score row))
                                (:tier row)
                                (:provider row)
                                (:question row)
                                (str "\"" (str/replace (:title row) #"\"" "\"\"") "\"")
                                (str "\"" (str/replace (:insight row) #"\"" "\"\"") "\"")
                                (:file-link row)
                                (str (:size-kb row))]))
                   rows)
        sorted-lines (sort-by
                      (fn [line]
                        (let [score-str (first (str/split line #","))]
                          (- (if (= score-str "N/A")
                               -1
                               (parse-long score-str)))))
                      lines)]
    (files/write-file output-file
                      (str header (str/join "\n" sorted-lines) "\n"))))

(defn -main [& [proposals-dir format]]
  (let [dir (or proposals-dir (latest-proposals-dir))
        fmt (or format "tsv")]

    (when (or (nil? dir) (not (files/exists? dir)))
      (println "Error: No proposals directory found")
      (System/exit 1))

    (let [output-file (str dir "/proposals-index." fmt)]

      (println (str "Exporting proposals from: " dir))
      (println (str "Output: " output-file))
      (println)

      ;; Collect rankings
      (let [rankings (collect-rankings dir)
            proposals (process-proposals dir rankings)]

        ;; Write output
        (case fmt
          "csv" (write-csv proposals output-file)
          "tsv" (write-tsv proposals output-file)
          (do
            (println (str "Unknown format: " fmt))
            (System/exit 1)))

        (println (str "✓ Exported " (count proposals) " proposals to:"))
        (println (str "  " output-file))
        (println)
        (println "View with:")
        (println (str "  open '" output-file "'  # Default app"))
        (println "  Numbers, Excel, or any TSV/CSV viewer")))))
