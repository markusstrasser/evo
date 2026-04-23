#!/usr/bin/env clojure

(ns gen-coverage
  "Generate FR coverage matrix as Markdown table.

   Usage:
     bb fr-matrix                          # Generate FR_MATRIX.md
     clojure -M:scripts -m gen-coverage --stdout

   Output: FR_MATRIX.md with:
   - Implementation status (intent citations)
   - Verification status (executable scenarios or explicit test citations)
   - Priority levels
   - Visual status indicators (🟢 🟡 🔴)"
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [kernel.intent :as intent]
            [plugins.manifest :as plugins]
            [spec.registry :as fr]))

(defn- boot-runtime!
  []
  (fr/load-registry!)
  (plugins/init!)
  nil)

(defn- clojure-test-file?
  [file]
  (and (.isFile ^java.io.File file)
       (re-find #"\.(clj|cljc|cljs)$" (.getName ^java.io.File file))))

(defn- extract-fr-ids-from-test-file
  [file]
  (let [content (slurp file)
        matches (re-seq #":fr/ids\s+#\{([^}]+)\}" content)]
    (->> matches
         (mapcat (fn [[_ ids-str]]
                   ;; `\w` is the regex word-char class; prior version used
                   ;; `\\w` which in a regex literal is a LITERAL backslash
                   ;; and matched nothing in real keywords. That silently
                   ;; zeroed the verified-frs set for every test citation.
                   (re-seq #":[\w./+-]+" ids-str)))
         (map #(keyword (subs % 1)))
         set)))

(defn- statically-verified-frs
  []
  (let [test-files (->> (file-seq (io/file "test"))
                        (filter clojure-test-file?))]
    (->> test-files
         (mapcat extract-fr-ids-from-test-file)
         set)))

(defn- scenario-verified-frs
  []
  (->> (fr/list-frs)
       (filter fr/has-executable-scenarios?)
       set))

(defn- build-audit
  []
  (let [impl-audit (intent/audit-coverage)
        implemented-frs (set (:cited-frs impl-audit))
        verified-frs (set/union (scenario-verified-frs)
                                (statically-verified-frs))]
    (vec
     (for [fr-id (sort (fr/list-frs))]
       (let [fr-meta (fr/get-fr fr-id)
             implemented? (contains? implemented-frs fr-id)
             verified? (contains? verified-frs fr-id)
             status (cond
                      (and implemented? verified?) :complete
                      implemented? :implemented-untested
                      verified? :verified-unimplemented
                      :else :missing)]
         {:id fr-id
          :desc (:desc fr-meta)
          :priority (:priority fr-meta)
          :implemented? implemented?
          :verified? verified?
          :status status})))))

(defn- coverage-summary
  [audit]
  (let [total (count audit)
        by-status (group-by :status audit)
        complete-count (count (:complete by-status))
        implemented-count (count (filter :implemented? audit))
        verified-count (count (filter :verified? audit))
        missing-count (count (:missing by-status))]
    {:total-frs total
     :complete complete-count
     :implemented implemented-count
     :verified verified-count
     :missing missing-count
     :complete-pct (if (zero? total) 100 (int (* 100 (/ complete-count total))))
     :implementation-pct (if (zero? total) 100 (int (* 100 (/ implemented-count total))))
     :verification-pct (if (zero? total) 100 (int (* 100 (/ verified-count total))))}))

(defn status-emoji
  "Return emoji for FR status."
  [status]
  (case status
    :complete "🟢"
    :implemented-untested "🟡"
    :verified-unimplemented "🟠"
    :missing "🔴"))

(defn priority-emoji
  "Return emoji for priority level."
  [priority]
  (case priority
    :critical "🔥"
    :high "⬆️"
    :medium "➡️"
    :low "⬇️"))

(defn generate-markdown-row
  "Generate markdown table row for a single FR."
  [{:keys [id desc priority implemented? verified? status]}]
  (let [impl-check (if implemented? "✅" "❌")
        verif-check (if verified? "✅" "❌")
        status-icon (status-emoji status)
        priority-icon (priority-emoji priority)]
    (str "| " (name id)
         " | " priority-icon " " (name priority)
         " | " impl-check
         " | " verif-check
         " | " status-icon " " (name status)
         " | " desc
         " |")))

(defn generate-coverage-matrix
  "Generate full coverage matrix as markdown string."
  []
  (let [audit (build-audit)
        summary (coverage-summary audit)

        ;; Sort by priority, then status
        priority-order {:critical 0 :high 1 :medium 2 :low 3}
        status-order {:missing 0 :implemented-untested 1 :verified-unimplemented 2 :complete 3}
        sorted-audit (sort-by (juxt (comp priority-order :priority)
                                    (comp status-order :status))
                              audit)

        ;; Generate table
        header "# FR Coverage Matrix\n\n"
        summary-text (str "**Generated:** " (java.time.LocalDateTime/now) "\n\n"
                          "## Summary\n\n"
                          "- **Total FRs:** " (:total-frs summary) "\n"
                          "- **Complete:** " (:complete summary) " (" (:complete-pct summary) "%)\n"
                          "- **Implemented (not verified):** " (- (:implemented summary) (:complete summary)) "\n"
                          "- **Verified (not implemented):** " (- (:verified summary) (:complete summary)) "\n"
                          "- **Missing:** " (:missing summary) "\n\n"
                          "## Legend\n\n"
                          "**Status:**\n"
                          "- 🟢 Complete: Has both intent and test coverage\n"
                          "- 🟡 Implemented-Untested: Has intent but no test\n"
                          "- 🟠 Verified-Unimplemented: Has test but no intent (rare)\n"
                          "- 🔴 Missing: No intent or test coverage\n\n"
                          "**Priority:**\n"
                          "- 🔥 Critical\n"
                          "- ⬆️ High\n"
                          "- ➡️ Medium\n"
                          "- ⬇️ Low\n\n")
        table-header "## Coverage Matrix\n\n| FR ID | Priority | Impl | Test | Status | Description |\n|-------|----------|------|------|--------|-------------|\n"
        table-rows (map generate-markdown-row sorted-audit)]
    (str header
         summary-text
         table-header
         (str/join "\n" table-rows)
         "\n")))

(defn -main
  [& args]
  (let [markdown (generate-coverage-matrix)
        to-stdout? (some #{"--stdout"} args)]
    (if to-stdout?
      (println markdown)
      (do
        (spit "FR_MATRIX.md" markdown)
        (println "✅ Generated FR_MATRIX.md")
        (println)
        (println "Summary:")
        (let [summary (coverage-summary (build-audit))]
          (println "  Total FRs:" (:total-frs summary))
          (println "  Complete:" (:complete summary) (str "(" (:complete-pct summary) "%)"))
          (println "  Implemented:" (:implemented summary) (str "(" (:implementation-pct summary) "%)"))
          (println "  Verified:" (:verified summary) (str "(" (:verification-pct summary) "%)"))
          (println "  Missing:" (:missing summary)))))))
