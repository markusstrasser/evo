(ns kernel
  (:require [datascript.core :as d]
            [clojure.string]
            [clojure.test :refer [deftest is run-tests]]))

;; ## 1. SCHEMA ##
(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref}
   :order {:db/index true}
   ;; Enforces that :order is unique per :parent
   :parent+order {:db/tupleAttrs [:parent :order]
                  :db/unique :db.unique/value}})

;; ## 2. DATALOG RULES ##
;; Rules define the transitive closure of the parent-child relationship.
;; A subtree-member relationship exists between an ancestor and descendant
;; if there's a direct parent-child link OR an indirect link through other nodes.
(def rules
  '[[(subtree-member ?ancestor ?descendant)
     [?descendant :parent ?ancestor]]
    [(subtree-member ?ancestor ?descendant)
     [?descendant :parent ?intermediate]
     (subtree-member ?ancestor ?intermediate)]])

;; ## 2. FRACTIONAL ORDERING ##
;; Canonical fractional indexing - Greenspan-style in ~25 LOC
(def ^:private digits "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
(def ^:private base (count digits))
(defn- code [s i] (let [c (when (and s (< i (count s))) (.charAt s i))]
                    (if c (inc (clojure.string/index-of digits c)) 0))) ; 0 is -∞ sentinel
(defn- ch [i] (.charAt digits (dec i))) ; 1..base ⇒ digit

(defn- rank-between
  "Return a rank strictly between a and b (strings or nil)."
  ([a b] (loop [i 0, acc ""]
           (let [lo (code a i) ; inclusive lower bound (0..base)
                 hi (let [x (code b i)] ; exclusive upper bound (1..base+1)
                      (if (pos? x) x (inc base)))]
             (if (< (inc lo) hi)
               (str acc (ch (quot (+ lo hi) 2)))
               (recur (inc i) (str acc (if (pos? lo) (ch lo) (ch 1))))))))
  ([] (rank-between nil nil))
  ([a] (rank-between a nil))) ; convenience: after a
;
;(defn- rank-first [] (rank-between nil "")) ; before first
;(defn- rank-last [last] (rank-between last nil))
;(defn- rank-after [a] (rank-between a nil))
;(defn- rank-before [b] (rank-between nil b))

;; Integration with existing API
(defn- get-ordered-siblings [db parent-ref]
  (->> (d/q '[:find ?o :in $ ?p :where [?e :parent ?p] [?e :order ?o]] db parent-ref)
       (mapv first)
       (remove nil?)
       (sort-by str)))

(defn- calculate-order [db parent-ref {:keys [rel target]}]
  (let [siblings (get-ordered-siblings db parent-ref)]
    (case rel
      :first (rank-between nil (first siblings))
      :last (rank-between (last siblings) nil)
      :after (let [target-order (:order (d/entity db [:id target]))
                   target-idx (.indexOf siblings target-order)
                   next-order (when (< (inc target-idx) (count siblings))
                                (nth siblings (inc target-idx)))]
               (rank-between target-order next-order))
      :before (let [target-order (:order (d/entity db [:id target]))
                    target-idx (.indexOf siblings target-order)
                    prev-order (when (pos? target-idx) (nth siblings (dec target-idx)))]
                (rank-between prev-order target-order)))))

;; ## 3. CORE LOGIC - COMMAND MODEL ##
;; This implementation uses a data-driven command model.
;; All mutations are represented as data maps.
;; A single pure function, `command->tx`, interprets these commands.
;; A single impure function, `execute!`, applies the resulting transaction.

;; --- Private Helpers ---
(comment "collect-descendant-ids function replaced by declarative Datalog query using rules")

;; --- Interpreter (Pure Function) ---
(defn- tree->txns
  "Recursively generates a flat vector of transaction maps from a nested entity map."
  [entity-map parent-ref order]
  (let [entity-id (:id entity-map)
        temp-id (str "temp-" entity-id) ;; Predictable temporary ID
        children (:children entity-map)

        ;; The transaction map for the current entity
        ;; :children is removed as it's not part of the new schema
        entity-txn (-> entity-map
                       (dissoc :children)
                       (assoc :db/id temp-id
                              :parent parent-ref
                              :order order))

        ;; Recursively generate transactions for children using fractional ordering
        children-txns (when children
                        (let [;; Generate fractional orders for all children at once
                              child-orders (loop [orders []
                                                  prev-order nil
                                                  remaining (count children)]
                                             (if (zero? remaining)
                                               orders
                                               (let [next-order (rank-between prev-order nil)]
                                                 (recur (conj orders next-order)
                                                        next-order
                                                        (dec remaining)))))]
                          (mapcat
                           (fn [child-map child-order]
                             (tree->txns child-map temp-id (str order "." child-order)))
                           children child-orders)))]
    (if children-txns
      (cons entity-txn children-txns)
      [entity-txn])))

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
          order (calculate-order db parent-ref position)]
      (tree->txns entity parent-ref order))

    :patch
    (let [{:keys [entity-id attrs]} command]
      (mapv (fn [[k v]] [:db/add [:id entity-id] k v]) attrs))

    :delete
    (let [{:keys [entity-id]} command
          parent-ref [:id entity-id]
          descendant-ids (d/q '[:find [?did ...]
                                :in $ % ?pref
                                :where
                                [?d :id ?did]
                                (subtree-member ?pref ?d)]
                              db rules parent-ref)
          all-to-delete (cons entity-id descendant-ids)]
      (mapv #(vector :db/retractEntity [:id %]) all-to-delete))))

;; --- Executor (Impure Function) ---
(defn execute!
  "Executes a command against a DataScript connection."
  [conn command]
  (when-let [tx-data (command->tx @conn command)]
    (d/transact! conn tx-data)))

;; --- Convenience Functions ---
(defn create!
  "Creates an entity with optional nested children. Wrapper around execute! for :put operations."
  [conn entity-map position]
  (execute! conn {:op :put :entity entity-map :position position}))

(defn delete!
  "Deletes an entity and all its descendants using declarative Datalog rules."
  [conn entity-id]
  (execute! conn {:op :delete :entity-id entity-id}))

;; ## 4. QUERIES ##
(defn children-ids
  "Returns child IDs in order."
  [db parent-id]
  (->> (d/q '[:find ?id ?order :in $ ?pid :where
              [?p :id ?pid] [?c :parent ?p]
              [?c :id ?id] [?c :order ?order]]
            db parent-id)
       (sort-by second)
       (mapv first)))

;; ## 5. TESTS ##
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

(deftest fractional-ordering-test
  "Tests the fractional ordering system edge cases"
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root" :name "Root"}])

    ;; Test many insertions to verify lexicographic ordering
    (doseq [i (range 10)]
      (execute! conn {:op :put, :entity {:id (str "item" i)},
                      :position {:rel :last, :target "root"}}))

    (let [children (children-ids @conn "root")
          orders (mapv #(:order (d/entity @conn [:id %])) children)]
      (is (= children (mapv #(str "item" %) (range 10))) "Sequential insertion maintains order")
      (is (= orders (sort orders)) "Orders are lexicographically sorted"))

    ;; Test insertion between existing items creates proper fractional ranks
    (execute! conn {:op :put, :entity {:id "between"},
                    :position {:rel :after, :target "item4"}})
    (let [orders (mapv #(:order (d/entity @conn [:id %]))
                       ["item4" "between" "item5"])]
      (is (= orders (sort orders)) "Fractional rank sorts between neighbors"))))

(run-tests)