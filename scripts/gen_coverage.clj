#!/usr/bin/env bb

(ns gen-coverage
  "Generate FR coverage matrix as Markdown table.

   Usage:
     bb scripts/gen_coverage.clj           # Generate FR_MATRIX.md
     bb scripts/gen_coverage.clj --stdout  # Print to stdout

   Output: FR_MATRIX.md with:
   - Implementation status (intent citations)
   - Verification status (test citations)
   - Priority levels
   - Visual status indicators (🟢 🟡 🔴)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; Load FR registry and intent coverage
(load-file "dev/spec_registry.cljc")
(load-file "src/kernel/intent.cljc")
(load-file "dev/test_scanner.cljc")

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
  (let [;; Require namespaces
        _ (require '[spec-registry :as fr])
        _ (require '[kernel.intent :as intent])

        ;; Get full audit
        audit (intent/full-audit)
        summary (intent/coverage-summary)

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
        (let [summary (do
                        (require '[kernel.intent :as intent])
                        (intent/coverage-summary))]
          (println "  Total FRs:" (:total-frs summary))
          (println "  Complete:" (:complete summary) (str "(" (:complete-pct summary) "%)"))
          (println "  Implemented:" (:implemented summary) (str "(" (:implementation-pct summary) "%)"))
          (println "  Verified:" (:verified summary) (str "(" (:verification-pct summary) "%)"))
          (println "  Missing:" (:missing summary)))))))

;; Run if executed as script
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
