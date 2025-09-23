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
  "Data Access: Fetches and sorts sibling order strings for a given parent."
  [db parent-ref]
  ;; Use :find [?o ...] to get a vector of scalars, then sort.
  (->> (d/q '[:find [?o ...]
              :in $ ?p
              :where [?e :parent ?p] [?e :order ?o]]
            db parent-ref)
       (sort)))

(defn- find-surrounding-orders [siblings target-order]
  (when-let [idx (first (keep-indexed #(when (= %2 target-order) %1) siblings))]
    [(when (pos? idx) (nth siblings (dec idx)))
     (when (< (inc idx) (count siblings)) (nth siblings (inc idx)))]))

(defn- resolve-rank
  "Policy: Decides *which* ranks to pass to rank-between based on user intent."
  [db parent-ref {:keys [rel parent sibling]}]
  (let [siblings (get-ordered-siblings db parent-ref)]
    (cond
      ;; :parent key for :first/:last
      (and parent (#{:first :last} rel))
      (case rel
        :first (rank-between nil (first siblings))
        :last (rank-between (last siblings) nil))

      ;; :sibling key for :before/:after  
      (and sibling (#{:before :after} rel))
      (let [t-order (:order (d/entity db [:id sibling]))
            [before after] (find-surrounding-orders siblings t-order)]
        (if (= rel :after)
          (rank-between t-order after)
          (rank-between before t-order))))))

(defn- linearize-subtree
  "Walks a tree entity-map, producing a lazy sequence of flat nodes.
  Each node contains its data, tempid, parent-ref, and order key."
  [entity-map parent-ref order]
  (let [tempid (d/tempid :db.part/user)
        children (:children entity-map)
        child-orders (rest (reductions (fn [prev _] (rank-between prev nil)) nil children))
        this-node {:node-data entity-map
                   :tempid tempid
                   :parent-ref parent-ref
                   :order order}]
    (cons this-node
          (mapcat linearize-subtree children (repeat tempid) child-orders))))

(defn- linearized-node->tx
  "Transforms a single flat node from the sequence into a transaction map."
  [{:keys [node-data tempid parent-ref order]}]
  (-> node-data
      (dissoc :children)
      (assoc :db/id tempid :parent parent-ref :order order)))

(defn tree->tx-data
  "Generates transaction data for a subtree put via a data pipeline."
  [db entity-map position]
  (let [{:keys [rel parent sibling]} position
        parent-id (cond
                    parent parent
                    sibling (:id (:parent (d/entity db [:id sibling])))
                    :else (throw (ex-info "No parent specified" {:position position})))
        parent-ref (when parent-id [:id parent-id])
        root-order (resolve-rank db parent-ref position)]
    (->> (linearize-subtree entity-map parent-ref root-order)
         (map linearized-node->tx)
         (vec))))

;; ## 4. HIGH-LEVEL API: COMMAND INTERPRETER ##
;; Unchanged. This abstraction correctly separates command definition from execution.
(defn command->tx
  "Takes a db value and a command map, returns transaction data."
  [db {:keys [op] :as command}]
  (case op
    :apply-txs (:tx-data command)
    :patch (mapv (fn [[k v]] [:db/add [:id (:entity-id command)] k v]) (:attrs command))
    :move (let [entity-id (:entity-id command)
                position (:position command)
                {:keys [rel parent sibling]} position
                new-parent-ref (cond
                                 parent [:id parent]
                                 sibling (:parent (d/entity db [:id sibling]))
                                 :else (throw (ex-info "No parent specified" {:position position})))
                new-order (resolve-rank db new-parent-ref position)]
            [[:db/add [:id entity-id] :parent new-parent-ref]
             [:db/add [:id entity-id] :order new-order]])
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
(defn insert!
  [conn entity-map position]
  (let [tx-data (tree->tx-data @conn entity-map position)]
    (execute! conn {:op :apply-txs :tx-data tx-data})))

(defn delete! [conn entity-id]
  (execute! conn {:op :delete :entity-id entity-id}))

(defn update! [conn entity-id attrs]
  (execute! conn {:op :patch :entity-id entity-id :attrs attrs}))

(defn move! [conn entity-id position]
  "Move an entity to a new position without changing its attributes or children"
  (execute! conn {:op :move :entity-id entity-id :position position}))

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
    (insert! conn {:id "branch1", :children [{:id "leaf1"} {:id "leaf2"}]} {:rel :first :parent "root"})
    (insert! conn {:id "branch2", :children [{:id "leaf3"}]} {:rel :last :parent "root"})

    (is (= ["branch1" "branch2"] (children-ids @conn "root")))
    (is (= ["leaf1" "leaf2"] (children-ids @conn "branch1")))

    ;; Reparent "branch1" and all its children to be the first child of "branch2"
    (insert! conn {:id "branch1"} {:rel :first :parent "branch2"})

    (is (= ["branch2"] (children-ids @conn "root")) "Root now has only branch2")
    (is (= ["branch1" "leaf3"] (children-ids @conn "branch2")) "Branch2 now contains branch1")
    (is (= ["leaf1" "leaf2"] (children-ids @conn "branch1")) "Branch1 still has its children")
    (is (= "branch2" (:id (:parent (d/entity @conn [:id "branch1"])))) "branch1 parent is now branch2")))

(deftest fractional-ordering-stress-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root"}])

    (doseq [i (range 10)]
      (insert! conn {:id (str "item" i)} {:rel :last :parent "root"}))

    (let [children (children-ids @conn "root")
          orders (mapv #(:order (d/entity @conn [:id %])) children)]
      (is (= children (mapv #(str "item" %) (range 10))) "Sequential insertion maintains order")
      (is (= orders (sort orders)) "Orders are lexicographically sorted"))

    (insert! conn {:id "between"} {:rel :after :sibling "item4"})
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
    (insert! conn {:id "parent", :name "Parent Node", :children [{:id "child1", :name "Child 1"} {:id "child2", :name "Child 2"}]} {:rel :first :parent "root"})
    (insert! conn {:id "sibling", :name "Sibling Node"} {:rel :last :parent "root"})

    ;; Test initial state
    (is (= ["parent" "sibling"] (children-ids @conn "root")))
    (is (= ["child1" "child2"] (children-ids @conn "parent")))
    (is (= "Parent Node" (:name (d/entity @conn [:id "parent"]))))

    ;; Test patch operation
    (update! conn "parent" {:name "Updated Parent", :description "New description"})
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

(deftest move-subtree-test
  "Test moving subtrees (nodes with children) to different positions"
  (let [conn (d/create-conn schema)]
    ;; Set up a complex tree structure
    (d/transact! conn [{:id "root"}])
    (insert! conn {:id "section-a", :name "Section A",
                   :children [{:id "comp-1", :name "Component 1",
                               :children [{:id "elem-1", :name "Element 1"}
                                          {:id "elem-2", :name "Element 2"}]}
                              {:id "comp-2", :name "Component 2"}]}
             {:rel :first :parent "root"})
    (insert! conn {:id "section-b", :name "Section B",
                   :children [{:id "comp-3", :name "Component 3"}]}
             {:rel :last :parent "root"})
    (insert! conn {:id "section-c", :name "Section C"}
             {:rel :last :parent "root"})

    ;; Verify initial structure
    (is (= ["section-a" "section-b" "section-c"] (children-ids @conn "root")))
    (is (= ["comp-1" "comp-2"] (children-ids @conn "section-a")))
    (is (= ["elem-1" "elem-2"] (children-ids @conn "comp-1")))
    (is (= ["comp-3"] (children-ids @conn "section-b")))
    (is (= [] (children-ids @conn "section-c")))

    ;; Test 1: Move a component with children to a different section
    (insert! conn {:id "comp-1"} {:rel :first :parent "section-b"})

    (is (= ["comp-2"] (children-ids @conn "section-a"))
        "comp-1 should be removed from section-a")
    (is (= ["comp-1" "comp-3"] (children-ids @conn "section-b"))
        "comp-1 should be first child of section-b")
    (is (= ["elem-1" "elem-2"] (children-ids @conn "comp-1"))
        "comp-1 should retain its children after move")

    ;; Test 2: Move an entire section with all its descendants
    (insert! conn {:id "section-b"} {:rel :after :sibling "section-c"})

    (is (= ["section-a" "section-c" "section-b"] (children-ids @conn "root"))
        "section-b should move after section-c")
    (is (= ["comp-1" "comp-3"] (children-ids @conn "section-b"))
        "section-b should retain its components")
    (is (= ["elem-1" "elem-2"] (children-ids @conn "comp-1"))
        "Deep children should be preserved")))

(deftest move-complex-component-test
  "Test moving complex nested components like UI components"
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "app"}])

    ;; Create a complex UI-like structure
    (insert! conn {:id "header", :type "component",
                   :children [{:id "nav", :type "navigation",
                               :children [{:id "home-link", :type "link"}
                                          {:id "about-link", :type "link"}]}
                              {:id "logo", :type "image"}]}
             {:rel :first :parent "app"})
    (insert! conn {:id "main", :type "component",
                   :children [{:id "sidebar", :type "component"}
                              {:id "content", :type "component"}]}
             {:rel :last :parent "app"})
    (insert! conn {:id "footer", :type "component"}
             {:rel :last :parent "app"})

    ;; Verify initial structure
    (is (= ["header" "main" "footer"] (children-ids @conn "app")))
    (is (= ["nav" "logo"] (children-ids @conn "header")))
    (is (= ["home-link" "about-link"] (children-ids @conn "nav")))

    ;; Test: Move navigation component to footer (like moving a component in UI editor)
    (insert! conn {:id "nav"} {:rel :first :parent "footer"})

    (is (= ["logo"] (children-ids @conn "header"))
        "nav should be removed from header")
    (is (= ["nav"] (children-ids @conn "footer"))
        "nav should be moved to footer")
    (is (= ["home-link" "about-link"] (children-ids @conn "nav"))
        "nav should preserve its link children")))

(run-tests)