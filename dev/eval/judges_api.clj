(ns dev.eval.judges-api
  "API-only LLM judge implementations for v3 evaluator.
   Replaces CLI-based judges with reproducible HTTP API calls."
  (:require [clojure.data.json :as json]
            [clj-http.client :as http]))

(defmulti call-judge
  "Dispatch LLM judge API calls by provider.
   Returns raw API response map."
  (fn [provider _payload] provider))

(defn- json-post
  "HTTP POST with JSON body, returns parsed response."
  [url headers body]
  (-> (http/post url {:headers (merge {"content-type" "application/json"} headers)
                      :body (json/write-str body)
                      :socket-timeout 60000
                      :conn-timeout 30000})
      :body
      (json/read-str :key-fn keyword)))

(defmethod call-judge :gpt5-codex [_ {:keys [prompt]}]
  (let [api-key (System/getenv "OPENAI_API_KEY")]
    (when-not api-key
      (throw (ex-info "Missing OPENAI_API_KEY environment variable" {:provider :gpt5-codex})))
    (json-post "https://api.openai.com/v1/chat/completions"
               {"authorization" (str "Bearer " api-key)}
               {:model "gpt-5-codex"
                :reasoning {:effort "high"}
                :response_format {:type "json_object"}
                :temperature 0.0
                :messages [{:role "user" :content prompt}]})))

(defmethod call-judge :gemini25-pro [_ {:keys [prompt]}]
  (let [api-key (System/getenv "GOOGLE_API_KEY")]
    (when-not api-key
      (throw (ex-info "Missing GOOGLE_API_KEY environment variable" {:provider :gemini25-pro})))
    (json-post "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent"
               {"x-goog-api-key" api-key}
               {:contents [{:parts [{:text prompt}]}]
                :generationConfig {:temperature 0.0
                                   :responseMimeType "application/json"}})))

(defmethod call-judge :claude-4.5 [_ {:keys [prompt]}]
  (let [api-key (System/getenv "ANTHROPIC_API_KEY")]
    (when-not api-key
      (throw (ex-info "Missing ANTHROPIC_API_KEY environment variable" {:provider :claude-4.5})))
    (json-post "https://api.anthropic.com/v1/messages"
               {"x-api-key" api-key
                "anthropic-version" "2023-06-01"}
               {:model "claude-sonnet-4.5"
                :max_tokens 4096
                :temperature 0.0
                :messages [{:role "user" :content prompt}]
                :system "You must respond with valid JSON only."})))

(defmethod call-judge :kimi-2 [_ {:keys [prompt]}]
  (let [api-key (System/getenv "KIMI_API_KEY")]
    (when-not api-key
      (throw (ex-info "Missing KIMI_API_KEY environment variable" {:provider :kimi-2})))
    (json-post "https://api.moonshot.cn/v1/chat/completions"
               {"authorization" (str "Bearer " api-key)}
               {:model "kimi-2"
                :response_format {:type "json_object"}
                :temperature 0.0
                :messages [{:role "user" :content prompt}]})))

(defmethod call-judge :grok-4 [_ {:keys [prompt]}]
  (let [api-key (System/getenv "XAI_API_KEY")]
    (when-not api-key
      (throw (ex-info "Missing XAI_API_KEY environment variable" {:provider :grok-4})))
    (json-post "https://api.x.ai/v1/chat/completions"
               {"authorization" (str "Bearer " api-key)}
               {:model "grok-4"
                :response_format {:type "json_object"}
                :temperature 0.0
                :messages [{:role "user" :content prompt}]})))

(defmethod call-judge :mock [_ {:keys [prompt]}]
  {:choices [{:message {:content "{\"verdict\":\"left\",\"criteria\":{\"test\":1.0},\"confidence\":0.5}"}}]})

(defn parse-json-out
  "Normalize API response to extract JSON content.
   Handles provider-specific response formats."
  [provider resp]
  (case provider
    :gpt5-codex (-> resp :choices first :message :content (json/read-str :key-fn keyword))
    :gemini25-pro (-> resp :candidates first :content :parts first :text (json/read-str :key-fn keyword))
    :claude-4.5 (-> resp :content first :text (json/read-str :key-fn keyword))
    :kimi-2 (-> resp :choices first :message :content (json/read-str :key-fn keyword))
    :grok-4 (-> resp :choices first :message :content (json/read-str :key-fn keyword))
    :mock (-> resp :choices first :message :content (json/read-str :key-fn keyword))
    (throw (ex-info "Unknown provider" {:provider provider}))))

(defn judge!
  "Call judge API and return parsed JSON map."
  [provider prompt]
  (let [resp (call-judge provider {:prompt prompt})]
    (parse-json-out provider resp)))

(defn mock-judge!
  "Mock judge for testing - extracts JSON from prompt string."
  [_provider prompt]
  (if-let [json-match (re-find #"\{.*\}" prompt)]
    (json/read-str json-match :key-fn keyword)
    {:verdict "left" :criteria {:test 1.0} :confidence 0.5}))
