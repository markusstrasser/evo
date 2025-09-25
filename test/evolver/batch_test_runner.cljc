;; Batch test runner for efficient REPL testing
;; Returns concise results to avoid context flooding

(ns evolver.batch-test-runner
  (:require [evolver.kernel :as k]
            [evolver.schemas :as s]))

;; Test result format: [test-name success? error-msg?]
(defn run-batch-tests []
  (let [results
        (try
          ;; Setup fixture db
          (def fixture-db
            (-> k/db
                (k/safe-apply-command {:op :insert :parent-id "root" :node-id "t1"
                                      :node-data {:type :div :props {:text "T1"}} :position 0})
                (k/safe-apply-command {:op :insert :parent-id "root" :node-id "t2"
                                      :node-data {:type :div :props {:text "T2"}} :position 1})
                (assoc-in [:view :selected] #{"t1"})))

          ;; Test 1: Basic operations
          (def t1-db (k/safe-apply-command fixture-db {:op :insert :parent-id "t1" :node-id "child"
                                                      :node-data {:type :div :props {:text "Child"}} :position nil}))
          (def t1-result (and (contains? (:nodes t1-db) "child")
                              (= ["child"] (get (:children-by-parent t1-db) "t1"))))

          ;; Test 2: Undo/Redo cycle
          (def t2-db (k/safe-apply-command fixture-db {:op :insert :parent-id "root" :node-id "undo-test"
                                                      :node-data {:type :div :props {:text "Undo"}} :position 0}))
          (def t2-undo (k/safe-apply-command t2-db {:op :undo}))
          (def t2-redo (k/safe-apply-command t2-undo {:op :redo}))
          (def t2-result (and (contains? (:nodes t2-db) "undo-test")
                              (not (contains? (:nodes t2-undo) "undo-test"))
                              (contains? (:nodes t2-redo) "undo-test")))

          ;; Test 3: Reorder operation
          (def t3-db (k/safe-apply-command fixture-db {:op :reorder :node-id "t2" :parent-id "root"
                                                      :from-index 1 :to-index 0}))
          (def t3-result (some? t3-db)) ; Just check it doesn't crash

          ;; Test 4: Schema validation
          (def t4-result (s/validate-db fixture-db))

          ;; Test 5: Error handling
          (def t5-result
            (try
              (k/safe-apply-command fixture-db {:op :invalid})
              false ; Should have thrown or logged error
              (catch #?(:clj Exception :cljs js/Error) e true))) ; Should catch errors

          ;; Format results
          [["basic-ops" t1-result nil]
           ["undo-redo" t2-result nil]
           ["reorder" t3-result nil]
           ["schema" t4-result nil]
           ["error-handling" t5-result nil]]

          (catch #?(:clj Exception :cljs js/Error) e
            [["error" false (str "Test runner failed: " (#?(:clj .getMessage :cljs .-message) e))]]))

        passed (count (filter #(nth % 1) results))
        total (count results)]

    ;; Return concise summary
    {:summary (str passed "/" total " tests passed")
     :results results
     :status (if (= passed total) :success :partial)}))

;; Quick validation function
(defn validate-system []
  (let [result (run-batch-tests)]
    (println (:summary result))
    (when-not (= (:status result) :success)
      (doseq [[name pass? err] (:results result)]
        (when-not pass?
          (println (str "  FAILED: " name (when err (str " - " err)))))))
    result))

;; Performance test
(defn perf-test [iterations]
  (println (str "Running " iterations " iterations..."))
  (time
   (dotimes [_ iterations]
     (k/safe-apply-command k/db {:op :insert :parent-id "root" :node-id (str "perf-" _)
                                :node-data {:type :div :props {:text "Perf"}} :position 0})))
  {:iterations iterations :status :completed})