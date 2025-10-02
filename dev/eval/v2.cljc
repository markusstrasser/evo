(ns dev.eval.v2
  "Enhanced evaluation system with XML prompts, smart truncation, and context injection.

   Improvements over eval.core:
   - XML-structured prompts (2025 best practices)
   - Smart truncation via LLM summarization
   - Project context injection (AUTO-SOURCE-OVERVIEW.md)
   - Enhanced criteria with descriptions
   - Model-specific prompt hints"
  (:require [dev.eval.core :as core]
            #?(:clj [dev.eval.prompt :as prompt])
            #?(:clj [dev.eval.smart-truncate :as smart])
            #?(:clj [dev.eval.llm :as llm])))

;; =============================================================================
;; Enhanced Configuration

(def enhanced-config
  "Enhanced default configuration with XML prompts and smart truncation."
  (merge core/default-config
         {:max-length 800                    ; higher limit (we'll summarize)
          :use-smart-truncation? true        ; use LLM summarization
          :use-xml-prompts? true             ; use XML-structured prompts
          :include-context? true             ; inject project context
          :criteria #?(:clj prompt/enhanced-criteria
                       :cljs core/default-config)
          :context-source :auto-source}))    ; :auto-source | :auto-project | :custom

;; =============================================================================
;; Context Loading

#?(:clj
   (defn load-context
     "Load project context based on source type."
     [source]
     (case source
       :auto-source (prompt/load-source-overview)
       :auto-project (prompt/load-project-overview)
       :custom source  ; assume it's already a string
       nil)))

;; =============================================================================
;; Enhanced Normalization

#?(:clj
   (defn normalize-proposals-v2
     "Normalize proposals using smart truncation or simple truncation.

      Returns:
      {:proposals {id -> normalized-text}
       :metadata {id -> {:truncated? :method :original-length}}}"
     [proposals {:keys [max-length use-smart-truncation?]}]
     (if use-smart-truncation?
       (smart/smart-normalize-proposals proposals max-length)
       ;; Fallback to simple normalization
       {:proposals (core/normalize-proposals proposals max-length)
        :metadata {}})))

;; =============================================================================
;; Enhanced Prompt Generation

#?(:clj
   (defn make-enhanced-prompt
     "Generate enhanced evaluation prompt with XML structure and context."
     [proposals order {:keys [use-xml-prompts? include-context? context-source
                              criteria model-hint]}]
     (if use-xml-prompts?
       ;; XML-structured prompt
       (prompt/make-xml-evaluation-prompt
        proposals
        order
        {:criteria criteria
         :context (when include-context? (load-context context-source))
         :model-hint model-hint})
       ;; Fallback to original prompt format
       (core/make-evaluation-prompt proposals order criteria))))

;; =============================================================================
;; Evaluation Round (Enhanced)

#?(:clj
   (defn evaluate-round-v2
     "Enhanced evaluation round with XML prompts and context."
     [proposals order config evaluator-fn]
     (let [prompt (make-enhanced-prompt proposals order config)]
       (evaluator-fn prompt order))))

;; =============================================================================
;; Main Evaluation Pipeline (Enhanced)

#?(:clj
   (defn evaluate-proposals-v2
     "Enhanced evaluation pipeline with smart truncation and XML prompts."
     [proposals {:keys [rounds position-weights] :as config} evaluator-fn]
     (let [;; 1. Smart normalize proposals
           {:keys [proposals metadata]} (normalize-proposals-v2 proposals config)
           ids (vec (keys proposals))

           ;; 2. Run multiple evaluation rounds
           rounds-results
           (doall
            (for [_ (range rounds)]
              (let [order (shuffle ids)
                    raw-scores (evaluate-round-v2 proposals order config evaluator-fn)
                    calibrated (core/calibrate-scores raw-scores order position-weights)]
                calibrated)))

           ;; 3. Aggregate scores
           aggregated (core/aggregate-scores rounds-results)

           ;; 4. Rank proposals
           ranking (core/rank-proposals aggregated)]

       {:ranking ranking
        :details aggregated
        :rounds-data rounds-results
        :metadata metadata})))  ; include truncation metadata

;; =============================================================================
;; Public API

#?(:clj
   (defn evaluate-v2
     "Enhanced evaluate function with all v2 improvements.

      Usage:
      (evaluate-v2 proposals)                      ; use all defaults
      (evaluate-v2 proposals {:rounds 5})          ; override config
      (evaluate-v2 proposals {:context \"...\"})   ; custom context
      (evaluate-v2 proposals config evaluator-fn)  ; custom evaluator"
     ([proposals]
      (evaluate-v2 proposals {} nil))
     ([proposals config-overrides]
      (evaluate-v2 proposals config-overrides nil))
     ([proposals config-overrides evaluator-fn]
      (let [config (merge enhanced-config config-overrides)
            ;; Use default Gemini if no evaluator provided
            evaluator (or evaluator-fn
                         #?(:clj (llm/make-evaluator :gemini)
                            :cljs nil))]
        (if evaluator
          (evaluate-proposals-v2 proposals config evaluator)
          (throw (ex-info "No evaluator provided" {})))))))

(comment
  ;; REPL testing

  (def test-proposals
    {:simple "Simple REST API with GET /users, POST /users."

     :complex (apply str (repeat 50 "Complex enterprise-grade API with extensive features. "))

     :moderate "Standard REST API with CRUD operations, JWT auth, pagination, and docs."})

  ;; V2 evaluation with all enhancements
  #?(:clj
     (do
       ;; With smart truncation and XML prompts
       (def result
         (evaluate-v2 test-proposals
                      {:rounds 3
                       :use-smart-truncation? true
                       :use-xml-prompts? true
                       :include-context? true
                       :context-source :auto-source}
                      (llm/make-evaluator :gemini)))

       ;; Check metadata to see if smart truncation was used
       (:metadata result)
       ;; => {:complex {:truncated? true :method :summary :original-length 2500}}

       (:ranking result)
       ;; => [:moderate :simple :complex]
       ))
  )
