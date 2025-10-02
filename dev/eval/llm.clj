(ns dev.eval.llm
  "LLM API integrations for evaluation system.

   Supports:
   - Gemini (via gemini CLI)
   - Codex (via codex CLI)
   - Grok (via scripts/grok)

   Key design:
   - Shell out to existing CLI tools (reuse project's battle-tested tooling)
   - Parse JSON responses
   - Handle errors gracefully
   - Temperature 0 for deterministic scoring"
  (:require [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [medley.core :as m]))

;; =============================================================================
;; API Call Implementations

(defn call-gemini
  "Call Gemini API via CLI tool.
   Returns parsed JSON response or error."
  [prompt {:keys [model temperature]
           :or {model "gemini-2.0-flash-exp"
                temperature 0.0}}]
  (let [result (shell/sh "gemini"
                         "--model" model
                         "-y" ; non-interactive
                         "-p" prompt
                         :env (merge (into {} (System/getenv))
                                     {"GEMINI_TEMPERATURE" (str temperature)}))]
    (if (zero? (:exit result))
      {:success true
       :output (:out result)}
      {:success false
       :error (:err result)})))

(defn call-codex
  "Call Codex API via CLI tool.
   Returns parsed JSON response or error."
  [prompt {:keys [model temperature reasoning-effort]
           :or {model "gpt-5-codex"
                temperature 0.0
                reasoning-effort "high"}}]
  ;; Use codex exec for non-interactive batch mode
  (let [result (shell/sh "bash" "-c"
                         (str "echo " (pr-str prompt) " | "
                              "codex exec -m " model " "
                              "-c model_reasoning_effort=" (pr-str reasoning-effort) " "
                              "--full-auto -")
                         :env (merge (into {} (System/getenv))
                                     {"OPENAI_TEMPERATURE" (str temperature)}))]
    (if (zero? (:exit result))
      {:success true
       :output (:out result)}
      {:success false
       :error (:err result)})))

(defn call-grok
  "Call Grok API via scripts/grok.
   Returns parsed JSON response or error."
  [prompt {:keys [model temperature]
           :or {model "grok-4-latest"
                temperature 0.0}}]
  (let [result (shell/sh "bash" "-c"
                         (str "echo " (pr-str prompt) " | "
                              "scripts/grok -m " model)
                         :env (merge (into {} (System/getenv))
                                     {"XAI_TEMPERATURE" (str temperature)}))]
    (if (zero? (:exit result))
      {:success true
       :output (:out result)}
      {:success false
       :error (:err result)})))

;; =============================================================================
;; Response Parsing

(defn extract-json
  "Extract JSON object from LLM response.
   LLMs sometimes wrap JSON in markdown or add commentary."
  [response]
  (let [;; Try to find JSON in markdown code block (non-greedy multiline)
        json-match (re-find #"```(?:json)?\s*(\{[^`]+\})\s*```" response)]
    (if json-match
      (second json-match)
      ;; Try to find raw JSON (non-greedy multiline)
      (let [json-match (re-find #"\{(?:[^{}]|\{[^{}]*\})*\}" response)]
        (if json-match
          json-match
          ;; Return original if no JSON found
          response)))))

(defn parse-scores
  "Parse scores from LLM response.

   Expected formats:
   1. Simple: {\"1\": 8, \"2\": 7, \"3\": 9}
   2. Detailed: {\"1\": {\"Criterion A\": 8, \"Criterion B\": 7}, \"2\": {...}}

   For detailed format, averages all criterion scores.
   Maps numeric string keys to proposal IDs based on order.

   Returns: {proposal-id -> score}"
  [response order]
  (try
    (let [json-str (extract-json response)
          parsed (json/read-str json-str :key-fn identity)
          ;; Convert string keys \"1\", \"2\", ... to proposal IDs
          scores (m/map-kv
                  (fn [k v]
                    (let [idx (dec (Integer/parseInt k))
                          id (nth order idx)
                          ;; Handle both simple scores and nested criterion scores
                          score (if (map? v)
                                  ;; Average all criterion scores
                                  (let [criterion-vals (vals v)]
                                    (/ (reduce + criterion-vals) (count criterion-vals)))
                                  ;; Simple numeric score
                                  v)]
                      [id (double score)]))
                  parsed)]
      {:success true
       :scores scores})
    (catch Exception e
      {:success false
       :error (str "Failed to parse scores: " (.getMessage e))
       :raw-response response})))

;; =============================================================================
;; Evaluator Functions

(defn- make-provider-evaluator
  "Create evaluator function for a specific provider.
   
   Unifies the common pattern across all providers:
   - Call API with prompt and options
   - Parse response into scores
   - Throw informative errors on failure
   
   api-call-fn: function that takes (prompt, opts) and returns result map
   provider-name: string for error messages (e.g. \"Gemini\", \"Codex\")"
  [api-call-fn provider-name opts]
  (fn [prompt order]
    (let [result (api-call-fn prompt opts)]
      (if (:success result)
        (let [parsed (parse-scores (:output result) order)]
          (if (:success parsed)
            (:scores parsed)
            (throw (ex-info (str "Failed to parse " provider-name " response")
                            {:result result
                             :parsed parsed}))))
        (throw (ex-info (str provider-name " API call failed")
                        {:error (:error result)}))))))

(defn make-gemini-evaluator
  "Create evaluator function that uses Gemini API."
  ([]
   (make-gemini-evaluator {}))
  ([opts]
   (make-provider-evaluator call-gemini "Gemini" opts)))

(defn make-codex-evaluator
  "Create evaluator function that uses Codex API."
  ([]
   (make-codex-evaluator {}))
  ([opts]
   (make-provider-evaluator call-codex "Codex" opts)))

(defn make-grok-evaluator
  "Create evaluator function that uses Grok API."
  ([]
   (make-grok-evaluator {}))
  ([opts]
   (make-provider-evaluator call-grok "Grok" opts)))

;; =============================================================================
;; Unified API

(defn make-evaluator
  "Create evaluator function for specified provider.

   provider: :gemini | :codex | :grok
   opts: provider-specific options (model, temperature, etc.)

   Returns function that takes (prompt, order) and returns {id -> score}."
  [provider & [opts]]
  (case provider
    :gemini (make-gemini-evaluator opts)
    :codex (make-codex-evaluator opts)
    :grok (make-grok-evaluator opts)
    (throw (ex-info "Unknown provider" {:provider provider}))))

(comment
  ;; REPL testing
  (def test-prompt
    "Evaluate these proposals on a scale of 1-10.
     Return JSON like {\"1\": score, \"2\": score}.

     1. Simple design
     2. Complex design
     3. Moderate design")

  ;; Test with mock order
  (def order [:a :b :c])

  ;; Test Gemini (requires API key)
  (def gemini-eval (make-evaluator :gemini))
  (gemini-eval test-prompt order)
  ;; => {:a 7.0 :b 5.0 :c 8.0}

  ;; Test parsing
  (parse-scores "{\"1\": 8, \"2\": 7, \"3\": 9}" [:a :b :c])
  ;; => {:success true :scores {:a 8.0 :b 7.0 :c 9.0}}

  (parse-scores "```json\n{\"1\": 8, \"2\": 7}\n```" [:a :b])
  ;; => {:success true :scores {:a 8.0 :b 7.0}}
  )
