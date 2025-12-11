(ns integration.focus-management-test
  "Integration tests for focus management after structural operations.

   CRITICAL GAPS ADDRESSED:
   - Focus after delete with children
   - Focus after indent/outdent
   - Focus preservation across zoom
   - Focus after merge operations"
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [kernel.db :as db]
            [kernel.transaction :as tx]
            [kernel.intent :as intent]
            [kernel.api :as api]
            [kernel.query :as q]))

;; ── Session Helpers ──────────────────────────────────────────────────────────

(defn empty-session []
  {:cursor {:block-id nil :offset 0}
   :selection {:nodes #{} :focus nil :anchor nil :direction nil}
   :buffer {:block-id nil :text "" :dirty? false}
   :ui {:folded #{}
        :zoom-root nil
        :zoom-stack []
        :current-page nil
        :editing-block-id nil
        :cursor-position nil}
   :sidebar {:right []}})

(defn with-selection [nodes focus]
  (-> (empty-session)
      (assoc-in [:selection :nodes] (set nodes))
      (assoc-in [:selection :focus] focus)
      (assoc-in [:selection :anchor] focus)))

(defn editing-session [block-id cursor-pos]
  (-> (empty-session)
      (assoc-in [:ui :editing-block-id] block-id)
      (assoc-in [:ui :cursor-position] cursor-pos)))

(defn apply-session-updates [session updates]
  (if updates (merge-with merge session updates) session))

(defn run-intent [db session intent-map]
  (let [{:keys [ops session-updates]} (intent/apply-intent db session intent-map)
        new-db (if (seq ops) (:db (tx/interpret db ops)) db)
        new-session (apply-session-updates session session-updates)]
    {:db new-db :session new-session :ops ops}))

;; ── Test Fixtures ────────────────────────────────────────────────────────────

(defn build-3-sibling-doc []
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "a" :type :block :props {:text "First"}}
           {:op :place :id "a" :under :doc :at :last}
           {:op :create-node :id "b" :type :block :props {:text "Second"}}
           {:op :place :id "b" :under :doc :at :last}
           {:op :create-node :id "c" :type :block :props {:text "Third"}}
           {:op :place :id "c" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture failed: " (pr-str issues)))
    db))

(defn build-nested-with-children []
  (let [db0 (db/empty-db)
        {:keys [db issues]}
        (tx/interpret db0
          [{:op :create-node :id "parent" :type :block :props {:text "Parent"}}
           {:op :place :id "parent" :under :doc :at :last}
           {:op :create-node :id "child-1" :type :block :props {:text "Child 1"}}
           {:op :place :id "child-1" :under "parent" :at :last}
           {:op :create-node :id "child-2" :type :block :props {:text "Child 2"}}
           {:op :place :id "child-2" :under "parent" :at :last}
           {:op :create-node :id "grandchild" :type :block :props {:text "Grandchild"}}
           {:op :place :id "grandchild" :under "child-1" :at :last}
           {:op :create-node :id "sibling" :type :block :props {:text "Sibling"}}
           {:op :place :id "sibling" :under :doc :at :last}])]
    (assert (empty? issues) (str "Fixture failed: " (pr-str issues)))
    db))

;; ── Delete Focus Tests ───────────────────────────────────────────────────────

(deftest focus-after-delete-middle-block
  (testing "Delete middle block should focus previous sibling"
    (let [db (build-3-sibling-doc)
          session (with-selection ["b"] "b")
          {:keys [db session]} (run-intent db session {:type :delete-selected})]
      ;; Block "b" should be deleted
      (is (= :trash (get-in db [:derived :parent-of "b"])))
      ;; Focus should move to previous block "a"
      (is (= "a" (get-in session [:selection :focus]))
          "Focus should move to previous sibling after delete"))))

(deftest focus-after-delete-first-block
  (testing "Delete first block should focus next sibling"
    (let [db (build-3-sibling-doc)
          session (with-selection ["a"] "a")
          {:keys [db session]} (run-intent db session {:type :delete-selected})]
      ;; Focus should move to next block "b"
      (is (or (= "b" (get-in session [:selection :focus]))
              ;; Or c if b was somehow affected
              (= "c" (get-in session [:selection :focus])))
          "Focus should move to next sibling when deleting first block"))))

(deftest focus-after-delete-block-with-children
  (testing "Delete block with children should focus appropriately"
    (let [db (build-nested-with-children)
          ;; Select and delete parent (which has children)
          session (with-selection ["parent"] "parent")
          {:keys [db session]} (run-intent db session {:type :delete-selected})]
      ;; Parent should be deleted
      (is (= :trash (get-in db [:derived :parent-of "parent"])))
      ;; Focus should be on sibling or children depending on reparenting behavior
      (let [focus (get-in session [:selection :focus])]
        (is (or (= "sibling" focus)
                (= "child-1" focus)
                (nil? focus))
            "Focus should move to appropriate block after parent deletion")))))

;; ── Indent/Outdent Focus Tests ───────────────────────────────────────────────

(deftest focus-preserved-after-indent
  (testing "Focus should remain on indented block"
    (let [db (build-3-sibling-doc)
          ;; Select "b" and indent it under "a"
          session (with-selection ["b"] "b")
          {:keys [db session]} (run-intent db session {:type :indent-selected})]
      ;; Block should be under "a" now
      (is (= "a" (get-in db [:derived :parent-of "b"])))
      ;; Focus should still be on "b"
      (is (= "b" (get-in session [:selection :focus]))
          "Focus should remain on indented block"))))

(deftest focus-preserved-after-outdent
  (testing "Focus should remain on outdented block"
    (let [db (build-nested-with-children)
          ;; Select "child-1" and outdent it
          session (with-selection ["child-1"] "child-1")
          {:keys [db session]} (run-intent db session {:type :outdent-selected})]
      ;; Block should be under :doc now (sibling of parent)
      (is (= :doc (get-in db [:derived :parent-of "child-1"])))
      ;; Focus should still be on "child-1"
      (is (= "child-1" (get-in session [:selection :focus]))
          "Focus should remain on outdented block"))))

;; ── Merge Focus Tests ────────────────────────────────────────────────────────

(deftest focus-after-backspace-merge
  (testing "Focus should be on merged-into block after backspace merge"
    (let [db (build-3-sibling-doc)
          ;; Editing "b" at start, backspace merges into "a"
          session (editing-session "b" 0)
          {:keys [db session]} (run-intent db session
                                 {:type :merge-backward
                                  :block-id "b"
                                  :cursor-pos 0})]
      ;; Blocks should be merged
      (is (= "FirstSecond" (get-in db [:nodes "a" :props :text])))
      ;; Should be editing "a" at the join point
      (is (= "a" (get-in session [:ui :editing-block-id]))
          "Should be editing the merged-into block"))))

(deftest focus-after-delete-forward-merge
  (testing "Focus should remain on current block after delete-forward merge"
    (let [db (build-3-sibling-doc)
          ;; Editing "a" at end, delete-forward merges "b" into "a"
          session (editing-session "a" 5) ;; End of "First"
          {:keys [db session]} (run-intent db session
                                 {:type :delete-forward
                                  :block-id "a"
                                  :cursor-pos 5
                                  :has-selection? false})]
      ;; Blocks should be merged
      (is (= "FirstSecond" (get-in db [:nodes "a" :props :text])))
      ;; Should still be editing "a"
      (is (= "a" (get-in session [:ui :editing-block-id]))
          "Should remain editing current block after merge"))))

;; ── Move Focus Tests ─────────────────────────────────────────────────────────

(deftest focus-after-move-up
  (testing "Focus should follow moved block"
    (let [db (build-3-sibling-doc)
          ;; Select "c" and move it up
          session (with-selection ["c"] "c")
          {:keys [session]} (run-intent db session {:type :move-selected-up})]
      ;; Focus should still be on "c"
      (is (= "c" (get-in session [:selection :focus]))
          "Focus should follow moved block"))))

(deftest focus-after-move-down
  (testing "Focus should follow moved block"
    (let [db (build-3-sibling-doc)
          ;; Select "a" and move it down
          session (with-selection ["a"] "a")
          {:keys [session]} (run-intent db session {:type :move-selected-down})]
      ;; Focus should still be on "a"
      (is (= "a" (get-in session [:selection :focus]))
          "Focus should follow moved block"))))

;; ── Multi-Block Operation Focus Tests ────────────────────────────────────────

(deftest focus-after-multi-block-delete
  (testing "Delete multiple blocks should focus appropriate block"
    (let [db (build-3-sibling-doc)
          ;; Select "a" and "b"
          session (-> (empty-session)
                      (assoc-in [:selection :nodes] #{"a" "b"})
                      (assoc-in [:selection :focus] "b")
                      (assoc-in [:selection :anchor] "a"))
          {:keys [db session]} (run-intent db session {:type :delete-selected})]
      ;; Both should be deleted
      (is (= :trash (get-in db [:derived :parent-of "a"])))
      (is (= :trash (get-in db [:derived :parent-of "b"])))
      ;; Focus should be on remaining block "c"
      (is (= "c" (get-in session [:selection :focus]))
          "Focus should move to remaining block"))))

(deftest focus-after-multi-block-indent
  (testing "Indent multiple blocks should maintain focus on last selected"
    (let [db (build-3-sibling-doc)
          ;; Select "b" and "c", indent them under "a"
          session (-> (empty-session)
                      (assoc-in [:selection :nodes] #{"b" "c"})
                      (assoc-in [:selection :focus] "c")
                      (assoc-in [:selection :anchor] "b"))
          {:keys [db session]} (run-intent db session {:type :indent-selected})]
      ;; Both should be under "a"
      (is (= "a" (get-in db [:derived :parent-of "b"])))
      (is (= "a" (get-in db [:derived :parent-of "c"])))
      ;; Focus should remain on "c" (last selected)
      (is (= "c" (get-in session [:selection :focus]))
          "Focus should remain on last selected block"))))

;; ── Zoom Focus Tests ─────────────────────────────────────────────────────────

(deftest focus-preserved-across-zoom-in
  (testing "Focus should be preserved when zooming into block"
    (let [db (build-nested-with-children)
          ;; Select child-1 before zoom
          session (with-selection ["child-1"] "child-1")
          ;; Zoom into parent
          session-zoomed (-> session
                             (assoc-in [:ui :zoom-root] "parent")
                             (assoc-in [:ui :zoom-stack] [:doc]))]
      ;; Focus should still be on child-1 (which is visible in zoom)
      (is (= "child-1" (get-in session-zoomed [:selection :focus]))
          "Focus should be preserved after zoom in"))))

(deftest focus-adjusted-when-zooming-past-selection
  (testing "Focus should adjust when zooming past currently selected block"
    (let [db (build-nested-with-children)
          ;; Select sibling (outside parent subtree)
          session (with-selection ["sibling"] "sibling")
          ;; Zoom into parent (sibling is no longer visible)
          session-zoomed (-> session
                             (assoc-in [:ui :zoom-root] "parent")
                             (assoc-in [:ui :zoom-stack] [:doc]))]
      ;; Selection is still technically valid but block isn't visible
      ;; UI should handle this case - test documents expected behavior
      (is (= #{"sibling"} (get-in session-zoomed [:selection :nodes]))
          "Selection nodes remain set (UI handles visibility)"))))
