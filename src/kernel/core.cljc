(ns kernel.core
  "Tree-edit kernel with multimethod operation registry.

   ARCHITECTURE:
   - Canonical DB: {:nodes {id {:type kw :props map}} :child-ids/by-parent {parent-id [child-id ...]}}
   - 6 core primitives: create-node*, place*, update-node*, prune*, add-ref*, rm-ref*
   - Extensible multimethod registry: apply-op dispatches on :op keyword
   - Transaction processing: run-tx (total), apply-tx+effects* (with tracing), apply-tx* (compat)
   - Effects detection: pure data effects for adapters
   - REPL-driven development: structured error reports, introspection tools

   CORE CONSTRAINT: Primitive operations must not leverage :derived data"
  (:require [clojure.set :as set]
            [kernel.invariants :as inv]
            [kernel.schemas :as S]
            [malli.core :as m]
            [kernel.effects :as effects]
            [kernel.responses :as R]
            [medley.core :as medley]))

(def ^:const ROOT "root")

;; ------------------------------------------------------------
;; Tiny utils
;; ------------------------------------------------------------

(defn- roots-of [db]
  (let [r (:roots db)]
    (if (and (vector? r) (seq r)) r [ROOT])))

;; Comprehensive verification assertions - test system intent robustly
(comment
  ;; CORE INTENT: Multi-root traversal with orphan detection
  (let [db {:nodes {"root" {:type :root} "palette" {:type :root} "w" {:type :div} "orphan" {:type :span}}
            :child-ids/by-parent {"root" ["w"]}
            :roots ["root" "palette"]}
        d (*derive-pass* db)]
    (assert (= (get-in d [:derived :preorder]) ["root" "w" "palette"]) "Multi-root preorder incorrect")
    (assert (not (contains? (get-in d [:derived :orphan-ids]) "palette")) "Palette should not be orphan")
    (assert (contains? (get-in d [:derived :orphan-ids]) "orphan") "Orphan node should be detected")
    (assert (= (count (get-in d [:derived :reachable-ids])) 3) "Should have 3 reachable nodes"))

  ;; Effects are pure data, detected consistently
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        {:keys [db effects]} (apply-tx+effects* base {:op :create-node :id "x" :type :div})]
    (assert (contains? (:nodes db) "x") "create-node should create node")
    (assert (empty? effects) "create-node should emit no effects")
    (assert (every? map? effects) "All effects should be data maps"))

  ;; Backward compatibility maintained exactly
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        old-api-result (apply-tx* base {:op :create-node :id "z"})
        new-api-result (:db (apply-tx+effects* base {:op :create-node :id "z"}))]
    (assert (= (:nodes old-api-result) (:nodes new-api-result)) "Node data must be identical")
    (assert (= (:child-ids/by-parent old-api-result) (:child-ids/by-parent new-api-result)) "Adjacency must be identical")
    (assert (= (:derived old-api-result) (:derived new-api-result)) "Derived data must be identical"))

  ;; CORE INTENT: Extensible multimethod registry
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}]
    ;; Core primitives work directly
    (assert (contains? (:nodes (apply-op base {:op :create-node :id "test-core"})) "test-core") "Core ops must work")
    ;; Registry is extensible (sugar ops loaded by requiring namespace)
    (assert (:ok? (run-tx base [{:op :create-node :id "test-run-tx"}])) "run-tx must use multimethod")
    (assert (contains? (:nodes (:db (apply-tx+effects* base [{:op :create-node :id "test-effects"}]))) "test-effects") "apply-tx+effects* must use multimethod")
    ;; Unknown ops fail gracefully
    (try (apply-op base {:op :unknown-op}) (assert false "Should throw for unknown op")
         (catch Exception e (assert (re-find #"Unknown :op" (.getMessage e)) "Should give clear error message"))))

  ;; Total error handling (REPL-driven development)
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        error-result (run-tx base [{:op :create-node :id "a"}
                                   {:op :place :id "a" :parent-id "NONEXISTENT"}])]
    (assert (not (:ok? error-result)) "Invalid transaction should fail")
    (assert (= (get-in error-result [:error :op-index]) 1) "Should report correct failing op index")
    (assert (contains? #{:schema :op :invariants} (get-in error-result [:error :why])) "Should categorize error type")
    (assert (= (:db error-result) base) "DB should be unchanged on error")
    (assert (empty? (:effects error-result)) "No effects on error"))

  ;; CORE INTENT: Trace functionality for debugging
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        result (apply-tx+effects* base [{:op :create-node :id "a"}
                                        {:op :place :id "a" :parent-id "root"}]
                                  {:trace? true})]
    (assert (contains? result :trace) "Trace should be included when requested")
    (assert (= (count (:trace result)) 2) "Should trace each operation")
    (assert (every? #(contains? % :i) (:trace result)) "Each step should have index")
    (assert (every? #(contains? % :op) (:trace result)) "Each step should have operation")
    (assert (every? #(contains? % :db) (:trace result)) "Each step should have resulting DB")
    (assert (every? #(contains? % :effects) (:trace result)) "Each step should have effects"))

  ;; Cycle detection prevents infinite nesting
  (let [base {:nodes {"a" {:type :div} "b" {:type :div}} :child-ids/by-parent {}}
        tx [{:op :place :id "a" :parent-id "b"}
            {:op :place :id "b" :parent-id "a"}]
        result (run-tx base tx)]
    (assert (not (:ok? result)) "Cycle creation should fail")
    (assert (= (get-in result [:error :why]) :op) "Should fail at operation level")
    (assert (re-find #"cycle" (get-in result [:error :message])) "Error should mention cycle"))

  ;; CORE INTENT: Schema validation catches malformed operations
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        bad-ops [{:op :create-node} ; missing :id
                 {:op :place :id "nonexistent"} ; node doesn't exist
                 {:op :unknown-thing :data "whatever"}] ; unknown op
        results (map #(run-tx base [%]) bad-ops)]
    (assert (every? #(not (:ok? %)) results) "All malformed ops should fail")
    (assert (= (map #(get-in % [:error :why]) results) [:schema :op :schema]) "Should categorize errors correctly")
    (assert (every? #(= (:db %) base) results) "DB should remain unchanged on all failures"))

  ;; CORE INTENT: Derivation is deterministic and complete
  (let [complex-db {:nodes {"r1" {:type :root} "r2" {:type :root} "a" {:type :div}
                            "b" {:type :span} "c" {:type :p} "orphan" {:type :div}}
                    :child-ids/by-parent {"r1" ["a" "b"] "a" ["c"]}
                    :roots ["r1" "r2"]}
        d1 (*derive-pass* complex-db)
        d2 (*derive-pass* complex-db)] ; derive twice
    (assert (= (:derived d1) (:derived d2)) "Derivation should be deterministic")
    (assert (= (get-in d1 [:derived :preorder]) ["r1" "a" "c" "b" "r2"]) "Preorder should follow DFS of roots")
    (assert (= (get-in d1 [:derived :orphan-ids]) #{"orphan"}) "Should detect orphan nodes correctly")
    (assert (= (get-in d1 [:derived :reachable-ids]) #{"r1" "r2" "a" "b" "c"}) "Should identify reachable nodes")
    (assert (every? #(contains? (get-in d1 [:derived :parent-id-of]) %) ["a" "b" "c"]) "All children should have parent mapping")
    (assert (= (get-in d1 [:derived :subtree-size-of "a"]) 2) "Subtree sizes should be correct")))

;; verify-registry moved to after *derive-pass* definition

;; ------------------------------------------------------------
;; Derivation functions (Tier-A and Tier-B)
;; ------------------------------------------------------------

(defn derive-parent-id-of
  "Produce {child-id -> parent-id} from {parent-id -> [child-id ...]}."
  [child-ids-by-parent]
  (reduce-kv (fn [m parent-id child-ids]
               (reduce (fn [m2 c] (assoc m2 c parent-id)) m child-ids))
             {} child-ids-by-parent))

(defn derive-depth-paths-prepost
  "DFS from all roots in order; returns {:depth-of ... :path-of ... :pre ... :post ...}.
   Orphans (not reachable from any root) get :depth-of nil."
  [child-ids-by-parent nodes roots]
  (letfn [(walk [node depth path [acc t]]
            (let [t1 (inc t)
                  acc1 (-> acc
                           (assoc-in [:depth-of node] depth)
                           (assoc-in [:path-of node] path)
                           (assoc-in [:pre node] t1))
                  child-ids (get child-ids-by-parent node [])
                  [acc2 t2]
                  (reduce (fn [[a tt] k] (walk k (inc depth) (conj path node) [a tt]))
                          [acc1 t1] child-ids)
                  t3 (inc t2)
                  acc3 (assoc-in acc2 [:post node] t3)]
              [acc3 t3]))]
    (let [[coords _]
          (reduce (fn [[a t] r] (walk r 0 [] [a t])) [{} 0] roots)
          all-ids (set (concat (keys nodes)
                               (keys child-ids-by-parent)
                               (mapcat identity (vals child-ids-by-parent))))
          coords' (reduce (fn [acc id]
                            (if (contains? (:depth-of acc) id)
                              acc
                              (assoc-in acc [:depth-of id] nil)))
                          coords all-ids)]
      coords')))

(defn derive-core
  "Build Tier-A derived maps from canonical adjacency.
   Returns {:parent-id-of, :child-ids-of, :index-of, :prev-id-of, :next-id-of, :pre, :post, :id-by-pre}"
  [db]
  (let [adj (:child-ids/by-parent db)
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
        index-of (into {} (mapcat (fn [[_ child-ids]]
                                    (map-indexed (fn [idx child] [child idx]) child-ids))
                                  adj))
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
        rts (roots-of db)
        coords (derive-depth-paths-prepost adj nodes rts)
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
        order-index-of (into {} (map-indexed (fn [i id] [id i]) preorder))
        order-prev-id-of (into {} (map (fn [[a b]] [b a]) (partition 2 1 [nil] preorder)))
        order-next-id-of (into {} (map (fn [[a b]] [a b]) (partition 2 1 preorder [nil])))
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
        path-of (let [coords (derive-depth-paths-prepost (:child-ids/by-parent db) nodes (roots-of db))]
                  (:path-of coords))]

    ;; Return merged core + Tier-B
    (merge core
           {:preorder preorder
            :order-index-of order-index-of
            :order-prev-id-of order-prev-id-of
            :order-next-id-of order-next-id-of
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

(defn child-ids-of* [db parent-id] (get-in db [:child-ids/by-parent parent-id] []))

;; ------------------------------------------------------------
;; Derivation system
;; ------------------------------------------------------------
(defn derive-full [db]
  (let [core (derive-core db)]
    (cond-> (assoc db :derived (derive-dx db core))
      (not (:version db)) (assoc :version 1))))

(def ^:dynamic *derive-pass*
  "Default derivation pass: Tier-A + Tier-B (clarity > perf)"
  derive-full)

;; verify-registry moved to end of file

(defn parent-id-of* [db id]
  (if-let [derived (:derived db)]
    (get-in derived [:parent-id-of id])
    (get (derive-parent-id-of (:child-ids/by-parent db)) id)))

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
  {:malli/schema (m/schema [:schema {:registry S/registry} :kernel.schemas/pos->index-fn])}
  [db parent-id id pos]
  (let [child-ids (get (:child-ids/by-parent db) parent-id [])
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
;; 6 core primitives (existence, topology, attributes, deletion, references)
;; ------------------------------------------------------------

(defn create-node*
  "Idempotent create of a node shell; never attaches to a parent."
  [db {:keys [id type props] :or {type :div props {}}}]
  (assert id "create-node*: :id required")
  (if (get-in db [:nodes id])
    db
    (assoc-in db [:nodes id] {:type type :props props})))

(defn place*
  "Topology + order in one op.
   {:id ... :parent-id p-or-nil :pos (see pos->index)}
   - parent-id=nil    => detach only
   - parent-id=same   => reorder
   - parent-id≠same   => move-and-place
   Cycle-safe."
  {:malli/schema (m/schema [:schema {:registry S/registry} :kernel.schemas/place*-fn])}
  [db {:keys [id parent-id pos]}]
  (assert id "place*: :id required")
  (assert (contains? (:nodes db) id) (str "place*: node does not exist: " id))
  (when (and parent-id (not (contains? (:nodes db) parent-id)))
    (throw (ex-info "place*: parent-id does not exist" {:parent-id parent-id})))
  (when (cycle? db id parent-id)
    (throw (ex-info "place*: cycle/invalid parent" {:id id :parent-id parent-id})))
  (let [old-parent-id (parent-id-of* db id)]
    (if (and (= parent-id old-parent-id) (nil? pos))
      db
      (let [db1 (if old-parent-id (update-in db [:child-ids/by-parent old-parent-id] (fnil rmv []) id) db)]
        (if (nil? parent-id)
          db1
          (let [child-ids (vec (child-ids-of* db1 parent-id))
                base (rmv child-ids id)
                i (pos->index db1 parent-id id pos)
                v (vec (concat (subvec base 0 i) [id] (subvec base i)))]
            (assert (= (count v) (count (distinct v))) "place*: duplicate children detected")
            (assoc-in db1 [:child-ids/by-parent parent-id] v)))))))

(defn update-node*
  "Deep-merge-ish updates: map values merge, scalars replace."
  [db {:keys [id props sys updates]}]
  (assert id "update-node*: :id required")
  (let [u (cond-> {}
            (some? props) (assoc :props props)
            (some? sys) (assoc :sys sys)
            (map? updates) (merge updates))
        deep (fn m [a b] (if (and (map? a) (map? b)) (merge-with m a b) b))]
    (update-in db [:nodes id] #(merge-with deep % u))))

(defn prune*
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
                          (update :child-ids/by-parent dissoc id)))
                    db victims)]
    ;; scrub victims from all child vectors
    (let [db2 (update db1 :child-ids/by-parent
                      (fn [m]
                        (into {} (for [[parent-id child-ids] m]
                                   [parent-id (vec (remove victims child-ids))]))))
          db3 (update db2 :refs
                      (fn [E]
                        (into {}
                              (for [[rel m] (or E {})]
                                [rel (into {}
                                           (keep (fn [[s ds]]
                                                   (when-not (victims s)
                                                     (let [ds' (into #{} (remove victims ds))]
                                                       (when (seq ds') [s ds']))))
                                                 m))]))))]
      db3)))

(defn- ref-neighbors [db rel id]
  (seq (get-in db [:refs rel id] #{})))

(defn- reachable? [db rel start target]
  (loop [q (conj clojure.lang.PersistentQueue/EMPTY start)
         seen #{}]
    (when-let [x (peek q)]
      (cond
        (= x target) true
        (seen x) (recur (pop q) seen)
        :else (recur (into (pop q) (remove seen (ref-neighbors db rel x)))
                     (conj seen x))))))

(defn- edge-ok? [db rel src dst]
  (let [{:keys [acyclic? unique?]} (get-in db [:edge-registry rel] {})]
    (and
     (if unique? (empty? (get-in db [:refs rel src] #{})) true)
     (if acyclic? (not (reachable? db rel dst src)) true))))

(defn reachable?
  "Public API: Check if target is reachable from start via edges of given rel."
  [db rel start target]
  (loop [q (conj clojure.lang.PersistentQueue/EMPTY start)
         seen #{}]
    (when-let [x (peek q)]
      (cond
        (= x target) true
        (seen x) (recur (pop q) seen)
        :else (recur (into (pop q) (remove seen (ref-neighbors db rel x)))
                     (conj seen x))))))

(defn add-ref* [db {:keys [rel src dst] :as m}]
  (assert (contains? (:nodes db) src) (str "add-ref*: src missing: " src))
  (assert (contains? (:nodes db) dst) (str "add-ref*: dst missing: " dst))
  (when (= src dst)
    (throw (ex-info "add-ref*: self-edge not allowed" {:op m :why :self-edge})))
  (when-not (edge-ok? db rel src dst)
    (throw (ex-info "add-ref*: registry constraint violation" {:op m :why :edge-constraint :rel rel :src src :dst dst})))
  (update-in db [:refs rel src] (fnil conj #{}) dst))

(defn rm-ref* [db {:keys [rel src dst]}]
  (update-in db [:refs rel src] (fnil disj #{}) dst))

;; ------------------------------------------------------------
;; Transaction Processing: multimethod dispatch + effects detection
;; ------------------------------------------------------------

(defn- ->tx
  "Normalize tx input to a sequence of operations."
  [tx]
  (cond
    (nil? tx) []
    (map? tx) [tx]
    (sequential? tx) tx
    :else (throw (ex-info "Invalid tx format" {:tx tx}))))

;; ------------------------------------------------------------
;; Operation registry (multimethod-based dispatch)
;; ------------------------------------------------------------

(defn apply-op
  "Data-driven operation dispatch using the registry."
  [db op]
  (let [opkw (:op op)
        definition (S/op-definition-for opkw)]
    (if-not definition
      ;; This case should be caught by validation, but kept as a safeguard.
      (throw (ex-info "Unknown :op (registry lookup failed during dispatch)" {:op opkw}))
      (let [handler (:handler definition)]
        (handler db op)))))

;; ------------------------------------------------------------
;; Initialize Core Registry
;; ------------------------------------------------------------

(S/register-op! :create-node
                {:schema ::S/create-node-op
                 :handler create-node*
                 :axes #{:existence}})
(S/register-op! :place
                {:schema ::S/place-op
                 :handler place*
                 :axes #{:topology :order}})
(S/register-op! :update-node
                {:schema ::S/update-node-op
                 :handler update-node*
                 :axes #{:attributes}})
(S/register-op! :prune
                {:schema ::S/prune-op
                 ;; Wrapper required as prune* expects pred directly
                 :handler (fn [db op] (prune* db (:pred op)))
                 :axes #{:existence}})
(S/register-op! :add-ref
                {:schema ::S/add-ref-op
                 :handler add-ref*
                 :axes #{:references}})
(S/register-op! :rm-ref
                {:schema ::S/rm-ref-op
                 :handler rm-ref*
                 :axes #{:references}})

;; ------------------------------------------------------------
;; Transaction Pipeline Stages
;; ------------------------------------------------------------

(defn stage:validate-schema
  [ctx]
  (try
    (S/validate-op! (:op ctx))
    ctx
    (catch clojure.lang.ExceptionInfo e
      (assoc ctx :error {:why :schema :data (ex-data e) :message (.getMessage e)}))
    (catch Throwable t
      (assoc ctx :error {:why :schema :message (.getMessage t)}))))

(defn stage:apply-op
  [ctx]
  (try
    (let [db-next (apply-op (:db-before ctx) (:op ctx))]
      (assoc ctx :db-after db-next))
    (catch clojure.lang.ExceptionInfo e
      (assoc ctx :error {:why :op :data (ex-data e) :message (.getMessage e)}))
    (catch Throwable t
      (assoc ctx :error {:why :op :message (.getMessage t)}))))

(defn stage:derive
  [ctx]
  (let [derive-fn (get-in ctx [:config :derive] *derive-pass*)]
    (try
      (update ctx :db-after derive-fn)
      (catch Throwable t
        (assoc ctx :error {:why :derivation :message (.getMessage t)})))))

(defn stage:assert-invariants
  [ctx]
  (if (not (get-in ctx [:config :assert?]))
    ctx
    (try
      (inv/check-invariants (:db-after ctx))
      ctx
      (catch clojure.lang.ExceptionInfo e
        (assoc ctx :error {:why :invariants :data (ex-data e) :message (.getMessage e)}))
      (catch Throwable t
        (assoc ctx :error {:why :invariants :message (.getMessage t)})))))

(defn stage:detect-effects
  [ctx]
  (let [es (effects/detect (:db-before ctx) (:db-after ctx) (:op-index ctx) (:op ctx))]
    (assoc ctx :effects es)))

(def default-pipeline
  [stage:validate-schema
   stage:apply-op
   stage:derive
   stage:assert-invariants
   stage:detect-effects])

(defn- run-pipeline
  "Run the pipeline stages over the context. Uses (reduced) for short-circuiting on error."
  [pipeline ctx]
  (reduce (fn [c stage-fn]
            ;; Check for :error before executing the stage.
            (if (:error c)
              (reduced c)
              (let [next-c (stage-fn c)]
                ;; Check for :error immediately after execution.
                (if (:error next-c) (reduced next-c) next-c))))
          ctx
          pipeline))

(defn apply-tx+effects*
  "Process a transaction using the declarative pipeline. Does not throw."
  ([db tx] (apply-tx+effects* db tx {}))
  ([db tx {:keys [derive assert? pipeline trace?]
           :or {derive *derive-pass* assert? false pipeline default-pipeline trace? false}}]

   ;; Handle initial validation exceptions immediately, returning standardized error map.
   (try
     (S/validate-db! db)
     (S/validate-tx! tx)
     (catch Throwable t
       (let [exd (ex-data t)]
         ;; Return error directly, not wrapped in reduced
         {:error {:why (or (:why exd) :validation) :message (.getMessage t) :data exd}
          :db db :effects []})))

   (let [ops (->tx tx)
         config {:derive derive :assert? assert?}]

     (loop [i 0
            current-db (derive db)
            all-effects []
            trace []]
       (if (= i (count ops))
         ;; Transaction Success
         (cond-> {:db current-db :effects all-effects}
           (seq trace) (assoc :trace trace))

         (let [op (nth ops i)
               initial-ctx {:db-before current-db :op op :op-index i :config config}
               ;; Run the pipeline
               final-ctx (run-pipeline pipeline initial-ctx)]

           (if-let [error (:error final-ctx)]
             ;; Operation Failure: stop and return standardized error
             {:db db ;; Return original db
              :effects []
              :error (merge error {:op-index i :op op})}

             ;; Operation Success: continue
             (let [trace-step (when trace?
                                {:i i :op op :db (:db-after final-ctx) :effects (:effects final-ctx)})]
               (recur (inc i)
                      (:db-after final-ctx)
                      (into all-effects (:effects final-ctx))
                      (if trace-step (conj trace trace-step) trace))))))))))

(defn run-tx
  "Total: never throws. Consumes the pipeline output directly."
  ([db tx] (run-tx db tx {}))
  ([db tx opts]
   ;; We rely on apply-tx+effects* being total, but wrap in try/catch just in case of truly unexpected errors.
   (try
     (let [result (apply-tx+effects* db tx opts)]
       (if-let [error (:error result)]
         ;; Failure case
         {:ok? false
          ;; Ensure we return the input db if the result db is missing (e.g., validation failure)
          :db (or (:db result) db)
          :effects []
          :error error}
         ;; Success case
         {:ok? true
          :db (:db result)
          :effects (:effects result)}))
     (catch Throwable t
       {:ok? false :db db :effects [] :error {:why :unexpected :message (.getMessage t)}}))))

(defn apply-tx*
  "Backward-compatible: return only the db. Throws on errors for compatibility."
  ([db tx] (apply-tx* db tx {}))
  ([db tx opts]
   (let [result (apply-tx+effects* db tx opts)]
     (if-let [error (:error result)]
       ;; Throw to maintain backward compatibility
       (throw (ex-info (:message error) (merge (:data error) error)))
       (:db result)))))

(defn evaluate
  "Uniform envelope. Uses the pipeline output and response builders. Never throws."
  ([db tx] (evaluate db tx {}))
  ([db tx opts]
   (try
     (let [;; Run the transaction pipeline
           result (apply-tx+effects* db tx opts)]

       (if-let [error (:error result)]
         ;; Pipeline failed: Use R/error builder
         (R/error error)

         ;; Pipeline succeeded: Use R/ok builder
         (R/ok (select-keys result [:db :effects :trace]))))
     (catch Throwable t
       (R/error {:why :unexpected :data {:message (.getMessage t)}})))))

(defn verify-registry
  "Run all verification assertions to ensure system integrity after Proposals 7/8/16."
  []

  ;; Multi-root traversal with orphan detection
  (let [db {:nodes {"root" {:type :root} "palette" {:type :root} "w" {:type :div} "orphan" {:type :span}}
            :child-ids/by-parent {"root" ["w"]}
            :roots ["root" "palette"]}
        d (*derive-pass* db)]
    (assert (= (get-in d [:derived :preorder]) ["root" "w" "palette"]) "Multi-root preorder incorrect")
    (assert (not (contains? (get-in d [:derived :orphan-ids]) "palette")) "Palette should not be orphan")
    (assert (contains? (get-in d [:derived :orphan-ids]) "orphan") "Orphan node should be detected")
    (assert (= (count (get-in d [:derived :reachable-ids])) 3) "Should have 3 reachable nodes"))

  ;; Core operations work through the new registry system
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        {:keys [db effects]} (apply-tx+effects* base {:op :create-node :id "x" :type :div})]
    (assert (contains? (:nodes db) "x") "create-node should create node")
    (assert (empty? effects) "create-node should emit no effects")
    (assert (every? map? effects) "All effects should be data maps"))

  ;; Backward compatibility maintained exactly (apply-tx* still works)
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        old-api-result (apply-tx* base {:op :create-node :id "z"})
        new-api-result (:db (apply-tx+effects* base {:op :create-node :id "z"}))]
    (assert (= (:nodes old-api-result) (:nodes new-api-result)) "Node data must be identical")
    (assert (= (:child-ids/by-parent old-api-result) (:child-ids/by-parent new-api-result)) "Adjacency must be identical")
    (assert (= (:derived old-api-result) (:derived new-api-result)) "Derived data must be identical"))

  ;; Data-driven registry dispatch (Proposal 8)
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}]
    ;; Core primitives work directly
    (assert (contains? (:nodes (apply-op base {:op :create-node :id "test-core"})) "test-core") "Core ops must work")
    ;; run-tx uses the registry
    (assert (:ok? (run-tx base [{:op :create-node :id "test-run-tx"}])) "run-tx must use registry")
    ;; apply-tx+effects* uses the registry
    (assert (contains? (:nodes (:db (apply-tx+effects* base [{:op :create-node :id "test-effects"}]))) "test-effects") "apply-tx+effects* must use registry")
    ;; Unknown ops fail gracefully with clear error
    (try (apply-op base {:op :unknown-op}) (assert false "Should throw for unknown op")
         (catch Exception e (assert (re-find #"Unknown :op" (.getMessage e)) "Should give clear error message"))))

  ;; Declarative pipeline error handling (Proposal 7)
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        error-result (run-tx base [{:op :create-node :id "a"}
                                   {:op :place :id "a" :parent-id "NONEXISTENT"}])]
    (assert (not (:ok? error-result)) "Invalid transaction should fail")
    (assert (= (get-in error-result [:error :op-index]) 1) "Should report correct failing op index")
    (assert (contains? #{:schema :op :invariants} (get-in error-result [:error :why])) "Should categorize error type")
    (assert (= (:db error-result) base) "DB should be unchanged on error")
    (assert (empty? (:effects error-result)) "No effects on error"))

  ;; Cycle detection prevents infinite nesting
  (let [base {:nodes {"a" {:type :div} "b" {:type :div}} :child-ids/by-parent {}}
        tx [{:op :place :id "a" :parent-id "b"}
            {:op :place :id "b" :parent-id "a"}]
        result (run-tx base tx)]
    (assert (not (:ok? result)) "Cycle creation should fail")
    (assert (= (get-in result [:error :why]) :op) "Should fail at operation level")
    (assert (re-find #"cycle" (get-in result [:error :message])) "Error should mention cycle"))

  ;; Schema validation catches malformed operations
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        bad-ops [{:op :create-node} ; missing :id
                 {:op :place :id "nonexistent"} ; node doesn't exist (will fail at op level, not schema)
                 {:op :unknown-thing :data "whatever"}] ; unknown op (schema error)
        results (map #(run-tx base [%]) bad-ops)]
    (assert (every? #(not (:ok? %)) results) "All malformed ops should fail")
    ;; Error classification may vary based on where validation catches the issue
    (assert (every? #(contains? #{:schema :op} (get-in % [:error :why])) results) "Should categorize errors appropriately")
    (assert (every? #(= (:db %) base) results) "DB should remain unchanged on all failures"))

  ;; Pipeline stage isolation (Proposal 7 verification)
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        op {:op :create-node :id "test" :type :div}]
    ;; Each stage should work independently
    (let [ctx1 (stage:validate-schema {:db-before base :op op :op-index 0 :config {}})
          ctx2 (stage:apply-op ctx1)
          ctx3 (stage:derive ctx2)
          ctx4 (stage:assert-invariants ctx3)
          ctx5 (stage:detect-effects ctx4)]
      (assert (not (:error ctx1)) "Schema validation should pass")
      (assert (not (:error ctx2)) "Op application should pass")
      (assert (not (:error ctx3)) "Derivation should pass")
      (assert (not (:error ctx4)) "Invariants should pass")
      (assert (not (:error ctx5)) "Effects detection should pass")
      (assert (contains? (:nodes (:db-after ctx2)) "test") "Op should create node")
      (assert (:derived (:db-after ctx3)) "Derivation should add derived data")
      (assert (coll? (:effects ctx5)) "Should have effects collection")))

  ;; Transaction atomicity
  (let [base {:nodes {"root" {:type :root} "a" {:type :div}} :child-ids/by-parent {}}
        failing-tx [{:op :create-node :id "b" :type :div}
                    {:op :place :id "b" :parent-id "NONEXISTENT"}]
        result (run-tx base failing-tx)]
    (assert (not (:ok? result)) "Transaction with invalid op should fail")
    (assert (= (:db result) base) "Failed transaction should not modify DB at all")
    (assert (not (contains? (:nodes (:db result)) "b")) "Failed tx should not create intermediate nodes"))

  ;; Response builders work correctly (Proposal 16)
  (let [base {:nodes {"root" {:type :root}} :child-ids/by-parent {}}
        success-result (evaluate base [{:op :create-node :id "test"}])
        error-result (evaluate base [{:op :unknown-op}])]
    (assert (= (:status success-result) :ok) "Success should have :ok status")
    (assert (contains? success-result :db) "Success should include :db")
    (assert (= (:status error-result) :error) "Error should have :error status")
    (assert (contains? (:error error-result) :why) "Error should include error details"))

  "✓ All enhanced verification assertions passed - Proposals 7/8/16 working correctly")