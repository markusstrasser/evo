#!/usr/bin/env bb

;; Self-test for bb lint:kernel-imports.
;;
;; Cleanup contract: System/exit is called exactly once, after the fixture
;; has been restored. Calling System/exit inside the try block would skip
;; the finally — the JVM does not run finally for in-flight stack frames
;; on Runtime.exit, only shutdown hooks. A failed selftest would otherwise
;; leave the injected fixture in src/kernel/ and break every later run.

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
    {:exit (:exit res) :out (:out res) :err (:err res)}))

(defn -main []
  (println "[selftest] Confirming baseline lint passes before injecting fixture…")
  (let [{:keys [exit out]} (run-lint)]
    (when-not (zero? exit)
      (println "[selftest] ✗ baseline lint already fails — fix that first.")
      (println out)
      (System/exit 1)))

  (println "[selftest] Writing forbidden-require fixture:" fixture-path)
  (spit (io/file fixture-path) fixture-body)
  (let [failure (try
                  (let [{:keys [exit out]} (run-lint)]
                    (cond
                      (zero? exit)
                      {:code 2
                       :msg "[selftest] ✗ lint did NOT catch the forbidden require — gate is broken."
                       :detail out}

                      (not (re-find #"forbidden dependency" out))
                      {:code 3
                       :msg "[selftest] ✗ lint exited non-zero but output did not mention the forbidden dependency."
                       :detail out}

                      :else nil))
                  (finally
                    (io/delete-file (io/file fixture-path) :silently)))]
    (if failure
      (do (println (:msg failure))
          (println (:detail failure))
          (System/exit (:code failure)))
      (println "[selftest] ✓ lint:kernel-imports catches the injected fixture"))))

(-main)
