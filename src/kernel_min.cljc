(ns kernel-min
  (:require [datascript.core :as d]
            [clojure.test :refer [deftest is run-tests]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

;; ---------- Schema (make order cheap to mutate) ----------
(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :pos {:db/cardinality :db.cardinality/one :db/index true}
   :references {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}})

;; ---------- Core helpers ----------
(defn e [db id]
  (d/entity db [:id id]))

(defn children-ids [db parent-id]
  (->> (d/q '[:find [?id ...]
              :in $ ?parent
              :where [?parent-e :id ?parent]
              [?e :parent ?parent-e]
              [?e :id ?id]]
            db parent-id)
       (sort-by #(:pos (e db %)))))

;; ---------- Position helpers ----------
(defn splice [v idx x]
  (let [i (min (max 0 (or idx (count v))) (count v))]
    (into (subvec v 0 i) (cons x (subvec v i)))))

(defn reorder-tx [parent-id ids]
  (map-indexed (fn [i id] [:db/add [:id id] :pos i]) ids))

(defn target [db {:keys [parent sibling rel idx] :as pos}]
  (let [p (or parent (when sibling (:id (:parent (e db sibling))))
              (throw (ex-info "No parent specified" {:position pos})))
        ks (children-ids db p)
        i (cond
            (= rel :first) 0
            (= rel :last) (count ks)
            (= rel :before) (let [j (.indexOf ks sibling)] (if (neg? j) (count ks) j))
            (= rel :after) (let [j (.indexOf ks sibling)] (if (neg? j) (count ks) (inc j)))
            :else (min (or idx (count ks)) (count ks)))]
    [p i]))

;; ---------- Tree operations ----------
(def cid (atom 0))

(defn insert! [conn entity pos]
  (let [db @conn
        {:keys [id]} entity
        id (or id (str "auto-" (swap! cid inc)))
        entity (assoc entity :id id)
        [p i] (target db pos)
        temp-id (- (swap! cid inc))
        tx (concat [[:db/add temp-id :id id]
                    [:db/add temp-id :parent [:id p]]]
                   (for [[k v] (dissoc entity :id :children)]
                     [:db/add [:id id] k v]))]
    (d/transact! conn tx)
    (let [siblings (children-ids @conn p)
          reordered (splice (vec (remove #{id} siblings)) i id)]
      (d/transact! conn (reorder-tx p reordered)))))

(defn move! [conn id pos]
  (let [db @conn
        desc (set (tree-seq #(seq (children-ids db %)) #(children-ids db %) id))
        [p i] (target db pos)]
    (when (contains? desc p)
      (throw (ex-info "Cycle detected" {:entity id :target p})))
    (let [old (:id (:parent (e db id)))
          same? (= old p)
          oldks (children-ids db old)
          newks (if same? oldks (children-ids db p))
          final-old (if same? [] (vec (remove #{id} oldks)))
          final-new (-> (vec (remove #{id} newks)) (splice i id))
          tx (cond-> []
               (not same?) (conj [:db/add [:id id] :parent [:id p]])
               (not same?) (into (reorder-tx old final-old))
               true (into (reorder-tx p final-new)))]
      (d/transact! conn tx))))

(defn set-refs! [conn id attr ids]
  (d/transact! conn
               (concat [[:db.fn/retractAttribute [:id id] attr]]
                       (for [rid ids] [:db/add [:id id] attr [:id rid]]))))

(defn delete! [conn entity-id]
  (let [db @conn
        parent (:id (:parent (e db entity-id)))
        siblings (children-ids db parent)]
    (d/transact! conn [[:db.fn/retractEntity [:id entity-id]]])
    (d/transact! conn (reorder-tx parent (vec (remove #{entity-id} siblings))))))

(defn update! [conn entity-id attrs]
  (let [tx (for [[k v] attrs]
             [:db/add [:id entity-id] k v])]
    (d/transact! conn tx)))

;; ---------- Test fixtures ----------
(defn- tree-fixture []
  (let [conn (d/create-conn schema)]
    (d/transact! conn [[:db/add -1 :id "root"] [:db/add -1 :pos 0]])
    (insert! conn {:id "a"} {:parent "root"})
    (insert! conn {:id "b"} {:parent "root"})
    (insert! conn {:id "c"} {:parent "a"})
    conn))

(defn- tree-structure [conn & paths]
  (let [db @conn]
    (into {}
          (for [path paths]
            [path (map :id (:children (e db (last path))))]))))

;; ---------- Tests ----------
(deftest basic-insert-test
  (let [conn (tree-fixture)]
    (is (= '("a" "b") (children-ids @conn "root")))
    (is (= '("c") (children-ids @conn "a")))))

(deftest position-resolution-test
  (let [conn (tree-fixture)]
    ; Test various position specifications
    (insert! conn {:id "d"} {:parent "root" :idx 1})
    (is (= '("a" "d" "b") (children-ids @conn "root")))

    (insert! conn {:id "e"} {:parent "root" :rel :first})
    (is (= '("e" "a" "d" "b") (children-ids @conn "root")))

    (insert! conn {:id "f"} {:parent "root" :rel :last})
    (is (= '("e" "a" "d" "b" "f") (children-ids @conn "root")))

    (insert! conn {:id "g"} {:sibling "d" :rel :before})
    (is (= '("e" "a" "g" "d" "b" "f") (children-ids @conn "root")))

    (insert! conn {:id "h"} {:sibling "d" :rel :after})
    (is (= '("e" "a" "g" "d" "h" "b" "f") (children-ids @conn "root")))))

(deftest tree-operations-test
  (let [conn (tree-fixture)]
    ; Test move operations
    (move! conn "c" {:parent "root"})
    (is (= '("a" "b" "c") (children-ids @conn "root")))
    (is (= '() (children-ids @conn "a")))

    ; Test delete operations
    (delete! conn "b")
    (is (= '("a" "c") (children-ids @conn "root")))))

(deftest cycle-detection-test
  (let [conn (tree-fixture)]
    ; Try to create a cycle: make "root" a child of "a"
    (is (thrown? Exception (move! conn "root" {:parent "a"})))))

(deftest cross-reference-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "form", :type "form"} {:parent "root"})
    (insert! conn {:id "input", :type "input", :name "email"} {:parent "form"})
    (insert! conn {:id "validator", :type "validator", :pattern "email"} {:parent "root"})

    ; Add cross-references using the :references attribute
    (update! conn "input" {:references [:id "validator"]})

    ; Verify relationships exist
    (is (= 1 (count (:references (e @conn "input")))))

    ; Test navigation patterns
    (let [validator-users (d/q '[:find [?id ...]
                                 :in $ ?validator-ref
                                 :where [?e :references ?validator-ref]
                                 [?e :id ?id]]
                               @conn [:id "validator"])]
      (is (= ["input"] validator-users)))))

;; Property-based testing generators and helpers
(defn gen-id [] (gen/fmap #(str "id-" %) (gen/choose 1 20)))

(defn gen-operation [db]
  (let [existing-ids (d/q '[:find [?id ...] :where [?e :id ?id]] db)]
    (gen/one-of
     (remove nil?
             [(gen/hash-map :op (gen/return :upsert)
                            :id (gen-id)
                            :pos (gen/hash-map :parent (gen/elements existing-ids)))
              (when (seq existing-ids)
                (gen/hash-map :op (gen/return :move)
                              :id (gen/elements existing-ids)
                              :pos (gen/hash-map :parent (gen/elements existing-ids))))
              (when (seq existing-ids)
                (gen/hash-map :op (gen/return :delete)
                              :id (gen/elements existing-ids)))]))))

(defn apply-operation! [conn op]
  (try
    (case (:op op)
      :upsert (insert! conn {:id (:id op)} (:pos op))
      :move (move! conn (:id op) (:pos op))
      :delete (delete! conn (:id op)))
    true
    (catch Exception _ false)))

(defn has-cycles? [db]
  (let [all-ids (d/q '[:find [?id ...] :where [?e :id ?id]] db)]
    (some (fn [id]
            (try
              (loop [fr #{id} acc #{id} depth 0]
                (if (or (empty? fr) (> depth 100)) false
                    (let [kids (set (mapcat #(children-ids db %) fr))]
                      (if (some acc kids) true
                          (recur kids (into acc kids) (inc depth))))))
              (catch Exception _ true)))
          all-ids)))

(defn children-sorted-by-pos? [db]
  (let [all-ids (d/q '[:find [?id ...] :where [?e :id ?id]] db)]
    (every? (fn [id]
              (let [kids (children-ids db id)
                    positions (map #(:pos (e db %)) kids)]
                (= positions (sort positions))))
            all-ids)))

(defn pids-round-trip? [db]
  (let [all-ids (d/q '[:find [?id ...] :where [?e :id ?id]] db)]
    (every? (fn [id]
              (if-let [parent (:id (:parent (e db id)))]
                (some #{id} (children-ids db parent))
                true)) ; root nodes are fine
            all-ids)))

(deftest property-fuzz-test
  (let [property (prop/for-all
                  [ops (gen/vector (gen/bind (gen/return {})
                                             (fn [_] (gen-operation @(tree-fixture))))
                                   5 10)]
                  (let [conn (tree-fixture)]
                    (doseq [op ops]
                      (apply-operation! conn op))
                    (let [db @conn]
                      (and (not (has-cycles? db))
                           (children-sorted-by-pos? db)
                           (pids-round-trip? db)))))]
    (is (:pass? (tc/quick-check 50 property)))))

(run-tests)