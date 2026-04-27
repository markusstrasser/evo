#!/usr/bin/env bb

(ns failure-modes-guardrails
  "Map failure-mode taxonomy entries to concrete guardrails.

   This deliberately avoids prose parsing. A mode must have an explicit row or
   it is reported as unmapped so stale taxonomy cannot masquerade as coverage."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def guardrails
  {:cursor-reset {:kind :e2e
                  :surface "test/e2e/editing.spec.js and test/e2e/navigate-then-type.spec.js assert typed text and cursor continuity"}
   :focus-not-attached {:kind :e2e
                        :surface "test/e2e/navigate-then-type.spec.js covers immediate typing after navigation/create"}
   :stale-closure {:kind :manual-smoke
                   :surface "DB-vs-DOM agreement remains browser-level; no low-noise static lint yet"}
   :duplicate-event-dispatch {:kind :test
                              :surface "test/keymap/ownership_test.cljc plus operation-count E2E coverage for Enter/Backspace"}
   :empty-block-special-case {:kind :test
                              :surface "editing/context tests include empty-block paths"}
   :declarative-anti-pattern {:kind :manual-smoke
                              :surface "contenteditable lifecycle review; candidate lint deferred until low false positive"}
   :cljs-silent-arity-mismatch {:kind :lint
                                :surface "docs/CODING_GOTCHAS.md documents the trap; no dedicated arity lint yet"}
   :dispatch-log-memory-leak {:kind :manual
                              :surface "Devtools/log-size inspection; no current automated guardrail"}
   :duplicate-data-attributes {:kind :lint-candidate
                               :surface "Low-risk DOM lint candidate for duplicate nested data-block-id"}
   :keyboard-event-not-triggering {:kind :lint
                                   :surface "npm run lint:e2e-keyboard rejects known broken Playwright keyboard forms"}
   :paste-depth-gaps {:kind :test
                      :surface "HTML/smart paste E2E and clipboard scenario tests cover representative depth cases"}
   :replicant-lifecycle-inversion {:kind :manual
                                   :surface "Review lifecycle hooks in components.block when editing render behavior changes"}
   :replicant-react-fragment {:kind :lint-candidate
                              :surface "Replicant fragment/vector shape lint candidate; keep manual until pattern is precise"}
   :replicant-vector-vs-function {:kind :lint-candidate
                                  :surface "Replicant vector/function shape lint candidate; keep manual until pattern is precise"}
   :session-db-timing {:kind :test
                       :surface "shell.dispatch-bridge tests plus E2E focus/cursor smoke cover DB/session ordering"}})

(defn -main [& _]
  (let [data (edn/read-string (slurp (io/file "resources/failure_modes.edn")))
        modes (-> data :modes keys sort)
        unmapped (remove guardrails modes)]
    (println "| Failure mode | Guardrail kind | Surface |")
    (println "| --- | --- | --- |")
    (doseq [mode modes
            :let [{:keys [kind surface]} (get guardrails mode {:kind :unmapped
                                                               :surface "No concrete guardrail mapped"})]]
      (println "|" mode "|" kind "|" surface "|"))
    (when (seq unmapped)
      (println)
      (println "Unmapped failure modes:")
      (doseq [mode unmapped]
        (println "-" mode)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
