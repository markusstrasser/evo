(ns kernel.projection-order-test
  (:require [clojure.test :refer [deftest is testing]]
            [kernel.db :as db]
            [kernel.journals :as journals]
            [kernel.query :as q]
            [kernel.transaction :as tx]))

(defn create [id type props]
  {:op :create-node :id id :type type :props props})

(defn block [id text]
  (create id :block {:text text}))

(defn page [id title]
  (create id :page {:title title}))

(defn place [id under at]
  {:op :place :id id :under under :at at})

(def canonical-db
  (:db (tx/interpret (db/empty-db)
                     [(block "A" "A")
                      (block "A1" "A1")
                      (block "A2" "A2")
                      (block "B" "B")
                      (block "B1" "B1")
                      (block "C" "C")
                      (block "T" "T")
                      (block "T1" "T1")
                      (place "A" :doc :last)
                      (place "A1" "A" :last)
                      (place "A2" "A" :last)
                      (place "B" :doc :last)
                      (place "B1" "B" :last)
                      (place "C" :doc :last)
                      (place "T" :trash :last)
                      (place "T1" "T" :last)])))

(def base-session
  {:selection {:nodes #{} :focus nil :anchor nil}
   :ui {:folded #{}
        :zoom-root nil
        :current-page nil
        :editing-block-id nil}})

(deftest canonical-tree-projection-distinctions
  (testing "source sibling order is local to children-by-parent"
    (is (= ["A" "B" "C"] (q/children canonical-db :doc)))
    (is (= ["A1" "A2"] (q/children canonical-db "A"))))

  (testing "doc traversal ignores trash"
    (is (= [:doc "A" "A1" "A2" "B" "B1" "C"]
           (mapv (get-in canonical-db [:derived :doc/id-by-pre])
                 (range 7)))))

  (testing "visible order respects folds"
    (is (= ["A" "A1" "A2" "B" "B1" "C"]
           (q/visible-blocks canonical-db base-session)))
    (is (= ["A" "B" "B1" "C"]
           (q/visible-blocks canonical-db
                             (assoc-in base-session [:ui :folded] #{"A"})))))

  (testing "visible order respects zoom"
    (is (= ["A1" "A2"]
           (q/visible-blocks canonical-db
                             (assoc-in base-session [:ui :zoom-root] "A")))))

  (testing "selectable order filters pages but keeps blocks"
    (let [db (:db (tx/interpret canonical-db
                                 [(page "P" "Page")
                                  (block "P1" "P1")
                                  (place "P" :doc :last)
                                  (place "P1" "P" :last)]))]
      (is (= ["P1"]
             (q/selectable-visible-blocks
              db
              (assoc-in base-session [:ui :current-page] "P")))))))

(deftest journal-projection-is-semantic-order
  (let [db (:db (tx/interpret (db/empty-db)
                              [(page "j-old" "Apr 24th, 2026")
                               (page "j-new" "2026-04-26")
                               (page "notes" "Notes")
                               (block "old-a" "old")
                               (block "new-a" "new")
                               (block "new-child" "child")
                               (place "j-old" :doc :last)
                               (place "j-new" :doc :last)
                               (place "notes" :doc :last)
                               (place "old-a" "j-old" :last)
                               (place "new-a" "j-new" :last)
                               (place "new-child" "new-a" :last)]))]
    (is (= ["j-new" "j-old"]
           (mapv :id (journals/visible-journal-pages db {:today-iso "2026-04-26"}))))
    (is (= ["new-a" "new-child" "old-a"]
           (journals/journals-visible-blocks db base-session {:today-iso "2026-04-26"})))
    (is (= ["new-a" "old-a"]
           (journals/journals-visible-blocks
            db
            (assoc-in base-session [:ui :folded] #{"new-a"})
            {:today-iso "2026-04-26"})))))
