(ns plugins.folding-test
  "Tests for folding plugin: expand/collapse/zoom operations."
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.query :as q]
            [kernel.intent :as intent]
            [plugins.folding]  ;; Load to register intents
            [plugins.folding :as fold]))

;; ── Test Setup ────────────────────────────────────────────────────────────────

(defn setup-tree
  "Create a test tree structure:
     doc
     ├─ a 'Root'
     │  ├─ b 'Child 1'
     │  │  └─ c 'Grandchild'
     │  └─ d 'Child 2'
     └─ e 'Root 2'"
  []
  (:db
   (tx/interpret (db/empty-db)
                 [{:op :create-node :id "a" :type :block :props {:text "Root"}}
                  {:op :create-node :id "b" :type :block :props {:text "Child 1"}}
                  {:op :create-node :id "c" :type :block :props {:text "Grandchild"}}
                  {:op :create-node :id "d" :type :block :props {:text "Child 2"}}
                  {:op :create-node :id "e" :type :block :props {:text "Root 2"}}
                  {:op :place :id "a" :under :doc :at :last}
                  {:op :place :id "b" :under "a" :at :last}
                  {:op :place :id "c" :under "b" :at :last}
                  {:op :place :id "d" :under "a" :at :last}
                  {:op :place :id "e" :under :doc :at :last}])))

;; ── Query Tests ───────────────────────────────────────────────────────────────

(deftest folded-queries
  (let [db (setup-tree)]
    (testing "initially nothing is folded"
      (is (= #{} (q/folded-set db)))
      (is (false? (q/folded? db "a")))
      (is (false? (q/folded? db "b"))))

    (testing "after folding a block"
      (let [db' (:db (tx/interpret db [{:op :update-node
                                        :id "session/ui"
                                        :props {:folded #{"a"}}}]))]
        (is (= #{"a"} (q/folded-set db')))
        (is (true? (q/folded? db' "a")))
        (is (false? (q/folded? db' "b")))))))

(deftest zoom-queries
  (let [db (setup-tree)]
    (testing "initially at root with no zoom"
      (is (= [] (q/zoom-stack db)))
      (is (nil? (q/zoom-root db)))
      (is (= 0 (q/zoom-level db)))
      (is (false? (q/in-zoom? db))))

    (testing "after zooming in once"
      (let [db' (:db (tx/interpret db [{:op :update-node
                                        :id "session/ui"
                                        :props {:zoom-stack [{:block-id :doc}]
                                               :zoom-root "a"}}]))]
        (is (= [{:block-id :doc}] (q/zoom-stack db')))
        (is (= "a" (q/zoom-root db')))
        (is (= 1 (q/zoom-level db')))
        (is (true? (q/in-zoom? db')))))))

;; ── Toggle Fold Tests ─────────────────────────────────────────────────────────

(deftest toggle-fold-test
  (let [db (setup-tree)]
    (testing "toggle fold on block with children collapses it"
      (let [{:keys [ops]} (intent/apply-intent db {:type :toggle-fold :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= #{"a"} (q/folded-set db')))
        (is (true? (q/folded? db' "a")))))

    (testing "toggle fold again expands it"
      (let [{:keys [ops]} (intent/apply-intent db {:type :toggle-fold :block-id "a"})
            db' (:db (tx/interpret db ops))
            {:keys [ops]} (intent/apply-intent db' {:type :toggle-fold :block-id "a"})
            db'' (:db (tx/interpret db' ops))]
        (is (= #{} (q/folded-set db'')))
        (is (false? (q/folded? db'' "a")))))

    (testing "toggle fold on leaf node does nothing"
      (let [{:keys [ops]} (intent/apply-intent db {:type :toggle-fold :block-id "e"})]
        (is (empty? ops))))))

;; ── Expand All Tests ──────────────────────────────────────────────────────────

(deftest expand-all-test
  (let [db (:db (tx/interpret (setup-tree)
                              [{:op :update-node
                                :id "session/ui"
                                :props {:folded #{"a" "b" "d"}}}]))]
    (testing "expand-all removes block and all descendants from folded set"
      (let [{:keys [ops]} (intent/apply-intent db {:type :expand-all :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= #{} (q/folded-set db')))
        (is (false? (q/folded? db' "a")))
        (is (false? (q/folded? db' "b")))
        (is (false? (q/folded? db' "d")))))

    (testing "expand-all on leaf does nothing"
      (let [{:keys [ops]} (intent/apply-intent db {:type :expand-all :block-id "c"})]
        (is (empty? ops))))))

;; ── Collapse Tests ────────────────────────────────────────────────────────────

(deftest collapse-test
  (let [db (setup-tree)]
    (testing "collapse adds block to folded set"
      (let [{:keys [ops]} (intent/apply-intent db {:type :collapse :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= #{"a"} (q/folded-set db')))
        (is (true? (q/folded? db' "a")))))

    (testing "collapse on already collapsed block is idempotent"
      (let [db' (:db (tx/interpret db [{:op :update-node
                                        :id "session/ui"
                                        :props {:folded #{"a"}}}]))
            {:keys [ops]} (intent/apply-intent db' {:type :collapse :block-id "a"})
            db'' (:db (tx/interpret db' ops))]
        (is (= #{"a"} (q/folded-set db'')))))

    (testing "collapse on leaf does nothing"
      (let [{:keys [ops]} (intent/apply-intent db {:type :collapse :block-id "c"})]
        (is (empty? ops))))))

;; ── Toggle All Folds Tests ────────────────────────────────────────────────────

(deftest toggle-all-folds-test
  (let [db (setup-tree)]
    (testing "toggle-all when nothing folded collapses top-level"
      (let [{:keys [ops]} (intent/apply-intent db {:type :toggle-all-folds :root-id :doc})
            db' (:db (tx/interpret db ops))]
        (is (= #{"a" "e"} (q/folded-set db')))
        (is (true? (q/folded? db' "a")))
        (is (true? (q/folded? db' "e")))
        (is (false? (q/folded? db' "b")))))

    (testing "toggle-all when some folded expands all"
      (let [db' (:db (tx/interpret db [{:op :update-node
                                        :id "session/ui"
                                        :props {:folded #{"a"}}}]))
            {:keys [ops]} (intent/apply-intent db' {:type :toggle-all-folds :root-id :doc})
            db'' (:db (tx/interpret db' ops))]
        (is (= #{} (q/folded-set db'')))))))

;; ── Zoom In Tests ─────────────────────────────────────────────────────────────

(deftest zoom-in-test
  (let [db (setup-tree)]
    (testing "zoom in pushes current root to stack"
      (let [{:keys [ops]} (intent/apply-intent db {:type :zoom-in :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= [{:block-id :doc}] (q/zoom-stack db')))
        (is (= "a" (q/zoom-root db')))
        (is (= 1 (q/zoom-level db')))))

    (testing "zoom in again pushes to stack"
      (let [{:keys [ops]} (intent/apply-intent db {:type :zoom-in :block-id "a"})
            db' (:db (tx/interpret db ops))
            {:keys [ops]} (intent/apply-intent db' {:type :zoom-in :block-id "b"})
            db'' (:db (tx/interpret db' ops))]
        (is (= [{:block-id :doc} {:block-id "a"}] (q/zoom-stack db'')))
        (is (= "b" (q/zoom-root db'')))
        (is (= 2 (q/zoom-level db'')))))

    (testing "zoom in on leaf does nothing"
      (let [{:keys [ops]} (intent/apply-intent db {:type :zoom-in :block-id "c"})]
        (is (empty? ops))))))

;; ── Zoom Out Tests ────────────────────────────────────────────────────────────

(deftest zoom-out-test
  (let [db (:db (tx/interpret (setup-tree)
                              [{:op :update-node
                                :id "session/ui"
                                :props {:zoom-stack [{:block-id :doc} {:block-id "a"}]
                                       :zoom-root "b"}}]))]
    (testing "zoom out pops from stack"
      (let [{:keys [ops]} (intent/apply-intent db {:type :zoom-out})
            db' (:db (tx/interpret db ops))]
        (is (= [{:block-id :doc}] (q/zoom-stack db')))
        (is (= "a" (q/zoom-root db')))
        (is (= 1 (q/zoom-level db')))))

    (testing "zoom out to root"
      (let [{:keys [ops]} (intent/apply-intent db {:type :zoom-out})
            db' (:db (tx/interpret db ops))
            {:keys [ops]} (intent/apply-intent db' {:type :zoom-out})
            db'' (:db (tx/interpret db' ops))]
        (is (= [] (q/zoom-stack db'')))
        (is (= :doc (q/zoom-root db'')))
        (is (= 0 (q/zoom-level db'')))))

    (testing "zoom out at root does nothing"
      (let [db-root (setup-tree)
            {:keys [ops]} (intent/apply-intent db-root {:type :zoom-out})]
        (is (empty? ops))))))

;; ── Zoom To Tests ─────────────────────────────────────────────────────────────

(deftest zoom-to-test
  (let [db (:db (tx/interpret (setup-tree)
                              [{:op :update-node
                                :id "session/ui"
                                :props {:zoom-stack [{:block-id :doc} {:block-id "a"} {:block-id "b"}]
                                       :zoom-root "c"}}]))]
    (testing "zoom to middle of stack"
      (let [{:keys [ops]} (intent/apply-intent db {:type :zoom-to :block-id "a"})
            db' (:db (tx/interpret db ops))]
        (is (= [{:block-id :doc} {:block-id "a"}] (q/zoom-stack db')))
        (is (= "a" (q/zoom-root db')))))

    (testing "zoom to non-existent block does nothing"
      (let [{:keys [ops]} (intent/apply-intent db {:type :zoom-to :block-id "nonexistent"})]
        (is (empty? ops))))))

;; ── Reset Zoom Tests ──────────────────────────────────────────────────────────

(deftest reset-zoom-test
  (let [db (:db (tx/interpret (setup-tree)
                              [{:op :update-node
                                :id "session/ui"
                                :props {:zoom-stack [{:block-id :doc} {:block-id "a"}]
                                       :zoom-root "b"}}]))]
    (testing "reset-zoom clears stack and root"
      (let [{:keys [ops]} (intent/apply-intent db {:type :reset-zoom})
            db' (:db (tx/interpret db ops))]
        (is (= [] (q/zoom-stack db')))
        (is (nil? (q/zoom-root db')))
        (is (= 0 (q/zoom-level db')))))))

;; ── Integration Tests ─────────────────────────────────────────────────────────

(deftest fold-and-zoom-integration
  (testing "fold state persists across zoom operations"
    (let [db (setup-tree)
          ;; Fold block a
          {:keys [ops]} (intent/apply-intent db {:type :collapse :block-id "a"})
          db' (:db (tx/interpret db ops))
          ;; Zoom into block a
          {:keys [ops]} (intent/apply-intent db' {:type :zoom-in :block-id "a"})
          db'' (:db (tx/interpret db' ops))]
      (is (true? (q/folded? db'' "a")))
      (is (= "a" (q/zoom-root db''))))))
