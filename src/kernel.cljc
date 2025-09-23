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

;; ## Write Model: Core API
;; ----------------------------------------------------------------------------

(defn- calculate-order
  "Calculates a fractional order using targeted Datalog aggregate queries."
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
  "Recursively traverses a nested entity map, generating a flat vector of transaction data.
   This is the core of the declarative-to-imperative transformation."
  [parent-id entity-tree]
  (let [child-maps (get entity-tree :children [])
        child-ids (mapv #(or (:id %) (str (random-uuid))) child-maps)]
    (-> (dissoc entity-tree :children)
        (assoc :parent [:id parent-id])
        ;; The root of this subtree goes first in the transaction list.
        (vector)
        ;; Then, recursively process all children.
        (into (mapcat
               (fn [[i child-map] child-id]
                 (tree->txns (:id entity-tree) (assoc child-map :id child-id :order (double i))))
               (map-indexed vector child-maps)
               child-ids)))))

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

(defn position!
  "Upserts a (potentially nested) entity map to a specified relational position."
  [conn entity-map position-spec]
  (let [db @conn
        parent-id (if (#{:first :last} (:rel position-spec))
                    (:target position-spec)
                    (d/q '[:find ?pid . :in $ ?tid :where [?t :id ?tid] [?t :parent ?p] [?p :id ?pid]]
                         db (:target position-spec)))
        parent-ref [:id parent-id]
        order (calculate-order db parent-ref position-spec)
        entity-id (or (:id entity-map) (str (random-uuid)))

        ;; Separate the root entity from its children for processing
        root-attrs (dissoc entity-map :children)
        child-trees (:children entity-map [])

        ;; Prepare the root entity transaction data
        final-root-entity (assoc root-attrs
                                 :id entity-id
                                 :order order
                                 :parent parent-ref)

        ;; Recursively generate transaction data for all descendants
        child-txns (mapcat
                    (fn [[i child-tree]]
                      (let [child-id (or (:id child-tree) (str (random-uuid)))]
                         ;; We assign a simple order here; the main `calculate-order` positions the whole block.
                        (tree->txns entity-id (assoc child-tree :id child-id :order (double i)))))
                    (map-indexed vector child-trees))]

    ;; Phase 1: Create all entities (without :children references)
    (d/transact! conn (-> [final-root-entity]
                          (into child-txns)))

    ;; Phase 2: Add :children relationships for cascade delete
    (letfn [(add-children-refs [parent-id]
              (let [child-ids (children-ids @conn parent-id)]
                (when (seq child-ids)
                  ;; Add children references for this parent
                  (d/transact! conn (mapv #(vector :db/add [:id parent-id] :children [:id %]) child-ids))
                  ;; Recursively add for all children
                  (doseq [child-id child-ids]
                    (add-children-refs child-id)))))]
      (add-children-refs entity-id))))

(defn patch!
  "Partially updates an entity with a map of new attribute values."
  [conn entity-id attr-map]
  (d/transact! conn (mapv (fn [[k v]] [:db/add [:id entity-id] k v]) attr-map)))

;; ## Read Model: View Helpers (Unchanged)
;; ----------------------------------------------------------------------------
(defn delete!
  "Deletes an entity and all its descendants."
  [conn entity-id]
  (d/transact! conn [[:db/retractEntity [:id entity-id]]]))

;; ## Tests
;; ----------------------------------------------------------------------------
(deftest kernel-api-tests
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root", :order 0.0}])

    ;; --- Test Nested Insert ---
    (let [nested-content {:id "main"
                          :text "Content"
                          :children [{:id "p1" :text "Paragraph 1"}
                                     {:id "p2" :text "Paragraph 2"
                                      :children [{:id "span1" :text "Span"}]}]}]
      (position! conn nested-content {:rel :first, :target "root"}))

    (is (= ["main"] (children-ids @conn "root")) "Root should have one child: main")
    (is (= "Content" (:text (d/entity @conn [:id "main"]))) "Main should have correct text")
    (is (= ["p1" "p2"] (children-ids @conn "main")) "Main should have two children")
    (is (= ["span1"] (children-ids @conn "p2")) "P2 should have one child")

    ;; --- Test Move of a sub-tree ---
    (position! conn {:id "header", :text "Header"} {:rel :first, :target "root"})
    (is (= ["header" "main"] (children-ids @conn "root")))

    ;; Move "main" (and its children) to be after "header"
    (position! conn {:id "main"} {:rel :after, :target "header"})
    (let [main-entity (d/entity @conn [:id "main"])]
      (is (= "root" (get-in main-entity [:parent :id])) "Parent of main should still be root")
      (is (> (:order main-entity) (:order (d/entity @conn [:id "header"]))) "Order should be updated"))
    (is (= ["p1" "p2"] (children-ids @conn "main")) "Children of main should persist after move")

    ;; --- Test Delete ---
    (delete! conn "main")
    (is (= ["header"] (children-ids @conn "root")) "Main should be deleted")
    (is (nil? (d/entity @conn [:id "p1"])) "p1 should be gone")
    (is (nil? (d/entity @conn [:id "p2"])) "p2 should be gone")
    (is (nil? (d/entity @conn [:id "span1"])) "span1 should be gone")))

;; Run tests automatically when file is loaded.
(let [results (run-tests)]
  (println)
  (println "Kernel tests complete.")
  (println "Tests run:" (-> results :test))
  (println "Assertions:" (-> results :pass))
  (println "Failures:" (-> results :fail)))