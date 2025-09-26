(ns evolver.test-macros
  "Macros for self-documenting tests"
  (:require [cljs.test :as test]))

;; User Story Documentation Macros
(defmacro user-story [story & body]
  `(test/testing ~(str "USER STORY: " story) ~@body))

(defmacro acceptance-criteria [criteria & body]
  `(test/testing ~(str "  ✓ " criteria) ~@body))

(defmacro jtbd [job & body]
  `(test/testing ~(str "JOB TO BE DONE: " job) ~@body))

(defmacro feature [feature-name & body]
  `(test/testing ~(str "FEATURE: " feature-name) ~@body))

(defmacro test-with-agent-validation [& body]
  `(evolver.test-helpers/with-agent-observation
     (fn []
       ~@body
       ;; Post-test validation using agent tools
       (when ((resolve 'agent.core/detect-environment) :store-accessible?)
         ;; Add validation checks here as agent tools are enhanced
         true))))