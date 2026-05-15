#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[clojure.java.io :as io])

(def fixture-path "src/kernel/_selftest_forbidden_import.cljc")

(def fixture-body
  "(ns kernel._selftest-forbidden-import
  ;; Selftest fixture for bb lint:kernel-imports — should be rejected.
  (:require [shell.executor :as executor]))

(defn touch [] executor/apply-intent!)
")

(defn run-lint []
  (let [res (p/sh "bb" "scripts/verify_kernel_boundaries.clj")]
    {:exit (:exit res)
     :out (:out res)
     :err (:err res)}))

(defn -main []
  (println "[selftest] Confirming baseline lint passes before injecting fixture…")
  (let [{:keys [exit out]} (run-lint)]
    (when-not (zero? exit)
      (println "[selftest] ✗ baseline lint already fails — fix that first.")
      (println out)
      (System/exit 1)))

  (println "[selftest] Writing forbidden-require fixture:" fixture-path)
  (spit (io/file fixture-path) fixture-body)
  (try
    (let [{:keys [exit out]} (run-lint)]
      (when (zero? exit)
        (println "[selftest] ✗ lint did NOT catch the forbidden require — gate is broken.")
        (println out)
        (System/exit 2))
      (when-not (re-find #"forbidden dependency" out)
        (println "[selftest] ✗ lint exited non-zero but output did not mention the forbidden dependency.")
        (println out)
        (System/exit 3)))
    (println "[selftest] ✓ lint:kernel-imports catches the injected fixture")
    (finally
      (io/delete-file (io/file fixture-path) :silently))))

(-main)
