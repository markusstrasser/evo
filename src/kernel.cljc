(ns kernel
  (:require [datascript.core :as d]
            [fractional-ordering :as fo]
            [clojure.test :refer [deftest is run-tests]]))

;; ## 1. SCHEMA ##
(def schema {:id {:db/unique :db.unique/identity}
             :parent {:db/valueType :db.type/ref}
             :order {:db/index true}})

;; ## 2. CORE LOGIC - COMMAND MODEL ##
;; This implementation uses a data-driven command model.
;; All mutations are represented as data maps.
;; A single pure function, `command->tx`, interprets these commands.
;; A single impure function, `execute!`, applies the resulting transaction.

;; --- Private Helpers ---
(defn- collect-descendant-ids
  "Recursively finds all entity IDs in a subtree."
  [db parent-id]
  (let [child-ids (d/q '[:find [?cid ...]
                         :in $ ?pid
                         :where
                         [?p :id ?pid]
                         [?c :parent ?p]
                         [?c :id ?cid]]
                       db parent-id)]
    (concat child-ids (mapcat #(collect-descendant-ids db %) child-ids))))

;; --- Interpreter (Pure Function) ---
(defn command->tx
  "Takes a db value and a command map, returns transaction data. Pure."
  [db {:keys [op] :as command}]
  (case op
    :put
    (let [{:keys [entity position]} command
          parent-id (if (#{:first :last} (:rel position))
                      (:target position)
                      (:id (:parent (d/entity db [:id (:target position)]))))
          parent-ref [:id parent-id]
          order (fo/calculate-order db parent-ref position)
          entity-id (or (:id entity) (str (random-uuid)))]
      [(-> entity
           (dissoc :children)
           (assoc :id entity-id
                  :parent parent-ref
                  :order order))])

    :patch
    (let [{:keys [entity-id attrs]} command]
      (mapv (fn [[k v]] [:db/add [:id entity-id] k v]) attrs))

    :delete
    (let [{:keys [entity-id]} command
          all-to-delete (cons entity-id (collect-descendant-ids db entity-id))]
      (mapv #(vector :db/retractEntity [:id %]) all-to-delete))))

;; --- Executor (Impure Function) ---
(defn execute!
  "Executes a command against a DataScript connection."
  [conn command]
  (when-let [tx-data (command->tx @conn command)]
    (d/transact! conn tx-data)))

;; ## 3. QUERIES ##
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
    (d/transact! conn [{:id "root" :name "Root"}])

    (execute! conn {:op :put, :entity {:id "child1", :name "A"}, :position {:rel :first, :target "root"}})
    (execute! conn {:op :put, :entity {:id "child2", :name "B"}, :position {:rel :last, :target "root"}})
    (is (= ["child1" "child2"] (children-ids @conn "root")) "Initial order")

    (execute! conn {:op :put, :entity {:id "child3", :name "C"}, :position {:rel :after, :target "child1"}})
    (is (= ["child1" "child3" "child2"] (children-ids @conn "root")) "Insert after")

    (execute! conn {:op :put, :entity {:id "child0", :name "Z"}, :position {:rel :first, :target "root"}})
    (is (= ["child0" "child1" "child3" "child2"] (children-ids @conn "root")) "Insert first")

    (execute! conn {:op :put, :entity {:id "grandchild1"}, :position {:rel :first, :target "child1"}})
    (is (= ["grandchild1"] (children-ids @conn "child1")) "Grandchild created")
    (execute! conn {:op :delete, :entity-id "child1"})
    (is (= ["child0" "child3" "child2"] (children-ids @conn "root")) "Parent deleted")
    (is (nil? (d/entity @conn [:id "child1"])) "Entity is gone")
    (is (nil? (d/entity @conn [:id "grandchild1"])) "Component grandchild is also gone")))

(deftest subtree-operations-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root" :name "Root"}])

    (execute! conn {:op :put, :entity {:id "branch1", :name "Branch 1"}, :position {:rel :first, :target "root"}})
    (execute! conn {:op :put, :entity {:id "leaf1", :name "Leaf 1"}, :position {:rel :first, :target "branch1"}})
    (execute! conn {:op :put, :entity {:id "leaf2", :name "Leaf 2"}, :position {:rel :last, :target "branch1"}})
    (execute! conn {:op :put, :entity {:id "branch2", :name "Branch 2"}, :position {:rel :last, :target "root"}})
    (execute! conn {:op :put, :entity {:id "leaf3", :name "Leaf 3"}, :position {:rel :first, :target "branch2"}})

    (is (= ["branch1" "branch2"] (children-ids @conn "root")))
    (is (= ["leaf1" "leaf2"] (children-ids @conn "branch1")))

    (execute! conn {:op :put, :entity {:id "branch1"}, :position {:rel :first, :target "branch2"}})
    (is (= ["branch2"] (children-ids @conn "root")) "Root now has only branch2")
    (is (= ["branch1" "leaf3"] (children-ids @conn "branch2")) "Branch2 contains branch1 and leaf3")
    (is (= ["leaf1" "leaf2"] (children-ids @conn "branch1")) "Branch1 still has its children")
    (is (= "branch2" (:id (:parent (d/entity @conn [:id "branch1"])))) "branch1 parent is now branch2")

    (execute! conn {:op :delete, :entity-id "branch1"})
    (is (= ["leaf3"] (children-ids @conn "branch2")))
    (is (nil? (d/entity @conn [:id "branch1"])))
    (is (nil? (d/entity @conn [:id "leaf1"])))
    (is (not (nil? (d/entity @conn [:id "leaf3"]))))))

(deftest deep-nesting-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root" :name "Root"}])

    (execute! conn {:op :put, :entity {:id "A"}, :position {:rel :first, :target "root"}})
    (execute! conn {:op :put, :entity {:id "A1"}, :position {:rel :first, :target "A"}})
    (execute! conn {:op :put, :entity {:id "A1a"}, :position {:rel :first, :target "A1"}})
    (execute! conn {:op :put, :entity {:id "A1b"}, :position {:rel :after, :target "A1a"}})
    (execute! conn {:op :put, :entity {:id "A2"}, :position {:rel :after, :target "A1"}})

    (is (= ["A1" "A2"] (children-ids @conn "A")))
    (is (= ["A1a" "A1b"] (children-ids @conn "A1")))

    (execute! conn {:op :put, :entity {:id "A1b"}, :position {:rel :first, :target "A2"}})
    (is (= ["A1a"] (children-ids @conn "A1")))
    (is (= ["A1b"] (children-ids @conn "A2")))

    (execute! conn {:op :delete, :entity-id "A1"})
    (is (= ["A2"] (children-ids @conn "A")))
    (is (nil? (d/entity @conn [:id "A1"])))
    (is (nil? (d/entity @conn [:id "A1a"])))))

(run-tests)