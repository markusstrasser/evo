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
                   {:keys #{:test-key}
                    :initial (fn [_db] {:test-key "test-value"})})

    (let [result (reg/run-all {})]
      (is (= {:test-key "test-value"} result))))

  (reg/clear!)

  (testing "Multiple plugins merge results"
    (reg/register! :plugin-a {:keys #{:a} :initial (fn [_] {:a 1})})
    (reg/register! :plugin-b {:keys #{:b} :initial (fn [_] {:b 2})})

    (is (= {:a 1 :b 2} (reg/run-all {})))))

(deftest initial-receives-db
  (testing ":initial is a pure function of the db"
    (reg/register! :reader
                   {:keys #{:node-count}
                    :initial (fn [db] {:node-count (count (:nodes db))})})
    (let [db {:nodes {:a {} :b {} :c {}}}]
      (is (= {:node-count 3} (reg/run-all db))))))

(deftest unregister
  (testing "Unregistering a plugin removes it"
    (reg/register! :temp {:keys #{:temp} :initial (fn [_] {:temp true})})
    (is (= {:temp true} (reg/run-all {})))
    (reg/unregister! :temp)
    (is (= {} (reg/run-all {})))))

(deftest plugin-errors-fail-loudly
  (testing "Plugin errors in :initial fail the derived pass"
    (reg/register! :bad {:keys #{:bad}
                         :initial (fn [_] (throw (ex-info "boom" {})))})
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/run-all {})))))

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
                 (reg/register! :bad {:keys #{:x}
                                      :apply-tx (fn [_ _ _ _] {})})))))

(deftest register-requires-key-set
  (testing "Spec without exact derived key ownership is rejected"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :bad {:initial (fn [_] {:x 1})})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :bad {:keys [:x]
                                      :initial (fn [_] {:x 1})})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :bad {:keys #{}
                                      :initial (fn [_] {})})))))

(deftest register-rejects-non-fn-apply-tx
  (testing "Non-function :apply-tx is rejected at registration time"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :bad {:keys #{:x}
                                      :initial (fn [_] {:x 1})
                                      :apply-tx "not a function"})))))

(deftest register-rejects-derived-key-collisions
  (testing "Plugins cannot claim core derived keys"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :bad {:keys #{:parent-of}
                                      :initial (fn [_] {:parent-of {}})}))))
  (testing "Plugins cannot claim another plugin's derived keys"
    (reg/register! :a {:keys #{:shared} :initial (fn [_] {:shared 1})})
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/register! :b {:keys #{:shared}
                                    :initial (fn [_] {:shared 2})})))))

(deftest reregister-releases-old-keys
  (testing "Same plugin id can hot-reload with a different key set"
    (reg/register! :a {:keys #{:old} :initial (fn [_] {:old 1})})
    (reg/register! :a {:keys #{:new} :initial (fn [_] {:new 2})})
    (reg/register! :b {:keys #{:old} :initial (fn [_] {:old 3})})
    (is (= {:new 2 :old 3} (reg/run-all {})))))

(deftest run-all-requires-emitted-keys-to-match-declared-keys
  (testing "Missing emitted keys are rejected"
    (reg/register! :missing {:keys #{:a :b} :initial (fn [_] {:a 1})})
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/run-all {}))))

  (reg/clear!)

  (testing "Extra emitted keys are rejected"
    (reg/register! :extra {:keys #{:a} :initial (fn [_] {:a 1 :b 2})})
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/run-all {}))))

  (reg/clear!)

  (testing "Non-map plugin output is rejected"
    (reg/register! :bad-output {:keys #{:a} :initial (fn [_] [:a 1])})
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reg/run-all {})))))

(deftest apply-tx-accepted-when-function
  (testing "Plugins that supply :apply-tx register successfully, even if
            Phase D's lean cut doesn't invoke it yet"
    (reg/register! :incremental
                   {:keys #{:idx}
                    :initial (fn [_] {:idx {}})
                    :apply-tx (fn [prev _db-before _ops _db-after] prev)})
    ;; run-all uses :initial regardless of :apply-tx presence (for now).
    (is (= {:idx {}} (reg/run-all {})))))
