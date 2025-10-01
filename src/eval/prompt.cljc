(ns eval.prompt
  "XML-structured prompts for LLM evaluation following 2025 best practices.

   Key principles:
   - XML tags for clear section boundaries
   - Explicit reasoning scaffolding
   - Concrete, measurable criteria
   - Context injection support
   - Model-specific optimizations"
  (:require [clojure.string :as str]))

;; =============================================================================
;; XML-Structured Prompt Templates

(defn format-criteria-xml
  "Format criteria as XML with clear structure."
  [criteria]
  (str "<evaluation_criteria>\n"
       (str/join "\n"
                 (map (fn [{criterion-name :name :keys [weight description]}]
                        (str "  <criterion weight=\"" weight "\">\n"
                             "    <name>" criterion-name "</name>\n"
                             (when description
                               (str "    <description>" description "</description>\n"))
                             "  </criterion>"))
                      criteria))
       "\n</evaluation_criteria>"))

(defn format-proposals-xml
  "Format proposals as XML with clear IDs."
  [proposals order]
  (str "<proposals>\n"
       (str/join "\n"
                 (map-indexed
                  (fn [idx id]
                    (str "  <proposal id=\"" (inc idx) "\" internal_id=\"" (name id) "\">\n"
                         "    " (str/replace (get proposals id) #"\n" "\n    ") "\n"
                         "  </proposal>"))
                  order))
       "\n</proposals>"))

(defn format-context-xml
  "Format project context (e.g., AUTO-SOURCE-OVERVIEW.md) as XML."
  [context]
  (when context
    (str "<project_context>\n"
         (str/replace context #"\n" "\n  ")
         "\n</project_context>\n\n")))

(defn make-xml-evaluation-prompt
  "Generate XML-structured evaluation prompt following 2025 best practices.

   Based on research findings:
   - XML tags create clear boundaries (prevent context contamination)
   - Explicit reasoning steps improve calibration
   - Structured output easier to parse
   - Context injection for domain-specific evaluation"
  [proposals order {:keys [criteria context model-hint]}]
  (str/trim
   (str
    ;; System context
    (when context (format-context-xml context))

    ;; Role and task definition
    "
<task>
You are an expert software architecture evaluator.

Your task: Evaluate the architectural proposals below using the specified criteria.
Score each proposal independently on a scale of 1-10 (10 = best).
</task>

"
    ;; Criteria with XML structure
    (format-criteria-xml criteria)
    "

"
    ;; Proposals with XML structure
    (format-proposals-xml proposals order)
    "

"
    ;; Instructions section
    "
<instructions>
1. Evaluate each proposal against ALL criteria listed above
2. Consider the criteria weights when forming your judgment
3. Be objective - ignore position, length, or writing style
4. Output ONLY valid JSON, no explanation or commentary

Output format:
{
  \"1\": <score_1_to_10>,
  \"2\": <score_1_to_10>,
  ...
}
</instructions>

"
    ;; Model-specific hints
    (when (= model-hint :claude)
      "
<thinking>
Before scoring, briefly consider:
- Does each proposal satisfy the functional requirements?
- Which design is simplest while still being complete?
- What are the maintainability implications?
</thinking>

")

    ;; Final output directive
    "
<output>
Provide your scores as JSON now:
</output>")))

(defn make-xml-evaluation-prompt-with-reasoning
  "Generate prompt that asks for explicit reasoning before scores.

   Research shows this improves calibration and reduces bias."
  [proposals order {:keys [criteria context]}]
  (str/trim
   (str
    (when context (format-context-xml context))

    "
<task>
You are an expert software architecture evaluator.

Evaluate architectural proposals using structured reasoning:
1. First, analyze each proposal's strengths/weaknesses
2. Then, assign scores based on criteria
</task>

"
    (format-criteria-xml criteria)
    "

"
    (format-proposals-xml proposals order)
    "

"
    "
<instructions>
For each proposal:
1. List 2-3 key strengths
2. List 2-3 key weaknesses
3. Assign score based on criteria weights

Output format:
{
  \"reasoning\": {
    \"1\": {\"strengths\": [...], \"weaknesses\": [...]},
    \"2\": {\"strengths\": [...], \"weaknesses\": [...]},...
  },
  \"scores\": {
    \"1\": <score>,
    \"2\": <score>,...
  }
}
</instructions>

"
    "
<output>
Provide your evaluation as JSON now:
</output>")))

;; =============================================================================
;; Enhanced Criteria Definitions

(def enhanced-criteria
  "Enhanced criteria with descriptions for better LLM understanding."
  [{:name "Functional Correctness"
    :weight 0.3
    :description "Does the proposal satisfy all stated requirements? Are all necessary components present?"}

   {:name "Complexity & Simplicity"
    :weight 0.2
    :description "Is the design as simple as possible while being complete? Low coupling, clear boundaries?"}

   {:name "Maintainability"
    :weight 0.2
    :description "Easy to understand, modify, and extend? Well-documented? Clear naming and structure?"}

   {:name "Performance & Scalability"
    :weight 0.2
    :description "Efficient design? Handles load well? Considers caching, rate limiting, optimization?"}

   {:name "Consistency & Best Practices"
    :weight 0.1
    :description "Follows established patterns (REST, GraphQL, etc)? Standard error handling? Conventional naming?"}])

;; =============================================================================
;; Context Loaders

#?(:clj
   (defn load-source-overview
     "Load AUTO-SOURCE-OVERVIEW.md as evaluation context."
     []
     (try
       (slurp "AUTO-SOURCE-OVERVIEW.md")
       (catch Exception _
         nil))))

#?(:clj
   (defn load-project-overview
     "Load AUTO-PROJECT-OVERVIEW.md as evaluation context."
     []
     (try
       (slurp "AUTO-PROJECT-OVERVIEW.md")
       (catch Exception _
         nil))))

(comment
  ;; REPL testing
  (def test-proposals
    {:a "Simple REST API with GET /users, POST /users"
     :b "Complex GraphQL + REST hybrid"
     :c "Moderate design with auth"})

  ;; Standard XML prompt
  (println
   (make-xml-evaluation-prompt
    test-proposals
    [:a :b :c]
    {:criteria enhanced-criteria
     :context (load-source-overview)}))

  ;; Prompt with reasoning
  (println
   (make-xml-evaluation-prompt-with-reasoning
    test-proposals
    [:a :b :c]
    {:criteria enhanced-criteria
     :context nil})))
