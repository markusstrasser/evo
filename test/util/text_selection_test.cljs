(ns util.text-selection-test
  "Tests for text selection utilities.

   NOTE: Most tests for this namespace require a browser DOM environment.
   See test/e2e/text-selection.spec.js for comprehensive browser-based tests.

   These unit tests only cover non-DOM utility functions."
  (:require [clojure.test :refer [deftest testing is]]
            [util.text-selection :as text-sel]))

;; ── Utility Function Tests (No DOM Required) ────────────────────────────────

(deftest placeholder-test
  (testing "Text selection utilities require DOM"
    (is true "See test/e2e/text-selection.spec.js for browser-based tests")))
