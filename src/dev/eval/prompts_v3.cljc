(ns dev.eval.prompts-v3
  "Strict JSON schema prompts for pairwise and pointwise evaluation.
   Enforces schema compliance with retry logic."
  (:require [clojure.string :as str]))

(def pairwise-schema
  "JSON schema for pairwise comparison output."
  {:type "object"
   :properties {:criteria {:type "object"
                           :additionalProperties {:type "number"}}
                :verdict {:type "string"
                          :enum ["left" "right"]}
                :confidence {:type "number"
                             :minimum 0
                             :maximum 1}}
   :required ["criteria" "verdict"]})

(def pointwise-schema
  "JSON schema for pointwise scoring output."
  {:type "object"
   :properties {:criteria {:type "object"
                           :additionalProperties {:type "number"}}
                :score {:type "number"
                        :minimum 0
                        :maximum 10}}
   :required ["criteria" "score"]})

(defn- format-rubric
  "Format rubric criteria for prompt."
  [criteria]
  (str/join "\n" (map #(str "- " %) criteria)))

(defn pairwise-prompt
  "Generate pairwise comparison prompt with strict JSON schema.
   Supports vignette flags for bias detection."
  [{:keys [left right rubric context flags]}]
  (let [left-tag (:left-tag flags "")
        right-tag (:right-tag flags "")
        left-text (str left-tag (when (seq left-tag) " ") (:text left))
        right-text (str right-tag (when (seq right-tag) " ") (:text right))
        criteria-list (format-rubric (:criteria rubric))]
    (str "Compare these two items and determine which is better.\n\n"
         "Context: " context "\n\n"
         "LEFT ITEM:\n" left-text "\n\n"
         "RIGHT ITEM:\n" right-text "\n\n"
         "Evaluation Criteria:\n" criteria-list "\n\n"
         "You must respond with ONLY valid JSON matching this exact schema:\n"
         "{\n"
         "  \"criteria\": {\"Criterion1\": <score>, \"Criterion2\": <score>, ...},\n"
         "  \"verdict\": \"left\" or \"right\",\n"
         "  \"confidence\": <0.0 to 1.0>\n"
         "}\n\n"
         "Rules:\n"
         "- criteria: numeric scores for each evaluation criterion\n"
         "- verdict: must be exactly \"left\" or \"right\"\n"
         "- confidence: optional, between 0.0 and 1.0\n"
         "- Output ONLY the JSON object, no additional text")))

(defn pointwise-prompt
  "Generate pointwise scoring prompt with strict JSON schema."
  [{:keys [item rubric context]}]
  (let [criteria-list (format-rubric (:criteria rubric))]
    (str "Evaluate this item and assign a quality score.\n\n"
         "Context: " context "\n\n"
         "ITEM:\n" (:text item) "\n\n"
         "Evaluation Criteria:\n" criteria-list "\n\n"
         "You must respond with ONLY valid JSON matching this exact schema:\n"
         "{\n"
         "  \"criteria\": {\"Criterion1\": <score>, \"Criterion2\": <score>, ...},\n"
         "  \"score\": <0 to 10>\n"
         "}\n\n"
         "Rules:\n"
         "- criteria: numeric scores for each evaluation criterion\n"
         "- score: overall score between 0 and 10\n"
         "- Output ONLY the JSON object, no additional text")))

(defn validate-pairwise
  "Validate pairwise judge output against schema.
   Returns [valid? errors]."
  [output]
  (let [errors []]
    (cond
      (not (map? output))
      [false ["Output must be a map"]]

      (not (contains? output :verdict))
      [false ["Missing required field: verdict"]]

      (not (contains? output :criteria))
      [false ["Missing required field: criteria"]]

      (not (#{:left :right "left" "right"} (:verdict output)))
      [false ["verdict must be 'left' or 'right'"]]

      (not (map? (:criteria output)))
      [false ["criteria must be a map"]]

      (and (contains? output :confidence)
           (or (not (number? (:confidence output)))
               (< (:confidence output) 0)
               (> (:confidence output) 1)))
      [false ["confidence must be a number between 0 and 1"]]

      :else
      [true []])))

(defn validate-pointwise
  "Validate pointwise judge output against schema.
   Returns [valid? errors]."
  [output]
  (cond
    (not (map? output))
    [false ["Output must be a map"]]

    (not (contains? output :score))
    [false ["Missing required field: score"]]

    (not (contains? output :criteria))
    [false ["Missing required field: criteria"]]

    (not (number? (:score output)))
    [false ["score must be a number"]]

    (or (< (:score output) 0) (> (:score output) 10))
    [false ["score must be between 0 and 10"]]

    (not (map? (:criteria output)))
    [false ["criteria must be a map"]]

    :else
    [true []]))

(defn validate-or-retry
  "Call judge with automatic retry on schema validation failure.
   Returns valid output or throws after max-retries."
  [provider prompt call-fn validator max-retries]
  (loop [attempt 0]
    (if (>= attempt max-retries)
      (throw (ex-info "Max retries exceeded for valid JSON response"
                      {:provider provider :attempts max-retries}))
      (let [output (call-fn provider prompt)
            [valid? errors] (validator output)]
        (if valid?
          output
          (do
            (println (str "Attempt " (inc attempt) " failed validation: " (str/join ", " errors)))
            (recur (inc attempt))))))))
