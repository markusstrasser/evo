(ns dev.eval.report
  "Report formatting and output for v3 evaluator results."
  (:require [clojure.string :as str]))

(defn format-ranking
  "Format ranking as numbered list with scores and CIs."
  [ranking ci]
  (str/join "\n"
            (map-indexed
             (fn [idx [id score]]
               (let [[lower upper] (get ci id [score score])]
                 (format "%d. %s (θ=%.3f, CI=[%.3f, %.3f])"
                         (inc idx) (name id) score lower upper)))
             ranking)))

(defn format-status
  "Format readiness status with color/indicator."
  [status]
  (case status
    :OK "✓ OK (ready for publication)"
    :UNSTABLE "⚠ UNSTABLE (low consensus or high brittleness)"
    :INVALID "✗ INVALID (schema adherence failed)"
    "UNKNOWN"))

(defn print!
  "Print formatted evaluation report to stdout."
  [result]
  (println "\n=== V3 Evaluation Report ===\n")
  (println "Status:" (format-status (:status result)))
  (println)

  (println "Rankings:")
  (println (format-ranking (:ranking result) (:ci result)))
  (println)

  (println "Quality Metrics:")
  (println "  Kendall-τ (split-half):" (format "%.3f" (:tau-split result)))
  (println "  Schema R² (adherence):" (format "%.3f" (:schema-r2 result)))
  (println "  Brittleness (Drop-K):" (format "k=%d (%.1f%%)"
                                             (get-in result [:brittleness :k])
                                             (* 100 (get-in result [:brittleness :pct]))))
  (println "  Dispersion (Mallows β):" (format "%.2f" (get-in result [:dispersion :beta])))
  (println "  Mean Kendall-τ:" (format "%.3f" (get-in result [:dispersion :mean-tau])))
  (println)

  (println "Bias Estimates:")
  (println "  Position (left):" (format "%.3f" (get-in result [:bias-beta :position :left])))
  (println "  Position (right):" (format "%.3f" (get-in result [:bias-beta :position :right])))
  (println "  Recency (NEW):" (format "%.3f" (get-in result [:bias-beta :recency :NEW])))
  (println)

  (println "Attack Success Rates:")
  (println "  Recency:" (format "%.1f%%" (* 100 (get-in result [:asr :recency]))))
  (println "  Provenance:" (format "%.1f%%" (* 100 (get-in result [:asr :provenance]))))
  (println "  Verbosity:" (format "%.1f%%" (* 100 (get-in result [:asr :verbosity]))))
  (println)

  (when (not= :OK (:status result))
    (println "⚠ WARNING: Results not ready for publication. Review quality gates."))

  result)

(defn export-edn
  "Export result to EDN file."
  [result filepath]
  #?(:clj
     (spit filepath (pr-str result))
     :cljs
     (throw (ex-info "EDN export not supported in CLJS" {})))
  filepath)
