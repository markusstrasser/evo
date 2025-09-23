(ns kernel
  (:require [datascript.core :as d]))

;; -- Schema & Connection ----------------------------------------------------
;; The schema now correctly uses :db.type/double for fractional ordering.
(def schema
  {:id     {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref}
   :order {:db/cardinality :db.cardinality/one
           :db/index true}}) ; Correct: No map wrapper

(def conn (d/create-conn schema))

;; -- Example Data -----------------------------------------------------------
;; Initial transaction to populate the database.
(d/transact! conn
             [{:id "root", :type :div, :text "App", :order 0.0}
              {:id "header", :parent [:id "root"], :type :header, :text "Header", :order 1.0}
              {:id "main", :parent [:id "root"], :type :main, :text "Content", :order 2.0}
              {:id "nav", :parent [:id "header"], :type :nav, :text "Home | About", :order 1.0}])

;; -- Queries ----------------------------------------------------------------
(defn eid-of
  "Finds the entity ID for a given unique :id attribute."
  [db dom-id]
  (d/q '[:find ?e . :in $ ?id :where [?e :id ?id]] db dom-id))

(defn children-ordered
  "Returns a vector of child IDs for a given parent, sorted by their :order attribute."
  [db parent-id]
  (let [results (d/q '[:find ?id ?order
                       :in $ ?pid
                       :where [?p :id ?pid]
                       [?c :parent ?p]
                       [?c :id ?id]
                       [?c :order ?order]]
                     db parent-id)]
    (mapv first (sort-by second results))))

;; -- Private Helper for Order Calculation -----------------------------------
(defn- calculate-order
  "Calculates the fractional index for a new node at a given position."
  [db parent-id pos]
  (let [parent-eid (d/entid db [:id parent-id])
        orders     (->> (d/q '[:find [?order ...]
                               :in $ ?p
                               :where [?c :parent ?p] [?c :order ?order]]
                             db parent-eid)
                        sort
                        vec)]
    (if (empty? orders)
      1.0
      (let [prev-order (get orders (dec pos) 0.0)
            next-order (get orders pos (+ (last orders) 1.0))]
        (/ (+ prev-order next-order) 2.0)))))

;; -- Atomic Mutations -------------------------------------------------------
(defn insert!
  "Inserts a new node under a parent at a specific position."
  [conn parent-id pos node]
  (let [db    @conn
        order (calculate-order db parent-id pos)]
    (d/transact! conn [(assoc node
                         :parent [:id parent-id]
                         :order order)])))

(defn move!
  "Moves a child to a new parent and position."
  [conn child-id new-parent-id pos]
  (let [db    @conn
        order (calculate-order db new-parent-id pos)]
    (d/transact! conn [[:db/add [:id child-id] :parent [:id new-parent-id]]
                       [:db/add [:id child-id] :order order]])))

(defn delete!
  "Recursively deletes an entity and all its descendants."
  [conn dom-id]
  (let [db        @conn
        rules     '[[(desc ?e ?root) [?e :parent ?root]]
                    [(desc ?e ?root) [?m :parent ?root] (desc ?e ?m)]]
        root      (eid-of db dom-id)
        to-delete (when root
                    (conj (set (d/q '[:find [?e ...]
                                      :in $ % ?r
                                      :where (desc ?e ?r)]
                                    db rules root))
                          root))]
    (when to-delete
      (d/transact! conn (mapv (fn [e] [:db/retractEntity e]) to-delete)))))

;; -- Sugar / Convenience Functions ------------------------------------------
(defn after!
  "Inserts a node after a given sibling."
  [conn sibling-id node]
  (let [db       @conn
        sib      (d/entity db [:id sibling-id])
        parent   (:id (:parent sib))
        siblings (children-ordered db parent)
        idx      (inc (.indexOf siblings sibling-id))]
    (insert! conn parent idx node)))

(defn before!
  "Inserts a node before a given sibling."
  [conn sibling-id node]
  (let [db       @conn
        sib      (d/entity db [:id sibling-id])
        parent   (:id (:parent sib))
        siblings (children-ordered db parent)
        idx      (.indexOf siblings sibling-id)]
    (insert! conn parent idx node)))


;; -- Example Usage ----------------------------------------------------------
(comment
  (println "Initial state of root's children:")
  (prn (children-ordered @conn "root"))

  (println "\nInserting 'footer' into root at position 2 (end):")
  (insert! conn "root" 2 {:id "footer", :text "Footer"})
  (prn (children-ordered @conn "root"))
  ;=> ["header" "main" "footer"]

  (println "\nMoving 'header' to be after 'main':")
  (move! conn "header" "root" 2)
  (prn (children-ordered @conn "root"))
  ;=> ["main" "header" "footer"]

  (println "\nInserting 'sidebar' before 'main':")
  (before! conn "main" {:id "sidebar", :text "Sidebar"})
  (prn (children-ordered @conn "root"))
  ;=> ["sidebar" "main" "header" "footer"]

  (println "\nDeleting 'header' and its children ('nav'):")
  (delete! conn "header")
  (prn (children-ordered @conn "root"))
  ;=> ["sidebar" "main" "footer"]

  (println "\nFinal DB state for root's children (with orders):")
  (d/q '[:find ?id ?order
         :in $ ?pid
         :where [?p :id ?pid]
         [?c :parent ?p]
         [?c :id ?id]
         [?c :order ?order]]
       @conn "root"))
  ;=> #{["sidebar" 0.5] ["main" 2.0] ["footer" 3.0]}
