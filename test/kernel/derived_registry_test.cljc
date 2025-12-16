(ns kernel.derived-registry-test
  "Tests for derived registry system."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [kernel.derived-registry :as reg]))

;; Fixture to clear plugins before each test only.
;; Note: We don't clear after, because other tests may depend on
;; plugins registered at namespace load time, and requiring a namespace
;; again doesn't re-run top-level forms.
(use-fixtures :each
  (fn [f]
    (reg/clear!)
    (f)))

(deftest register-and-run
  (testing "Registering and running a simple plugin"
    (reg/register! :test-plugin
                   (fn [_db]
                     {:test-key "test-value"}))

    (let [result (reg/run-all {})]
      (is (= {:test-key "test-value"} result))))

  (reg/clear!) ; Clear between testing blocks

  (testing "Multiple plugins merge results"
    (reg/register! :plugin-a (fn [_] {:a 1}))
    (reg/register! :plugin-b (fn [_] {:b 2}))

    (let [result (reg/run-all {})]
      (is (= {:a 1, :b 2} result)))))

(deftest plugin-can-read-db
  (testing "Plugin receives db and can read from it"
    (reg/register! :reader
                   (fn [db]
                     {:node-count (count (:nodes db))}))

    (let [db {:nodes {:a {} :b {} :c {}}}
          result (reg/run-all db)]
      (is (= {:node-count 3} result)))))

(deftest unregister
  (testing "Unregistering a plugin removes it"
    (reg/register! :temp (fn [_] {:temp true}))
    (is (= {:temp true} (reg/run-all {})))

    (reg/unregister! :temp)
    (is (= {} (reg/run-all {})))))

(deftest error-handling
  (testing "Plugin errors don't crash run-all"
    (reg/register! :good (fn [_] {:good true}))
    (reg/register! :bad (fn [_] (throw (ex-info "boom" {}))))
    (reg/register! :also-good (fn [_] {:also-good true}))

    ;; Should merge results from good plugins despite the error
    (let [result (reg/run-all {})]
      (is (:good result))
      (is (:also-good result)))))
