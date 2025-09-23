(ns kernel
  (:require [datascript.core :as d]
            [fractional-ordering :as fo]
            [clojure.test :refer [deftest is run-tests]])
  )

;; Based on the agent.md learnings, we avoid :db/isComponent since it causes issues
;; in DataScript. We'll implement manual cascade deletion instead.
(def schema {:id {:db/unique :db.unique/identity}
             :parent {:db/valueType :db.type/ref}
             :order {:db/index true}}) ; String for lexicographical sorting

(defn put!
  "Creates or moves an entity to a position."
  [conn entity-map position-spec]
  (let [db @conn
        parent-id (if (#{:first :last} (:rel position-spec))
                    (:target position-spec)
                    (:id (:parent (d/entity db [:id (:target position-spec)]))))
        parent-ref [:id parent-id]
        order (fo/calculate-order db parent-ref position-spec)
        entity-id (or (:id entity-map) (str (random-uuid)))
        tx-data [(-> entity-map
                     (dissoc :children)
                     (assoc :id entity-id
                            :parent parent-ref
                            :order order))]]
    (d/transact! conn tx-data)))

(defn patch!
  "Partially update entity attributes."
  [conn entity-id attr-map]
  (let [tx-data (mapv (fn [[k v]] [:db/add [:id entity-id] k v]) attr-map)]
    (d/transact! conn tx-data)))

;; Manual cascade deletion based on agent.md learnings
(defn- collect-descendant-ids [db parent-id]
  (let [child-ids (d/q '[:find [?cid ...]
                         :in $ ?pid
                         :where
                         [?p :id ?pid]
                         [?c :parent ?p]
                         [?c :id ?cid]]
                       db parent-id)]
    (concat child-ids (mapcat #(collect-descendant-ids db %) child-ids))))

(defn delete!
  "Deletes an entity and ALL descendants using manual cascade deletion."
  [conn entity-id]
  (let [db @conn
        all-to-delete (cons entity-id (collect-descendant-ids db entity-id))
        tx-data (mapv #(vector :db/retractEntity [:id %]) all-to-delete)]
    (d/transact! conn tx-data)))

(defn children-ids
  "Returns child IDs in order."
  [db parent-id]
  (->> (d/q '[:find ?id ?order :in $ ?pid :where
              [?p :id ?pid] [?c :parent ?p]
              [?c :id ?id] [?c :order ?order]]
            db parent-id)
       (sort-by second)
       (mapv first)))

;; ## 4. TESTS ##
(deftest revised-kernel-test
  (let [conn (d/create-conn schema)]
    ;; Create root entity first
    (d/transact! conn [{:id "root" :name "Root"}])

    ;; Seed with a root and two children
    (put! conn {:id "child1" :name "A"} {:rel :first :target "root"})
    (put! conn {:id "child2" :name "B"} {:rel :last :target "root"})
    (is (= ["child1" "child2"] (children-ids @conn "root")) "Initial order")

    ;; Insert between
    (put! conn {:id "child3" :name "C"} {:rel :after :target "child1"})
    (is (= ["child1" "child3" "child2"] (children-ids @conn "root")) "Insert after")

    ;; Insert at the beginning
    (put! conn {:id "child0" :name "Z"} {:rel :first :target "root"})
    (is (= ["child0" "child1" "child3" "child2"] (children-ids @conn "root")) "Insert first")

    ;; Deletion with cascade
    (put! conn {:id "grandchild1"} {:rel :first :target "child1"})
    (is (= ["grandchild1"] (children-ids @conn "child1")) "Grandchild created")
    (delete! conn "child1")
    (is (= ["child0" "child3" "child2"] (children-ids @conn "root")) "Parent deleted")
    (is (nil? (d/entity @conn [:id "child1"])) "Entity is gone")
    (is (nil? (d/entity @conn [:id "grandchild1"])) "Component grandchild is also gone")))

(deftest subtree-operations-test
  (let [conn (d/create-conn schema)]
    ;; Create root entity first
    (d/transact! conn [{:id "root" :name "Root"}])

    ;; Test creating nested structure: root -> branch1 -> leaf1, leaf2
    (put! conn {:id "branch1" :name "Branch 1"} {:rel :first :target "root"})
    (put! conn {:id "leaf1" :name "Leaf 1"} {:rel :first :target "branch1"})
    (put! conn {:id "leaf2" :name "Leaf 2"} {:rel :last :target "branch1"})

    ;; Create another branch: root -> branch2 -> leaf3
    (put! conn {:id "branch2" :name "Branch 2"} {:rel :last :target "root"})
    (put! conn {:id "leaf3" :name "Leaf 3"} {:rel :first :target "branch2"})

    ;; Verify initial structure
    (is (= ["branch1" "branch2"] (children-ids @conn "root")) "Root has two branches")
    (is (= ["leaf1" "leaf2"] (children-ids @conn "branch1")) "Branch1 has two leaves")
    (is (= ["leaf3"] (children-ids @conn "branch2")) "Branch2 has one leaf")

    ;; Test moving subtree: move branch1 (with children) under branch2
    (put! conn {:id "branch1"} {:rel :first :target "branch2"})
    (is (= ["branch2"] (children-ids @conn "root")) "Root now has only branch2")
    (is (= ["leaf3" "branch1"] (children-ids @conn "branch2")) "Branch2 now contains leaf3 and branch1")
    (is (= ["leaf1" "leaf2"] (children-ids @conn "branch1")) "Branch1 still has its children after move")

    ;; Verify parent relationships are correct after move
    (is (= "branch1" (:id (:parent (d/entity @conn [:id "leaf1"])))) "leaf1 parent is still branch1")
    (is (= "branch1" (:id (:parent (d/entity @conn [:id "leaf2"])))) "leaf2 parent is still branch1")
    (is (= "branch2" (:id (:parent (d/entity @conn [:id "branch1"])))) "branch1 parent is now branch2")

    ;; Test cascade deletion: delete branch1 should cascade to leaf1 and leaf2
    (delete! conn "branch1")
    (is (= ["leaf3"] (children-ids @conn "branch2")) "Only leaf3 remains under branch2")
    (is (nil? (d/entity @conn [:id "branch1"])) "branch1 is deleted")
    (is (nil? (d/entity @conn [:id "leaf1"])) "leaf1 is cascade deleted")
    (is (nil? (d/entity @conn [:id "leaf2"])) "leaf2 is cascade deleted")
    (is (not (nil? (d/entity @conn [:id "leaf3"]))) "leaf3 still exists")))

(deftest deep-nesting-test
  (let [conn (d/create-conn schema)]
    ;; Create root entity first
    (d/transact! conn [{:id "root" :name "Root"}])

    ;; Create 3-level deep tree: root -> A -> A1 -> A1a, A1b
    (put! conn {:id "A" :name "Node A"} {:rel :first :target "root"})
    (put! conn {:id "A1" :name "Node A1"} {:rel :first :target "A"})
    (put! conn {:id "A1a" :name "Node A1a"} {:rel :first :target "A1"})
    (put! conn {:id "A1b" :name "Node A1b"} {:rel :after :target "A1a"})

    ;; Add sibling to A1
    (put! conn {:id "A2" :name "Node A2"} {:rel :after :target "A1"})

    ;; Verify deep structure
    (is (= ["A"] (children-ids @conn "root")) "Root has A")
    (is (= ["A1" "A2"] (children-ids @conn "A")) "A has A1 and A2")
    (is (= ["A1a" "A1b"] (children-ids @conn "A1")) "A1 has A1a and A1b")
    (is (= [] (children-ids @conn "A2")) "A2 has no children initially")

    ;; Test moving node between siblings: move A1b from A1 to A2
    (put! conn {:id "A1b"} {:rel :first :target "A2"})
    (is (= ["A1a"] (children-ids @conn "A1")) "A1 now has only A1a")
    (is (= ["A1b"] (children-ids @conn "A2")) "A2 now has A1b")

    ;; Test cascade deletion on deep structure: delete A1 should cascade to A1a
    (delete! conn "A1")
    (is (= ["A2"] (children-ids @conn "A")) "A now has only A2")
    (is (nil? (d/entity @conn [:id "A1"])) "A1 is deleted")
    (is (nil? (d/entity @conn [:id "A1a"])) "A1a is cascade deleted")
    (is (not (nil? (d/entity @conn [:id "A1b"]))) "A1b still exists under A2")))

(run-tests)