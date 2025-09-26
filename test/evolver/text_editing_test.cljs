(ns evolver.text-editing-test
  (:require [cljs.test :refer [deftest is testing]]
            [evolver.kernel :as K]
            [evolver.commands :as commands]))

(def test-db
  {:nodes {"root" {:type :page}
           "p1" {:type :p :props {:text "First paragraph"}}
           "p2" {:type :p :props {:text "Second paragraph"}}
           "p3" {:type :p :props {:text "Third paragraph"}}
           "div1" {:type :div :props {:text "Nested div"}}
           "p4" {:type :p :props {:text "Fourth paragraph"}}}
   :children-by-parent {"root" ["p1" "p2" "p3"]
                        "p1" []
                        "p2" ["div1"]
                        "p3" ["p4"]
                        "div1" []
                        "p4" []}
   :parent-by-child {"p1" "root"
                     "p2" "root"
                     "p3" "root"
                     "div1" "p2"
                     "p4" "p3"}
   :view {:collapsed #{}}})

(defn mock-pos [id]
  (let [parent (get-in test-db [:parent-by-child id])
        children-of-parent (get-in test-db [:children-by-parent parent] [])
        index (.indexOf children-of-parent id)]
    (when (and parent (>= index 0))
      {:parent parent
       :index index
       :children children-of-parent})))

(deftest test-enter-new-block
  (testing "ENTER at end of block creates new sibling"
    (let [context {:cursor "p1" :pos mock-pos :cursor-position 15 :block-content "First paragraph"}
          cmd ((commands/intent->command :enter-new-block) context)]
      (is (= (:op cmd) :insert))
       (is (= (:parent-id cmd) "root"))
      (is (= (:position cmd) 1))
      (is (= (get-in cmd [:node-data :props :text]) ""))))

  (testing "ENTER in middle of block splits content"
    (let [context {:cursor "p1" :pos mock-pos :cursor-position 6 :block-content "First paragraph"}
          cmd ((commands/intent->command :enter-new-block) context)]
      (is (= (:op cmd) :transaction))
      (is (= 2 (count (:commands cmd))))
      (let [[patch-cmd insert-cmd] (:commands cmd)]
        (is (= (:op patch-cmd) :patch))
        (is (= (get-in patch-cmd [:updates :props :text]) "First "))
        (is (= (:op insert-cmd) :insert))
        (is (= (get-in insert-cmd [:node-data :props :text]) "paragraph"))))))

(deftest test-line-break
  (testing "SHIFT+ENTER adds line break within block"
    (let [context {:cursor "p1" :cursor-position 6 :block-content "First paragraph"}
          cmd ((commands/intent->command :enter-line-break) context)]
      (is (= (:op cmd) :patch))
      (is (= (:node-id cmd) "p1"))
      (is (= (get-in cmd [:updates :props :text]) "First \nparagraph")))))

(deftest test-backspace-merge
  (testing "Backspace at start merges with previous block"
    (let [context {:cursor "p2" :pos mock-pos :db test-db :cursor-position 0}
          cmd ((commands/intent->command :backspace-merge-up) context)]
      (is (= (:op cmd) :transaction))
      (let [commands (:commands cmd)]
        (is (some #(= (:op %) :patch) commands))
        (is (some #(= (:op %) :move) commands))
        (is (some #(= (:op %) :delete) commands))))))

(deftest test-delete-merge
  (testing "Delete at end merges with next block"
    (let [context {:cursor "p1" :pos mock-pos :db test-db
                   :cursor-position 15 :block-content "First paragraph"}
          cmd ((commands/intent->command :delete-merge-down) context)]
      (is (= (:op cmd) :transaction))
      (let [commands (:commands cmd)]
        (is (some #(= (:op %) :patch) commands))
        (is (some #(= (:op %) :move) commands))
        (is (some #(= (:op %) :delete) commands))))))

(deftest test-navigation-still-works
  (testing "Navigation functions still work correctly"
    (is (= (K/get-next test-db "p1") "p2"))
    (is (= (K/get-next test-db "p2") "div1"))
    (is (= (K/get-prev test-db "p2") "p1"))
    (is (= (K/get-prev test-db "div1") "p2"))))