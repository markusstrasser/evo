(ns browser-ui
  "Browser-specific UI tests that use DOM APIs"
  (:require [cljs.test :refer [deftest testing is are run-tests]]))

;; === BROWSER API TESTS ===

(deftest browser-environment-test
  (testing "Browser environment is available"
    ;; These should pass
    (is (exists? js/window))
    (is (exists? js/document))
    (is (exists? js/console))

    ;; Test document properties
    (is (string? (.-title js/document)))
    (is (exists? (.-body js/document)))))

(deftest dom-manipulation-test
  (testing "DOM manipulation capabilities"
    ;; Create a test element
    (let [test-div (.createElement js/document "div")]
      (set! (.-id test-div) "test-element")
      (set! (.-innerHTML test-div) "Test Content")

      ;; Test element properties
      (is (= "test-element" (.-id test-div)))
      (is (= "Test Content" (.-innerHTML test-div)))

      ;; Add to DOM temporarily
      (.appendChild (.-body js/document) test-div)

      ;; Test that we can find it
      (let [found-element (.getElementById js/document "test-element")]
        (is (not (nil? found-element)))
        (is (= "Test Content" (.-innerHTML found-element))))

      ;; Clean up
      (.removeChild (.-body js/document) test-div))))

(deftest local-storage-test
  (testing "Local storage functionality"
    ;; Test local storage is available
    (is (exists? js/localStorage))

    ;; Test set/get
    (.setItem js/localStorage "test-key" "test-value")
    (is (= "test-value" (.getItem js/localStorage "test-key")))

    ;; Clean up
    (.removeItem js/localStorage "test-key")
    (is (nil? (.getItem js/localStorage "test-key")))))

(deftest browser-integration-test
  (testing "Browser integration works correctly"
    ;; Test that non-existent elements return null (correct behavior)
    (let [non-existent (.getElementById js/document "element-that-does-not-exist")]
      (is (nil? non-existent) "Non-existent elements should return null"))

    ;; Test document title exists (don't care about specific value)
    (is (string? (.-title js/document)) "Document title should be a string")

    ;; Test window width is a positive number (don't care about exact value)
    (is (and (number? (.-innerWidth js/window))
             (pos? (.-innerWidth js/window)))
        "Window width should be a positive number")))

(deftest window-properties-test
  (testing "Window and navigator properties"
    ;; These should mostly pass
    (is (exists? js/navigator))
    (is (string? (.-userAgent js/navigator)))
    (is (number? (.-innerWidth js/window)))
    (is (number? (.-innerHeight js/window)))

;; Test reasonable window width (most screens are at least 800px)
    (is (> (.-innerWidth js/window) 800) "Window width should be greater than 800px")))

(deftest console-api-test
  (testing "Console API functionality"
    ;; Test console methods exist
    (is (exists? js/console.log))
    (is (exists? js/console.error))
    (is (exists? js/console.warn))

    ;; Test we can call them (these should work)
    (js/console.log "🧪 Test log message from browser-ui-test")
    (js/console.warn "⚠️ Test warning from browser-ui-test")

;; Test console.log returns undefined (correct behavior)
    (is (undefined? (js/console.log "test")) "console.log should return undefined")))

;; Run tests automatically when namespace loads in browser
(run-tests)