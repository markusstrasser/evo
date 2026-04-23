#!/usr/bin/env clojure

(ns audit-ci
  "CI/CD ratchet check: Ensure critical FR coverage never drops below 100%.

   Usage:
     bb fr-audit
     clojure -M:scripts -m audit-ci

   Exit codes:
     0 - All critical FRs covered
     1 - Critical FRs missing implementation

   This script prevents regression by failing CI if any CRITICAL FR loses
   its intent coverage. Non-critical FRs generate warnings but don't fail."
  (:require [kernel.intent :as intent]
            [plugins.manifest :as plugins]
            [spec.registry :as fr]))

(defn- boot-runtime!
  []
  (fr/load-registry!)
  (plugins/init!)
  nil)

(defn- invariant? [fr-id]
  ;; Invariants are enforced by the architecture (state machine, transaction
  ;; pipeline, kernel purity checks) rather than a single intent, so they
  ;; cannot be satisfied by adding :fr/ids to a handler. Audit them
  ;; elsewhere (arch:verify, kernel purity scans, failure_modes.edn).
  (= :invariant (:type (fr/get-fr fr-id))))

(defn -main
  []
  (boot-runtime!)
  (println "🔍 Auditing Critical FR Coverage...")
  (println)

  (let [audit (intent/audit-coverage)
        ;; Separate intent-level misses from invariant misses. Only
        ;; intent-level misses are audit failures; invariants with no
        ;; intent citation is normal.
        critical-uncited (:critical-uncited audit)
        critical-intent-uncited (remove invariant? critical-uncited)
        critical-invariant-uncited (filter invariant? critical-uncited)
        total-frs (:total-frs audit)
        implementation-pct (:implementation-pct audit)
        critical-count (count (fr/critical-frs))]

    ;; Show overall stats
    (println "📊 Coverage Statistics:")
    (println "  Total FRs:" total-frs)
    (println "  Implementation Coverage:" implementation-pct "%")
    (println "  Critical FRs:" critical-count)
    (println)

    ;; Note invariants without intent citation — informational only.
    (when (seq critical-invariant-uncited)
      (println "ℹ️  Critical invariants (enforced architecturally, no intent citation required):")
      (doseq [fr-id critical-invariant-uncited]
        (println "   •" fr-id "—" (:desc (fr/get-fr fr-id))))
      (println))

    ;; Check critical intent-level coverage (HARD ENFORCEMENT)
    (if (seq critical-intent-uncited)
      (do
        (println "❌ FAILURE: The following CRITICAL intent-level FRs are unimplemented:")
        (println)
        (doseq [fr-id critical-intent-uncited]
          (let [fr-meta (fr/get-fr fr-id)]
            (println "   🔥" fr-id)
            (println "      " (:desc fr-meta))
            (println "      Spec:" (:spec-ref fr-meta))))
        (println)
        (println "Critical intent-level FRs MUST have intent citations.")
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
