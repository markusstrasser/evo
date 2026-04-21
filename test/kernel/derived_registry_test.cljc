(ns kernel.derived-registry-test
  "Tests for the derived-index plugin registry.

   Plugins are maps `{:initial fn, :apply-tx fn?}`. Phase D of the kernel
   refactor put the protocol surface in place; :apply-tx is deferred
   until a plugin migrates. This file tests :initial — the oracle path
   every plugin uses today."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [kernel.derived-registry :as reg]))

;; Fixture clears plugins before each test. (We don't clear after, since
;; other tests may depend on plugins registered at namespace load time
;; and re-requiring a namespace doesn't re-run top-level forms.)
(use-fixtures :each
  (fn [f]
    (reg/clear!)
    (f)))

(deftest register-and-run
  (testing "Registering a plugin with :initial and running it"
    (reg/register! :test-plugin
                   {:initial (fn [_db] {:test-key "test-value"})})

    (let [result (reg/run-all {})]
      (is (= {:test-key "test-value"} result))))

  (reg/clear!)

  (testing "Multiple plugins merge results"
    (reg/register! :plugin-a {:initial (fn [_] {:a 1})})
    (reg/register! :plugin-b {:initial (fn [_] {:b 2})})

    (is (= {:a 1 :b 2} (reg/run-all {})))))

(deftest initial-receives-db
  (testing ":initial is a pure function of the db"
    (reg/register! :reader
                   {:initial (fn [db] {:node-count (count (:nodes db))})})
    (let [db {:nodes {:a {} :b {} :c {}}}]
      (is (= {:node-count 3} (reg/run-all db))))))

(deftest unregister
  (testing "Unregistering a plugin removes it"
    (reg/register! :temp {:initial (fn [_] {:temp true})})
    (is (= {:temp true} (reg/run-all {})))
    (reg/unregister! :temp)
    (is (= {} (reg/run-all {})))))

(deftest error-handling
  (testing "Plugin errors in :initial don't crash run-all"
    (reg/register! :good {:initial (fn [_] {:good true})})
    (reg/register! :bad {:initial (fn [_] (throw (ex-info "boom" {})))})
    (reg/register! :also-good {:initial (fn [_] {:also-good true})})

    (let [result (reg/run-all {})]
      (is (:good result))
      (is (:also-good result)))))

;; ── Spec validation ─────────────────────────────────────────────────────────

(deftest register-rejects-non-map-spec
  (testing "A bare function is no longer a valid spec"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :bad (fn [_] {:x 1}))))))

(deftest register-requires-initial
  (testing "Spec without :initial is rejected"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :bad {})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :bad {:apply-tx (fn [_ _ _ _] {})})))))

(deftest register-rejects-non-fn-apply-tx
  (testing "Non-function :apply-tx is rejected at registration time"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :bad {:initial (fn [_] {})
                                      :apply-tx "not a function"})))))

(deftest apply-tx-accepted-when-function
  (testing "Plugins that supply :apply-tx register successfully, even if
            Phase D's lean cut doesn't invoke it yet"
    (reg/register! :incremental
                   {:initial (fn [_] {:idx {}})
                    :apply-tx (fn [prev _db-before _ops _db-after] prev)})
    ;; run-all uses :initial regardless of :apply-tx presence (for now).
    (is (= {:idx {}} (reg/run-all {})))))
