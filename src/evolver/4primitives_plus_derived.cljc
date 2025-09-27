(ns evolver.4primitives-plus-derived
  "Tiny, testable tree-edit kernel over a canonical value.
   Canonical DB:
     {:nodes                    {id {:type kw :props map}}
      :children-by-parent-id    {parent-id [child-id ...]}
      :derived                  {:parent-id-of   {id parent-id}
                                 :child-ids-of   {parent-id [child-id ...]}
                                 :index-of       {id idx}
                                 :prev-id-of     {id prev-id?}
                                 :next-id-of     {id next-id?}
                                 :depth-of       {id int}
                                 :path-of        {id [ancestor-ids ...]}
                                 :pre            {id int}
                                 :post           {id int}}}

   Ops are maps with an :op key; e.g. {:op :ins :id \"a\" :parent-id \"root\"}."

  (:require [clojure.set :as set]
            [clojure.pprint :refer [pprint]]))

;; ------------------------------------------------------------
;; Tiny utils
;; ------------------------------------------------------------

(defn rmv [v x] (vec (remove #{x} v)))
(defn clamp [i lo hi] (-> i (max lo) (min hi)))
(defn child-ids-of* [db parent-id] (get-in db [:children-by-parent-id parent-id] []))

;; ------------------------------------------------------------
;; Derived helpers (always computed fresh from :children-by-parent)
;; ------------------------------------------------------------

(defn derive-parent-id-of
  "Produce {child-id -> parent-id} from {parent-id -> [child-id ...]}."
  [children-by-parent-id]
  (reduce-kv (fn [m parent-id child-ids]
               (reduce (fn [m2 c] (assoc m2 c parent-id)) m child-ids))
             {} children-by-parent-id))

(defn- derive-depth-paths-prepost
  "DFS from \"root\"; returns {:depth-of ... :path-of ... :pre ... :post ...}.
   Orphan nodes (not reachable from root) get :depth-of nil."
  [children-by-parent-id nodes]
  (letfn [(walk [node depth path [acc t]]
            (let [t1 (inc t)
                  acc1 (-> acc
                           (assoc-in [:depth-of node] depth)
                           (assoc-in [:path-of node] path)
                           (assoc-in [:pre node] t1))
                  child-ids (get children-by-parent-id node [])
                  [acc2 t2]
                  (reduce (fn [[a tt] k]
                            (walk k (inc depth) (conj path node) [a tt]))
                          [acc1 t1] child-ids)
                  t3 (inc t2)
                  acc3 (assoc-in acc2 [:post node] t3)]
              [acc3 t3]))]
    (let [[coords _] (walk "root" 0 [] [{} 0])
          ;; Find all nodes mentioned anywhere
          all-ids (set (concat (keys nodes)
                               (keys children-by-parent-id)
                               (mapcat identity (vals children-by-parent-id))))
          ;; Mark orphans with nil depth
          coords-with-orphans (reduce (fn [acc id]
                                        (if (contains? (:depth-of acc) id)
                                          acc
                                          (assoc-in acc [:depth-of id] nil)))
                                      coords all-ids)]
      coords-with-orphans)))

(defn- compute-derived
  "Build Tier A+B derived maps from canonical adjacency."
  [db]
  (let [adj (:children-by-parent-id db)
        nodes (:nodes db)
        parent-id-of (derive-parent-id-of adj)
        ;; cover anything mentioned anywhere (keys(nodes), parents, children)
        all-ids (set (concat (keys nodes)
                             (keys adj)
                             (mapcat identity (vals adj))))
        child-ids-of (into {}
                           (for [id all-ids]
                             [id (vec (get adj id []))]))
        ;; index, prev, next
        index-of (reduce-kv
                  (fn [m _ child-ids]
                    (reduce (fn [m2 [i c]] (assoc m2 c i))
                            m (map-indexed vector child-ids)))
                  {} adj)
        {:keys [prev-id-of next-id-of]}
        (reduce-kv
         (fn [{:keys [prev-id-of next-id-of] :as acc} _ child-ids]
           (let [n (count child-ids)]
             (loop [i 0 prev prev-id-of next next-id-of]
               (if (= i n)
                 {:prev-id-of prev :next-id-of next}
                 (let [id (nth child-ids i)
                       prev (cond-> prev (> i 0) (assoc id (nth child-ids (dec i))))
                       next (cond-> next (< i (dec n)) (assoc id (nth child-ids (inc i))))]
                   (recur (inc i) prev next))))))
         {:prev-id-of {} :next-id-of {}} adj)
        coords (derive-depth-paths-prepost adj nodes)]
    {:parent-id-of parent-id-of
     :child-ids-of child-ids-of
     :index-of index-of
     :prev-id-of prev-id-of
     :next-id-of next-id-of
     :depth-of (:depth-of coords)
     :path-of (:path-of coords)
     :pre (:pre coords)
     :post (:post coords)}))

(defn derive-all
  "Attach fresh :derived snapshot to db."
  [db]
  (assoc db :derived (compute-derived db)))

(defn parent-id-of* [db id]
  (if-let [derived (:derived db)]
    (get-in derived [:parent-id-of id])
    (get (derive-parent-id-of (:children-by-parent-id db)) id)))

(defn subtree-ids
  "Inclusive set: root + all descendants of `root`."
  [db root]
  (letfn [(walk [acc x] (reduce walk (conj acc x) (child-ids-of* db x)))]
    (walk #{} root)))

(defn cycle? [db id new-parent]
  (and new-parent (contains? (subtree-ids db id) new-parent)))

(defn pos->index
  "Convert position spec to insertion index.
   pos ∈ {nil,:first,:last, [:index i], [:before anchor-id], [:after anchor-id]}"
  [db parent-id id pos]
  (let [child-ids (-> (:derived db) :child-ids-of (get parent-id []))
        base (vec (remove #{id} child-ids))
        idx (fn [anchor-id]
              (let [i (.indexOf base anchor-id)]
                (when (neg? i)
                  (throw (ex-info "anchor not in parent" {:anchor anchor-id :parent parent-id})))
                i))]
    (cond
      (nil? pos) (count base) ; default :last
      (= :first pos) 0
      (= :last pos) (count base)
      (and (vector? pos) (= :index (first pos)))
      (let [i (second pos)]
        (when (or (neg? i) (> i (count base)))
          (throw (ex-info "index OOB" {:i i :n (count base)})))
        i)
      (and (vector? pos) (= :before (first pos))) (idx (second pos))
      (and (vector? pos) (= :after (first pos))) (inc (idx (second pos)))
      :else (throw (ex-info "bad :pos" {:pos pos})))))

;; ------------------------------------------------------------
;; 4 primitives (existence, edge+order, attributes, deletion)
;; ------------------------------------------------------------

(defn ensure-node*
  "Idempotent create of a node shell; never attaches to a parent."
  [db {:keys [id type props] :or {type :div props {}}}]
  (assert id "ensure-node*: :id required")
  (if (get-in db [:nodes id])
    db
    (assoc-in db [:nodes id] {:type type :props props})))

(defn set-parent*
  "Topology + order in one op.
   {:id ... :parent-id p-or-nil :pos (see pos->index)}
   - parent-id=nil    => detach only
   - parent-id=same   => reorder
   - parent-id≠same   => move-and-place
   Cycle-safe."
  [db {:keys [id parent-id pos]}]
  (assert id "set-parent*: :id required")
  (assert (contains? (:nodes db) id) (str "set-parent*: node does not exist: " id))
  (when (cycle? db id parent-id)
    (throw (ex-info "set-parent*: cycle/invalid parent" {:id id :parent-id parent-id})))
  (let [old-parent-id (parent-id-of* db id)
        db1 (if old-parent-id (update-in db [:children-by-parent-id old-parent-id] (fnil rmv []) id) db)]
    (if (nil? parent-id)
      db1
      (let [child-ids (vec (child-ids-of* db1 parent-id))
            base (rmv child-ids id)
            i (pos->index db1 parent-id id pos)
            v (vec (concat (subvec base 0 i) [id] (subvec base i)))]
        (assert (= (count v) (count (distinct v))) "set-parent*: duplicate children detected")
        (assoc-in db1 [:children-by-parent-id parent-id] v)))))

(defn patch-props*
  "Deep-merge-ish updates: map values merge, scalars replace."
  [db {:keys [id props sys updates]}]
  (assert id "patch-props*: :id required")
  (let [u (cond-> {}
            (some? props) (assoc :props props)
            (some? sys) (assoc :sys sys)
            (map? updates) (merge updates))
        deep (fn m [a b] (if (and (map? a) (map? b)) (merge-with m a b) b))]
    (update-in db [:nodes id] #(merge-with deep % u))))

(defn purge*
  "Hard delete by predicate over ids. Closure guarantee: if a node matches,
   its entire subtree is removed."
  [db pred]
  (let [ids (set (keys (:nodes db)))
        victims (reduce (fn [acc id]
                          (if (pred db id)
                            (set/union acc (subtree-ids db id))
                            acc))
                        #{} ids)
        db1 (reduce (fn [d id]
                      (-> d
                          (update :nodes dissoc id)
                          (update :children-by-parent-id dissoc id)))
                    db victims)]
    ;; scrub victims from all child vectors
    (update db1 :children-by-parent-id
            (fn [m]
              (into {} (for [[parent-id child-ids] m]
                         [parent-id (vec (remove victims child-ids))]))))))

;; ------------------------------------------------------------
;; Sugar ops
;; ------------------------------------------------------------

(defn ins
  "Create + attach. Throws if :id already exists."
  [db {:keys [id parent-id type props pos] :or {type :div props {}}}]
  (assert id "ins: :id required")
  (when (get-in db [:nodes id])
    (throw (ex-info "ins: id already exists" {:id id})))
  (-> db
      (ensure-node* {:id id :type type :props props})
      (set-parent* {:id id :parent-id parent-id :pos (or pos :last)})))

(defn mv
  "Move-and-place via set-parent* (optionally validate :from-parent-id)."
  [db {:keys [id from-parent-id to-parent-id pos]}]
  (assert (contains? (:nodes db) id) (str "mv: node does not exist: " id))
  (when (and from-parent-id (not (some #{id} (child-ids-of* db from-parent-id))))
    (throw (ex-info "mv: :from-parent-id does not contain id" {:id id :from-parent-id from-parent-id})))
  (set-parent* db {:id id :parent-id to-parent-id :pos (or pos :last)}))

(defn reorder
  "Within-parent reorder via set-parent*."
  [db {:keys [id parent-id pos]}]
  (assert (contains? (:nodes db) id) (str "reorder: node does not exist: " id))
  (when-not (some #{id} (child-ids-of* db parent-id))
    (throw (ex-info "reorder: id not in :parent-id" {:id id :parent-id parent-id})))
  (when (nil? pos)
    (throw (ex-info "reorder: target pos required" {:id id :parent-id parent-id})))
  (set-parent* db {:id id :parent-id parent-id :pos pos}))

(defn del
  "Delete id (and its subtree) via purge*."
  [db {:keys [id]}]
  (purge* db (fn [_ x] (= x id))))

;; ------------------------------------------------------------
;; Interpreter: derive AFTER EVERY OP (clarity > perf)
;; ------------------------------------------------------------

(defn- ->tx
  "Normalize tx input to a sequence of operations."
  [tx]
  (cond
    (nil? tx) []
    (map? tx) [tx]
    (sequential? tx) tx
    :else (throw (ex-info "Invalid tx format" {:tx tx}))))

(defn- dispatch
  "Return the function implementing an op keyword."
  [k]
  (case k
    ;; core
    :ensure-node ensure-node*
    :set-parent set-parent*
    :patch-props patch-props*
    :purge (fn [db m] (purge* db (:pred m)))
    ;; sugar
    :ins ins
    :mv mv
    :reorder reorder
    :del del
    (fn [_ _] (throw (ex-info "Unknown :op" {:op k})))))

(defn interpret*
  "Apply a seq of op maps to db, deriving after each step.
   Example op: {:op :ins :id \"a\" :under \"root\"}"
  [db tx]
  (reduce
   (fn [d m]
     (when-not (map? m) (throw (ex-info "interpret*: op must be a map" {:got m})))
     (let [k (:op m)] (when-not k (throw (ex-info "interpret*: missing :op" {:got m}))))
     (-> d
         ((dispatch (:op m)) m)
         (derive-all)))
   (derive-all db) ;; ensure db has :derived even before first op
   (->tx tx)))

;; ------------------------------------------------------------
;; REPL asserts (run the file, they should all pass)
;; ------------------------------------------------------------

(defn- ok [& _] true)

(let [db0 {:nodes {} :children-by-parent-id {"root" []}}
      tx [{:op :ins :id "a" :parent-id "root" :type :div}
          {:op :ins :id "b" :parent-id "root"}
          {:op :ins :id "c" :parent-id "a"}]
      db1 (interpret* db0 tx)]

  ;; structure
  (assert (= ["a" "b"] (get-in db1 [:children-by-parent-id "root"])))
  (assert (= ["c"] (get-in db1 [:children-by-parent-id "a"])))
  (assert (= "a" (get-in db1 [:derived :parent-id-of "c"])))

  ;; basic derived (index/prev/next)
  (assert (= 1 (get-in db1 [:derived :index-of "b"])))
  (assert (nil? (get-in db1 [:derived :prev-id-of "a"])))
  (assert (= "b" (get-in db1 [:derived :next-id-of "a"])))

  ;; reorder within root
  (let [db2 (interpret* db1 [{:op :reorder :id "a" :parent-id "root" :pos [:index 1]}])]
    (assert (= ["b" "a"] (get-in db2 [:children-by-parent-id "root"])))
    (assert (= 1 (get-in db2 [:derived :index-of "a"]))))

  ;; move c up to root at index 0
  (let [db3 (interpret* db1 [{:op :mv :id "c" :from-parent-id "a" :to-parent-id "root" :pos [:index 0]}])]
    (assert (= ["c" "a" "b"] (get-in db3 [:children-by-parent-id "root"])))
    (assert (= [] (get-in db3 [:children-by-parent-id "a"])))
    (assert (= 0 (get-in db3 [:derived :index-of "c"]))))

  ;; cycle prevention: move a under c should throw
  (let [threw? (try (interpret* db1 [{:op :set-parent :id "a" :parent-id "c"}])
                    false
                    (catch #?(:clj Exception :cljs :default) _ true))]
    (assert threw?))

  ;; delete (purge closure): removing a removes c as well
  (let [db4 (interpret* db1 [{:op :del :id "a"}])]
    (assert (nil? (get-in db4 [:nodes "a"])))
    (assert (nil? (get-in db4 [:nodes "c"])))
    (assert (= ["b"] (get-in db4 [:children-by-parent-id "root"]))))

  (ok))

;; Usage example:
;; (def db {:nodes {} :children-by-parent {"root" []}})
;; (def db' (interpret* db [{:op :ins :id "x" :under "root"}]))
;; (pprint (:derived db'))