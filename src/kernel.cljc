(ns kernel
  (:require [datascript.core :as d]
            [clojure.test :refer [deftest is run-tests]]))

;; ## Schema (Unchanged)
;; ----------------------------------------------------------------------------
(def schema {:id {:db/unique :db.unique/identity}
             :parent {:db/valueType :db.type/ref}
             :children {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many
                        :db/isComponent true}
             :order {:db/index true}})

;; ## Write Model: Refactored API
;; ----------------------------------------------------------------------------

(defn- calculate-order
  "Calculates a fractional order using targeted Datalog aggregate queries. (Unchanged)"
  [db parent-ref {:keys [rel target]}]
  (case rel
    :first
    (if-let [min-order (d/q '[:find (min ?o) . :in $ ?p :where [_ :parent ?p] [_ :order ?o]] db parent-ref)]
      (/ min-order 2.0)
      1.0)

    :last
    (if-let [max-order (d/q '[:find (max ?o) . :in $ ?p :where [_ :parent ?p] [_ :order ?o]] db parent-ref)]
      (inc max-order)
      1.0)

    (:before :after)
    (let [target-ref [:id target]
          s-order (d/q '[:find ?o . :in $ ?s :where [?s :order ?o]] db target-ref)]
      (if (= rel :before)
        (let [prev-order (d/q '[:find (max ?o) . :in $ ?p ?s-order :where [_ :parent ?p] [_ :order ?o] [(< ?o ?s-order)]] db parent-ref s-order)]
          (/ (+ (or prev-order 0.0) s-order) 2.0))
        (let [next-order (d/q '[:find (min ?o) . :in $ ?p ?s-order :where [_ :parent ?p] [_ :order ?o] [(> ?o ?s-order)]] db parent-ref s-order)]
          (/ (+ s-order (or next-order (+ s-order 2.0))) 2.0))))))

(defn- tree->txns
  "Recursively transforms a nested entity map into a flat vector of transaction data.
   Uses temporary IDs for all references within the same transaction."
  ([entity-map parent-ref order]
   (tree->txns entity-map parent-ref order nil))
  ([entity-map parent-ref order given-temp-id]
   (let [entity-id (or (:id entity-map) (str (random-uuid)))
         child-maps (get entity-map :children [])
         ;; Use given temp-id or generate one
         temp-id (or given-temp-id (str "temp-" entity-id))
         ;; Generate temp IDs for children
         child-temp-ids (mapv #(str "temp-" (or (:id %) (str (random-uuid)))) child-maps)
         ;; Create the root entity map for this subtree
         root-entity (cond-> (-> entity-map
                                 (dissoc :children)
                                 (assoc :db/id temp-id
                                        :id entity-id
                                        :order order
                                        :parent parent-ref))
                       ;; Only add :children if there are actual children (avoid nil values)
                       (seq child-temp-ids) (assoc :children child-temp-ids))]
     ;; Prepend the root entity, then recurse for all children.
     (into [root-entity]
           (mapcat
            (fn [i child-map child-temp-id]
              ;; Pass the child's temp-id so it matches the parent's reference
              (tree->txns child-map temp-id (double i) child-temp-id))
            (range)
            child-maps
            child-temp-ids)))))

(defn- get-parent-id
  "Determines the parent ID from a position specification."
  [db {:keys [rel target]}]
  (if (#{:first :last} rel)
    target
    (d/q '[:find ?pid . :in $ ?tid :where [?t :id ?tid] [?t :parent ?p] [?p :id ?pid]]
         db target)))

(defn create!
  "Creates a new, potentially nested, entity at a specified position."
  [conn entity-map position-spec]
  (let [db @conn
        parent-id (get-parent-id db position-spec)
        parent-ref [:id parent-id]
        order (calculate-order db parent-ref position-spec)
        tx-data (tree->txns entity-map parent-ref order)]
    (d/transact! conn tx-data)))

(defn move!
  "Moves an existing entity (and its subtree) to a new position."
  [conn entity-id position-spec]
  (let [db @conn
        parent-id (get-parent-id db position-spec)
        parent-ref [:id parent-id]
        order (calculate-order db parent-ref position-spec)]
    (d/transact! conn [[:db/add [:id entity-id] :parent parent-ref]
                       [:db/add [:id entity-id] :order order]])))

(defn position!
  "Upserts an entity. Dispatches to create! or move!."
  [conn entity-map position-spec]
  (if (and (:id entity-map) (d/entity @conn [:id (:id entity-map)]))
    (move! conn (:id entity-map) position-spec)
    (create! conn entity-map position-spec)))

(defn patch!
  "Partially updates an entity with a map of new attribute values. (Unchanged)"
  [conn entity-id attr-map]
  (d/transact! conn (mapv (fn [[k v]] [:db/add [:id entity-id] k v]) attr-map)))

(defn delete!
  "Deletes an entity and all its descendants. (Unchanged)"
  [conn entity-id]
  (d/transact! conn [[:db/retractEntity [:id entity-id]]]))

(defn children-ids
  "Returns a sorted vector of child IDs for a given parent. (Unchanged)"
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
(deftest kernel-api-tests
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root", :order 0.0}])

    ;; --- Test Nested Create ---
    (println "\n=== Testing nested create ===")
    (let [nested-content {:id "main"
                          :text "Content"
                          :children [{:id "p1" :text "Paragraph 1"}
                                     {:id "p2" :text "Paragraph 2"
                                      :children [{:id "span1" :text "Span"}]}]}]
      (position! conn nested-content {:rel :first, :target "root"})
      (println "✓ Nested content created successfully"))

    (is (= ["main"] (children-ids @conn "root")) "Root should have one child: main")
    (is (= "Content" (:text (d/entity @conn [:id "main"]))) "Main should have correct text")
    (is (= ["p1" "p2"] (children-ids @conn "main")) "Main should have two children")
    (is (= ["span1"] (children-ids @conn "p2")) "P2 should have one child")

    ;; --- Test Move of a sub-tree ---
    (println "\n=== Testing move operations ===")
    (position! conn {:id "header", :text "Header"} {:rel :first, :target "root"})
    (is (= ["header" "main"] (children-ids @conn "root")) "Header should be first, main second")

    ;; Move "main" (and its children) to be after "header"  
    (position! conn {:id "main"} {:rel :after, :target "header"})
    (let [main-entity (d/entity @conn [:id "main"])]
      (is (= "root" (get-in main-entity [:parent :id])) "Parent of main should still be root")
      (is (> (:order main-entity) (:order (d/entity @conn [:id "header"]))) "Order should be updated")
      (is (= ["p1" "p2"] (children-ids @conn "main")) "Children of main should persist after move"))

    ;; --- Test Delete with cascade ---
    (println "\n=== Testing cascade delete ===")
    (println "Before delete - entities exist:")
    (println "  main:" (boolean (d/entity @conn [:id "main"])))
    (println "  p1:" (boolean (d/entity @conn [:id "p1"])))
    (println "  p2:" (boolean (d/entity @conn [:id "p2"])))
    (println "  span1:" (boolean (d/entity @conn [:id "span1"])))

    (delete! conn "main")
    (is (= ["header"] (children-ids @conn "root")) "Main should be deleted")

    (println "After delete - entities exist:")
    (println "  main:" (boolean (d/entity @conn [:id "main"])))
    (println "  p1:" (boolean (d/entity @conn [:id "p1"])))
    (println "  p2:" (boolean (d/entity @conn [:id "p2"])))
    (println "  span1:" (boolean (d/entity @conn [:id "span1"])))

    (is (nil? (d/entity @conn [:id "p1"])) "p1 should be gone")
    (is (nil? (d/entity @conn [:id "p2"])) "p2 should be gone")
    (is (nil? (d/entity @conn [:id "span1"])) "span1 should be gone")))

(let [results (run-tests)]
  (println)
  (println "Kernel tests complete.")
  (println "Tests run:" (-> results :test))
  (println "Assertions:" (-> results :pass))
  (println "Failures:" (-> results :fail)))