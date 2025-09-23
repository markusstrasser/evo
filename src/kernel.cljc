(ns kernel
  (:require [datascript.core :as d]
            [clojure.string :as s]
            [clojure.test :refer [deftest is run-tests]]))

;; ---------- 1) Schema & rules (unchanged) ----------
(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref}
   :order {:db/index true}
   :parent+order {:db/tupleAttrs [:parent :order]
                  :db/unique :db.unique/value}})

(def rules
  '[[(subtree-member ?a ?d) [?d :parent ?a]]
    [(subtree-member ?a ?d) [?d :parent ?m] (subtree-member ?a ?m)]])

;; ---------- 2) Fractional ordering (tight) ----------
(def ^:private digits "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
(def ^:private base (count digits))
(defn- code [s i] (let [c (when (and s (< i (count s))) (nth s i))]
                    (if c (inc (s/index-of digits (str c))) 0)))
(defn- ch [i] (nth digits (dec i)))
(defn- rank-between [a b]
  (loop [i 0, acc ""]
    (let [lo (code a i)
          hi (let [x (code b i)] (if (pos? x) x (inc base)))]
      (if (< (inc lo) hi)
        (str acc (ch (quot (+ lo hi) 2)))
        (recur (inc i) (str acc (if (pos? lo) (ch lo) (ch 1))))))))

;; ---------- 3) Tiny db helpers ----------
(defn- e [db id] (d/entity db [:id id]))
(defn- pid [db id] (-> (e db id) :parent :id))
(defn- orders [db parent-ref]
  (->> (d/q '[:find [?o ...] :in $ ?p :where [?e :parent ?p] [?e :order ?o]] db parent-ref)
       sort vec))
(defn- neighbors [v x]
  (when-let [i (first (keep-indexed #(when (= %2 x) %1) v))]
    [(get v (dec i)) (get v (inc i))]))

;; Resolve both parent-ref and new :order in one place.
(defn- resolve-position
  "position = {:parent id | :sibling id, :rel #{:first :last :before :after}}
   Defaults to append when :rel missing."
  [db {:keys [parent sibling rel] :as pos}]
  (let [parent-id (or parent (when sibling (pid db sibling))
                      (throw (ex-info "No parent specified" {:position pos})))
        pref [:id parent-id]
        os (orders db pref)
        ord (case rel
              :first (rank-between nil (first os))
              :last (rank-between (last os) nil)
              :before (let [t (:order (e db sibling))
                            [bef _] (neighbors os t)]
                        (rank-between bef t))
              :after (let [t (:order (e db sibling))
                           [_ aft] (neighbors os t)]
                       (rank-between t aft))
              ;; default append
              (rank-between (last os) nil))]
    [pref ord]))

;; ---------- 4) Tree insertion (compact walk) ----------
(defn- walk->tx
  "Assigns fresh :order to children within the new subtree."
  [node parent-ref order]
  (let [id (d/tempid :db.part/user)
        kids (:children node [])
        this (-> node (dissoc :children) (assoc :db/id id :parent parent-ref :order order))
        ;; sequence of orders: mid, next-after-mid, ...
        kid-ord (take (count kids) (iterate #(rank-between % nil) (rank-between nil nil)))]
    (into [this]
          (mapcat (fn [[k o]] (walk->tx k id o)) (map vector kids kid-ord)))))

(defn- ensure-new-ids! [db root]
  (letfn [(ids [n] (cons (:id n) (mapcat ids (:children n []))))]
    (when-let [dups (seq (filter #(e db %) (ids root)))]
      (throw (ex-info "IDs already exist; use move! for existing entities" {:ids dups})))))

(defn insert! [conn entity {:keys [parent sibling] :as position}]
  (ensure-new-ids! @conn entity)
  (let [[pref root-order] (resolve-position @conn position)]
    (d/transact! conn (walk->tx entity pref root-order))))

;; ---------- 5) Mutations ----------
(defn delete! [conn entity-id]
  (let [db @conn
        desc (d/q '[:find [?id ...] :in $ % ?p :where (subtree-member ?p ?d) [?d :id ?id]]
                  db rules [:id entity-id])]
    (d/transact! conn (mapv (fn [id] [:db/retractEntity [:id id]]) (cons entity-id desc)))))

(defn update! [conn entity-id attrs]
  (when (some #{:parent :order :id} (keys attrs))
    (throw (ex-info "Cannot modify structural attributes" {:attrs (select-keys attrs [:parent :order :id])})))
  (d/transact! conn (for [[k v] attrs] [:db/add [:id entity-id] k v])))

(defn move! [conn entity-id {:keys [parent sibling] :as position}]
  (let [db @conn
        desc (set (d/q '[:find [?id ...] :in $ % ?p :where (subtree-member ?p ?d) [?d :id ?id]]
                       db rules [:id entity-id]))
        tgt-p (or parent (pid db sibling))]
    (when (contains? desc tgt-p)
      (throw (ex-info "Cycle detected" {:entity entity-id :target tgt-p})))
    (let [[pref new-o] (resolve-position db position)
          ent (e db entity-id)]
      (d/transact! conn
                   [[:db/retract [:id entity-id] :parent [:id (-> ent :parent :id)]]
                    [:db/retract [:id entity-id] :order (:order ent)]
                    [:db/add [:id entity-id] :parent pref]
                    [:db/add [:id entity-id] :order new-o]]))))

;; ---------- 6) Read ----------
(defn children-ids [db parent-id]
  (->> (d/q '[:find ?id ?o :in $ ?pid
              :where [?p :id ?pid] [?c :parent ?p] [?c :id ?id] [?c :order ?o]]
            db parent-id)
       (sort-by second) (mapv first)))

;; ---------- 8) Test Utilities ----------
(defn- tree-fixture []
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root"}])
    conn))

(defn- tree-structure [conn & paths]
  "Get tree structure as nested vectors for easy comparison"
  (letfn [(structure [id] [id (mapv structure (children-ids @conn id))])]
    (if (= 1 (count paths))
      (structure (first paths))
      (mapv structure paths))))

;; ---------- 9) Core Tests ----------
(deftest fractional-ordering-properties
  "Test mathematical properties of fractional ordering"
  ;; Test that we can generate different orders by building incrementally
  (let [orders (loop [acc [], prev nil, n 10]
                 (if (zero? n)
                   acc
                   (let [next-order (rank-between prev nil)]
                     (recur (conj acc next-order) next-order (dec n)))))]
    ;; All orders should be unique
    (is (= (count orders) (count (set orders))))
    ;; Should be lexicographically sortable  
    (is (= orders (sort orders)))
    ;; Should work with insertions between existing orders
    (let [between (rank-between (first orders) (second orders))]
      (is (< (compare (first orders) between) 0))
      (is (< (compare between (second orders)) 0)))))

(deftest position-resolution-edge-cases
  "Test position resolution with various edge cases"
  (let [conn (tree-fixture)]
    (insert! conn {:id "a"} {:parent "root"})
    (insert! conn {:id "b"} {:parent "root"})

    ;; Default position (append)
    (insert! conn {:id "c"} {:parent "root"})
    (is (= ["a" "b" "c"] (children-ids @conn "root")))

    ;; Sibling positioning
    (insert! conn {:id "between"} {:rel :after :sibling "a"})
    (is (= ["a" "between" "b" "c"] (children-ids @conn "root")))

    ;; Error conditions
    (is (thrown-with-msg? Exception #"No parent specified"
                          (insert! conn {:id "orphan"} {})))
    (is (thrown-with-msg? Exception #"IDs already exist"
                          (insert! conn {:id "a"} {:parent "root"})))))

(deftest crud-operations-integration
  "Test full CRUD lifecycle with validation"
  (let [conn (tree-fixture)]
    ;; Create hierarchical data
    (insert! conn {:id "ui", :type "app",
                   :children [{:id "header", :children [{:id "nav"}]}
                              {:id "main", :children [{:id "sidebar"} {:id "content"}]}
                              {:id "footer"}]}
             {:parent "root"})

    ;; Verify initial structure
    (is (= ["ui" [["header" [["nav" []]]]
                  ["main" [["sidebar" []] ["content" []]]]
                  ["footer" []]]]
           (tree-structure conn "ui")))

    ;; Update attributes (non-structural)
    (update! conn "nav" {:label "Navigation" :visible true})
    (is (= "Navigation" (:label (e @conn "nav"))))

    ;; Move components around
    (move! conn "nav" {:rel :first :parent "footer"}) ; nav to footer
    (move! conn "sidebar" {:rel :after :sibling "content"}) ; reorder in main

    ;; Verify restructured tree
    (is (= ["ui" [["header" []]
                  ["main" [["content" []] ["sidebar" []]]]
                  ["footer" [["nav" []]]]]]
           (tree-structure conn "ui")))

    ;; Delete subtree
    (delete! conn "main")
    (is (= ["ui" [["header" []] ["footer" [["nav" []]]]]]
           (tree-structure conn "ui")))
    (is (nil? (e @conn "content")) "Cascade delete worked")

    ;; Validation checks
    (is (thrown-with-msg? Exception #"Cannot modify structural"
                          (update! conn "nav" {:parent "other"})))
    (is (thrown-with-msg? Exception #"Cycle detected"
                          (move! conn "ui" {:parent "nav"})))))

(deftest complex-restructuring-scenarios
  "Test realistic tree manipulation scenarios"
  (let [conn (tree-fixture)]
    ;; Build a document-like structure
    (insert! conn {:id "doc", :title "My Document"
                   :children [{:id "sec1", :title "Introduction"
                               :children [{:id "p1", :text "First paragraph"}
                                          {:id "p2", :text "Second paragraph"}]}
                              {:id "sec2", :title "Methods"
                               :children [{:id "subsec1", :title "Approach A"}
                                          {:id "subsec2", :title "Approach B"}]}
                              {:id "sec3", :title "Conclusion"}]}
             {:parent "root"})

    ;; Scenario 1: Reorganize sections (move sec3 between sec1 and sec2)
    (move! conn "sec3" {:rel :after :sibling "sec1"})
    (is (= ["sec1" "sec3" "sec2"] (children-ids @conn "doc")))

    ;; Scenario 2: Merge sections (move subsections from sec2 to sec1)  
    (move! conn "subsec1" {:rel :last :parent "sec1"})
    (move! conn "subsec2" {:rel :last :parent "sec1"})
    (is (= ["p1" "p2" "subsec1" "subsec2"] (children-ids @conn "sec1")))
    (is (= [] (children-ids @conn "sec2")))

    ;; Scenario 3: Bulk operations with ordering constraints
    (insert! conn {:id "toc", :title "Table of Contents"} {:rel :first :parent "doc"})
    (insert! conn {:id "appendix"} {:rel :last :parent "doc"})
    (is (= ["toc" "sec1" "sec3" "sec2" "appendix"] (children-ids @conn "doc")))

    ;; Scenario 4: Complex nested moves preserving deep structure
    (let [sec1-structure (tree-structure conn "sec1")]
      (move! conn "sec1" {:rel :first :parent "appendix"})
      (is (= sec1-structure (tree-structure conn "sec1")) "Deep structure preserved"))))

(deftest performance-and-ordering-stress
  "Test performance with many operations and verify ordering invariants"
  (let [conn (tree-fixture)]
    ;; Generate many sequential inserts
    (doseq [i (range 50)]
      (insert! conn {:id (str "item-" i) :data i} {:parent "root"}))

    ;; Verify ordering is maintained
    (let [items (children-ids @conn "root")
          orders (mapv #(:order (e @conn %)) items)]
      (is (= 50 (count items)))
      (is (= orders (sort orders)) "Orders maintain sort invariant"))

    ;; Test interleaved insertions maintain ordering
    (insert! conn {:id "between-10-11"} {:rel :after :sibling "item-10"})
    (insert! conn {:id "between-20-21"} {:rel :after :sibling "item-20"})

    (let [items (children-ids @conn "root")
          item-10-idx (.indexOf items "item-10")
          item-20-idx (.indexOf items "item-20")]
      (is (= "between-10-11" (nth items (inc item-10-idx))))
      (is (= "between-20-21" (nth items (inc item-20-idx))))

      ;; Orders should still be sorted
      (let [orders (mapv #(:order (e @conn %)) items)]
        (is (= orders (sort orders)) "Interleaved insertions preserve order")))

    ;; Bulk operations should work efficiently
    (doseq [i (range 0 50 5)] ; Delete every 5th item
      (delete! conn (str "item-" i)))

    (is (= 42 (count (children-ids @conn "root"))) "Bulk deletes worked")))

(deftest utility-functions-and-edge-cases
  "Test helper functions and edge cases"
  (let [conn (tree-fixture)]
    ;; Test neighbors function
    (is (= [nil "b"] (neighbors ["a" "b" "c"] "a")))
    (is (= ["a" "c"] (neighbors ["a" "b" "c"] "b")))
    (is (= ["b" nil] (neighbors ["a" "b" "c"] "c")))
    (is (= nil (neighbors ["a" "b" "c"] "missing")))

    ;; Test empty tree operations
    (is (= [] (children-ids @conn "root")))
    (insert! conn {:id "first"} {:parent "root"})
    (is (= ["first"] (children-ids @conn "root")))

    ;; Test single child operations
    (insert! conn {:id "before-first"} {:rel :before :sibling "first"})
    (insert! conn {:id "after-first"} {:rel :after :sibling "first"})
    (is (= ["before-first" "first" "after-first"] (children-ids @conn "root")))

    ;; Test entity resolution
    (is (= "first" (:id (e @conn "first"))))
    (is (= "root" (pid @conn "first")))
    (is (nil? (e @conn "nonexistent")))

    ;; Test tree structure helper
    (insert! conn {:id "child-of-first"} {:parent "first"})
    (is (= ["first" [["child-of-first" []]]]
           (tree-structure conn "first")))))

;; Run tests when file is loaded
