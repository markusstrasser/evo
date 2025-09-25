(ns evolver.structural-editing
  (:require [evolver.tree :refer [apply-op! insert! schema]]
            [clojure.test :refer [deftest is run-tests]]
            [datascript.core :as d]))
(defn conn []
  (doto (d/create-conn schema)
    (d/transact! [[:db/add -1 :id "root"] [:db/add -1 :position 0]])
    (insert! {:id "a"} {:parent "root"})))

(def rules
  '[[(ancestor ?a ?d) [?d :parent ?a]]
    [(ancestor ?a ?d) [?d :parent ?m] (ancestor ?a ?m)]
    [(descendant ?d ?a) (ancestor ?a ?d)]])

;; depth = number of ancestors
(def db @(conn))
;;:in $ ?p means "expect database as first param, parent-id as second param."
(d/q '[:find (count ?a) .
       :in $ % ?e
       :where (ancestor ?a ?e)]
     db rules [:id "a"])
(defn get-ancestors [db eid]
  (vec (->>
         (iterate #(get-in (d/pull db [:parent] %) [:parent :db/id]) eid)
         rest
         (take-while some?))))

(d/pull db [:id {:_parent [:id :position]}] [:id "root"])
;The ... collection forms:
;
;[?var ...] → vector of scalars
;[(pull ?var [...]) ...] → vector of maps
;[?var ?other ...] → vector of tuples

;; #:db{:id 1} is {:db/id 1}
;;pull by entityID...eID
(d/pull db '[*] 1)
(get-ancestors db 2)

(def colls
  (d/q '[:find (pull ?e [*])
         :where [?e]] db))



(get-ancestors db 1)
(defn db->id:entity [db]
  (map (fn [coll] (let [m (first coll)]
                    {(keyword (:id m))
                     (assoc m
                       :parent-id (get-in m [:parent :db/id])
                       :level (count (get-ancestors db (:db/id m))))})) colls))

(def m {:db/id 2, :id "a", :parent #:db {:id 1}, :position 0})
(map #({:a %}))

(d/pull @db '[*] [:id "root"])

(get-tree (conn))
;(deftest structural-editing-test
;  (let [conn (conn)]
;    (is (= '("a") (tree/get-child-ids @conn "root")))))