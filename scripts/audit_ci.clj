#!/usr/bin/env bb

(ns audit-ci
  "CI/CD ratchet check: Ensure critical FR coverage never drops below 100%.

   Usage:
     bb scripts/audit_ci.clj

   Exit codes:
     0 - All critical FRs covered
     1 - Critical FRs missing implementation

   This script prevents regression by failing CI if any CRITICAL FR loses
   its intent coverage. Non-critical FRs generate warnings but don't fail."
  (:require [clojure.java.io :as io]))

;; Load FR registry and intent coverage
(load-file "dev/spec_registry.cljc")
(load-file "src/kernel/intent.cljc")

(defn -main
  []
  (println "🔍 Auditing Critical FR Coverage...")
  (println)

  (require '[spec-registry :as fr])
  (require '[kernel.intent :as intent])

  (let [audit (intent/audit-coverage)
        critical-uncited (:critical-uncited audit)
        total-frs (:total-frs audit)
        implementation-pct (:implementation-pct audit)
        critical-count (count (fr/critical-frs))]

    ;; Show overall stats
    (println "📊 Coverage Statistics:")
    (println "  Total FRs:" total-frs)
    (println "  Implementation Coverage:" implementation-pct "%")
    (println "  Critical FRs:" critical-count)
    (println)

    ;; Check critical coverage (HARD ENFORCEMENT)
    (if (seq critical-uncited)
      (do
        (println "❌ FAILURE: The following CRITICAL FRs are unimplemented:")
        (println)
        (doseq [fr-id critical-uncited]
          (let [fr-meta (fr/get-fr fr-id)]
            (println "   🔥" fr-id)
            (println "      " (:desc fr-meta))
            (println "      Spec:" (:spec-ref fr-meta))))
        (println)
        (println "Critical FRs MUST have intent citations.")
        (println "Add :fr/ids to the implementing intent(s).")
        (System/exit 1))
      (do
        (println "✅ SUCCESS: All" critical-count "critical FRs covered!")
        (println)))

    ;; Warn about uncited non-critical FRs (SOFT WARNINGS)
    (let [uncited-count (count (:uncited-frs audit))]
      (when (pos? uncited-count)
        (println "⚠️  WARNING:" uncited-count "non-critical FRs uncited")
        (println "   This is acceptable during prototyping.")
        (println "   Run (intent/audit-coverage) in REPL for details.")))

    (println)
    (println "Coverage ratchet check passed!")
    (System/exit 0)))

;; Run if executed as script
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
