(ns evolver.test-macros
  "Macros for self-documenting tests"
  (:require #?(:cljs [cljs.test :as test]
               :clj [clojure.test :as test])))

;; User Story Documentation Macros
(defmacro user-story [story & body]
  `(~'testing ~(str "USER STORY: " story) ~@body))

(defmacro acceptance-criteria [criteria & body]
  `(~'testing ~(str "  ✓ " criteria) ~@body))

(defmacro jtbd [job & body]
  `(~'testing ~(str "JOB TO BE DONE: " job) ~@body))

(defmacro feature [feature-name & body]
  `(~'testing ~(str "FEATURE: " feature-name) ~@body))

(defmacro test-with-agent-validation [& body]
  `(#?(:cljs evolver.test-helpers/with-agent-observation
       :clj do)
     (fn []
       ~@body
       ;; Post-test validation using agent tools (ClojureScript only)
       #?(:cljs
          (when ((resolve 'agent.core/detect-environment) :store-accessible?)
            ;; Add validation checks here as agent tools are enhanced
            true)
          :clj true))))