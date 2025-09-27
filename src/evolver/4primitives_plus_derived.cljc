(ns evolver.4primitives-plus-derived
  "Tiny, testable tree-edit kernel over a canonical value.
   Canonical DB:
     {:nodes               {id {:type kw :props map}}
      :children-by-parent  {parent-id [child-id ...]}
      :derived             {:parent-of  {id parent}
                            :children-of{parent [child ...]}
                            :index      {id idx}
                            :prev       {id prev-id?}
                            :next       {id next-id?}
                            :depth      {id int}
                            :paths      {id [ancestors ...]}
                            :pre        {id int}
                            :post       {id int}}}

   Ops are maps with an :op key; e.g. {:op :ins :id \"a\" :under \"root\"}."

  (:require [clojure.set :as set]
            [clojure.pprint :refer [pprint]]))

;; ------------------------------------------------------------
;; Tiny utils
;; ------------------------------------------------------------

(defn rmv [v x] (vec (remove #{x} v)))
(defn clamp [i lo hi] (-> i (max lo) (min hi)))
(defn children-of* [db p] (get-in db [:children-by-parent p] []))

;; ------------------------------------------------------------
;; Derived helpers (always computed fresh from :children-by-parent)
;; ------------------------------------------------------------

(defn derive-parent-of
  "Produce {child -> parent} from {parent -> [child ...]}."
  [children-by-parent]
  (reduce-kv (fn [m p kids]
               (reduce (fn [m2 c] (assoc m2 c p)) m kids))
             {} children-by-parent))

(defn- derive-depth-paths-prepost
  "DFS from \"root\"; returns {:depth ... :paths ... :pre ... :post ...}."
  [children-by-parent]
  (letfn [(walk [node depth path [acc t]]
            (let [t1 (inc t)
                  acc1 (-> acc
                           (assoc-in [:depth node] depth)
                           (assoc-in [:paths node] path)
                           (assoc-in [:pre node] t1))
                  kids (get children-by-parent node [])
                  [acc2 t2]
                  (reduce (fn [[a tt] k]
                            (walk k (inc depth) (conj path node) [a tt]))
                          [acc1 t1] kids)
                  t3 (inc t2)
                  acc3 (assoc-in acc2 [:post node] t3)]
              [acc3 t3]))]
    (first (walk "root" 0 [] [{} 0]))))

(defn- compute-derived
  "Build Tier A+B derived maps from canonical adjacency."
  [db]
  (let [cbp (:children-by-parent db)
        nodes (:nodes db)
        parent-of (derive-parent-of cbp)
        ;; cover anything mentioned anywhere (keys(nodes), parents, children)
        all-ids (set (concat (keys nodes)
                             (keys cbp)
                             (mapcat identity (vals cbp))))
        children-of (into {}
                          (for [id all-ids]
                            [id (vec (get cbp id []))]))
        ;; index, prev, next
        index (reduce-kv
               (fn [m _ kids]
                 (reduce (fn [m2 [i c]] (assoc m2 c i))
                         m (map-indexed vector kids)))
               {} cbp)
        {:keys [prev next]}
        (reduce-kv
         (fn [{:keys [prev next] :as acc} _ kids]
           (let [n (count kids)]
             (loop [i 0 prev prev next next]
               (if (= i n)
                 {:prev prev :next next}
                 (let [id (nth kids i)
                       prev (cond-> prev (> i 0) (assoc id (nth kids (dec i))))
                       next (cond-> next (< i (dec n)) (assoc id (nth kids (inc i))))]
                   (recur (inc i) prev next))))))
         {:prev {} :next {}} cbp)
        coords (derive-depth-paths-prepost cbp)]
    {:parent-of parent-of
     :children-of children-of
     :index index
     :prev prev
     :next next
     :depth (:depth coords)
     :paths (:paths coords)
     :pre (:pre coords)
     :post (:post coords)}))

(defn derive-all
  "Attach fresh :derived snapshot to db."
  [db]
  (assoc db :derived (compute-derived db)))

(defn parent-of* [db id]
  (get (derive-parent-of (:children-by-parent db)) id))

(defn subtree-ids
  "Inclusive set: root + all descendants of `root`."
  [db root]
  (letfn [(walk [acc x] (reduce walk (conj acc x) (children-of* db x)))]
    (walk #{} root)))

(defn cycle? [db id new-parent]
  (and new-parent (contains? (subtree-ids db id) new-parent)))

(defn ->index
  "Normalize position; accepts integer, :first, :last, or [:index n]."
  [kids index]
  (cond
    (nil? index) (count kids)
    (keyword? index) (case index :first 0 :last (count kids) (count kids))
    (and (vector? index) (= :index (first index)))
    (clamp (second index) 0 (count kids))
    (int? index) (clamp index 0 (count kids))
    :else (count kids)))

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
   {:id ... :parent p-or-nil :index (int|:first|:last|[:index n])}
   - parent=nil    => detach only
   - parent=same   => reorder
   - parent≠same   => move-and-place
   Cycle-safe."
  [db {:keys [id parent index]}]
  (assert id "set-parent*: :id required")
  (when (cycle? db id parent)
    (throw (ex-info "set-parent*: cycle/invalid parent" {:id id :parent parent})))
  (let [oldp (parent-of* db id)
        db1 (if oldp (update-in db [:children-by-parent oldp] (fnil rmv []) id) db)]
    (if (nil? parent)
      db1
      (let [kids (vec (children-of* db1 parent))
            base (rmv kids id)
            i (->index base index)
            v (vec (concat (subvec base 0 i) [id] (subvec base i)))]
        (assoc-in db1 [:children-by-parent parent] v)))))

(defn patch-props*
  "Deep-merge-ish updates: map values merge, scalars replace."
  [db {:keys [id updates props sys] :as payload}]
  (assert id "patch-props*: :id required")
  (let [u (merge {:props props} (select-keys payload [:sys]) (or updates {}))
        merge-deep (fn m [a b] (if (and (map? a) (map? b)) (merge-with m a b) b))]
    (update-in db [:nodes id] #(merge-with merge-deep % u))))

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
                          (update :children-by-parent dissoc id)))
                    db victims)]
    ;; scrub victims from all child vectors
    (update db1 :children-by-parent
            (fn [m]
              (into {} (for [[p kids] m]
                         [p (vec (remove victims kids))]))))))

;; ------------------------------------------------------------
;; Sugar ops
;; ------------------------------------------------------------

(defn ins
  "Create + attach. Throws if :id already exists."
  [db {:keys [id under type props index at] :or {type :div props {}}}]
  (assert id "ins: :id required")
  (when (get-in db [:nodes id])
    (throw (ex-info "ins: id already exists" {:id id})))
  (-> db
      (ensure-node* {:id id :type type :props props})
      (set-parent* {:id id :parent under :index (or index at :last)})))

(defn mv
  "Move-and-place via set-parent* (optionally validate :from)."
  [db {:keys [id from to index at]}]
  (when (and from (not (some #{id} (children-of* db from))))
    (throw (ex-info "mv: :from does not contain id" {:id id :from from})))
  (set-parent* db {:id id :parent to :index (or index at :last)}))

(defn reorder
  "Within-parent reorder via set-parent*."
  [db {:keys [id parent to index at]}]
  (when-not (some #{id} (children-of* db parent))
    (throw (ex-info "reorder: id not in :parent" {:id id :parent parent})))
  (set-parent* db {:id id :parent parent :index (or index to at)}))

(defn del
  "Delete id (and its subtree) via purge*."
  [db {:keys [id]}]
  (purge* db (fn [_ x] (= x id))))

;; ------------------------------------------------------------
;; Interpreter: derive AFTER EVERY OP (clarity > perf)
;; ------------------------------------------------------------

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
   tx))

;; ------------------------------------------------------------
;; REPL asserts (run the file, they should all pass)
;; ------------------------------------------------------------

(defn- ok [& _] true)

(let [db0 {:nodes {} :children-by-parent {"root" []}}
      tx [{:op :ins :id "a" :under "root" :type :div}
          {:op :ins :id "b" :under "root"}
          {:op :ins :id "c" :under "a"}]
      db1 (interpret* db0 tx)]

  ;; structure
  (assert (= ["a" "b"] (get-in db1 [:children-by-parent "root"])))
  (assert (= ["c"] (get-in db1 [:children-by-parent "a"])))
  (assert (= "a" (get-in db1 [:derived :parent-of "c"])))

  ;; basic derived (index/prev/next)
  (assert (= 1 (get-in db1 [:derived :index "b"])))
  (assert (nil? (get-in db1 [:derived :prev "a"])))
  (assert (= "b" (get-in db1 [:derived :next "a"])))

  ;; reorder within root
  (let [db2 (interpret* db1 [{:op :reorder :id "a" :parent "root" :to 1}])]
    (assert (= ["b" "a"] (get-in db2 [:children-by-parent "root"])))
    (assert (= 1 (get-in db2 [:derived :index "a"]))))

  ;; move c up to root at index 0
  (let [db3 (interpret* db1 [{:op :mv :id "c" :from "a" :to "root" :index 0}])]
    (assert (= ["c" "a" "b"] (get-in db3 [:children-by-parent "root"])))
    (assert (= [] (get-in db3 [:children-by-parent "a"])))
    (assert (= 0 (get-in db3 [:derived :index "c"]))))

  ;; cycle prevention: move a under c should throw
  (let [threw? (try (interpret* db1 [{:op :set-parent :id "a" :parent "c"}])
                    false
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) _ true))]
    (assert threw?))

  ;; delete (purge closure): removing a removes c as well
  (let [db4 (interpret* db1 [{:op :del :id "a"}])]
    (assert (nil? (get-in db4 [:nodes "a"])))
    (assert (nil? (get-in db4 [:nodes "c"])))
    (assert (= ["b"] (get-in db4 [:children-by-parent "root"]))))

  (ok))

;; Usage example:
;; (def db {:nodes {} :children-by-parent {"root" []}})
;; (def db' (interpret* db [{:op :ins :id "x" :under "root"}]))
;; (pprint (:derived db'))