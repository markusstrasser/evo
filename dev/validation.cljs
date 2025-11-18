(ns dev.validation
  "Intent validation for development and testing.

   Validates all intents against their registered specs at runtime in browser.
   No-op stub in Node/CLJ environments."
  (:require [malli.core :as m]
            [malli.error :as me]
            [kernel.intent :as intent]))

(defn browser-env?
  "Check if running in browser (not Node.js)."
  []
  (and (exists? js/window)
       (exists? js/document)))

(defn validate-intent
  "Validate intent against its registered spec.
   Returns intent if valid, throws ex-info with rich context if invalid.
   No-op in Node.js."
  [intent]
  (if (browser-env?)
    (if-let [spec (get-in @intent/!intents [(:type intent) :spec])]
      (if (m/validate spec intent)
        intent
        (let [explanation (m/explain spec intent)
              humanized (me/humanize explanation)]
          (throw (ex-info
                  (str "Intent validation failed: " (:type intent))
                  {:intent intent
                   :spec spec
                   :errors humanized
                   :explanation explanation}))))
      ;; No spec registered - warn but don't block
      (do
        (js/console.warn "No spec registered for intent type:" (:type intent))
        intent))
    ;; Node.js - return intent unchanged
    intent))

(defn wrap-apply-intent-with-validation
  "Wrap intent/apply-intent to validate before executing.
   Call once in dev environment to enable validation.

   No-op in Node.js environments."
  []
  (when (browser-env?)
    (let [original-fn intent/apply-intent]
      (set! intent/apply-intent
            (fn [db intent]
              (validate-intent intent)  ;; Validate first
              (original-fn db intent))))))  ;; Then execute

;; Auto-enable in browser dev mode
(when (and (browser-env?)
           (exists? js/goog.global.CLOSURE_UNCOMPILED_DEFINES))
  (wrap-apply-intent-with-validation))
