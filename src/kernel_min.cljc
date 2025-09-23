(ns kernel-min
  (:require [datascript.core :as d]
            [clojure.test :refer [deftest is run-tests]]))

;; ---------- Schema (make order cheap to mutate) ----------
(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :pos {:db/cardinality :db.cardinality/one :db/index true}})

;; ---------- Core helpers ----------
(defn e [db id]
  (d/entity db [:id id]))

(defn children-ids [db parent-id]
  (->> (d/q '[:find [?id ...]
              :in $ ?parent
              :where [?parent-e :id ?parent]
              [?e :parent ?parent-e]
              [?e :id ?id]]
            db parent-id)
       (sort-by #(:pos (e db %)))))

;; ---------- Position helpers ----------
(defn splice [v idx x]
  (let [i (min (max 0 (or idx (count v))) (count v))]
    (into (subvec v 0 i) (cons x (subvec v i)))))

(defn target [db {:keys [parent sibling rel idx] :as pos}]
  (let [rel-fns {:first (fn [_] 0)
                 :last (fn [child-ids] (count child-ids))
                 :before (fn [child-ids] (let [j (.indexOf child-ids sibling)] (if (neg? j) (count child-ids) j)))
                 :after (fn [child-ids] (let [j (.indexOf child-ids sibling)] (if (neg? j) (count child-ids) (inc j))))
                 nil (fn [child-ids] (min (or idx (count child-ids)) (count child-ids)))}
        p (or parent (when sibling (:id (:parent (e db sibling))))
              (throw (ex-info "No parent specified" {:position pos})))
        child-ids (children-ids db p)]
    [p ((or (rel-fns rel) (rel-fns nil)) child-ids)]))

;; ---------- Tree operations ----------
(def cid (atom 0))

(defn insert! [conn entity pos]
  (let [db @conn
        {:keys [id]} entity
        id (or id (str "auto-" (swap! cid inc)))
        entity (assoc entity :id id)
        [p i] (target db pos)
        temp-entity-id (- (swap! cid inc))]
    (d/transact! conn
                 (cond-> [[:db/add temp-entity-id :id id]
                          [:db/add temp-entity-id :parent [:id p]]]
                   true (into (for [[k v] (dissoc entity :id :children)]
                                [:db/add [:id id] k v]))))
    (let [siblings (children-ids @conn p)
          reordered (splice (vec (remove #{id} siblings)) i id)]
      (d/transact! conn (map-indexed (fn [idx id] [:db/add [:id id] :pos idx]) reordered)))))

(defn move! [conn id pos]
  (let [db @conn
        descendant-ids (set (tree-seq #(seq (children-ids db %)) #(children-ids db %) id))
        [p i] (target db pos)]
    (when (contains? descendant-ids p)
      (throw (ex-info "Cycle detected" {:entity id :target p})))
    (let [old-parent-id (:id (:parent (e db id)))
          same-parent? (= old-parent-id p)
          old-siblings (children-ids db old-parent-id)
          new-siblings (if same-parent? old-siblings (children-ids db p))
          final-old (if same-parent? [] (vec (remove #{id} old-siblings)))
          final-new (-> (vec (remove #{id} new-siblings)) (splice i id))]
      (d/transact! conn
                   (cond-> []
                     (not same-parent?) (conj [:db/add [:id id] :parent [:id p]])
                     (not same-parent?) (into (map-indexed (fn [idx id] [:db/add [:id id] :pos idx]) final-old))
                     true (into (map-indexed (fn [idx id] [:db/add [:id id] :pos idx]) final-new)))))))

(defn delete! [conn entity-id]
  (let [db @conn
        parent-id (:id (:parent (e db entity-id)))
        siblings (children-ids db parent-id)]
    (d/transact! conn [[:db.fn/retractEntity [:id entity-id]]])
    (d/transact! conn (map-indexed (fn [idx id] [:db/add [:id id] :pos idx]) (vec (remove #{entity-id} siblings))))))

(defn update! [conn entity-id attrs]
  (let [tx (for [[k v] attrs]
             [:db/add [:id entity-id] k v])]
    (d/transact! conn tx)))

;; ---------- Test fixtures ----------
(defn- tree-fixture []
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (insert! conn {:id "a"} {:parent "root"})
    (insert! conn {:id "b"} {:parent "root"})
    (insert! conn {:id "c"} {:parent "a"})
    conn))

(defn- tree-structure [conn & paths]
  (let [db @conn]
    (into {}
          (for [path paths]
            [path (map :id (:children (e db (last path))))]))))

;; ---------- Tests ----------
(deftest basic-insert-test
  (let [conn (tree-fixture)]
    (is (= '("a" "b") (children-ids @conn "root")))
    (is (= '("c") (children-ids @conn "a")))))

(deftest position-resolution-test
  (let [conn (tree-fixture)]
    ; Test various position specifications
    (insert! conn {:id "d"} {:parent "root" :idx 1})
    (is (= '("a" "d" "b") (children-ids @conn "root")))

    (insert! conn {:id "e"} {:parent "root" :rel :first})
    (is (= '("e" "a" "d" "b") (children-ids @conn "root")))

    (insert! conn {:id "f"} {:parent "root" :rel :last})
    (is (= '("e" "a" "d" "b" "f") (children-ids @conn "root")))

    (insert! conn {:id "g"} {:sibling "d" :rel :before})
    (is (= '("e" "a" "g" "d" "b" "f") (children-ids @conn "root")))

    (insert! conn {:id "h"} {:sibling "d" :rel :after})
    (is (= '("e" "a" "g" "d" "h" "b" "f") (children-ids @conn "root")))))

(deftest tree-operations-test
  (let [conn (tree-fixture)]
    ; Test move operations
    (move! conn "c" {:parent "root"})
    (is (= '("a" "b" "c") (children-ids @conn "root")))
    (is (= '() (children-ids @conn "a")))

    ; Test delete operations
    (delete! conn "b")
    (is (= '("a" "c") (children-ids @conn "root")))))

(deftest cycle-detection-test
  (let [conn (tree-fixture)]
    ; Try to create a cycle: make "root" a child of "a"
    (is (thrown? Exception (move! conn "root" {:parent "a"})))))

(deftest splice-test
  (is (= [1 2 99 3 4] (splice [1 2 3 4] 2 99)))
  (is (= [99 1 2 3 4] (splice [1 2 3 4] 0 99)))
  (is (= [1 2 3 4 99] (splice [1 2 3 4] 10 99)))
  (is (= [1 2 3 4 99] (splice [1 2 3 4] nil 99))))

(deftest entity-lookup-test
  (let [conn (tree-fixture)]
    (is (= "root" (:id (e @conn "root"))))
    (is (= "a" (:id (e @conn "a"))))
    (is (nil? (e @conn "nonexistent")))))

(deftest update-test
  (let [conn (tree-fixture)]
    (update! conn "a" {:name "updated-a" :value 42})
    (let [entity (e @conn "a")]
      (is (= "updated-a" (:name entity)))
      (is (= 42 (:value entity))))))

(deftest auto-id-generation-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (insert! conn {:name "auto-node"} {:parent "root"})
    (let [children (children-ids @conn "root")]
      (is (= 1 (count children)))
      (is (.startsWith (first children) "auto-")))))

(deftest threaded-operations-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (doto conn
      (insert! {:id "a"} {:parent "root"})
      (insert! {:id "b"} {:parent "root"})
      (insert! {:id "c"} {:parent "a"}))
    (is (= '("a" "b") (children-ids @conn "root")))
    (is (= '("c") (children-ids @conn "a")))

    ; Test threaded move and delete
    (doto conn
      (move! "c" {:parent "root"}))
    (is (= '("a" "b" "c") (children-ids @conn "root")))
    (is (= '() (children-ids @conn "a")))

    (doto conn
      (delete! "b"))
    (is (= '("a" "c") (children-ids @conn "root")))))

(deftest nested-tree-operations-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    ; Create a deeply nested tree
    (insert! conn {:id "level1"} {:parent "root"})
    (insert! conn {:id "level2"} {:parent "level1"})
    (insert! conn {:id "level3"} {:parent "level2"})
    (insert! conn {:id "level4"} {:parent "level3"})

    ; Verify the nested structure
    (is (= '("level1") (children-ids @conn "root")))
    (is (= '("level2") (children-ids @conn "level1")))
    (is (= '("level3") (children-ids @conn "level2")))
    (is (= '("level4") (children-ids @conn "level3")))

    ; Test moving a deep node
    (move! conn "level4" {:parent "root"})
    (is (= '("level1" "level4") (children-ids @conn "root")))
    (is (= '() (children-ids @conn "level3")))

    ; Test cycle detection in nested tree
    (is (thrown? Exception (move! conn "level1" {:parent "level2"})))))

(deftest position-edge-cases-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (insert! conn {:id "a"} {:parent "root"})

    ; Test inserting before non-existent sibling (should throw)
    (is (thrown? Exception (insert! conn {:id "b"} {:sibling "nonexistent" :rel :before})))

    ; Test inserting after non-existent sibling (should throw)
    (is (thrown? Exception (insert! conn {:id "c"} {:sibling "nonexistent" :rel :after})))

    ; Test moving to invalid position (should not throw, just append)
    (insert! conn {:id "d"} {:parent "root" :idx 100})
    (is (= '("a" "d") (children-ids @conn "root")))))

(deftest api-composition-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])

    ; Test composing operations
    (insert! conn {:id "composed-node" :type "test"} {:parent "root"})

    (is (= '("composed-node") (children-ids @conn "root")))

    ; Test using e function in composition
    (let [node-id (->> (children-ids @conn "root") first)]
      (is (= "composed-node" (:id (e @conn node-id)))))))

(run-tests)