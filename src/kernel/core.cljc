(ns evolver.core
  "Tiny, testable tree-edit kernel over a canonical value.
   Canonical DB:
     {:nodes                    {id {:type kw :props map}}
      :children-by-parent-id    {parent-id [child-id ...]}
      :derived                  {}}

   The 4 CORE OPS! SHOULD NOT LEVERAGE :DERIVED ever"
  (:require [clojure.set :as set]
            [evolver.invariants :as inv]
            [evolver.schemas :as S]
            [malli.core :as m]))

(def ^:const ROOT "root")

;; ------------------------------------------------------------
;; Tiny utils
;; ------------------------------------------------------------

;; ------------------------------------------------------------
;; Derivation functions (Tier-A and Tier-B)
;; ------------------------------------------------------------

(defn derive-parent-id-of
  "Produce {child-id -> parent-id} from {parent-id -> [child-id ...]}."
  [children-by-parent-id]
  (reduce-kv (fn [m parent-id child-ids]
               (reduce (fn [m2 c] (assoc m2 c parent-id)) m child-ids))
             {} children-by-parent-id))

(defn derive-depth-paths-prepost
  "DFS from ROOT; returns {:depth-of ... :path-of ... :pre ... :post ...}.
   Orphan nodes (not reachable from ROOT) get :depth-of nil."
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
    (let [[coords _] (walk ROOT 0 [] [{} 0])
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

(defn derive-core
  "Build Tier-A derived maps from canonical adjacency.
   Returns {:parent-id-of, :child-ids-of, :index-of, :prev-id-of, :next-id-of, :pre, :post, :id-by-pre}"
  [db]
  (let [adj (:children-by-parent-id db)
        nodes (:nodes db)

        ;; Tier-A: Structural truth
        parent-id-of (derive-parent-id-of adj)
        ;; Ensure every id has a child-ids vector (possibly empty)
        all-ids (set (concat (keys nodes)
                             (keys adj)
                             (mapcat identity (vals adj))))
        child-ids-of (into {}
                           (for [id all-ids]
                             [id (vec (get adj id []))]))
        ;; index-of: sibling position
        index-of (reduce-kv
                  (fn [m _ child-ids]
                    (reduce (fn [m2 [i c]] (assoc m2 c i))
                            m (map-indexed vector child-ids)))
                  {} adj)
        ;; prev/next siblings
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
        ;; DFS pre/post times
        coords (derive-depth-paths-prepost adj nodes)
        pre (:pre coords)
        post (:post coords)
        id-by-pre (into {} (map (fn [[id pre-val]] [pre-val id]) pre))]

    ;; Return Tier-A only
    {:parent-id-of parent-id-of
     :child-ids-of child-ids-of
     :index-of index-of
     :prev-id-of prev-id-of
     :next-id-of next-id-of
     :pre pre
     :post post
     :id-by-pre id-by-pre}))

(defn derive-dx
  "Build Tier-B derived maps from Tier-A core + db.
   Takes db and core (Tier-A), returns merged core + Tier-B fields."
  [db core]
  (let [{:keys [parent-id-of child-ids-of pre post]} core
        nodes (:nodes db)

        ;; Tier-B: DX pack that pays rent
        preorder (->> pre (sort-by val) (map first) vec)
        doc-index-of (into {} (map-indexed (fn [i id] [id i]) preorder))
        doc-prev-id-of (into {} (map (fn [[a b]] [b a]) (partition 2 1 [nil] preorder)))
        doc-next-id-of (into {} (map (fn [[a b]] [a b]) (partition 2 1 preorder [nil])))
        position-of (into {} (for [[id parent-id] parent-id-of]
                               [id {:parent-id parent-id :index (get (:index-of core) id 0)}]))
        child-count-of (into {} (map (fn [[p child-ids]] [p (count child-ids)]) child-ids-of))
        first-child-id-of (into {} (for [[p child-ids] child-ids-of :when (seq child-ids)]
                                     [p (first child-ids)]))
        last-child-id-of (into {} (for [[p child-ids] child-ids-of :when (seq child-ids)]
                                    [p (last child-ids)]))
        subtree-size-of (into {} (map (fn [id]
                                        (let [pre-val (get pre id 0)
                                              post-val (get post id 0)]
                                          [id (quot (inc (- post-val pre-val)) 2)]))
                                      (keys parent-id-of)))
        ;; Find reachable vs orphan nodes
        reachable-ids (into #{} (for [id (keys pre) :when (some? (get pre id))] id))
        orphan-ids (set/difference (set (keys nodes)) reachable-ids)
        ;; Path computation from depth-paths (we need to recompute this from adjacency)
        path-of (let [coords (derive-depth-paths-prepost (:children-by-parent-id db) nodes)]
                  (:path-of coords))]

    ;; Return merged core + Tier-B
    (merge core
           {:preorder preorder
            :doc-index-of doc-index-of
            :doc-prev-id-of doc-prev-id-of
            :doc-next-id-of doc-next-id-of
            :position-of position-of
            :child-count-of child-count-of
            :first-child-id-of first-child-id-of
            :last-child-id-of last-child-id-of
            :subtree-size-of subtree-size-of
            :reachable-ids reachable-ids
            :orphan-ids orphan-ids
            :path-of path-of})))

;; ------------------------------------------------------------

(defn rmv [v x] (vec (remove #{x} v)))

#?(:clj (defn index-of [v x] (.indexOf ^java.util.List v x))
   :cljs (defn index-of [v x]
           (loop [i 0]
             (if (< i (count v))
               (if (= (nth v i) x) i (recur (inc i)))
               -1))))

(defn child-ids-of* [db parent-id] (get-in db [:children-by-parent-id parent-id] []))

;; ------------------------------------------------------------
;; Derivation system
;; ------------------------------------------------------------
(defn derive-full [db]
  (let [core (derive-core db)]
    (assoc db :derived (derive-dx db core))))

(def ^:dynamic *derive-pass*
  "Default derivation pass: Tier-A + Tier-B (clarity > perf)"
  derive-full)

(defn parent-id-of* [db id]
  (if-let [derived (:derived db)]
    (get-in derived [:parent-id-of id])
    (get (derive-parent-id-of (:children-by-parent-id db)) id)))

(defn subtree-ids
  "Inclusive set: root + all descendants of `root`."
  [db root]
  (letfn [(walk [acc x] (reduce walk (conj acc x) (child-ids-of* db x)))]
    (walk #{} root)))

(defn cycle? [db id new-parent-id]
  "Efficient cycle check using pre/post intervals."
  (when new-parent-id
    (if-let [derived (:derived db)]
      (let [id-pre (get-in derived [:pre id])
            id-post (get-in derived [:post id])
            parent-pre (get-in derived [:pre new-parent-id])
            parent-post (get-in derived [:post new-parent-id])]
        ;; If any pre/post is nil (orphans), fallback to subtree walk
        (if (and id-pre id-post parent-pre parent-post)
          ;; new-parent-id is in subtree of id if parent's interval is contained in id's interval
          (< id-pre parent-pre parent-post id-post)
          ;; fallback when any interval data is missing
          (contains? (subtree-ids db id) new-parent-id)))
      ;; fallback to subtree walk if no derived data
      (contains? (subtree-ids db id) new-parent-id))))

(defn pos->index
  "Convert position spec to insertion index.
   pos ∈ {nil,:first,:last, [:index i], [:before anchor-id], [:after anchor-id]}"
  {:malli/schema (m/schema [:schema {:registry S/registry} :evolver.schemas/pos->index-fn])}
  [db parent-id id pos]
  (let [child-ids (get (:children-by-parent-id db) parent-id [])
        base (vec (remove #{id} child-ids))
        idx (fn [anchor-id]
              (let [i (index-of base anchor-id)]
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
  {:malli/schema (m/schema [:schema {:registry S/registry} :evolver.schemas/set-parent*-fn])}
  [db {:keys [id parent-id pos]}]
  (assert id "set-parent*: :id required")
  (assert (contains? (:nodes db) id) (str "set-parent*: node does not exist: " id))
  (when (and parent-id (not (contains? (:nodes db) parent-id)))
    (throw (ex-info "set-parent*: parent-id does not exist" {:parent-id parent-id})))
  (when (cycle? db id parent-id)
    (throw (ex-info "set-parent*: cycle/invalid parent" {:id id :parent-id parent-id})))
  (let [old-parent-id (parent-id-of* db id)]
    (if (and (= parent-id old-parent-id) (nil? pos))
      db
      (let [db1 (if old-parent-id (update-in db [:children-by-parent-id old-parent-id] (fnil rmv []) id) db)]
        (if (nil? parent-id)
          db1
          (let [child-ids (vec (child-ids-of* db1 parent-id))
                base (rmv child-ids id)
                i (pos->index db1 parent-id id pos)
                v (vec (concat (subvec base 0 i) [id] (subvec base i)))]
            (assert (= (count v) (count (distinct v))) "set-parent*: duplicate children detected")
            (assoc-in db1 [:children-by-parent-id parent-id] v)))))))

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
   its entire subtree is removed. Uses interval optimization when derived data available."
  [db pred]
  (let [ids (set (keys (:nodes db)))
        victims (if-let [derived (:derived db)]
                  ;; Interval-based approach: if pred(id) then remove all nodes 
                  ;; with pre in [pre[id], post[id]]
                  (let [pre-to-id (into {} (map (fn [[id pre-val]] [pre-val id]) (:pre derived)))
                        roots (filter #(pred db %) ids)
                        intervals (for [root roots
                                        :let [pre-val (get-in derived [:pre root])
                                              post-val (get-in derived [:post root])]
                                        :when (and pre-val post-val)
                                        pre-time (range pre-val (inc post-val))]
                                    (get pre-to-id pre-time))
                        interval-victims (set (filter identity (flatten intervals)))]
                    ;; Include any roots that might not be in preorder (orphans)
                    (reduce (fn [acc root]
                              (if (get-in derived [:pre root])
                                acc ; already handled by intervals
                                (set/union acc (subtree-ids db root))))
                            interval-victims roots))
                  ;; Fallback to recursive walk
                  (reduce (fn [acc id]
                            (if (pred db id)
                              (set/union acc (subtree-ids db id))
                              acc))
                          #{} ids))
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
    ;; sugar-ops - we need to import these
    :ins #?(:clj (fn [db m] (let [sugar-ops-ns (requiring-resolve 'evolver.sugar-ops/ins)]
                              (sugar-ops-ns db m)))
            :cljs (fn [db m] (throw (ex-info "Sugar ops not yet implemented in ClojureScript" {:op :ins}))))
    :mv #?(:clj (fn [db m] (let [sugar-ops-ns (requiring-resolve 'evolver.sugar-ops/mv)]
                             (sugar-ops-ns db m)))
           :cljs (fn [db m] (throw (ex-info "Sugar ops not yet implemented in ClojureScript" {:op :mv}))))
    :del #?(:clj (fn [db m] (let [sugar-ops-ns (requiring-resolve 'evolver.sugar-ops/del)]
                              (sugar-ops-ns db m)))
            :cljs (fn [db m] (throw (ex-info "Sugar ops not yet implemented in ClojureScript" {:op :del}))))
    :reorder #?(:clj (fn [db m] (let [sugar-ops-ns (requiring-resolve 'evolver.sugar-ops/reorder)]
                                  (sugar-ops-ns db m)))
                :cljs (fn [db m] (throw (ex-info "Sugar ops not yet implemented in ClojureScript" {:op :reorder}))))
    :move-up #?(:clj (fn [db m] (let [sugar-ops-ns (requiring-resolve 'evolver.sugar-ops/move-up)]
                                  (sugar-ops-ns db m)))
                :cljs (fn [db m] (throw (ex-info "Sugar ops not yet implemented in ClojureScript" {:op :move-up}))))
    :move-down #?(:clj (fn [db m] (let [sugar-ops-ns (requiring-resolve 'evolver.sugar-ops/move-down)]
                                    (sugar-ops-ns db m)))
                  :cljs (fn [db m] (throw (ex-info "Sugar ops not yet implemented in ClojureScript" {:op :move-down}))))
    (fn [_ _] (throw (ex-info "Unknown :op" {:op k})))))

(defn interpret*
  "Apply a seq of op maps to db, deriving after each step.
   Options:
   - :derive  Function to derive state after each op (default: *derive-pass*)
   - :assert? Flag to run invariant checks after each op (default: false)"
  {:malli/schema (m/schema [:schema {:registry S/registry} :evolver.schemas/interpret*-fn])}
  ([db tx] (interpret* db tx {}))
  ([db tx {:keys [derive assert?]
           :or {derive *derive-pass* assert? false}}]
   (S/validate-db! db)
   (S/validate-tx! tx)
   (reduce
    (fn [d m]
      (S/validate-op! m)
      (let [d1 (-> d
                   ((dispatch (:op m)) m)
                   derive)]
        (when assert? (inv/check-invariants d1))
        d1))
    (derive db)
    (->tx tx))))