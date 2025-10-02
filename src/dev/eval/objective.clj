(ns dev.eval.objective
  "Objective feature extraction from OpenAPI specs.
   Uses Spectral linting and OWASP security heuristics."
  (:require [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn spectral-lint
  "Run Spectral linter on OpenAPI YAML and extract features.
   Returns {:failures {...} :warnings {...} :feature-vector {...}}."
  [openapi-yaml]
  (let [tmp-file (java.io.File/createTempFile "oas" ".yaml")]
    (try
      (spit tmp-file openapi-yaml)
      (let [result (shell/sh "npx" "@stoplight/spectral-cli" "lint"
                             (.getPath tmp-file) "--format" "json")
            output (:out result)
            parsed (when (seq output)
                     (try (json/read-str output :key-fn keyword)
                          (catch Exception _ [])))]
        {:failures (count (filter #(= "error" (:severity %)) parsed))
         :warnings (count (filter #(= "warning" (:severity %)) parsed))
         :feature-vector (reduce (fn [acc issue]
                                   (update acc (:code issue) (fnil inc 0)))
                                 {}
                                 parsed)})
      (finally
        (.delete tmp-file)))))

(defn owasp-features
  "Extract OWASP security feature flags from OpenAPI spec.
   Returns feature map with security indicators."
  [openapi-yaml]
  (let [spec-str (str openapi-yaml)]
    {:missing-auth (if (re-find #"securitySchemes" spec-str) 0 1)
     :bola-risk (if (re-find #"\{id\}|\{userId\}" spec-str) 1 0)
     :ssrf-risk (if (re-find #"url=|callback=" spec-str) 1 0)
     :info-disclosure (if (re-find #"stackTrace|errorDetails" spec-str) 1 0)
     :mass-assignment (if (re-find #"additionalProperties.*true" spec-str) 1 0)}))

(defn extract-features
  "Extract combined objective features from OpenAPI spec.
   Returns unified feature map."
  [openapi-yaml]
  (let [spectral (spectral-lint openapi-yaml)
        owasp (owasp-features openapi-yaml)]
    {:spectral spectral
     :owasp owasp
     :combined (merge (:feature-vector spectral) owasp)}))
