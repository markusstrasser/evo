#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def integration-root (fs/path "test" "integration"))
(def state-machine-path (fs/path "test" "kernel" "state_machine_test.cljc"))

(defn clj-test-file? [path]
  (let [name (fs/file-name path)]
    (and (fs/regular-file? path)
         (str/ends-with? name "_test.cljc"))))

(defn file-issues [path checks]
  (let [content (slurp (str path))]
    (keep (fn [{:keys [label pattern]}]
            (when-not (re-find pattern content)
              {:path (str path)
               :issue label}))
          checks)))

(defn integration-harness-issues []
  (let [checks [{:label "missing [harness.runtime-fixtures :as runtime-fixtures] require"
                 :pattern #"\[harness\.runtime-fixtures\s+:as\s+runtime-fixtures\]"}
                {:label "missing explicit (use-fixtures :once runtime-fixtures/bootstrap-runtime)"
                 :pattern #"\(use-fixtures\s+:once\s+runtime-fixtures/bootstrap-runtime\)"}]]
    (mapcat #(file-issues % checks)
            (filter clj-test-file? (file-seq (fs/file integration-root))))))

(defn kernel-harness-issues []
  (file-issues state-machine-path
               [{:label "missing [harness.intent-fixtures :as intent-fixtures] require"
                 :pattern #"\[harness\.intent-fixtures\s+:as\s+intent-fixtures\]"}
                {:label "missing explicit (use-fixtures :each intent-fixtures/with-state-machine-intents)"
                 :pattern #"\(use-fixtures\s+:each\s+intent-fixtures/with-state-machine-intents\)"}]))

(defn main []
  (println "Verifying explicit test-harness bootstrap...")
  (let [issues (concat (integration-harness-issues)
                       (kernel-harness-issues))]
    (if (seq issues)
      (do
        (println)
        (doseq [{:keys [path issue]} issues]
          (println (str path " - " issue)))
        (System/exit 1))
      (println "✓ Isolated integration/kernel tests declare explicit bootstrap fixtures"))))

(main)
