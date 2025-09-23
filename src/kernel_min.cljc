(ns kernel-min
  (:require [datascript.core :as d]
            [clojure.test :refer [deftest is run-tests]]))

;; ---------- Schema (make order cheap to mutate) ----------
(def schema
  {:id {:db/unique :db.unique/identity}
   :parent {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :pos {:db/cardinality :db.cardinality/one :db/index true}
   :references {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}})

;; ---------- Tiny helpers ----------
(defn e [db id] (d/entity db [:id id]))
(defn pid [db id] (some-> (e db id) :parent :id))
(defn children-ids [db parent-id]
  (->> (d/q '[:find ?id ?p :in $ ?pref
              :where [?c :parent ?pref] [?c :id ?id] [?c :pos ?p]]
            db [:id parent-id])
       (sort-by second) (mapv first)))

(defn splice [v idx x]
  (let [i (min (max 0 (or idx (count v))) (count v))]
    (into (subvec v 0 i) (cons x (subvec v i)))))

(defn reorder-tx [parent-id ids]
  (map-indexed (fn [i id] [:db/add [:id id] :pos i]) ids))

(defn target [db {:keys [parent sibling rel idx] :as pos}]
  (let [p (or parent (when sibling (pid db sibling))
              (throw (ex-info "No parent specified" {:position pos})))
        ks (children-ids db p)
        i (case rel
            :first 0
            :last (count ks)
            :before (let [j (.indexOf ks sibling)] (if (neg? j) (count ks) j))
            :after (let [j (.indexOf ks sibling)] (if (neg? j) (count ks) (inc j)))
            (min (or idx (count ks)) (count ks)))]
    [p i]))

;; ---------- Subtree tx builder (single transaction, no temp-pos hacks) ----------
(def ^:private cid (atom 0))
(defn- walk->tx
  "Return flat tx-data for node subtree, assigning :parent and :pos for each child."
  [{:keys [id children] :as node} parent-ref idx]
  (let [eid (- (swap! cid inc))
        me (-> (dissoc node :children)
               (assoc :db/id eid :id id :parent parent-ref :pos idx))
        kid-tx (mapcat (fn [[i ch]] (walk->tx ch eid i))
                       (map-indexed vector (or children [])))]
    (cons me kid-tx)))

;; ---------- Public ops ----------
(defn insert! [conn entity pos]
  (when (some #(e @conn %) (tree-seq :children #(or (:children %) []) entity))
    (throw (ex-info "IDs already exist; use move! for existing entities" {})))
  (let [[p i] (target @conn pos)]
    (d/transact! conn (walk->tx entity [:id p] i))
    (let [after (splice (vec (remove #{(:id entity)} (children-ids @conn p))) i (:id entity))]
      (d/transact! conn (reorder-tx p after)))))

(defn move! [conn entity-id pos]
  (let [db @conn
        ;; cycle check via rule-free closure (slow is fine)
        desc (loop [fr #{entity-id} acc #{}]
               (if (empty? fr) acc
                   (let [kids (set (mapcat #(children-ids db %) fr))]
                     (recur kids (into acc kids)))))
        [p i] (target db pos)]
    (when (contains? desc p)
      (throw (ex-info "Cycle detected" {:entity entity-id :target p})))
    (let [old (pid db entity-id)
          old-ks (children-ids db old)
          new-ks (children-ids db p)]
      (d/transact! conn [[:db/add [:id entity-id] :parent [:id p]]])
      (d/transact! conn (reorder-tx old (vec (remove #{entity-id} old-ks))))
      (d/transact! conn (reorder-tx p (splice (vec (remove #{entity-id} new-ks)) i entity-id))))))

(defn delete! [conn entity-id]
  (let [db @conn
        parent (pid db entity-id)
        ;; collect descendants (slow but tiny code)
        all (loop [stack [entity-id] acc []]
              (if-let [x (peek stack)]
                (recur (into (pop stack) (children-ids db x)) (conj acc x))
                acc))]
    (d/transact! conn (mapv (fn [id] [:db/retractEntity [:id id]]) all))
    (when parent (d/transact! conn (reorder-tx parent (children-ids @conn parent))))))

(defn update! [conn entity-id attrs]
  (when (some #{:parent :pos :id} (keys attrs))
    (throw (ex-info "Cannot modify structural attributes" {:attrs (select-keys attrs [:parent :pos :id])})))
  (d/transact! conn (for [[k v] attrs] [:db/add [:id entity-id] k v])))

;; ---------- Tests (adapted from original) ----------
(defn- tree-fixture []
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:id "root"}])
    conn))

(defn- tree-structure [conn & paths]
  (letfn [(structure [id] [id (mapv structure (children-ids @conn id))])]
    (if (= 1 (count paths))
      (structure (first paths))
      (mapv structure paths))))

(deftest basic-insert-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "a"} {:parent "root"})
    (insert! conn {:id "b"} {:parent "root"})
    (insert! conn {:id "c"} {:parent "root"})
    (is (= ["a" "b" "c"] (children-ids @conn "root")))

    (let [positions (mapv #(:pos (e @conn %)) (children-ids @conn "root"))]
      (is (= [0 1 2] positions)))))

(deftest position-resolution-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "a"} {:parent "root"})
    (insert! conn {:id "b"} {:parent "root"})
    (insert! conn {:id "c"} {:parent "root"})
    (is (= ["a" "b" "c"] (children-ids @conn "root")))

    (insert! conn {:id "between"} {:rel :after :sibling "a"})
    (is (= ["a" "between" "b" "c"] (children-ids @conn "root")))

    (insert! conn {:id "first"} {:rel :first :parent "root"})
    (is (= ["first" "a" "between" "b" "c"] (children-ids @conn "root")))

    (insert! conn {:id "last"} {:rel :last :parent "root"})
    (is (= ["first" "a" "between" "b" "c" "last"] (children-ids @conn "root")))))

(deftest tree-operations-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "ui", :type "app",
                   :children [{:id "header", :children [{:id "nav"}]}
                              {:id "main", :children [{:id "sidebar"} {:id "content"}]}
                              {:id "footer"}]}
             {:parent "root"})

    (is (= ["ui" [["header" [["nav" []]]]
                  ["main" [["sidebar" []] ["content" []]]]
                  ["footer" []]]]
           (tree-structure conn "ui")))

    (update! conn "nav" {:label "Navigation" :visible true})
    (is (= "Navigation" (:label (e @conn "nav"))))

    (move! conn "nav" {:rel :first :parent "footer"})
    (move! conn "sidebar" {:rel :after :sibling "content"})

    (is (= ["ui" [["header" []]
                  ["main" [["content" []] ["sidebar" []]]]
                  ["footer" [["nav" []]]]]]
           (tree-structure conn "ui")))

    (delete! conn "main")
    (is (= ["ui" [["header" []] ["footer" [["nav" []]]]]]
           (tree-structure conn "ui")))
    (is (nil? (e @conn "content")))))

(deftest cycle-detection-test
  (let [conn (tree-fixture)]
    ; Create multi-level hierarchy
    (insert! conn {:id "a", :children [{:id "b", :children [{:id "c", :children [{:id "d"}]}]}]}
             {:parent "root"})

    ; Test direct cycle
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Cycle detected"
         (move! conn "a" {:parent "a"})))

    ; Test indirect cycle (grandparent)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Cycle detected"
         (move! conn "a" {:parent "c"})))

    ; Test deep cycle
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Cycle detected"
         (move! conn "a" {:parent "d"})))

    ; Test that valid moves still work
    (insert! conn {:id "safe-target"} {:parent "root"})
    (move! conn "d" {:parent "safe-target"}) ; Should work
    (is (= "safe-target" (pid @conn "d")))))

(deftest cross-reference-test
  (let [conn (tree-fixture)]
    (insert! conn {:id "form", :type "form"} {:parent "root"})
    (insert! conn {:id "input", :type "input", :name "email"} {:parent "form"})
    (insert! conn {:id "validator", :type "validator", :pattern "email"} {:parent "root"})

    ; Add cross-references using the :references attribute
    (update! conn "input" {:references [[:id "validator"]]})

    ; Verify relationships exist
    (is (= 1 (count (:references (e @conn "input")))))

    ; Test navigation patterns
    (let [validator-users (d/q '[:find [?id ...]
                                 :in $ ?validator-ref
                                 :where [?e :references ?validator-ref]
                                 [?e :id ?id]]
                               @conn [:id "validator"])]
      (is (= ["input"] validator-users)))))