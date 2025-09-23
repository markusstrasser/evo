(ns kernel
  (:require [datascript.core :as d]
            [clojure.string :as s]
            [clojure.test :refer [deftest is run-tests]]))

;; ---------- 1) Schema & rules (unchanged) ----------
(def schema
  {:id           {:db/unique :db.unique/identity}
   :parent       {:db/valueType :db.type/ref}
   :order        {:db/index true}
   :parent+order {:db/tupleAttrs [:parent :order]
                  :db/unique     :db.unique/value}})

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

;; ---------- 7) Tests ----------
(deftest subtree-reparenting-test
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root"}])
    (insert! conn {:id "branch1", :children [{:id "leaf1"} {:id "leaf2"}]} {:rel :first :parent "root"})
    (insert! conn {:id "branch2", :children [{:id "leaf3"}]} {:rel :last :parent "root"})

    (is (= ["branch1" "branch2"] (children-ids @conn "root")))
    (is (= ["leaf1" "leaf2"] (children-ids @conn "branch1")))

    ;; Reparent "branch1" and all its children to be the first child of "branch2"
    (move! conn "branch1" {:rel :first :parent "branch2"})

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

(deftest neighbors-test
  ;; Test helper function behavior
  (is (= [nil "b"] (neighbors ["a" "b" "c"] "a")) "First element")
  (is (= ["a" "c"] (neighbors ["a" "b" "c"] "b")) "Middle element")
  (is (= ["b" nil] (neighbors ["a" "b" "c"] "c")) "Last element")
  (is (= nil (neighbors ["a" "b" "c"] "d")) "Missing element")
  (is (= [nil nil] (neighbors ["a"] "a")) "Single element")
  (is (= nil (neighbors [] "a")) "Empty list"))

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
    (insert! conn {:id       "section-a", :name "Section A",
                   :children [{:id       "comp-1", :name "Component 1",
                               :children [{:id "elem-1", :name "Element 1"}
                                          {:id "elem-2", :name "Element 2"}]}
                              {:id "comp-2", :name "Component 2"}]}
             {:rel :first :parent "root"})
    (insert! conn {:id       "section-b", :name "Section B",
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
    (move! conn "comp-1" {:rel :first :parent "section-b"})

    (is (= ["comp-2"] (children-ids @conn "section-a"))
        "comp-1 should be removed from section-a")
    (is (= ["comp-1" "comp-3"] (children-ids @conn "section-b"))
        "comp-1 should be first child of section-b")
    (is (= ["elem-1" "elem-2"] (children-ids @conn "comp-1"))
        "comp-1 should retain its children after move")

    ;; Test 2: Move an entire section with all its descendants
    (move! conn "section-b" {:rel :after :sibling "section-c"})

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
    (insert! conn {:id       "header", :type "component",
                   :children [{:id       "nav", :type "navigation",
                               :children [{:id "home-link", :type "link"}
                                          {:id "about-link", :type "link"}]}
                              {:id "logo", :type "image"}]}
             {:rel :first :parent "app"})
    (insert! conn {:id       "main", :type "component",
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
    (move! conn "nav" {:rel :first :parent "footer"})

    (is (= ["logo"] (children-ids @conn "header"))
        "nav should be removed from header")
    (is (= ["nav"] (children-ids @conn "footer"))
        "nav should be moved to footer")
    (is (= ["home-link" "about-link"] (children-ids @conn "nav"))
        "nav should preserve its link children")))

(run-tests)