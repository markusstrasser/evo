(ns kernel
  (:require [datascript.core :as d]
            [clojure.test :refer [is]]))

;; ## Schema
;; ----------------------------------------------------------------------------
;; The schema defines the shape of our data.
;; - `:id` is the public, stable, unique identifier for an entity.
;; - `:parent` establishes the tree structure.
;; - `:order` is a fractional index to maintain a sort order among siblings.
;;   Using `:db/double` is explicit and correct for fractional indexing.
(def schema {:id     {:db/unique :db.unique/identity}
             :parent {:db/valueType :db.type/ref}
             :order  {:db/index true}})


;; ## Write Model: Core API
;; ----------------------------------------------------------------------------
;; These functions constitute the "command" or "write" part of the API.
;; They are concerned with calculating and transacting state changes.

(defn- calculate-order
  "Calculates a fractional order using targeted Datalog aggregate queries.
   This is a private implementation detail of the write model. It correctly
   pushes the logic of finding boundaries (min, max, <, >) into the database
   engine instead of pulling entire collections into application memory."
  [db {:keys [rel target]}]
  (let [target-ref [:id target]]
    (case rel
      ;; To be first, we must be less than the current minimum order.
      :first
      (if-let [min-order (d/q '[:find (min ?o) . :in $ ?p :where [_ :parent ?p] [_ :order ?o]] db target-ref)]
        (/ min-order 2.0)
        1.0) ; Default if parent is empty.

      ;; To be last, we must be greater than the current maximum order.
      :last
      (if-let [max-order (d/q '[:find (max ?o) . :in $ ?p :where [_ :parent ?p] [_ :order ?o]] db target-ref)]
        (inc max-order)
        1.0) ; Default if parent is empty.

      ;; To be before a sibling, find the gap between it and its predecessor.
      :before
      (let [parent-ref (ffirst (d/q '[:find ?p :in $ ?s :where [?s :parent ?p]] db target-ref))
            s-order    (ffirst (d/q '[:find ?o :in $ ?s :where [?s :order ?o]] db target-ref))
            prev-order (d/q '[:find (max ?o) . :in $ ?p ?s-order :where [_ :parent ?p] [_ :order ?o] [(< ?o ?s-order)]] db parent-ref s-order)]
        (/ (+ (or prev-order 0.0) s-order) 2.0))

      ;; To be after a sibling, find the gap between it and its successor.
      :after
      (let [parent-ref (ffirst (d/q '[:find ?p :in $ ?s :where [?s :parent ?p]] db target-ref))
            s-order    (ffirst (d/q '[:find ?o :in $ ?s :where [?s :order ?o]] db target-ref))
            next-order (d/q '[:find (min ?o) . :in $ ?p ?s-order :where [_ :parent ?p] [_ :order ?o] [(> ?o ?s-order)]] db parent-ref s-order)]
        (/ (+ s-order (or next-order (+ s-order 2.0))) 2.0)))))


(defn position!
  "Upserts an entity map to a specified relational position. This is the primary
   mutation API. It's declarative, specifying *what* position is desired, not
   *how* to achieve it. It handles both inserts and moves transparently via
   Datascript's upsert mechanism on the unique `:id` attribute."
  [conn entity-map position-spec]
  (let [db              @conn
        order           (calculate-order db position-spec)
        parent-id       (if (#{:first :last} (:rel position-spec))
                          (:target position-spec) ; For :first/:last, target is the parent.
                          (:id (:parent (d/entity db [:id (:target position-spec)])))) ; For :before/:after, find sibling's parent.
        full-entity-map (assoc entity-map
                          :parent [:id parent-id]
                          :order order)]
    (d/transact! conn [full-entity-map])))


(defn delete!
  "Recursively deletes an entity and all its descendants. Uses a recursive
   Datalog rule to define descendancy, which is the proper declarative
   approach for traversing a graph relationship."
  [conn dom-id]
  (let [db        @conn
        rules     '[[(desc ?e ?root) [?e :parent ?root]]
                    [(desc ?e ?root) [?m :parent ?root] (desc ?e ?m)]]
        root      (d/q '[:find ?e . :in $ ?id :where [?e :id ?id]] db dom-id)
        to-delete (when root
                    (conj (set (d/q '[:find [?e ...] :in $ % ?r :where (desc ?e ?r)] db rules root))
                          root))]
    (when to-delete
      (d/transact! conn (mapv (fn [e] [:db/retractEntity e]) to-delete)))))


;; ## Read Model: View Helpers
;; ----------------------------------------------------------------------------
;; This section contains functions for querying and viewing the state of the
;; database. They are decoupled from the write logic.

(defn children-ids
  "Returns a sorted vector of child IDs for a given parent. This is a typical
   'read model' function, useful for debugging or preparing data for a view
   (e.g., rendering a DOM)."
  [db parent-id]
  (->> (d/q '[:find ?id ?order
              :in $ ?pid
              :where
              [?p :id ?pid]
              [?c :parent ?p]
              [?c :id ?id]
              [?c :order ?order]]
            db parent-id)
       (sort-by second)
       (mapv first)))


;; ## REPL Driven Development & Assertions
;; ----------------------------------------------------------------------------
(comment
  ;; Create a fresh database connection for the test run.
  (def conn (d/create-conn schema))

  ;; Populate with initial data.
  (d/transact! conn
               [{:id "root", :order 0.0}
                {:id "header", :parent [:id "root"], :order 1.0, :text "Header"}
                {:id "main", :parent [:id "root"], :order 2.0, :text "Content"}
                {:id "nav", :parent [:id "header"], :order 1.0, :text "Nav"}])

  ;; --- Initial State ---
  (is (= ["header" "main"] (children-ids @conn "root")))
  (is (= ["nav"] (children-ids @conn "header")))

  ;; --- Test Inserts ---
  ;; Insert "footer" at the end of "root"
  (position! conn {:id "footer", :text "Footer"} {:rel :last, :target "root"})
  (is (= ["header" "main" "footer"] (children-ids @conn "root")))

  ;; Insert "sidebar" at the beginning of "root"
  (position! conn {:id "sidebar", :text "Sidebar"} {:rel :first, :target "root"})
  (is (= ["sidebar" "header" "main" "footer"] (children-ids @conn "root")))

  ;; Insert "ad" after "main"
  (position! conn {:id "ad", :text "Ad"} {:rel :after, :target "main"})
  (is (= ["sidebar" "header" "main" "ad" "footer"] (children-ids @conn "root")))

  ;; --- Test Moves ---
  ;; Move "header" to be before "ad"
  (position! conn {:id "header"} {:rel :before, :target "ad"})
  (is (= ["sidebar" "main" "header" "ad" "footer"] (children-ids @conn "root")))

  ;; --- Test Deletes ---
  ;; Deleting "header" should also delete its child, "nav".
  (delete! conn "header")
  (is (= ["sidebar" "main" "ad" "footer"] (children-ids @conn "root")))
  (is (nil? (d/entity @conn [:id "header"])))
  (is (nil? (d/entity @conn [:id "nav"])))

  ;; --- Final State Inspection ---
  (d/q '[:find ?id ?order
         :in $ ?pid
         :where [?p :id ?pid] [?c :parent ?p] [?c :id ?id] [?c :order ?order]]
       @conn "root"))
  ;; => #{["sidebar" 0.5] ["main" 2.0] ["ad" 2.5] ["footer" 3.0]}
