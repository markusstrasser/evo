(ns kernel
  (:require [datascript.core :as d]
            [clojure.test :refer [deftest is run-tests]]))

;; ## Schema
;; ----------------------------------------------------------------------------
;; The schema defines the shape of our data.
;; - `:id` is the public, stable, unique identifier for an entity.
;; - `:parent` establishes the child-to-parent tree structure.
;; - `:children` establishes the parent-to-child tree structure. By making it a component
;;   (`:db/isComponent true`), DataScript handles recursive deletion automatically:
;;   when a parent is deleted, all its children are automatically deleted too.
;; - `:order` is a fractional index to maintain a sort order among siblings.
(def schema {:id {:db/unique :db.unique/identity}
             :parent {:db/valueType :db.type/ref}
             :children {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many
                        :db/isComponent true}
             :order {:db/index true}})

;; ## Write Model: Core API
;; ----------------------------------------------------------------------------
;; These functions constitute the "command" or "write" part of the API.
;; They are concerned with calculating and transacting state changes.

(defn- calculate-order
  "Calculates a fractional order using targeted Datalog aggregate queries.
   This is a private implementation detail of the write model."
  [db {:keys [rel target]}]
  (let [target-ref [:id target]]
    (case rel
      :first
      (if-let [min-order (d/q '[:find (min ?o) . :in $ ?p :where [_ :parent ?p] [_ :order ?o]] db target-ref)]
        (/ min-order 2.0)
        1.0)

      :last
      (if-let [max-order (d/q '[:find (max ?o) . :in $ ?p :where [_ :parent ?p] [_ :order ?o]] db target-ref)]
        (inc max-order)
        1.0)

      :before
      (let [parent-ref (ffirst (d/q '[:find ?p :in $ ?s :where [?s :parent ?p]] db target-ref))
            s-order (ffirst (d/q '[:find ?o :in $ ?s :where [?s :order ?o]] db target-ref))
            prev-order (d/q '[:find (max ?o) . :in $ ?p ?s-order :where [_ :parent ?p] [_ :order ?o] [(< ?o ?s-order)]] db parent-ref s-order)]
        (/ (+ (or prev-order 0.0) s-order) 2.0))

      :after
      (let [parent-ref (ffirst (d/q '[:find ?p :in $ ?s :where [?s :parent ?p]] db target-ref))
            s-order (ffirst (d/q '[:find ?o :in $ ?s :where [?s :order ?o]] db target-ref))
            next-order (d/q '[:find (min ?o) . :in $ ?p ?s-order :where [_ :parent ?p] [_ :order ?o] [(> ?o ?s-order)]] db parent-ref s-order)]
        (/ (+ s-order (or next-order (+ s-order 2.0))) 2.0)))))

(defn position!
  "Upserts an entity map to a specified relational position."
  [conn entity-map position-spec]
  (let [db @conn
        order (calculate-order db position-spec)
        parent-id (if (#{:first :last} (:rel position-spec))
                    (:target position-spec)
                          ;; Correctly query for the target's parent's ID.
                    (d/q '[:find ?pid .
                           :in $ ?target-id
                           :where
                           [?target :id ?target-id]
                           [?target :parent ?p]
                           [?p :id ?pid]]
                         db (:target position-spec)))
        entity-id (or (:id entity-map) (str (random-uuid)))
        final-entity (assoc entity-map
                            :id entity-id
                            :order order
                            :parent [:id parent-id])]
    ;; Transact the entity and update parent's children
    (d/transact! conn [final-entity
                       [:db/add [:id parent-id] :children [:id entity-id]]])))

(defn patch!
  "Partially updates an entity with a map of new attribute values.
   Unlike `position!`, this only affects the specified attributes and does
   not change an entity's position."
  [conn entity-id attr-map]
  (let [tx-data (mapv (fn [[k v]] [:db/add [:id entity-id] k v]) attr-map)]
    (d/transact! conn tx-data)))

(defn delete!
  "Deletes an entity and all its descendants automatically via DataScript's :db/isComponent."
  [conn entity-id]
  ;; DataScript will automatically delete all children due to :db/isComponent true on :children
  (d/transact! conn [[:db/retractEntity [:id entity-id]]]))

;; ## Read Model: View Helpers
;; ----------------------------------------------------------------------------
;; This section contains functions for querying and viewing the state.

(defn children-ids
  "Returns a sorted vector of child IDs for a given parent."
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

;; ## Tests
;; ----------------------------------------------------------------------------
;; This block defines the tests for the kernel's functionality.

(deftest kernel-api-tests
  (let [conn (d/create-conn schema)]
    ;; Populate with initial data
    (d/transact! conn
                 [{:id "root", :order 0.0, :children [[:id "header"] [:id "main"]]}
                  {:id "header", :parent [:id "root"], :order 1.0, :text "Header", :children [[:id "nav"]]}
                  {:id "main", :parent [:id "root"], :order 2.0, :text "Content"}
                  {:id "nav", :parent [:id "header"], :order 1.0, :text "Nav"}])

    ;; --- Initial State ---
    (is (= ["header" "main"] (children-ids @conn "root")))
    (is (= ["nav"] (children-ids @conn "header")))

    ;; --- Test Inserts (via position!) ---
    (position! conn {:id "footer", :text "Footer"} {:rel :last, :target "root"})
    (is (= ["header" "main" "footer"] (children-ids @conn "root")))

    (position! conn {:id "sidebar", :text "Sidebar"} {:rel :first, :target "root"})
    (is (= ["sidebar" "header" "main" "footer"] (children-ids @conn "root")))

    (position! conn {:id "ad", :text "Ad"} {:rel :after, :target "main"})
    (is (= ["sidebar" "header" "main" "ad" "footer"] (children-ids @conn "root")))

    ;; --- Test Moves (via position!) ---
    (position! conn {:id "header"} {:rel :before, :target "ad"})
    (is (= ["sidebar" "main" "header" "ad" "footer"] (children-ids @conn "root")))
    ;; Verify that the move did not delete other attributes
    (is (= "Header" (:text (d/entity @conn [:id "header"]))))

    ;; --- Test Patch ---
    (patch! conn "main" {:text "New Content"})
    (is (= "New Content" (:text (d/entity @conn [:id "main"]))))
    ;; Verify patch did not affect position
    (is (= ["sidebar" "main" "header" "ad" "footer"] (children-ids @conn "root")))

    ;; --- Test Deletes (via :db/isComponent) ---
    ;; Deleting "header" should also delete its child "nav" due to the schema.
    (delete! conn "header")
    (is (= ["sidebar" "main" "ad" "footer"] (children-ids @conn "root")))
    (is (nil? (d/entity @conn [:id "header"])))
    (is (nil? (d/entity @conn [:id "nav"])))))

;; Run tests automatically when file is loaded.
(let [results (run-tests)]
  (println)
  (println "Kernel tests complete.")
  (println "Tests run:" (-> results :test))
  (println "Assertions:" (-> results :pass))
  (println "Failures:" (-> results :fail)))