(ns evolver.feature-tests
  "Self-documenting feature tests that serve as living specifications"
  (:require [cljs.test :refer [deftest is testing]]
            [agent.core :as agent]
            [evolver.test-helpers :as helpers]
            [evolver.test-macros :refer-macros [user-story acceptance-criteria jtbd feature]]))

;; Macros moved to test-helpers.cljs

;; Acceptance criteria macro moved to test-helpers.cljs

;; JTBD macro moved to test-helpers.cljs

;; Feature macro moved to test-helpers.cljs

;; Agent observation wrapper moved to test-helpers.cljs

(defmacro test-with-agent-validation [& body]
  `(helpers/with-agent-observation
     (fn []
       ~@body
       ;; Post-test validation using agent tools
       (when ((resolve 'agent.core/detect-environment) :store-accessible?)
         ;; Add validation checks here as agent tools are enhanced
         true))))

;; Example Feature Test Structure
(deftest document-navigation-feature
  "As a user editing a hierarchical document, I want to navigate efficiently"

  (jtbd "Navigate through nested content structures quickly"
        (user-story "Navigate between sibling nodes with keyboard shortcuts"
                    (acceptance-criteria "Arrow Down moves to next sibling"
                                         (test-with-agent-validation
                                          ;; Test implementation will go here
                                          (let [test-doc (helpers/setup-test-document)]
                                            (is true "Placeholder - implement navigation test"))))

                    (acceptance-criteria "Arrow Up moves to previous sibling"
                                         (test-with-agent-validation
                                          ;; Test implementation will go here
                                          (let [test-doc (helpers/setup-test-document)]
                                            (is true "Placeholder - implement navigation test"))))))

  (jtbd "Reorganize content structure while maintaining context"
        (user-story "Move selected nodes to different positions"
                    (acceptance-criteria "Selected nodes move up when Alt+Shift+Up pressed"
                                         (test-with-agent-validation
                                          ;; Test implementation will go here
                                          (let [test-doc (helpers/setup-test-document)]
                                            (is true "Placeholder - implement move test")))))))

;; Template for new feature tests
(defmacro define-feature-test [feature-name job-description user-stories]
  `(deftest ~(symbol (str (name feature-name) "-feature"))
     ~(str "Feature: " feature-name)
     (jtbd ~job-description
           ~@user-stories)))

;; Helper to extract documentation from tests for reporting
(defn extract-test-documentation []
  "Extract user stories and acceptance criteria from test metadata"
  ;; This would analyze the test structure to generate documentation
  ;; For now, return a placeholder
  {:features []
   :user-stories []
   :acceptance-criteria []})