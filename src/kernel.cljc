(ns kernel
  (:require [datascript.core :as d]
            [clojure.test :refer [deftest is run-tests]]))

;; ## Schema
;; ----------------------------------------------------------------------------
(def schema {:id {:db/unique :db.unique/identity}
             :parent {:db/valueType :db.type/ref}
             :order {:db/index true}})

;; ## Write Model: Unified API
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

(defn- resolve-position
  "Calculates {:parent [:id _] :order _} from a position spec.
   Infers parent from target for :before/:after relations."
  [db {:keys [rel target]}]
  (let [parent-id (if (#{:first :last} rel)
                    target
                    (d/q '[:find ?pid . :in $ ?tid :where
                           [?t :id ?tid] [?t :parent ?p] [?p :id ?pid]]
                         db target))
        parent-ref [:id parent-id]]
    {:parent parent-ref
     :order (calculate-order db parent-ref {:rel rel :target target})}))

(defn- data->txns
  "Recursively transforms a nested entity map into a flat vector of upsert transactions."
  [entity-map default-parent-ref default-order]
  (let [entity-id (or (:id entity-map) (str (random-uuid)))
        temp-id (d/tempid :db.part/user)
        children (get entity-map :children [])
        base-entity (-> entity-map
                        (dissoc :children)
                        (assoc :db/id temp-id
                               :id entity-id
                               :parent default-parent-ref
                               :order default-order))
        child-txns (mapcat
                    (fn [i child-map]
                      (data->txns child-map temp-id (double i)))
                    (range)
                    children)]
    (conj child-txns base-entity)))

(defn position!
  "Upserts an entity and its children to a specified position.
   Replaces create!, move!, and the original position! dispatcher."
  [conn entity-map position-spec]
  (let [db @conn
        {:keys [parent order]} (resolve-position db position-spec)
        tx-data (data->txns entity-map parent order)]
    (d/transact! conn tx-data)))

(defn patch!
  "Partially updates an entity with a map of new attribute values."
  [entity-id conn attr-map]
  (d/transact! conn (mapv (fn [[k v]] [:db/add [:id entity-id] k v]) attr-map)))

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

(defn- collect-descendant-ids
  "Recursively finds all descendant entity IDs for a given parent ID."
  [db parent-id]
  (let [child-ids (d/q '[:find [?cid ...] :in $ ?pid :where
                         [?p :id ?pid] [?c :parent ?p] [?c :id ?cid]]
                       db parent-id)]
    (concat child-ids (mapcat #(collect-descendant-ids db %) child-ids))))

;; ## Read Model
;; ----------------------------------------------------------------------------

(defn delete!
  "Deletes an entity and all its descendants recursively."
  [conn entity-id]
  (let [db @conn
        all-to-delete (cons entity-id (collect-descendant-ids db entity-id))
        tx-data (mapv #(vector :db/retractEntity [:id %]) all-to-delete)]
    (d/transact! conn tx-data)))

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
    (delete! conn "main")
    (is (= ["header"] (children-ids @conn "root")) "Main should be deleted")

    (is (nil? (d/entity @conn [:id "p1"])) "p1 should be gone")
    (is (nil? (d/entity @conn [:id "p2"])) "p2 should be gone")
    (is (nil? (d/entity @conn [:id "span1"])) "span1 should be gone")))

(let [results (run-tests)]
  (println)
  (println "Kernel tests complete.")
  (println "Tests run:" (-> results :test))
  (println "Assertions:" (-> results :pass))
  (println "Failures:" (-> results :fail)))