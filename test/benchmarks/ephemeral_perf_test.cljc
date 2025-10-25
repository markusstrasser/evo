(ns benchmarks.ephemeral-perf-test
  "Micro-benchmark for ephemeral update fast path.

   Validates that ephemeral operations (cursor updates, edit mode changes)
   skip derive-indexes and don't balloon memory/time."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.api :as api]
            [kernel.transaction :as tx]))

(defn- make-test-db
  "Create a DB with a single page and block for testing."
  []
  (-> (db/empty-db)
      (tx/interpret [{:op :create-node :id "page" :type :page :props {:title "Test"}}
                     {:op :place :id "page" :under :doc :at :last}
                     {:op :create-node :id "a" :type :block :props {:text "Block A"}}
                     {:op :place :id "a" :under "page" :at :last}])
      :db))

(deftest ephemeral-updates-benchmark
  (testing "1000 ephemeral updates complete in reasonable time"
    (let [db0 (make-test-db)
          start-time #?(:clj (System/nanoTime)
                        :cljs (.now js/performance))

          ;; Perform 1000 ephemeral updates (cursor position changes)
          final-db (reduce
                    (fn [db i]
                      (:db (api/dispatch db {:type :update-cursor
                                             :block-id "a"
                                             :cursor-pos i})))
                    db0
                    (range 1000))

          end-time #?(:clj (System/nanoTime)
                      :cljs (.now js/performance))
          elapsed-ms #?(:clj (/ (- end-time start-time) 1e6)
                        :cljs (- end-time start-time))]

      (is (some? final-db) "Final DB should exist")
      (is (= (get-in final-db [:nodes "a" :type]) :block)
          "Block 'a' should still exist after ephemeral updates")

      ;; Performance sanity check: 1000 updates should complete in < 1 second
      ;; (If derive-indexes ran every time, this would be much slower)
      (is (< elapsed-ms 1000)
          (str "1000 ephemeral updates took " elapsed-ms "ms (expected < 1000ms)"))

      (println (str "\nEphemeral perf: 1000 updates in " (int elapsed-ms) "ms"
                    " (" (/ (int elapsed-ms) 1000.0) "ms per update)")))))

(deftest document-update-triggers-derive
  (testing "Document updates still trigger derive-indexes"
    (let [db0 (make-test-db)

          ;; Make a document change (not ephemeral)
          db1 (:db (api/dispatch db0 {:type :update-content
                                       :block-id "a"
                                       :text "Updated text"}))

          ;; Derived indexes should be fresh
          result (db/validate db1)]

      (is (:ok? result)
          "Derived indexes should be fresh after document update"))))
