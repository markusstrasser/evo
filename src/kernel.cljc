(ns kernel
  (:require [datascript.core :as d]
            [clojure.string]
            [clojure.test :refer [deftest is run-tests]]))

;; ## 1. SCHEMA & RULES ##
;; Defines the data model and declarative rules for subtree queries.
;; This section is unchanged; the schema and rule are fundamentally correct.
(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref}
   :order {:db/index true}
   :parent+order {:db/tupleAttrs [:parent :order]
                  :db/unique :db.unique/value}})

(def rules
  '[[(subtree-member ?ancestor ?descendant) [?descendant :parent ?ancestor]]
    [(subtree-member ?ancestor ?descendant) [?descendant :parent ?intermediate]
     (subtree-member ?ancestor ?intermediate)]])

;; ## 2. LOW-LEVEL MECHANISM: FRACTIONAL ORDERING ##
;; Pure, context-free implementation of fractional indexing. Unchanged.
(def ^:private digits "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
(def ^:private base (count digits))
(defn- code [s i]
  (let [c (when (and s (< i (count s))) (nth s i))]
    (if c (inc (clojure.string/index-of digits (str c))) 0)))
(defn- ch [i] (nth digits (dec i)))

(defn- rank-between [a b]
  (loop [i 0, acc ""]
    (let [lo (code a i)
          hi (let [x (code b i)] (if (pos? x) x (inc base)))]
      (if (< (inc lo) hi)
        (str acc (ch (quot (+ lo hi) 2)))
        (recur (inc i) (str acc (if (pos? lo) (ch lo) (ch 1))))))))

;; ## 3. MID-LEVEL POLICY & PREPARATION ##
;; REWRITTEN for semantic clarity. Hierarchy (:parent) and sibling order (:order) are decoupled.
;; The `:order` attribute is now purely local to its parent.

(defn- get-ordered-siblings
  "Data Access: Fetches sibling order strings for a given parent."
  [db parent-ref]
  (->> (d/q '[:find ?o :in $ ?p :where [?e :parent ?p] [?e :order ?o]] db parent-ref)
       (mapv first) (remove nil?) (sort-by str)))

(defn- find-surrounding-orders [siblings target-order]
  (when-let [idx (first (keep-indexed #(when (= %2 target-order) %1) siblings))]
    [(when (pos? idx) (nth siblings (dec idx)))
     (when (< (inc idx) (count siblings)) (nth siblings (inc idx)))]))

(defn- resolve-rank
  "Policy: Decides *which* ranks to pass to rank-between based on user intent."
  [db parent-ref {:keys [rel target]}]
  (let [siblings (get-ordered-siblings db parent-ref)]
    (case rel
      :first (rank-between nil (first siblings))
      :last (rank-between (last siblings) nil)
      :after (let [t-order (:order (d/entity db [:id target]))
                   [_ after] (find-surrounding-orders siblings t-order)]
               (rank-between t-order after))
      :before (let [t-order (:order (d/entity db [:id target]))
                    [before _] (find-surrounding-orders siblings t-order)]
                (rank-between before t-order)))))

(declare prep-tx-for-subtree)

(defn- prep-tx-for-children [children parent-tempid]
  "Generates local orders and full transaction data for a set of children."
  (let [child-orders (rest (reductions (fn [prev _] (rank-between prev nil)) nil children))]
    (mapcat prep-tx-for-subtree children (repeat parent-tempid) child-orders)))

(defn- prep-tx-for-subtree [entity-map parent-ref order]
  "Transforms a single node and its children into transaction data.
  The `order` is local and not derived from the parent's order."
  (let [temp-id (d/tempid :db.part/user)
        children (:children entity-map)
        root-tx (-> entity-map
                    (dissoc :children)
                    (assoc :db/id temp-id :parent parent-ref :order order))
        children-txs (when (seq children)
                       (prep-tx-for-children children temp-id))]
    (cons root-tx children-txs)))

(defn tree->tx-data
  "Public Preparation Function: Top-level call to generate all tx data for a subtree put."
  [db entity-map position]
  (let [parent-id (if (#{:first :last} (:rel position))
                    (:target position)
                    (:id (:parent (d/entity db [:id (:target position)]))))
        parent-ref (when parent-id [:id parent-id])
        order (resolve-rank db parent-ref position)]
    (vec (flatten (prep-tx-for-subtree entity-map parent-ref order)))))

;; ## 4. HIGH-LEVEL API: COMMAND INTERPRETER ##
;; Unchanged. This abstraction correctly separates command definition from execution.
(defn command->tx
  "Takes a db value and a command map, returns transaction data."
  [db {:keys [op] :as command}]
  (case op
    :apply-txs (:tx-data command)
    :patch (mapv (fn [[k v]] [:db/add [:id (:entity-id command)] k v]) (:attrs command))
    :delete (let [e-id (:entity-id command)
                  descendants (d/q '[:find [?did ...] :in $ % ?p :where
                                     (subtree-member ?p ?d) [?d :id ?did]]
                                   db rules [:id e-id])]
              (mapv #(vector :db/retractEntity [:id %]) (cons e-id descendants)))))

(defn execute!
  "Executes a command against a DataScript connection."
  [conn command]
  (when-let [tx-data (command->tx @conn command)]
    (d/transact! conn tx-data)))

;; ## 5. PUBLIC CONVENIENCE FUNCTIONS ##
;; Unchanged. These compose the layers cleanly.
(defn create!
  [conn entity-map position]
  (let [tx-data (tree->tx-data @conn entity-map position)]
    (execute! conn {:op :apply-txs :tx-data tx-data})))

(defn delete! [conn entity-id]
  (execute! conn {:op :delete :entity-id entity-id}))

(defn patch! [conn entity-id attrs]
  (execute! conn {:op :patch :entity-id entity-id :attrs attrs}))

(defn children-ids
  "Query function to get child IDs in correct order."
  [db parent-id]
  (->> (d/q '[:find ?id ?order :in $ ?pid :where
              [?p :id ?pid] [?c :parent ?p]
              [?c :id ?id] [?c :order ?order]]
            db parent-id)
       (sort-by second)
       (mapv first)))

(deftest subtree-reparenting-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root"}])
    (create! conn {:id "branch1", :children [{:id "leaf1"} {:id "leaf2"}]} {:rel :first, :target "root"})
    (create! conn {:id "branch2", :children [{:id "leaf3"}]} {:rel :last, :target "root"})

    (is (= ["branch1" "branch2"] (children-ids @conn "root")))
    (is (= ["leaf1" "leaf2"] (children-ids @conn "branch1")))

    ;; Reparent "branch1" and all its children to be the first child of "branch2"
    (create! conn {:id "branch1"} {:rel :first, :target "branch2"})

    (is (= ["branch2"] (children-ids @conn "root")) "Root now has only branch2")
    (is (= ["branch1" "leaf3"] (children-ids @conn "branch2")) "Branch2 now contains branch1")
    (is (= ["leaf1" "leaf2"] (children-ids @conn "branch1")) "Branch1 still has its children")
    (is (= "branch2" (:id (:parent (d/entity @conn [:id "branch1"])))) "branch1 parent is now branch2")))

(deftest fractional-ordering-stress-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root"}])

    (doseq [i (range 10)]
      (create! conn {:id (str "item" i)} {:rel :last, :target "root"}))

    (let [children (children-ids @conn "root")
          orders (mapv #(:order (d/entity @conn [:id %])) children)]
      (is (= children (mapv #(str "item" %) (range 10))) "Sequential insertion maintains order")
      (is (= orders (sort orders)) "Orders are lexicographically sorted"))

    (create! conn {:id "between"} {:rel :after, :target "item4"})
    (is (= ["item0" "item1" "item2" "item3" "item4" "between" "item5" "item6" "item7" "item8" "item9"]
           (children-ids @conn "root")))
    (let [orders (mapv #(:order (d/entity @conn [:id %])) ["item4" "between" "item5"])]
      (is (< (compare (nth orders 0) (nth orders 1)) 0) "Fractional rank is greater than predecessor")
      (is (< (compare (nth orders 1) (nth orders 2)) 0) "Fractional rank is less than successor"))))

(deftest find-surrounding-orders-test
  ;; Test helper function behavior
  (is (= [nil "b"] (find-surrounding-orders ["a" "b" "c"] "a")) "First element")
  (is (= ["a" "c"] (find-surrounding-orders ["a" "b" "c"] "b")) "Middle element")
  (is (= ["b" nil] (find-surrounding-orders ["a" "b" "c"] "c")) "Last element")
  (is (= nil (find-surrounding-orders ["a" "b" "c"] "d")) "Missing element")
  (is (= [nil nil] (find-surrounding-orders ["a"] "a")) "Single element")
  (is (= nil (find-surrounding-orders [] "a")) "Empty list"))

(deftest delete-and-patch-operations-test
  (let [conn (d/create-conn schema)]
    ;; Set up test data
    (d/transact! conn [{:id "root"}])
    (create! conn {:id "parent", :name "Parent Node", :children [{:id "child1", :name "Child 1"} {:id "child2", :name "Child 2"}]} {:rel :first, :target "root"})
    (create! conn {:id "sibling", :name "Sibling Node"} {:rel :last, :target "root"})

    ;; Test initial state
    (is (= ["parent" "sibling"] (children-ids @conn "root")))
    (is (= ["child1" "child2"] (children-ids @conn "parent")))
    (is (= "Parent Node" (:name (d/entity @conn [:id "parent"]))))

    ;; Test patch operation
    (patch! conn "parent" {:name "Updated Parent", :description "New description"})
    (let [updated-parent (d/entity @conn [:id "parent"])]
      (is (= "Updated Parent" (:name updated-parent)) "Name should be updated")
      (is (= "New description" (:description updated-parent)) "Description should be added"))

    ;; Test that patch doesn't affect children
    (is (= ["child1" "child2"] (children-ids @conn "parent")) "Children should remain unchanged after patch")

    ;; Test delete operation (should cascade to children)
    (delete! conn "parent")
    (is (= ["sibling"] (children-ids @conn "root")) "Parent should be deleted from root children")
    (is (nil? (d/entity @conn [:id "parent"])) "Parent entity should not exist")
    (is (nil? (d/entity @conn [:id "child1"])) "Child1 should be deleted (cascade)")
    (is (nil? (d/entity @conn [:id "child2"])) "Child2 should be deleted (cascade)")
    (is (not (nil? (d/entity @conn [:id "sibling"]))) "Sibling should still exist")))

(run-tests)