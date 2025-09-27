(ns evolver.trash-restore
  (:require [clojure.pprint :refer [pprint]]))

;; ------------------------------------------------------------
;; DB SHAPE
;; {:nodes {id {:type :div :props {} :meta {..}}}
;;  :children-by-parent {parent-id [child-ids ...]}
;;  :parent-of {child-id parent-id}                           ; derived
;; }
;; Special buckets we treat like folders:
(def ^:private ROOT "root")
(def ^:private TRASH "trash")
(def ^:private RESTORED "restored")

;; ------------------------------------------------------------
;; Small utils (explicit, boring, dependable)

(defn index-of [v x]
  (loop [i 0]
    (cond
      (>= i (count v)) -1
      (= (nth v i) x)  i
      :else            (recur (inc i)))))

(defn rmv [v x] (vec (remove #{x} v)))
(defn clamp [i lo hi] (-> i (max lo) (min hi)))
(defn now-ms [] (System/currentTimeMillis))

(defn ensure-buckets
  "Ensure all standard folders exist as parents (empty vectors if missing)."
  [db]
  (reduce (fn [d k] (update d [:children-by-parent k] #(or % [])))
          db [ROOT TRASH RESTORED]))

(defn derive-parent-of [children-by-parent]
  (reduce-kv (fn [m p kids] (reduce #(assoc %1 %2 p) m kids))
             {} children-by-parent))

(defn ensure-derived [db]
  (assoc db :parent-of (derive-parent-of (:children-by-parent db))))

(defn parent-of [db id] (get-in db [:parent-of id]))
(defn children-of [db id] (get-in db [:children-by-parent id] []))

(defn ancestors-of
  "Walk up via :parent-of until nil; returns [parent grandparent ...]."
  [db id]
  (loop [cur (parent-of (ensure-derived db) id) acc []]
    (if (nil? cur) acc
                   (recur (parent-of (ensure-derived db) cur) (conj acc cur)))))

(defn in-trash? [db id]
  (some #{TRASH} (cons (parent-of (ensure-derived db) id)
                       (ancestors-of db id))))

(defn v-insert
  "Insert x into vector v at index i (clamped), removing any prior occurrence."
  [v x i]
  (let [base (rmv (vec v) x)
        i    (clamp i 0 (count base))]
    (vec (concat (subvec base 0 i) [x] (subvec base i)))))

(defn subtree-ids
  "Depth-first list of ids in the subtree rooted at id (includes root id)."
  [db id]
  (letfn [(walk [acc n]
            (let [kids (children-of db n)]
              (reduce walk (conj acc n) kids)))]
    (walk [] id)))

;; ------------------------------------------------------------
;; CORE PRIMITIVES (minimal basis)
;; 1) ensure-node   — existence
;; 2) set-parent    — topology+order (detach, move, reorder unified)
;; 3) patch-props   — attributes
;; 4) purge         — physical deletion via predicate (policy-supplied)

(defn ensure-node
  "Idempotently create a node. Does NOT attach it anywhere."
  [db {:keys [id type props] :or {type :div props {}}}]
  (if (get-in db [:nodes id]) db
                              (assoc-in (update db :nodes #(or % {}))
                                        [:nodes id] {:type type :props props})))

(defn set-parent
  "Attach/move/reorder a node under `parent` at `index` (int; nil = append).
   If `parent` is nil, the node is detached (no parent)."
  [db {:keys [id parent index]}]
  (let [db (ensure-derived (ensure-buckets db))
        cur-parent (parent-of db id)
        ;; detach from current parent (if any)
        db (if cur-parent
             (update-in db [:children-by-parent cur-parent] (fnil rmv []) id)
             db)
        ;; attach into new parent (if provided)
        db (if parent
             (let [kids (children-of db parent)
                   i    (if (some? index) index (count kids))]
               (assoc-in db [:children-by-parent parent] (v-insert kids id i)))
             db)]
    (ensure-derived db)))

(defn patch-props
  "Shallow merge of node attrs; nested map merge for :props."
  [db {:keys [id updates]}]
  (let [merge-props (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))]
    (-> db
        (update-in [:nodes id]
                   #(merge-with merge-props % updates))
        ensure-derived)))

(defn purge
  "Physically remove nodes for which (pred db id) is truthy.
   Removes from :nodes, clears their children lists, and detaches from parents.
   DOES NOT cascade by default; pass a predicate that selects the subtree you want."
  [db pred]
  (let [db (ensure-derived db)
        ids (->> (keys (:nodes db))
                 (filter #(pred db %))
                 set)]
    (-> db
        ;; detach each id from its parent vector
        (as-> d (reduce (fn [d id]
                          (if-let [p (parent-of d id)]
                            (update-in d [:children-by-parent p] (fnil rmv []) id)
                            d))
                        d ids))
        ;; drop their own children lists (orphans remain unless also selected)
        (update :children-by-parent #(apply dissoc % ids))
        ;; drop node entries
        (update :nodes #(apply dissoc % ids))
        ensure-derived)))

;; ------------------------------------------------------------
;; TRASH / RESTORE POLICY (soft-delete)
;; Trash stores restore hints on the node itself (:meta).

(defn- stamp-trash-meta [db id scope old-parent old-index]
  (assoc-in db [:nodes id :meta]
            {:trash/at (now-ms)
             :trash/scope scope
             :trash/was-parent old-parent
             :trash/was-index  old-index}))

(defn trash
  "Move a node to TRASH. Scopes:
   :subtree — move the whole subtree by moving only the root node under TRASH.
   :one     — move only the node; children are lifted into the old parent at the node’s slot.
              Note: restoring later does NOT steal children back; they stay where they were lifted."
  [db id {:keys [scope] :or {scope :subtree}}]
  (let [db         (ensure-derived (ensure-buckets db))
        old-parent (parent-of db id)
        old-index  (index-of (children-of db old-parent) id)]
    (case scope
      :subtree
      (-> db
          (stamp-trash-meta id :subtree old-parent old-index)
          (set-parent {:id id :parent TRASH :index nil}))

      :one
      (let [kids      (children-of db id)
            pre       (subvec (vec (children-of db old-parent)) 0 old-index)
            post      (subvec (vec (children-of db old-parent)) (inc old-index))
            ;; lift children into old parent at the node’s position
            db'       (assoc-in db [:children-by-parent old-parent]
                                (vec (concat pre kids post)))
            ;; clear node's children list (node goes alone to TRASH)
            db'       (assoc-in db' [:children-by-parent id] [])
            ;; fix each kid's parent to old-parent
            db'       (reduce (fn [d k] (assoc-in d [:parent-of k] old-parent)) db' kids)]
        (-> db'
            (stamp-trash-meta id :one old-parent old-index)
            (set-parent {:id id :parent TRASH :index nil})))

      (throw (ex-info "Unknown trash scope" {:scope scope})))))

(defn restore
  "Restore a trashed node to its recorded parent/index if available.
   If parent is gone, park under RESTORED."
  [db id]
  (let [db   (ensure-derived (ensure-buckets db))
        meta (get-in db [:nodes id :meta])]
    (if-not meta db
                 (let [{:keys [trash/was-parent trash/was-index]} meta
                       parent (if (and was-parent (get-in db [:nodes was-parent]))
                                was-parent
                                RESTORED)
                       idx    (or was-index (count (children-of db parent)))]
                   (-> db
                       (update-in [:nodes id] dissoc :meta)
                       (set-parent {:id id :parent parent :index idx}))))))

;; ------------------------------------------------------------
;; GC helpers (compose predicates)

(defn older-than-days?
  "True if node has :trash/at at least d days ago."
  [db id d]
  (when-let [t (get-in db [:nodes id :meta :trash/at])]
    (>= (- (now-ms) t) (* d 24 60 60 1000))))

(defn no-external-inbound-refs?
  "Stub for demo. Replace with a real ref check if you model references.
   Here we conservatively always allow purge (returns true)."
  [_db _id] true)

;; Example purge predicate builder
(defn make-trash-predicate
  [{:keys [min-age-days require-no-external-refs?]
    :or   {min-age-days 30 require-no-external-refs? true}}]
  (fn [db id]
    (and (in-trash? db id)
         (older-than-days? db id min-age-days)
         (or (not require-no-external-refs?)
             (no-external-inbound-refs? db id)))))

;; ------------------------------------------------------------
;; Example data + REPL assertions

(def db0
  (-> {:nodes {ROOT {:type :div}
               "a"   {:type :div}
               "a1"  {:type :p}
               "b"   {:type :p}}
       :children-by-parent {ROOT ["a" "b"]
                            "a"   ["a1"]}}
      ensure-buckets
      ensure-derived))

;; 1) Trash subtree of a (a -> trash, keeps a1 under a)
(def db1 (trash db0 "a" {:scope :subtree}))
(assert (= (parent-of db1 "a") TRASH))
(assert (= (children-of db1 "a") ["a1"]))               ;; subtree preserved
(assert (= (children-of db1 TRASH) ["a"]))              ;; a parked in trash
(assert (= (children-of db1 ROOT) ["b"]))               ;; root lost 'a'

;; 2) Restore a (should come back to original slot before b)
(def db2 (restore db1 "a"))
(assert (= (children-of db2 ROOT) ["a" "b"]))
(assert (= (children-of db2 "a") ["a1"]))
(assert (nil? (get-in db2 [:nodes "a" :meta])))

;; 3) Trash ONE node b (no children); identical to subtree here, trivial
(def db3 (trash db2 "b" {:scope :one}))
(assert (= (children-of db3 ROOT) ["a"]))               ;; b removed
(assert (= (children-of db3 TRASH) ["b"]))              ;; b in trash

;; 4) Trash ONE on a (lifts a1 to root at a's former slot)
(def db4 (trash db2 "a" {:scope :one}))
(assert (= (children-of db4 ROOT) ["a1" "b"]))          ;; lifted children
(assert (= (children-of db4 "a") []))                   ;; a lost kids
(assert (= (children-of db4 TRASH) ["a"]))              ;; a alone in trash

;; 5) Purge old trash: age a fast-forward by 40 days and delete it
(def forty-days-ms (* 40 24 60 60 1000))
(def db5 (update-in db4 [:nodes "a" :meta :trash/at] #(- (now-ms) forty-days-ms)))
(def pred (make-trash-predicate {:min-age-days 30}))
(def db6 (purge db5 pred))
(assert (nil? (get-in db6 [:nodes "a"])))
(assert (not-any? #{"a"} (children-of db6 TRASH)))

;; Optional: pretty print final state for visual inspection
(comment
  (pprint db0)
  (pprint db1)
  (pprint db2)
  (pprint db3)
  (pprint db4)
  (pprint db6))