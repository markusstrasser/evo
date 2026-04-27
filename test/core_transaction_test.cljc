(ns core-transaction-test
  "Tests for core.transaction - invariant-based, implementation-agnostic tests"
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]))

(defn create-op [id node-type] {:op :create-node :id id :type node-type :props {}})
(defn place-op [id under at] {:op :place :id id :under under :at at})
(defn update-op [id props] {:op :update-node :id id :props props})

(defn apply-ops
  "Apply ops and return final db. Fails test if validation issues occur."
  ([ops] (apply-ops (db/empty-db) ops))
  ([db ops]
   (let [result (tx/interpret db ops)]
     (when (seq (:issues result))
       (is false (str "Unexpected validation issues: " (:issues result))))
     (:db result))))

(defn apply-ops-expect-failure
  "Apply ops expecting validation failure. Returns issues vector."
  ([ops] (apply-ops-expect-failure (db/empty-db) ops))
  ([db ops]
   (let [result (tx/interpret db ops)]
     (is (seq (:issues result)) "Expected validation issues")
     (:issues result))))

(defn node-exists? [db id] (contains? (:nodes db) id))
(defn node-type [db id] (get-in db [:nodes id :type]))

(def ^:private system-keys
  "Keys auto-added by kernel (timestamps). Strip for cleaner test assertions."
  #{:created-at :updated-at})

(defn node-props
  "Get user-defined props, excluding system metadata (timestamps)."
  [db id]
  (apply dissoc (get-in db [:nodes id :props]) system-keys))

(defn node-props-all
  "Get all props including system metadata (timestamps)."
  [db id]
  (get-in db [:nodes id :props]))

(defn children-of [db parent] (get (:children-by-parent db) parent []))
(defn parent-of [db id] (get-in db [:derived :parent-of id]))
(defn db-valid? [db] (:ok? (db/validate db)))

(deftest test-create-node
  (testing "creating a node makes it exist with correct type"
    (let [db (apply-ops [(create-op "a" :div)])]
      (is (node-exists? db "a"))
      (is (= :div (node-type db "a")))
      (is (db-valid? db)))))

(deftest test-create-duplicate-rejected
  (testing "creating duplicate node is rejected"
    (let [db (apply-ops [(create-op "a" :div)])
          issues (apply-ops-expect-failure db [(create-op "a" :span)])]
      (is (= :duplicate-create (-> issues first :issue))))))

(deftest test-place-establishes-parent-child
  (testing "placing a node establishes parent-child relationship"
    (let [db (apply-ops [(create-op "a" :div)
                         (place-op "a" :doc :first)])]
      (is (= ["a"] (children-of db :doc)))
      (is (= :doc (parent-of db "a")))
      (is (db-valid? db)))))

(deftest test-place-positions
  (testing "place respects :first, :last, and relational positions"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (create-op "c" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" :doc :last)
                         (place-op "c" :doc {:after "a"})])]
      (is (= ["a" "c" "b"] (children-of db :doc)))
      (is (db-valid? db)))))

(deftest test-update-merges-props
  (testing "update merges properties into node"
    (let [db (apply-ops [(create-op "a" :div)
                         (update-op "a" {:x 1 :y 2})])]
      (is (= {:x 1 :y 2} (node-props db "a")))
      (is (db-valid? db))))

  (testing "multiple updates accumulate properties"
    (let [db (apply-ops [(create-op "a" :div)
                         (update-op "a" {:x 1})
                         (update-op "a" {:y 2})
                         (update-op "a" {:z 3})])]
      (is (= {:x 1 :y 2 :z 3} (node-props db "a")))
      (is (db-valid? db)))))

(deftest test-adjacent-update-semantics-preserve-sequential-replacement
  (testing "adjacent update-node ops are not pre-merged across scalar/map replacement"
    (let [db (apply-ops [(create-op "a" :block)
                         (update-op "a" {:a {:x 0}})
                         (update-op "a" {:a 1})
                         (update-op "a" {:a {:y 2}})])]
      (is (= {:a {:y 2}} (node-props db "a")))
      (is (db-valid? db))))

  (testing "adjacent update-node ops are not pre-merged across map/scalar replacement"
    (let [db (apply-ops [(create-op "a" :block)
                         (update-op "a" {:a {:x 0}})
                         (update-op "a" {:a 1})])]
      (is (= {:a 1} (node-props db "a")))
      (is (db-valid? db)))))

(deftest test-idempotent-place
  (testing "placing node at same position is idempotent"
    (let [db1 (apply-ops [(create-op "a" :div)
                          (place-op "a" :doc :first)])
          db2 (apply-ops db1 [(place-op "a" :doc :first)])]
      (is (= db1 db2) "DB unchanged by redundant place")
      (is (db-valid? db2)))))

(deftest test-reject-missing-node
  (testing "placing non-existent node is rejected"
    (let [issues (apply-ops-expect-failure [(place-op "missing" :doc :first)])]
      (is (= :node-not-found (-> issues first :issue))))))

(deftest test-reject-missing-parent
  (testing "placing under non-existent parent is rejected"
    (let [db (apply-ops [(create-op "a" :div)])
          issues (apply-ops-expect-failure db [(place-op "a" "missing-parent" :first)])]
      (is (= :parent-not-found (-> issues first :issue))))))

(deftest test-reject-self-cycle
  (testing "placing node under itself is rejected"
    (let [db (apply-ops [(create-op "a" :div)])
          issues (apply-ops-expect-failure db [(place-op "a" "a" :first)])]
      (is (= :cycle-detected (-> issues first :issue))))))

(deftest test-reject-descendant-cycle
  (testing "placing node under its descendant is rejected"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" "a" :first)])
          issues (apply-ops-expect-failure db [(place-op "a" "b" :first)])]
      (is (= :cycle-detected (-> issues first :issue))))))

(deftest test-reject-invalid-anchor
  (testing "anchor node must be sibling under same parent"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (place-op "a" :doc :first)])
          issues (apply-ops-expect-failure db [(place-op "b" :trash {:before "a"})])]
      (is (= :anchor-not-sibling (-> issues first :issue))))))

(deftest test-build-tree
  (testing "can build multi-level tree structure"
    (let [db (apply-ops [(create-op "root" :div)
                         (create-op "header" :header)
                         (create-op "content" :div)
                         (place-op "root" :doc :first)
                         (place-op "header" "root" :first)
                         (place-op "content" "root" :last)])]
      (is (node-exists? db "root"))
      (is (node-exists? db "header"))
      (is (node-exists? db "content"))
      (is (= ["root"] (children-of db :doc)))
      (is (= ["header" "content"] (children-of db "root")))
      (is (= "root" (parent-of db "header")))
      (is (= "root" (parent-of db "content")))
      (is (db-valid? db)))))

(deftest test-move-subtree
  (testing "moving node preserves its children"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (create-op "c" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" "a" :first)
                         (place-op "c" :doc :last)
                         (place-op "a" "c" :first)])]
      (is (= ["c"] (children-of db :doc)))
      (is (= ["a"] (children-of db "c")))
      (is (= ["b"] (children-of db "a")))
      (is (= "c" (parent-of db "a")))
      (is (= "a" (parent-of db "b")))
      (is (db-valid? db)))))

(deftest test-complex-sequence
  (testing "complex operation sequence produces correct final state"
    (let [db (apply-ops [(create-op "a" :div)
                         (place-op "a" :doc :first)
                         (update-op "a" {:class "container"})
                         (create-op "b" :span)
                         (place-op "b" "a" :first)
                         (update-op "b" {:text "Hello"})
                         (update-op "a" {:id "main"})
                         (create-op "c" :p)
                         (place-op "c" "a" :last)])]
      (is (= {:class "container" :id "main"} (node-props db "a")))
      (is (= {:text "Hello"} (node-props db "b")))
      (is (= ["b" "c"] (children-of db "a")))
      (is (db-valid? db)))))

(deftest test-invariant-parent-child-bidirectional
  (testing "parent-of and children-by-parent stay in sync"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" "a" :first)])]
      (is (= "a" (parent-of db "b")))
      (is (= ["b"] (children-of db "a")))
      (is (db-valid? db)))))

(deftest test-invariant-transactional-failure
  (testing "failed public transaction leaves DB unchanged and applies no ops"
    (let [db (apply-ops [(create-op "a" :div)])
          result (tx/interpret db [(update-op "a" {:x 1})
                                   (place-op "missing" :doc :first)
                                   (update-op "a" {:y 2})])]
      (is (seq (:issues result)))
      (is (= db (:db result)) "Public DB unchanged on validation failure")
      (is (= [] (:ops result)) "No materialized ops are public on validation failure")
      (is (= 0 (-> result :trace first :num-applied)))
      (is (= [(update-op "a" {:x 1 :updated-at 0})]
             (-> result :trace first :validated-prefix-ops))
          "Validated prefix is trace-only debugging context"))))

(deftest test-invariant-no-orphans
  (testing "all nodes except roots have parents"
    (let [db (apply-ops [(create-op "a" :div)
                         (create-op "b" :div)
                         (create-op "c" :div)
                         (place-op "a" :doc :first)
                         (place-op "b" "a" :first)
                         (place-op "c" "a" :last)])
          all-nodes (keys (:nodes db))
          ;; Roots like "session" exist in :nodes but not in :parent-of
          ;; Node IDs are strings, but roots is now a vector of keywords
          roots-set (set (:roots db))
          is-root? (fn [node-id] (contains? roots-set (keyword node-id)))
          placed-nodes (remove is-root? all-nodes)
          nodes-with-parents (keys (get-in db [:derived :parent-of]))]
      (is (= (set placed-nodes) (set nodes-with-parents)))
      (is (db-valid? db)))))

;; ── Timestamp Preservation Tests ──────────────────────────────────────────────

(deftest test-create-auto-timestamps
  (testing "create-node auto-adds created-at and updated-at"
    (let [db (:db (tx/interpret (db/empty-db)
                                [(create-op "a" :block)]
                                {:tx/now-ms 42}))
          props (node-props-all db "a")]
      (is (= 42 (:created-at props)) "created-at should use explicit materialization time")
      (is (= 42 (:updated-at props)) "updated-at should use explicit materialization time")
      (is (= (:created-at props) (:updated-at props)) "both should be equal on creation"))))

(deftest test-create-preserves-existing-timestamps
  (testing "create-node preserves timestamps from props (e.g., file import)"
    (let [old-time 1609459200000  ; Jan 1, 2021
          db (apply-ops [{:op :create-node
                          :id "a"
                          :type :page
                          :props {:title "Test"
                                  :created-at old-time
                                  :updated-at old-time}}])
          props (node-props-all db "a")]
      (is (= old-time (:created-at props)) "created-at should be preserved")
      (is (= old-time (:updated-at props)) "updated-at should be preserved"))))

(deftest test-update-refreshes-updated-at
  (testing "update-node refreshes updated-at but not created-at"
    (let [old-time 1609459200000
          db1 (apply-ops [{:op :create-node
                           :id "a"
                           :type :block
                           :props {:text "hi"
                                   :created-at old-time
                                   :updated-at old-time}}])
          db2 (:db (tx/interpret db1
                                  [(update-op "a" {:text "hello"})]
                                  {:tx/now-ms (inc old-time)}))
          props (node-props-all db2 "a")]
      (is (= old-time (:created-at props)) "created-at unchanged")
      (is (= (inc old-time) (:updated-at props)) "updated-at should use explicit materialization time"))))

;; ── dry-run Tests ───────────────────────────────────────────────────────────

(deftest test-dry-run-basic
  (testing "dry-run normalizes, validates, and applies ops without derive-indexes"
    (let [db (db/empty-db)
          result (tx/dry-run db [(create-op "a" :block)
                                 (place-op "a" :doc :last)])]
      (is (map? result))
      (is (empty? (:issues result)) "No issues for valid ops")
      (is (contains? (:nodes (:db result)) "a") "Node created in result db")
      ;; dry-run does NOT recompute derived indexes
      (is (nil? (get-in (:db result) [:derived :parent-of "a"]))
          "Derived indexes not recomputed by dry-run")
      (is (= 2 (count (:ops result))) "Returns normalized ops"))))

(deftest test-dry-run-validation-failure
  (testing "dry-run returns issues for invalid ops"
    (let [db (db/empty-db)
          result (tx/dry-run db [(place-op "nonexistent" :doc :last)])]
      (is (seq (:issues result)) "Should have validation issues")
      (is (= db (:db result)) "DB unchanged on validation failure"))))

(deftest test-dry-run-matches-interpret
  (testing "dry-run + derive-indexes produces same tree structure as interpret"
    (let [db (db/empty-db)
          ops [(create-op "x" :block)
               (place-op "x" :doc :last)
               (update-op "x" {:text "hello"})]
          dry-result (tx/dry-run db ops)
          interpret-result (tx/interpret db ops)
          derived-db (db/derive-indexes (:db dry-result))
          ;; Compare structure, ignoring timestamps (which differ by ~1ms)
          strip-ts (fn [nodes]
                     (into {} (map (fn [[k v]]
                                    [k (update v :props dissoc :created-at :updated-at)])
                                   nodes)))]
      (is (= (strip-ts (:nodes derived-db))
             (strip-ts (:nodes (:db interpret-result))))
          "Same nodes after derivation (ignoring timestamps)")
      (is (= (:children-by-parent derived-db)
             (:children-by-parent (:db interpret-result)))
          "Same tree structure after derivation")
      (is (= (:derived derived-db)
             (:derived (:db interpret-result)))
          "Same derived indexes after derivation"))))
