(ns dev.eval.smart-truncate
  "Smart truncation using LLM summarization instead of simple character limits.

   Instead of just cutting at 500 chars, we use a fast LLM to create an
   information-preserving summary that captures the key architectural decisions."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; =============================================================================
;; Smart Truncation via LLM Summarization

(def summarization-prompt
  "You are an expert at distilling software architecture proposals to their essence.

Extract ONLY the key architectural decisions and technical details. Remove:
- Verbose explanations
- Repetitive content
- Marketing language
- Unnecessary adjectives

Output format: Concise technical summary in 3-5 bullet points.

<proposal>
%s
</proposal>

<instructions>
Focus on:
- Core architecture patterns (REST, GraphQL, WebSocket, etc)
- Key technical decisions (auth, caching, scaling, etc)
- Critical APIs or endpoints
- Important design tradeoffs

Output as plain bullet list, no preamble.
</instructions>")

(defn call-gemini-flash
  "Call Gemini Flash for fast, cheap summarization."
  [prompt]
  (let [result (shell/sh "gemini"
                         "--model" "gemini-2.0-flash-exp"
                         "-y"
                         "-p" prompt)]
    (if (zero? (:exit result))
      {:success true
       :output (str/trim (:out result))}
      {:success false
       :error (:err result)})))

(defn smart-truncate
  "Intelligently truncate proposal using LLM summarization.

   If proposal is short (<= max-length), return unchanged.
   If long, use fast LLM to create information-dense summary.

   Returns map:
   {:text \"...\" :truncated? true/false :method :summary/:none}"
  [proposal max-length]
  (if (<= (count proposal) max-length)
    ;; Short enough, no truncation needed
    {:text proposal
     :truncated? false
     :method :none}

    ;; Too long, use smart summarization
    (let [prompt (format summarization-prompt proposal)
          result (call-gemini-flash prompt)]
      (if (:success result)
        {:text (str (:output result)
                    "\n\n[Summarized from " (count proposal) " chars]")
         :truncated? true
         :method :summary
         :original-length (count proposal)}
        ;; Fallback to simple truncation if summarization fails
        {:text (str (subs proposal 0 max-length)
                    "... [+" (- (count proposal) max-length) " chars]")
         :truncated? true
         :method :simple-fallback
         :error (:error result)}))))

(defn smart-normalize-proposals
  "Normalize all proposals using smart truncation.

   Returns:
   {:proposals {id -> normalized-text}
    :metadata {id -> {:truncated? :method :original-length}}}"
  [proposals max-length]
  (let [results (into {}
                      (map (fn [[id text]]
                             (let [result (smart-truncate text max-length)]
                               [id result]))
                           proposals))]
    {:proposals (into {} (map (fn [[id r]] [id (:text r)]) results))
     :metadata (into {} (map (fn [[id r]]
                               [id (select-keys r [:truncated? :method :original-length])])
                             results))}))

(comment
  ;; REPL testing
  (def short-proposal
    "Simple REST API with GET /users, POST /users.")

  (def verbose-proposal
    "This is a comprehensive, enterprise-grade, highly scalable, and robust API design
     that leverages cutting-edge technologies and best practices to deliver exceptional
     value to our stakeholders. The architecture is designed with extensibility in mind,
     featuring a RESTful interface for standard CRUD operations, a GraphQL endpoint for
     flexible querying, WebSocket support for real-time updates, OAuth2 authentication
     with refresh tokens, rate limiting to prevent abuse, comprehensive monitoring and
     logging, circuit breakers for resilience, and extensive documentation using OpenAPI.
     This design ensures that our system can handle millions of requests per second while
     maintaining 99.999% uptime and providing an exceptional developer experience through
     SDK generation in multiple programming languages.")

  ;; Test short proposal (no truncation)
  (smart-truncate short-proposal 500)
  ;; => {:text "Simple REST API..." :truncated? false :method :none}

  ;; Test long proposal (smart summarization)
  (smart-truncate verbose-proposal 200)
  ;; => {:text "- REST + GraphQL + WebSocket\n- OAuth2 auth\n..."
  ;;     :truncated? true :method :summary :original-length 700}

  ;; Batch normalize
  (smart-normalize-proposals
   {:a short-proposal
    :b verbose-proposal}
   200)
  )
