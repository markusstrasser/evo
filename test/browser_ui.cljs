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

(deftest failing-browser-test
  (testing "This test will fail to demonstrate failure capture"
    ;; This will fail - checking for a non-existent element
    (let [non-existent (.getElementById js/document "element-that-does-not-exist")]
      (is (not (nil? non-existent)) "Should find non-existent element (this will fail)")

      ;; This will also fail - wrong document title
      (is (= "Wrong Title" (.-title js/document)) "Document title should be 'Wrong Title' (this will fail)")

      ;; This will fail - checking window width is exactly 1000
      (is (= 1000 (.-innerWidth js/window)) "Window width should be exactly 1000px (this will fail)"))))

(deftest window-properties-test
  (testing "Window and navigator properties"
    ;; These should mostly pass
    (is (exists? js/navigator))
    (is (string? (.-userAgent js/navigator)))
    (is (number? (.-innerWidth js/window)))
    (is (number? (.-innerHeight js/window)))

    ;; This will likely fail unless on a very specific screen size
    (is (> (.-innerWidth js/window) 2000) "Window width should be greater than 2000px (likely to fail)")))

(deftest console-api-test
  (testing "Console API functionality"
    ;; Test console methods exist
    (is (exists? js/console.log))
    (is (exists? js/console.error))
    (is (exists? js/console.warn))

    ;; Test we can call them (these should work)
    (js/console.log "🧪 Test log message from browser-ui-test")
    (js/console.warn "⚠️ Test warning from browser-ui-test")

    ;; This test will fail - console.log doesn't return anything meaningful
    (is (= "logged" (js/console.log "test")) "console.log should return 'logged' (this will fail)")))

;; Run tests automatically when namespace loads in browser
(run-tests)