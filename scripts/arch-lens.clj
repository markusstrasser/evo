#!/usr/bin/env bb
;; Master architectural lens generator
;; Produces token-efficient, comprehensive codebase view
;; Usage: bb arch-lens [output-file]

(require '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(defn run-script [script]
  (println (str "\n==> Running " script "..."))
  (let [result (shell/sh "bb" (str "scripts/" script))]
    (if (zero? (:exit result))
      (:out result)
      (str "Error running " script ": " (:err result)))))

(defn -main [& args]
  (let [output-file (or (first args) "arch-lens-output.md")
        scripts ["arch-summary.clj"
                 "function-complexity.clj"
                 "abstractions.clj"
                 "data-flow.clj"]
        outputs (map run-script scripts)
        combined (str/join "\n\n---\n\n" outputs)
        header (str "# Evo Architecture Lens\n\n"
                    "Generated: " (java.util.Date.) "\n\n"
                    "This document provides multiple views of the Evo codebase "
                    "for architectural decision-making. "
                    "Each section offers a different lens on the system.\n\n"
                    "**Token count:** ~" (int (/ (count combined) 4)) " tokens\n\n"
                    "---\n\n")]

    (spit output-file (str header combined))
    (println)
    (println (str "✅ Architectural lens saved to: " output-file))
    (println (str "📊 Estimated tokens: ~" (int (/ (count combined) 4))))
    (println)
    (println "To use with AI models:")
    (println (str "  cat " output-file " | llmx \"Review this architecture\" --model gpt-5.1"))))

(apply -main *command-line-args*)
