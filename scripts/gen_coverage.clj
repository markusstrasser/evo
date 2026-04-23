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

(defn- fr-type
  "FR :type defaults to :intent-level when not explicitly set.
   Invariants use :type :invariant; scenario-only docs use :type :scenario."
  [fr-meta]
  (get fr-meta :type :intent-level))

(defn- fr-status
  "Completion status for a single FR.

   Intent-level FRs need both an implementing intent (cited via
   :fr/ids on `register-intent!`) AND at least one test citation.

   Invariants are enforced by the code's shape (transaction pipeline,
   state machine, kernel purity checks) rather than by any single
   intent, so :implemented? is ill-defined for them. Their rubric is
   just 'is there a test that verifies the invariant holds?'."
  [fr-type impl? verif?]
  (case fr-type
    :invariant
    (if verif? :complete :missing)

    ;; :intent-level and any unknown type fall back to the 2×2 rubric
    (cond
      (and impl? verif?) :complete
      impl? :implemented-untested
      verif? :verified-unimplemented
      :else :missing)))

(defn- build-audit
  []
  (let [impl-audit (intent/audit-coverage)
        implemented-frs (set (:cited-frs impl-audit))
        verified-frs (set/union (scenario-verified-frs)
                                (statically-verified-frs))]
    (vec
     (for [fr-id (sort (fr/list-frs))]
       (let [fr-meta (fr/get-fr fr-id)
             type' (fr-type fr-meta)
             implemented? (contains? implemented-frs fr-id)
             verified? (contains? verified-frs fr-id)
             ;; For invariants, :implemented? is not applicable — the
             ;; whole system upholds them; no single handler is "the"
             ;; implementation. Rendered as "—" in the matrix table.
             impl-display (if (= :invariant type') nil implemented?)]
         {:id fr-id
          :type type'
          :desc (:desc fr-meta)
          :priority (:priority fr-meta)
          :implemented? impl-display
          :verified? verified?
          :status (fr-status type' implemented? verified?)})))))

(defn- coverage-summary
  [audit]
  (let [total (count audit)
        by-status (group-by :status audit)
        by-type (group-by :type audit)
        complete-count (count (:complete by-status))
        verified-count (count (filter :verified? audit))
        missing-count (count (:missing by-status))
        intent-level (:intent-level by-type)
        invariants (:invariant by-type)
        intent-complete (count (filter #(= :complete (:status %)) intent-level))
        invariant-complete (count (filter #(= :complete (:status %)) invariants))
        ;; "implementation coverage" is only meaningful for intent-level
        ;; FRs — invariants aren't implemented by a single handler.
        intent-implemented (count (filter :implemented? intent-level))]
    {:total-frs total
     :complete complete-count
     :verified verified-count
     :missing missing-count
     :intent-total (count intent-level)
     :intent-complete intent-complete
     :intent-implemented intent-implemented
     :invariant-total (count invariants)
     :invariant-complete invariant-complete
     :complete-pct (if (zero? total) 100 (int (* 100 (/ complete-count total))))
     :implementation-pct (if (zero? (count intent-level))
                           100
                           (int (* 100 (/ intent-implemented (count intent-level)))))
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
  "Generate markdown table row for a single FR.

   `:implemented?` renders as \"—\" for invariants (concept inapplicable)."
  [{:keys [id type desc priority implemented? verified? status]}]
  (let [impl-check (cond
                     (nil? implemented?) "—"
                     implemented?        "✅"
                     :else               "❌")
        verif-check (if verified? "✅" "❌")
        status-icon (status-emoji status)
        priority-icon (priority-emoji priority)
        type-tag (case type
                   :invariant "invariant"
                   :intent-level "intent"
                   (name (or type :intent-level)))]
    (str "| " (name id)
         " | " type-tag
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
                          "- **Missing:** " (:missing summary) "\n\n"
                          "### By type\n\n"
                          "- **Intent-level:** "
                          (:intent-complete summary) " / " (:intent-total summary)
                          " complete (needs both an implementing intent and a test citation)\n"
                          "- **Invariants:** "
                          (:invariant-complete summary) " / " (:invariant-total summary)
                          " verified (architectural properties — 'complete' ≡ verified by a test; implementation is the code's shape)\n\n"
                          "## Legend\n\n"
                          "**Type determines rubric:**\n\n"
                          "- `intent` (intent-level FR) — complete iff an intent cites it via `:fr/ids` **and** at least one test cites it.\n"
                          "- `invariant` — enforced by the transaction pipeline / state machine / kernel-purity checks, not by any single handler. Complete iff at least one test verifies it. The `Impl` column shows `—` because the concept is inapplicable.\n\n"
                          "**Status:**\n"
                          "- 🟢 Complete\n"
                          "- 🟡 Implemented-Untested: intent exists, no test citation\n"
                          "- 🟠 Verified-Unimplemented: test exists, no intent citation (intent-level FRs only; rare — usually means TDD got ahead of the handler)\n"
                          "- 🔴 Missing: nothing covers it\n\n"
                          "**Priority:**\n"
                          "- 🔥 Critical\n"
                          "- ⬆️ High\n"
                          "- ➡️ Medium\n"
                          "- ⬇️ Low\n\n")
        table-header "## Coverage Matrix\n\n| FR ID | Type | Priority | Impl | Test | Status | Description |\n|-------|------|----------|------|------|--------|-------------|\n"
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
          (println "  Missing:" (:missing summary))
          (println "  Intent-level:"
                   (:intent-complete summary) "/" (:intent-total summary) "complete")
          (println "  Invariants:"
                   (:invariant-complete summary) "/" (:invariant-total summary) "verified"))))))
