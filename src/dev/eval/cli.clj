(ns dev.eval.cli
  "Command-line interface for tournament evaluation.
   Called by the MCP server to run evaluations."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [dev.eval.core-v3 :as v3]))

(def cli-options
  [["-i" "--items PATH" "Path to items EDN file"
    :required "PATH"]
   ["-p" "--prompt PATH" "Path to evaluation prompt file"
    :required "PATH"]
   ["-j" "--judges EDN" "EDN list of judge keywords (e.g., [:gpt5-codex :gemini25-pro])"
    :default "[:gpt5-codex :gemini25-pro :grok-4]"
    :parse-fn edn/read-string]
   ["-r" "--max-rounds N" "Maximum tournament rounds"
    :default 3
    :parse-fn #(Integer/parseInt %)]
   ["-o" "--output PATH" "Output path for results EDN"
    :required "PATH"]
   ["-h" "--help" "Show help"]])

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (do
        (println "Tournament Evaluation CLI")
        (println summary)
        (System/exit 0))

      errors
      (do
        (doseq [err errors]
          (println "Error:" err))
        (System/exit 1))

      :else
      (try
        ;; Load items
        (let [items (edn/read-string (slurp (:items options)))
              prompt (slurp (:prompt options))

              ;; Build rubric from prompt
              rubric {:criteria ["Correctness"
                                 "Clarity"
                                 "Maintainability"
                                 "Performance"]
                      :context prompt}

              ;; Run evaluation
              result (v3/evaluate! items {:providers (:judges options)
                                          :max-rounds (:max-rounds options)
                                          :rubric rubric})]

          ;; Write results
          (spit (:output options) (pr-str result))
          (System/exit 0))

        (catch Exception e
          (println "Evaluation failed:" (.getMessage e))
          (.printStackTrace e)
          (System/exit 1))))))
